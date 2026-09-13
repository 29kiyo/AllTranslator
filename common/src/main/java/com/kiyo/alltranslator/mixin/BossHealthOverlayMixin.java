package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Boss bar name translation (PHASE_INSTRUCTIONS.md Phase 14 item 6, "対応する" plan
 * confirmed 2026-09-02 - see DEVELOPMENT_STATUS.md's corresponding decision entry).
 *
 * Verified via javap against the MC 26.2 merged-deobf jar (this session):
 *  - BossEvent (common, world.BossEvent) holds a protected Component name field with
 *    public getName()/setName(Component). ServerBossEvent overrides setName() to
 *    broadcast a ClientboundBossEventPacket.UpdateNameOperation (via
 *    ClientboundBossEventPacket#createUpdateNamePacket) to subscribed players
 *    whenever the server-authoritative name actually changes.
 *  - LerpingBossEvent (client.gui.components.LerpingBossEvent) is the CLIENT-side
 *    render-state object BossHealthOverlay actually keeps per boss bar (keyed by
 *    UUID, in its private Map<UUID, LerpingBossEvent> events field). It extends
 *    BossEvent but does NOT override getName() - it inherits BossEvent's plain field
 *    getter verbatim.
 *  - BossHealthOverlay#extractRenderState(GuiGraphicsExtractor) calls
 *    LerpingBossEvent#getName() directly (the compiled bytecode's invokevirtual
 *    owner is LerpingBossEvent itself, matching this Mixin's @Redirect target
 *    exactly) to obtain the Component it then measures/draws via
 *    GuiGraphicsExtractor#text(Font, Component, int, int, int).
 *
 * This @Redirect intentionally targets ONLY this client-side draw-time read, never
 * BossEvent#getName()/setName() themselves and never anything under
 * server.level.ServerBossEvent. That keeps the translation strictly a local rendering
 * substitution: the ServerBossEvent instance's name field, and therefore what gets
 * broadcast to every other subscribed player (LAN or dedicated server), is completely
 * untouched - matching the same "client-only redirect, never touch server state"
 * constraint already established for chat/tooltip/entity-name translation
 * (ARCHITECTURE.md §9).
 *
 * Treated as key-less dynamic text (ARCHITECTURE.md §3): by the time a boss bar name
 * reaches this call it is a plain Component with no Minecraft translation key of its
 * own (vanilla bosses construct their BossEvent's name from an already-resolved
 * Component, and mod bosses may supply arbitrary literal text), so it always goes
 * through TranslatableTextInterceptor#intercept(original, null) exactly like
 * chat/tellraw, never through ExistingTranslationChecker's keyed existing-translation
 * path.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {

    @Redirect(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;getName()Lnet/minecraft/network/chat/Component;"))
    private Component alltranslator$translateBossBarName(LerpingBossEvent bossEvent) {
        Component original = bossEvent.getName();
        if (original == null) {
            return original;
        }
        TranslatableTextInterceptor interceptor = AllTranslatorCore.bossBarNameInterceptor();
        if (interceptor == null) {
            return original;
        }
        if (!AllTranslatorCore.configManager().model().translateBossBarNames) {
            return original;
        }
        return interceptor.intercept(original, null);
    }
}
