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
 * Internally routed to NeoForge's ItemTooltipEvent / Fabric's ItemTooltipCallback
 * entirely inside Architectury's own architectury-api jar - this project never uses
 * the @ExpectPlatform annotation itself (grep-confirmed empty across common/fabric/
 * neoforge as of Phase 10); ClientTooltipEvent.ITEM is one of several Architectury-
 * provided cross-platform APIs this project relies on instead (see also
 * KeyMappingRegistry, ClientTickEvent, CommandRegistrationEvent, LifecycleEvent).
 * ARCHITECTURE.md §1/§21 has been corrected to match (Phase 10).
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
        // MC 26.2 note: TranslatableContents#getKey() confirmed working in practice -
        // exercised on every tooltip line across repeated Fabric/NeoForge runClient
        // sessions (Phase 4-9) with correct existing-translation behavior observed and
        // no exceptions. Falls back to null (keyless/dynamic text path) if the
        // instanceof check ever fails to match.
        if (component.getContents() instanceof TranslatableContents tc) {
            return tc.getKey();
        }
        return null;
    }
}
