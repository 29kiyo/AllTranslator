package com.kiyo.alltranslator.mixin;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/**
 * Item display-name translation (PHASE_INSTRUCTIONS.md Phase 4).
 *
 * This Mixin applies on both physical client and dedicated server (ItemStack is a
 * shared class), but is a safe no-op on the server: AllTranslatorCore's item-name
 * interceptor reference is only ever set by AllTranslatorClientCore#init(), which is
 * never called on a dedicated server.
 *
 * Phase 13: optional " (original name)" suffix, gated by
 * ConfigModel#showOriginalNameOnItems (off by default). Only appended when
 * intercept() actually produced a different Component (i.e. a real translation
 * happened) - never appended to already-untranslated/pending text, so toggling
 * this on never doubles up plain English text with itself.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "getHoverName", at = @At("RETURN"), cancellable = true)
    private void alltranslator$translateHoverName(CallbackInfoReturnable<Component> cir) {
        TranslatableTextInterceptor interceptor = AllTranslatorCore.itemNameInterceptor();
        if (interceptor == null) {
            return;
        }
        if (!AllTranslatorCore.configManager().model().translateItemNames) {
            return;
        }
        Component original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        String key = (original.getContents() instanceof TranslatableContents tc) ? tc.getKey() : null;
        Component translated = interceptor.intercept(original, key);
        if (translated == original) {
            return;
        }
        if (AllTranslatorCore.configManager().model().showOriginalNameOnItems) {
            String originalPlain = original.getString();
            MutableComponent withOriginal = translated.copy()
                    .append(Component.literal(" (" + originalPlain + ")").setStyle(translated.getStyle()));
            // Phase 13 fix: register the composed "translated (original)" string as
            // final, or it gets re-sent to the translation API the next time this
            // Component's getString() is read (see class Javadoc).
            TranslatableTextInterceptor.registerKnownOutput(withOriginal.getString());
            cir.setReturnValue(withOriginal);
        } else {
            cir.setReturnValue(translated);
        }
    }
}
