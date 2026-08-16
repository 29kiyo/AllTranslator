package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entity display-name translation (PHASE_INSTRUCTIONS.md Phase 4).
 *
 * MC 26.2 note (HIGHER UNCERTAINTY than ItemStackMixin): "Entity#getName() -> Component"
 * has been a stable Minecraft API for many years, but was NOT independently confirmed
 * against a 26.2 primary source during this Phase (search results were inconclusive).
 * Verify the exact method name/signature via IntelliJ "Generate Sources" against the
 * actual 26.2 mappings before relying on this in production. If the method has been
 * renamed, this Mixin will simply fail to apply and the build/launch will report a
 * missing target - it will not silently do the wrong thing.
 *
 * Same client-only guard pattern as ItemStackMixin: no-ops unless
 * AllTranslatorClientCore#init() has installed an interceptor (client side only).
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void alltranslator$translateEntityName(CallbackInfoReturnable<Component> cir) {
        TranslatableTextInterceptor interceptor = AllTranslatorCore.entityNameInterceptor();
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
