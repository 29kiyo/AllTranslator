package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TellrawTranslationCoordinator;

import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.TellRawCommand;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Phase 14 (item 7, Option A - see TellrawTranslationCoordinator's class Javadoc for the
 * full scope decision/rationale for why THIS class specifically, rather than a general
 * ServerPlayer#sendSystemMessage hook).
 *
 * Confirmed via javap against the MC 26.2 merged jar that
 * TellRawCommand#lambda$register$0(CommandContext) is exactly:
 *   for (ServerPlayer p : EntityArgument.getPlayers(ctx, "targets"))
 *       p.sendSystemMessage(ComponentArgument.getResolvedComponent(ctx, "message", p));
 * - i.e. a single ServerPlayer#sendSystemMessage(Component) call site inside a per-target
 * loop, which this @Redirects.
 *
 * Registered in alltranslator.mixins.json's "mixins" array (not "client"), same reasoning
 * as PlayerListMixin: TellRawCommand's registered command only ever actually executes
 * server-side (dedicated server or singleplayer's integrated server), but the mixin class
 * itself must be loadable on the physical client too (Mixin/Architectury does not
 * distinguish at the mixin-config level) - matching the established pattern for other
 * non-client-only server mixins in this project. AllTranslatorCore#tellrawTranslationCoordinator()
 * is always non-null wherever AllTranslatorCore.init() has run (both sides), same as
 * PlayerListMixin's reasoning for serverChatTranslationCoordinator(), so the null check
 * below is defensive only.
 */
@Mixin(TellRawCommand.class)
public abstract class TellRawCommandMixin {

    @Redirect(
            method = "lambda$register$0",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;sendSystemMessage(Lnet/minecraft/network/chat/Component;)V")
    )
    private static void alltranslator$redirectTellraw(ServerPlayer recipient, Component message) {
        TellrawTranslationCoordinator coordinator = AllTranslatorCore.tellrawTranslationCoordinator();
        if (coordinator == null) {
            recipient.sendSystemMessage(message);
            return;
        }
        coordinator.handleOutgoing(recipient, message);
    }
}
