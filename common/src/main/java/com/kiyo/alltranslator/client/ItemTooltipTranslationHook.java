package com.kiyo.alltranslator.client;

import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import dev.architectury.event.events.client.ClientTooltipEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Item tooltip translation (PHASE_INSTRUCTIONS.md Phase 4).
 *
 * Uses Architectury API's cross-platform ClientTooltipEvent.ITEM. Confirmed signature
 * (from architectury-21.0.7-sources.jar, dev/architectury/event/events/client/ClientTooltipEvent.java):
 *
 *   interface Item {
 *       void append(ItemStack stack, List<Component> lines,
 *                    net.minecraft.world.item.Item.TooltipContext tooltipContext,
 *                    TooltipFlag flag);
 *   }
 *
 * Internally routed to NeoForge's ItemTooltipEvent / Fabric's ItemTooltipCallback via
 * @ExpectPlatform - so no per-loader Mixin is needed for tooltip lines specifically.
 *
 * register() must only be called from a confirmed client-side entrypoint - see
 * AllTranslatorClientCore.
 */
public final class ItemTooltipTranslationHook {

    private ItemTooltipTranslationHook() {}

    public static void register(TranslatableTextInterceptor interceptor) {
        ClientTooltipEvent.ITEM.register((stack, lines, tooltipContext, flag) -> {
            for (int i = 0; i < lines.size(); i++) {
                Component original = lines.get(i);
                Component translated = interceptor.intercept(original, extractKey(original));
                if (translated != original) {
                    lines.set(i, translated);
                }
            }
        });
    }

    private static String extractKey(Component component) {
        // MC 26.2 note: TranslatableContents#getKey() assumed stable from the
        // post-componentization (1.19+) Component API; not independently
        // re-verified against 26.2 sources. Falls back to null (keyless/dynamic
        // text path) if the instanceof check ever fails to match.
        if (component.getContents() instanceof TranslatableContents tc) {
            return tc.getKey();
        }
        return null;
    }
}
