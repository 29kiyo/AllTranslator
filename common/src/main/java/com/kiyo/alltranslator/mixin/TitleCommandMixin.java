package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TitleTranslationCoordinator;
import com.llamalad7.mixinextras.sugar.Local;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.server.commands.TitleCommand;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Real-world follow-up fix (Toast/Advancement translation task session, user request):
 * translates /title command output specifically - "tellraw/tell/msg/title, and other
 * freely-typed-text commands that have no vanilla translation" per user decision, as
 * opposed to general command feedback (e.g. /give, /tp), which already has vanilla
 * translations for every argument and must NOT be touched.
 *
 * Confirmed via javap against the MC 26.2 merged jar that TitleCommand#showTitle(...) -
 * the single private helper backing the title/subtitle/actionbar subcommands ("times" has
 * no Component argument and is a separate, untouched method) - does, per recipient:
 *   1. ComponentUtils.resolve(ResolutionContext.builder()...withEntityOverride(recipient)
 *      ...build(), originalComponent)
 *   2. packetFactory.apply(resolvedComponent)
 *   3. recipient.connection.send(packet)
 * This @Redirect targets step 1's ComponentUtils.resolve(...) call specifically (NOT
 * step 3's send(), which was considered and rejected: by the time send() is reached the
 * Component has already been converted into an opaque Packet with no public API to
 * extract or replace its Component payload, so translating that late is not possible).
 * recipient (a ServerPlayer) is captured via MixinExtras' @Local sugar
 * (com.llamalad7.mixinextras.sugar.Local, confirmed present and usable from common
 * module's compileJava via a standalone compile test before writing this Mixin -
 * CLAUDE.md §3) since it is a live Java local variable in scope at this exact bytecode
 * offset (the loop variable from showTitle's per-recipient for-each), letting this Mixin
 * resolve a per-player target language without needing send()'s receiver.
 *
 * Deliberately gated on the EXISTING ConfigModel#translateSystemMessages flag via
 * TitleTranslationCoordinator (same category as tellraw/advancement announcements - a
 * server-generated, freely-typed system message), not a new dedicated toggle.
 *
 * IMPORTANT LIMITATION (documented per CLAUDE.md §3 - see TitleTranslationCoordinator's
 * Javadoc for the full explanation): this cannot await an in-progress async translation
 * (Brigadier command execution is synchronous, and CLAUDE.md §7 forbids blocking the
 * server thread). Only an ALREADY-CACHED translation is used for the send itself; if none
 * exists yet, the original Component is sent this one time while a background translation
 * warms the cache for a later identical /title command.
 *
 * "clear"/"reset"/"times" send ClientboundClearTitlesPacket/
 * ClientboundSetTitlesAnimationPacket via their own separate methods
 * (clearTitle()/resetTitle()/setTimes(), confirmed via javap), never through showTitle()'s
 * ComponentUtils.resolve() call this Mixin targets - correctly unaffected.
 */
@Mixin(TitleCommand.class)
public abstract class TitleCommandMixin {

    @Redirect(
            method = "showTitle",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/ComponentUtils;resolve(Lnet/minecraft/network/chat/ResolutionContext;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;")
    )
    private static MutableComponent alltranslator$translateTitleComponent(
            ResolutionContext ctx, Component component, @Local ServerPlayer recipient) throws CommandSyntaxException {
        MutableComponent resolved = ComponentUtils.resolve(ctx, component);

        TitleTranslationCoordinator coordinator = AllTranslatorCore.titleTranslationCoordinator();
        if (coordinator == null) {
            return resolved;
        }

        Component translated = coordinator.resolveForSend(recipient, resolved);
        return translated instanceof MutableComponent mutable ? mutable : translated.copy();
    }
}
