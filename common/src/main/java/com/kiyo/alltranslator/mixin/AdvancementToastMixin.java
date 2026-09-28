package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Phase 14 (Toast/Advancement translation task): translates the title shown in the
 * vanilla "advancement made"/"challenge complete" toast (AdvancementToast).
 *
 * Scope decision (CLAUDE.md §7 - don't do more than needed): javap against the MC 26.2
 * merged-deobf jar confirmed AdvancementToast#extractRenderState(GuiGraphicsExtractor,
 * Font, long) calls DisplayInfo#getTitle() exactly ONCE (used for both the single-line
 * and multi-line/wrapped layout branches - the two are just different rendering paths
 * for the same title text). DisplayInfo#getDescription() is never called from this
 * class at all - only the toast's title line is ever shown, matching what a player
 * actually sees pop up. This @Redirect therefore only ever needs to touch that one
 * getTitle() call site; the advancement tree (ClientAdvancements/AdvancementTree) and
 * the F-key progress screen (AdvancementsScreen/AdvancementWidget/AdvancementTab) are
 * deliberately OUT of scope for this task (see DEVELOPMENT_STATUS.md) - those classes
 * are untouched by this Mixin and continue to show untranslated titles/descriptions.
 *
 * Why a Component-level @Redirect works here despite DisplayInfo/Advancement/
 * AdvancementHolder all being immutable (no setters - confirmed via javap): unlike
 * ItemStackMixin/EntityMixin, which intercept a per-call getter result, this class
 * doesn't need to replace any stored data. AdvancementToast#extractRenderState() is
 * called by ToastManager on every rendered frame while the toast is visible (confirmed
 * via javap: the method is re-invoked each frame, not cached at construction like
 * AdvancementWidget's titleLines is - which is exactly why the progress screen is
 * harder to retrofit and was excluded above). Redirecting the single getTitle() call
 * to return an intercepted Component is therefore sufficient: same "original shown on
 * frame 1, translated text appears automatically once the async translation resolves"
 * behavior already used by ItemTooltipTranslationHook, with zero mutation of any MC
 * data structure.
 *
 * Client-only guard pattern matches every other Phase 4/13/14 text hook: no-ops
 * (returns the original Component unchanged) unless AllTranslatorClientCore#init() has
 * installed an interceptor, which only ever happens on a confirmed physical client.
 */
@Mixin(AdvancementToast.class)
public abstract class AdvancementToastMixin {

    @Redirect(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/advancements/DisplayInfo;title()Lnet/minecraft/network/chat/Component;"
            )
    )
    private Component alltranslator$translateToastTitle(DisplayInfo displayInfo) {
        Component original = displayInfo.title();
        TranslatableTextInterceptor interceptor = AllTranslatorCore.advancementToastInterceptor();
        if (interceptor == null) {
            return original;
        }
        if (!AllTranslatorCore.configManager().model().translateAdvancementToasts) {
            return original;
        }
        String key = (original.getContents() instanceof TranslatableContents tc) ? tc.getKey() : null;
        return interceptor.intercept(original, key);
    }

    /**
     * Real-world follow-up fix: the toast's heading line ("Advancement made!" /
     * "Challenge complete!" / "Goal reached!") is a SEPARATE Component from the
     * advancement's own title above - AdvancementType#getDisplayName(), one of 3 fixed
     * enum-backed values (TASK/CHALLENGE/GOAL). Confirmed via javap that
     * extractRenderState() calls this at TWO call sites (the single-line and
     * multi-line/wrapped rendering branches); a single @Redirect with no ordinal
     * applies to both automatically. Always translation-key-backed
     * (TranslatableContents), so vanilla's own translation (any language MC itself
     * ships) is used via existing-translation lookup (ARCHITECTURE.md §3) before ever
     * reaching the API - this text is never mod/datapack-authored.
     */
    @Redirect(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/advancements/AdvancementType;getDisplayName()Lnet/minecraft/network/chat/Component;"
            )
    )
    private Component alltranslator$translateToastTypeHeading(AdvancementType type) {
        Component original = type.getDisplayName();
        TranslatableTextInterceptor interceptor = AllTranslatorCore.advancementToastInterceptor();
        if (interceptor == null) {
            return original;
        }
        if (!AllTranslatorCore.configManager().model().translateAdvancementToasts) {
            return original;
        }
        String key = (original.getContents() instanceof TranslatableContents tc) ? tc.getKey() : null;
        return interceptor.intercept(original, key);
    }
}
