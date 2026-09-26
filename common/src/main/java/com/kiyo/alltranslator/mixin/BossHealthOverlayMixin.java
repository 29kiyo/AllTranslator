package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
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
 * Phase 13相当セッション(2026-09-26)修正: originally this always called
 * intercept(original, null), treating every boss bar name as keyless dynamic text
 * (ARCHITECTURE.md §3) regardless of source. Real-world testing found this wrong for
 * vanilla bosses: the client-side Component for e.g. the Wither still carries its
 * original TranslatableContents (key "entity.minecraft.wither") - Minecraft's
 * component network codec preserves TranslatableContents across the wire rather than
 * flattening it to a literal string, so ClientboundBossEventPacket's payload arrives
 * on the client with the key intact. Always forcing key=null meant
 * ExistingTranslationChecker (and therefore vanilla's own official ja_jp translation,
 * e.g. "ウィザー") was never consulted, so every boss name - including ones Minecraft
 * itself already knows how to localize - was sent to the translation API as a bare
 * English word. Observed real-machine result: "Wither" was translated as a generic
 * English word ("凋れ"/"枯れる", inconsistent across runs) instead of vanilla's actual
 * proper-noun translation "ウィザー".
 *
 * Fixed by extracting the key the same way EntityMixin/ItemTooltipTranslationHook do
 * (TranslatableContents#getKey() if present, else null) and passing THAT to
 * intercept(), instead of hardcoding null. This restores ARCHITECTURE.md §3's
 * priority (existing translation first) for any boss whose name Component still
 * carries a translation key - vanilla bosses, and any mod boss that sets its
 * BossEvent's name from a Component.translatable(...) rather than a literal string.
 * A mod boss using Component.literal("Some Boss Name") (no key) still falls through
 * to the original keyless dynamic-text path unchanged - no regression for that case.
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
        String key = (original.getContents() instanceof TranslatableContents tc) ? tc.getKey() : null;
        return interceptor.intercept(original, key);
    }
}
