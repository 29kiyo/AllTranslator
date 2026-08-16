package com.kiyo.alltranslator.client;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import com.kiyo.alltranslator.text.ChatTranslationCoordinator;

/**
 * Client-only bootstrap for Phase 4 text-interception hooks.
 *
 * MUST be called only from a loader's confirmed physical-client entrypoint:
 *   Fabric:   AllTranslatorFabricClient#onInitializeClient
 *   NeoForge: AllTranslatorNeoForge, listening for FMLClientSetupEvent
 *
 * Never call this from AllTranslator#init()/AllTranslatorCore#init(), since those
 * also run on dedicated servers. The interceptor instances installed here are the
 * only thing that makes the ItemStackMixin / EntityMixin hooks active (they check
 * for null and no-op otherwise), which is what keeps translation client-side only
 * per ARCHITECTURE.md §9.
 */
public final class AllTranslatorClientCore {

    private static boolean initialized = false;

    private AllTranslatorClientCore() {}

    public static synchronized void init() {
        if (initialized) {
            AllTranslator.LOGGER.warn("AllTranslatorClientCore.init() called more than once; ignoring.");
            return;
        }
        if (AllTranslatorCore.localizedTextResolver() == null) {
            AllTranslator.LOGGER.error(
                    "AllTranslatorClientCore.init() called before AllTranslatorCore.init(); "
                            + "translation hooks NOT installed.");
            return;
        }
        initialized = true;

        TranslatableTextInterceptor tooltipInterceptor = new TranslatableTextInterceptor(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.languageResolver());
        TranslatableTextInterceptor itemNameInterceptor = new TranslatableTextInterceptor(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.languageResolver());
        TranslatableTextInterceptor entityNameInterceptor = new TranslatableTextInterceptor(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.languageResolver());

        AllTranslatorCore.installClientTextInterceptors(tooltipInterceptor, itemNameInterceptor, entityNameInterceptor);

        ItemTooltipTranslationHook.register(tooltipInterceptor);

        // Phase 5: chat translation. Loader modules (fabric/neoforge) register the
        // actual receive-event listener and call AllTranslatorCore.chatTranslationCoordinator()
        // once this returns, since Fabric API's message events and NeoForge's
        // ClientChatReceivedEvent are not exposed through Architectury's common event bus.
        ChatTranslationCoordinator chatTranslationCoordinator = new ChatTranslationCoordinator(
                AllTranslatorCore.translationService(), AllTranslatorCore.languageResolver(), AllTranslatorCore.configManager());
        AllTranslatorCore.installChatTranslationCoordinator(chatTranslationCoordinator);

        AllTranslator.LOGGER.info("{} client-side text translation hooks installed "
                + "(item tooltip: event-based, item/entity name: Mixin-based, chat: coordinator ready)", AllTranslator.MOD_NAME);
    }
}
