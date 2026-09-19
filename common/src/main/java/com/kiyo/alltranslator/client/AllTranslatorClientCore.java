package com.kiyo.alltranslator.client;
import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import com.kiyo.alltranslator.text.ChatTranslationCoordinator;
import net.minecraft.client.Minecraft;
import com.kiyo.alltranslator.modjarlang.GeneratedLangPackRepositorySource;
import com.kiyo.alltranslator.modjarlang.ModJarLangTranslationCoordinator;
/**
 * Client-only bootstrap for Phase 4 text-interception hooks, Phase 5 chat translation,
 * and (Phase 8) the shared config screen's L-keybinding.
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
        // Phase 14 (SERVER_PROXY): client-only S2C response receiver. MUST be
        // registered here (confirmed physical client), never from the common
        // AllTranslatorCore.init() - see AllTranslatorNetworking's Javadoc.
        com.kiyo.alltranslator.network.AllTranslatorNetworking.registerClientReceiver();
        com.kiyo.alltranslator.client.RemoteConfigClientReceiver.register();
        TranslatableTextInterceptor tooltipInterceptor = new TranslatableTextInterceptor(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.languageResolver());
        TranslatableTextInterceptor itemNameInterceptor = new TranslatableTextInterceptor(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.languageResolver());
        TranslatableTextInterceptor entityNameInterceptor = new TranslatableTextInterceptor(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.languageResolver());
        AllTranslatorCore.installClientTextInterceptors(tooltipInterceptor, itemNameInterceptor, entityNameInterceptor);
        ItemTooltipTranslationHook.register(tooltipInterceptor);
        // Phase 13: other mods' Screen widgets (buttons, toggles, labels). A
        // dedicated interceptor instance (separate cache from tooltip/item/entity)
        // to keep this new, broader-scoped feature's translations independently
        // invalidatable if that's ever needed, matching the Phase 4 "one instance
        // per content category" pattern.
        TranslatableTextInterceptor screenWidgetInterceptor = new TranslatableTextInterceptor(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.languageResolver());
        ScreenWidgetTranslationHook.register(screenWidgetInterceptor);
        // Phase 14 (Toast/Advancement translation task): "advancement made"/"challenge
        // complete" toast title. Dedicated interceptor instance (own cache namespace),
        // same "one instance per content category" pattern as tooltip/item/entity/screen
        // widget above - see AdvancementToastMixin for the actual hook.
        TranslatableTextInterceptor advancementToastInterceptor = new TranslatableTextInterceptor(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.languageResolver());
        AllTranslatorCore.installAdvancementToastInterceptor(advancementToastInterceptor);
        // Phase 14 (Toast/Advancement translation task, follow-up): "New Recipes
        // Unlocked!" toast. Dedicated interceptor instance, same pattern as above.
        TranslatableTextInterceptor recipeToastInterceptor = new TranslatableTextInterceptor(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.languageResolver());
        AllTranslatorCore.installRecipeToastInterceptor(recipeToastInterceptor);
        // Phase 14 (Boss bar name translation): dedicated interceptor instance, same
        // "one instance per content category" pattern as the toast interceptors above -
        // see BossHealthOverlayMixin for the actual hook.
        TranslatableTextInterceptor bossBarNameInterceptor = new TranslatableTextInterceptor(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.languageResolver());
        AllTranslatorCore.installBossBarNameInterceptor(bossBarNameInterceptor);
        // Phase 5: chat translation. Loader modules (fabric/neoforge) register the
        // actual receive-event listener and call AllTranslatorCore.chatTranslationCoordinator()
        // once this returns, since Fabric API's message events and NeoForge's
        // ClientChatReceivedEvent are not exposed through Architectury's common event bus.
        ChatTranslationCoordinator chatTranslationCoordinator = new ChatTranslationCoordinator(
                AllTranslatorCore.translationService(), AllTranslatorCore.languageResolver(), AllTranslatorCore.configManager());
        AllTranslatorCore.installChatTranslationCoordinator(chatTranslationCoordinator);
        // Phase 13 fix: retry-queue tick pump for ChatTranslationCoordinator's
        // pending patches - see that class's Javadoc ("Retry queue") for why a
        // single immediate patch attempt is not reliable. Uses the same
        // Architectury common ClientTickEvent.CLIENT_POST already relied on by
        // AllTranslatorKeyBindings, so this covers Fabric and NeoForge identically
        // with no loader-specific code.
        dev.architectury.event.events.client.ClientTickEvent.CLIENT_POST.register(
                client -> chatTranslationCoordinator.onClientTick(client));
        // Phase 14 real-world crash fix: RemoteConfigClientReceiver defers its
        // setScreen() call to a safe tick boundary - see that class's Javadoc for why
        // (the same ClientTickEvent.CLIENT_POST wiring pattern as the coordinator above).
        dev.architectury.event.events.client.ClientTickEvent.CLIENT_POST.register(
                client -> com.kiyo.alltranslator.client.RemoteConfigClientReceiver.onClientTick(client));
        // Phase 14 (M-key auto-sync, user request): push this client's own
        // ConfigModel#forcedTargetLanguage to the server on every login, so a
        // player who only ever uses the familiar M-key screen doesn't also need
        // to remember /alltranslator language. Uses Architectury's common
        // ClientPlayerEvent.CLIENT_PLAYER_JOIN (verified via javap against
        // architectury-fabric-21.0.7.jar: LocalPlayer-only callback, no loader
        // split needed) - fires once per join, both singleplayer and multiplayer.
        // See PlayerLanguageSyncPayloads' Javadoc for what this payload does and
        // does not carry (a language code only, never any config/API data).
        dev.architectury.event.events.client.ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(
                localPlayer -> com.kiyo.alltranslator.client.PlayerLanguageSyncClient.sendCurrentLanguage());
        // Phase 8: the shared L-keybinding that opens AllTranslatorConfigScreen. Uses
        // Architectury's common KeyMappingRegistry/ClientTickEvent (verified via javap -
        // no per-loader split needed), so registering it once here covers both Fabric
        // and NeoForge exactly like the interceptors/coordinator above.
        AllTranslatorKeyBindings.register();
        // Phase 13: client-only error-toast wiring. TranslationService itself (common,
        // also runs server-side) knows nothing about toasts - it only calls a generic
        // BiConsumer<apiDisplayName, failureType> if one is set, same injection pattern
        // as ChatTranslationCoordinator above. Config checks (toastEnabled/soundEnabled)
        // are re-read from ConfigManager on every failure, not cached at registration
        // time, so a mid-session settings change takes effect immediately.
        AllTranslatorCore.translationService().setFailureListener((apiDisplayName, failureType) -> {
            var model = AllTranslatorCore.configManager().model();
            if (!model.apiErrorToastEnabled) return;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> AllTranslatorErrorToast.show(
                    mc.gui.toastManager(), apiDisplayName, failureType, model.apiErrorToastSoundEnabled));
        });
        // Mod jar lang bulk translation: coordinator is client-only (drives
        // per-mod translation work using this client's own TranslationService/API
        // config - ARCHITECTURE.md §9, entirely local, no server involvement needed).
        ModJarLangTranslationCoordinator modJarLangCoordinator = new ModJarLangTranslationCoordinator(
                AllTranslatorCore.localizedTextResolver(), AllTranslatorCore.generatedLangPackStore());
        AllTranslatorCore.installModJarLangTranslationCoordinator(modJarLangCoordinator);
        // NOTE: registering GeneratedLangPackRepositorySource with
        // Minecraft#getResourcePackRepository()#addPackFinder is done from EACH
        // loader module (AllTranslatorFabricClient / AllTranslatorNeoForge), NOT
        // here. PackRepository#addPackFinder is only public via NeoForge's access
        // transformer - confirmed via javap: it is package-private on the raw
        // (pre-AT) merged jar this common module compiles against, the exact same
        // AT-gated situation as ServerPlayer#getLanguage() (see ARCHITECTURE.md
        // §20.1) - so common code cannot call it directly. Fabric compiles/runs
        // against a differently-obfuscated jar where it IS public, so this is not
        // symmetric between loaders; each loader module calls it itself, both
        // passing the exact same common GeneratedLangPackRepositorySource instance,
        // obtained via AllTranslatorCore.generatedLangPackStore() below.

        AllTranslator.LOGGER.info("{} client-side text translation hooks installed "
                + "(item tooltip: event-based, item/entity name: Mixin-based, chat: coordinator ready, "
                + "config screen: L-key registered, error toast: wired)", AllTranslator.MOD_NAME);
    }
}
