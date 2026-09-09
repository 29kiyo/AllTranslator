package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
 *
 * Real-world follow-up fix (Toast/Advancement translation task session): Player entities
 * (this instanceof Player - covers both the local client player and every other online
 * player rendered nearby, e.g. above-head nametags/tab list name resolution that also
 * routes through Entity#getName()) are EXCLUDED entirely, never even reaching the
 * interceptor. A player's in-game name is an identifier, not natural-language content -
 * translating it is never correct behavior (e.g. a Cyrillic or CJK-charset username being
 * garbled by an LLM "translation" attempt), unlike a mob/entity display name such as a
 * custom-named or mod-added creature, which IS legitimate content to translate.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void alltranslator$translateEntityName(CallbackInfoReturnable<Component> cir) {
        if ((Object) this instanceof Player) {
            return;
        }
        TranslatableTextInterceptor interceptor = AllTranslatorCore.entityNameInterceptor();
        if (interceptor == null) {
            return;
        }
        if (!AllTranslatorCore.configManager().model().translateEntityNames) {
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
