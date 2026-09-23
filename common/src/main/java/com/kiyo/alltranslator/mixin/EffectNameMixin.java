package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.effect.MobEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Active-effect list translation (inventory screen's right-side potion effect icons -
 * EffectsInInventory, distinct from ItemTooltipTranslationHook which covers item
 * tooltips only). Discovered as an unhandled gap during Phase 14 Fabric
 * re-verification (DEVELOPMENT_STATUS.md).
 *
 * Verified via javap against the MC 26.2 merged-deobf jar (this session):
 *  - EffectsInInventory#getEffectName(MobEffectInstance) (private) calls
 *    MobEffectInstance#getEffect() -> Holder<MobEffect>#value() -> MobEffect#getDisplayName(),
 *    then .copy() and (for amplifier 1-9) appends a roman-numeral suffix built via
 *    Component.translatable("enchantment.level.<n>") - the suffix logic runs AFTER
 *    getDisplayName() returns, so redirecting getDisplayName() itself intercepts only
 *    the base effect name Component, before the numeral is appended.
 *  - MobEffect#getDisplayName() returns a plain Component (a TranslatableContents in
 *    vanilla practice, e.g. key "effect.minecraft.speed"), so this is KEY-BASED
 *    dynamic text (ARCHITECTURE.md §3), unlike boss bar names - the existing-
 *    translation-first priority applies normally via intercept(original, key).
 *
 * Client-side rendering substitution only; no server/network state is touched (same
 * "client-only redirect" constraint as BossHealthOverlayMixin, ARCHITECTURE.md §9).
 */
@Mixin(net.minecraft.client.gui.screens.inventory.EffectsInInventory.class)
public abstract class EffectNameMixin {

    @Redirect(
            method = "getEffectName(Lnet/minecraft/world/effect/MobEffectInstance;)Lnet/minecraft/network/chat/Component;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/effect/MobEffect;getDisplayName()Lnet/minecraft/network/chat/Component;"))
    private Component alltranslator$translateEffectName(MobEffect effect) {
        Component original = effect.getDisplayName();
        if (original == null) {
            return original;
        }
        TranslatableTextInterceptor interceptor = AllTranslatorCore.effectNameInterceptor();
        if (interceptor == null) {
            return original;
        }
        if (!AllTranslatorCore.configManager().model().translateEffectNames) {
            return original;
        }
        String key = original.getContents() instanceof TranslatableContents tc ? tc.getKey() : null;
        return interceptor.intercept(original, key);
    }
}
