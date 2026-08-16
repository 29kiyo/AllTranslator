package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Item display-name translation (PHASE_INSTRUCTIONS.md Phase 4).
 *
 * Target confirmed via NeoForge 1.21.x javadoc: "Component ItemStack#getHoverName()".
 * MC 26.2 note: re-verify this exact signature against IntelliJ-generated sources
 * (Fabric docs: "Generating Sources") before shipping - it was not independently
 * re-confirmed against 26.2 sources.
 *
 * This Mixin applies on both physical client and dedicated server (ItemStack is a
 * shared class), but is a safe no-op on the server: AllTranslatorCore's item-name
 * interceptor reference is only ever set by AllTranslatorClientCore#init(), which is
 * never called on a dedicated server.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void alltranslator$translateHoverName(CallbackInfoReturnable<Component> cir) {
        TranslatableTextInterceptor interceptor = AllTranslatorCore.itemNameInterceptor();
        if (interceptor == null) {
            return;
        }
        Component original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        String key = (original.getContents() instanceof TranslatableContents tc) ? tc.getKey() : null;
        Component translated = interceptor.intercept(original, key);
        if (translated != original) {
            cir.setReturnValue(translated);
        }
    }
}
