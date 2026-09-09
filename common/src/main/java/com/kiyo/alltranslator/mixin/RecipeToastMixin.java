package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Phase 14 (Toast/Advancement translation task, follow-up): translates the "New
 * Recipes Unlocked!" toast (RecipeToast) title/description.
 *
 * Confirmed via javap that RecipeToast holds these as two PRIVATE STATIC FINAL
 * Component fields (TITLE_TEXT/DESCRIPTION_TEXT), assigned once in the class's
 * static initializer from vanilla translation keys ("recipe.toast.title" /
 * "recipe.toast.description") - NOT via a getter method, unlike
 * AdvancementToast/DisplayInfo. extractRenderState() reads both fields directly
 * (GETSTATIC bytecode), so this uses a Mixin FIELD @Redirect (a standard
 * SpongePowered Mixin capability for intercepting a field read at a specific call
 * site, distinct from an INVOKE @Redirect) rather than an @Accessor - redirecting
 * the read means every OTHER (hypothetical) reader of these fields elsewhere is
 * completely unaffected, and no @Mutable override of the final fields themselves
 * is needed.
 *
 * Both fields are private, so direct "RecipeToast.TITLE_TEXT" references from this
 * (ordinary, non-Mixin-processed-at-compile-time-for-privates) class do not compile -
 * @Shadow declares them so Mixin's bytecode transformer resolves the reference at
 * weave time instead, the standard way to reference a target class's private static
 * state from within its own Mixin (same category of mechanism as
 * ChatComponentAccessor's @Accessor, just for direct field reference rather than a
 * generated getter).
 *
 * Both Components are always translation-key-backed, so vanilla's own shipped
 * translation is used via existing-translation lookup (ARCHITECTURE.md §3) before
 * ever reaching the API - recipe unlock text is never mod/datapack-authored
 * dynamic content, so this always benefits from that priority.
 */
@Mixin(RecipeToast.class)
public abstract class RecipeToastMixin {

    @Shadow
    private static Component TITLE_TEXT;

    @Shadow
    private static Component DESCRIPTION_TEXT;

    @Redirect(
            method = "extractRenderState",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/gui/components/toasts/RecipeToast;TITLE_TEXT:Lnet/minecraft/network/chat/Component;"
            )
    )
    private static Component alltranslator$translateRecipeToastTitle() {
        return alltranslator$translate(TITLE_TEXT);
    }

    @Redirect(
            method = "extractRenderState",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/gui/components/toasts/RecipeToast;DESCRIPTION_TEXT:Lnet/minecraft/network/chat/Component;"
            )
    )
    private static Component alltranslator$translateRecipeToastDescription() {
        return alltranslator$translate(DESCRIPTION_TEXT);
    }

    private static Component alltranslator$translate(Component original) {
        TranslatableTextInterceptor interceptor = AllTranslatorCore.recipeToastInterceptor();
        if (interceptor == null) {
            return original;
        }
        if (!AllTranslatorCore.configManager().model().translateRecipeToasts) {
            return original;
        }
        String key = (original.getContents() instanceof TranslatableContents tc) ? tc.getKey() : null;
        return interceptor.intercept(original, key);
    }
}
