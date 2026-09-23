package com.kiyo.alltranslator;

import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.config.CredentialStore;
import com.kiyo.alltranslator.lang.CompositeLanguageDataSource;
import com.kiyo.alltranslator.lang.CustomLanguageFileManager;
import com.kiyo.alltranslator.lang.ExistingTranslationChecker;
import com.kiyo.alltranslator.lang.LanguageResolver;
import com.kiyo.alltranslator.lang.LocalizedTextResolver;
import com.kiyo.alltranslator.lang.MinecraftClientLanguageSupplier;
import com.kiyo.alltranslator.lang.MinecraftLanguageDataSource;
import com.kiyo.alltranslator.modjarlang.GeneratedLangPackStore;
import com.kiyo.alltranslator.modjarlang.ModJarLangTranslationCoordinator;
import com.kiyo.alltranslator.provider.ProviderFactory;
import com.kiyo.alltranslator.server.PerPlayerLanguageResolver;
import com.kiyo.alltranslator.server.PlayerTranslationSettingsManager;
import com.kiyo.alltranslator.server.WorldCacheConnector;
import com.kiyo.alltranslator.service.ApiManager;
import com.kiyo.alltranslator.service.CacheManager;
import com.kiyo.alltranslator.service.PendingRequestMap;
import com.kiyo.alltranslator.service.TranslationService;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import com.kiyo.alltranslator.text.ChatTranslationCoordinator;
import com.kiyo.alltranslator.text.ServerChatTranslationCoordinator;
import com.kiyo.alltranslator.text.TellrawTranslationCoordinator;
import com.kiyo.alltranslator.text.AdvancementAnnounceTranslationCoordinator;
import com.kiyo.alltranslator.text.TitleTranslationCoordinator;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.kiyo.alltranslator.AllTranslator;

/**
 * Loader-agnostic wiring for the Translation Core (Phase 2), Language Files (Phase 3),
 * Minecraft Text hooks (Phase 4), Chat (Phase 5), and Server support (Phase 6 - see
 * AllTranslatorClientCore for the client-only half of Phase 4/5's wiring; Phase 6's
 * server-side pieces below are wired unconditionally here since they're all common,
 * loader-agnostic, side-agnostic classes that are safe no-ops until explicitly enabled in
 * config.json).
 * Each loader module calls init(Path) once during common mod init, e.g.:
 *   Fabric:   AllTranslatorCore.init(FabricLoader.getInstance().getConfigDir().resolve("alltranslator"));
 *   NeoForge: AllTranslatorCore.init(FMLPaths.CONFIGDIR.get().resolve("alltranslator"));
 */
public final class AllTranslatorCore {

    private static ConfigManager configManager;
    private static CredentialStore credentialStore;
    private static ApiManager apiManager;
    private static CacheManager cacheManager;
    private static TranslationService translationService;
    private static LanguageResolver languageResolver;
    private static MinecraftLanguageDataSource languageDataSource;
    private static CustomLanguageFileManager customLanguageFileManager;
    private static ExistingTranslationChecker existingTranslationChecker;
    private static LocalizedTextResolver localizedTextResolver;
    // Mod jar lang bulk translation: safe on both physical sides (pure file-path
    // storage, no client-only Minecraft class touched) - see GeneratedLangPackStore's
    // own Javadoc. The coordinator that actually DRIVES translation work, however, is
    // client-only (installed only by AllTranslatorClientCore#init()), same
    // null-on-dedicated-server pattern as chatTranslationCoordinator below.
    private static GeneratedLangPackStore generatedLangPackStore;
    private static ModJarLangTranslationCoordinator modJarLangTranslationCoordinator;
    private static ExecutorService executor;

    // Phase 4: installed only by AllTranslatorClientCore#init() (client-side only).
    // Remain null on dedicated servers, which is what keeps ItemStackMixin/EntityMixin
    // safe no-ops on the server per ARCHITECTURE.md §9.
    private static TranslatableTextInterceptor tooltipInterceptor;
    private static TranslatableTextInterceptor itemNameInterceptor;
    private static TranslatableTextInterceptor entityNameInterceptor;
    // Phase 14 (Toast/Advancement translation task): installed only by
    // AllTranslatorClientCore#init() (client-side only), same null-on-dedicated-server
    // guarantee as the three interceptors above. Backs AdvancementToastMixin.
    private static TranslatableTextInterceptor advancementToastInterceptor;
    // Phase 14 (Toast/Advancement translation task, follow-up): same pattern, backs
    // RecipeToastMixin ("New Recipes Unlocked!" toast).
    private static TranslatableTextInterceptor recipeToastInterceptor;
    // Phase 14 (Boss bar name translation): installed only by
    // AllTranslatorClientCore#init() (client-side only), same null-on-dedicated-server
    // guarantee as the interceptors above. Backs BossHealthOverlayMixin.
    private static TranslatableTextInterceptor bossBarNameInterceptor;
    // Phase 14 (active-effect list translation, Fabric re-verification follow-up):
    // installed only by AllTranslatorClientCore#init() (client-side only), same
    // null-on-dedicated-server guarantee as the interceptors above. Backs EffectNameMixin.
    private static TranslatableTextInterceptor effectNameInterceptor;

    // Phase 5: installed only by AllTranslatorClientCore#init() (client-side only),
    // same null-on-dedicated-server guarantee as the interceptors above.
    private static ChatTranslationCoordinator chatTranslationCoordinator;

    // Phase 6: server support. Unlike Phase 4/5's client-only fields above, these are wired
    // unconditionally in init() below (both physical sides) because
    // PlayerTranslationSettingsManager/PerPlayerLanguageResolver/ServerChatTranslationCoordinator
    // reference only common (non-client-only) Minecraft classes (ServerPlayer, MinecraftServer)
    // and are harmless if never exercised - e.g. on a pure remote-play client,
    // PlayerListMixin's target class simply never runs locally, so
    // serverChatTranslationCoordinator() is never actually invoked even though the reference
    // itself is non-null.
    private static PlayerTranslationSettingsManager playerTranslationSettingsManager;
    private static PerPlayerLanguageResolver perPlayerLanguageResolver;
    private static ServerChatTranslationCoordinator serverChatTranslationCoordinator;
    private static TellrawTranslationCoordinator tellrawTranslationCoordinator;
    // Phase 14 (Toast/Advancement translation task, follow-up): per-player translation
    // of the "X has made the advancement [Y]" chat announcement. Deliberately gated on
    // the EXISTING ConfigModel#translateSystemMessages flag (user decision - this is
    // conceptually a server-generated system announcement, same category as tellraw),
    // not a new dedicated flag. Wired unconditionally (both physical sides), same
    // reasoning as tellrawTranslationCoordinator above.
    private static AdvancementAnnounceTranslationCoordinator advancementAnnounceCoordinator;
    // Real-world follow-up fix (Toast/Advancement translation task session): per-player
    // /title (title/subtitle/actionbar) translation. Wired unconditionally (both physical
    // sides), same reasoning as the other server-side coordinators above.
    private static TitleTranslationCoordinator titleTranslationCoordinator;

    private AllTranslatorCore() {}

    public static synchronized void init(Path configDir) {
        if (translationService != null) {
            AllTranslator.LOGGER.warn("AllTranslatorCore.init() called more than once; ignoring.");
            return;
        }

        configManager = new ConfigManager(configDir);
        ConfigModel config = configManager.load();

        credentialStore = new CredentialStore(configDir);
        credentialStore.load();

        apiManager = new ApiManager();
        apiManager.reload(config.apis);

        cacheManager = new CacheManager(config.memoryCacheCapacity, config.dynamicTextCacheTtlDays);

        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "alltranslator-worker");
            t.setDaemon(true);
            return t;
        });

        // Phase 13 fix: force HTTP/1.1. HttpClient defaults to attempting HTTP/2
        // upgrade negotiation, which real-world testing showed hangs (times out)
        // against local LLM servers such as LM Studio's built-in llama.cpp HTTP
        // server, which only speaks HTTP/1.1 - the mod-side request never even
        // reached LM Studio's own request log, while a plain curl (also HTTP/1.1
        // by default) succeeded in ~4.6s against the same endpoint. Cloud API
        // providers (OpenAI, DeepL, Google) all support HTTP/1.1 as well, so this
        // is not expected to regress the already-working cloud-provider path.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        translationService = new TranslationService(
                apiManager,
                cacheManager,
                credentialStore,
                ProviderFactory.createDefaultProviders(httpClient),
                new PendingRequestMap(),
                executor,
                config.maxConcurrentHttpRequests
        );
        // Phase 14 (ARCHITECTURE.md §26.4): read the mode on every request, so a config
        // change (local screen or remote save) takes effect without a restart.
        translationService.setSelectionModeSupplier(() -> configManager.model().apiSelectionMode);

        // Language resolution: forced config override -> client's own MC language -> en_us.
        languageResolver = new LanguageResolver(configManager);
        languageResolver.setClientLanguageSupplier(new MinecraftClientLanguageSupplier());

        // Existing-translation sources, highest priority first: user-authored config/alltranslator/lang/
        // overrides, then whatever Minecraft/resource packs already provide.
        customLanguageFileManager = new CustomLanguageFileManager(configDir);
        customLanguageFileManager.initialize(); // creates lang/ + default ja_jp.json starter on first run
        languageDataSource = new MinecraftLanguageDataSource(); // no-ops safely on dedicated server
        existingTranslationChecker = new ExistingTranslationChecker(
                new CompositeLanguageDataSource(List.of(customLanguageFileManager, languageDataSource)));

        localizedTextResolver = new LocalizedTextResolver(existingTranslationChecker, translationService, languageResolver);

        generatedLangPackStore = new GeneratedLangPackStore(configDir);

        // Phase 6: server support. See ARCHITECTURE.md §9 (opt-in server-side chat
        // translation) and §8.2 (persistent cache world-save path).
        playerTranslationSettingsManager = new PlayerTranslationSettingsManager(configDir);
        playerTranslationSettingsManager.load();
        perPlayerLanguageResolver = new PerPlayerLanguageResolver(configManager, playerTranslationSettingsManager);
        serverChatTranslationCoordinator = new ServerChatTranslationCoordinator(translationService, perPlayerLanguageResolver, configManager);
        tellrawTranslationCoordinator = new TellrawTranslationCoordinator(translationService, perPlayerLanguageResolver, configManager);
        advancementAnnounceCoordinator = new AdvancementAnnounceTranslationCoordinator(translationService, existingTranslationChecker, perPlayerLanguageResolver, configManager);
        titleTranslationCoordinator = new TitleTranslationCoordinator(translationService, perPlayerLanguageResolver, configManager);
        WorldCacheConnector.install(cacheManager);

        // Phase 7: FTB Quests optional compatibility (detection-only skeleton; see
        // compat/ftbquests/FtbQuestsCompat.java for why no real hooks exist yet).
        com.kiyo.alltranslator.compat.ftbquests.FtbQuestsCompat.reportStatus();

        // Phase 14: live sidebar scoreboard (per-enabled-API status/in-flight count,
        // queue depth). Off by default (ConfigModel#scoreboardEnabled) - install()
        // just wires the lifecycle/tick hooks, it doesn't turn anything on by itself.
        com.kiyo.alltranslator.server.ScoreboardManager.install();

        // Phase 14 (SERVER_PROXY): common (both-physical-side-safe) C2S/S2C payload
        // registration. See AllTranslatorNetworking's Javadoc for why this is safe to
        // call unconditionally here (same reasoning as ServerChatTranslationCoordinator
        // above) and why the CLIENT-side response receiver is registered separately,
        // from AllTranslatorClientCore.init() instead.
        com.kiyo.alltranslator.network.AllTranslatorNetworking.registerCommon();
        com.kiyo.alltranslator.network.ServerConfigNetworking.registerCommon();
        com.kiyo.alltranslator.network.PlayerLanguageSyncNetworking.registerCommon();

        // Phase 9: /alltranslator (alias /at) commands, ARCHITECTURE.md §13. Registered via
        // Architectury's common CommandRegistrationEvent (verified via javap/sources: fires on
        // both dedicated servers and the integrated/singleplayer server - same registration
        // point PlayerListMixin's non-client "mixins" array already relies on being active on
        // both physical sides).
        dev.architectury.event.events.common.CommandRegistrationEvent.EVENT.register(
                (dispatcher, registry, selection) -> com.kiyo.alltranslator.command.CommandHandlers.register(dispatcher));

        AllTranslator.LOGGER.info("All Translator translation core initialized (" + config.apis.size() + " API config(s) loaded)");
    }

    public static TranslationService translationService() { return translationService; }
    public static ApiManager apiManager() { return apiManager; }
    public static CacheManager cacheManager() { return cacheManager; }
    public static ConfigManager configManager() { return configManager; }
    public static CredentialStore credentialStore() { return credentialStore; }
    public static LanguageResolver languageResolver() { return languageResolver; }
    public static MinecraftLanguageDataSource languageDataSource() { return languageDataSource; }
    public static CustomLanguageFileManager customLanguageFileManager() { return customLanguageFileManager; }
    public static ExistingTranslationChecker existingTranslationChecker() { return existingTranslationChecker; }
    public static LocalizedTextResolver localizedTextResolver() { return localizedTextResolver; }
    public static GeneratedLangPackStore generatedLangPackStore() { return generatedLangPackStore; }

    /** Called only from AllTranslatorClientCore#init() (client-side only). */
    public static synchronized void installClientTextInterceptors(
            TranslatableTextInterceptor tooltip,
            TranslatableTextInterceptor itemName,
            TranslatableTextInterceptor entityName) {
        tooltipInterceptor = tooltip;
        itemNameInterceptor = itemName;
        entityNameInterceptor = entityName;
    }

    public static TranslatableTextInterceptor tooltipInterceptor() { return tooltipInterceptor; }
    public static TranslatableTextInterceptor itemNameInterceptor() { return itemNameInterceptor; }
    public static TranslatableTextInterceptor entityNameInterceptor() { return entityNameInterceptor; }

    /** Called only from AllTranslatorClientCore#init() (client-side only). */
    public static synchronized void installAdvancementToastInterceptor(TranslatableTextInterceptor interceptor) {
        advancementToastInterceptor = interceptor;
    }

    public static TranslatableTextInterceptor advancementToastInterceptor() { return advancementToastInterceptor; }

    /** Called only from AllTranslatorClientCore#init() (client-side only). */
    public static synchronized void installRecipeToastInterceptor(TranslatableTextInterceptor interceptor) {
        recipeToastInterceptor = interceptor;
    }

    public static TranslatableTextInterceptor recipeToastInterceptor() { return recipeToastInterceptor; }

    /** Called only from AllTranslatorClientCore#init() (client-side only). */
    public static synchronized void installBossBarNameInterceptor(TranslatableTextInterceptor interceptor) {
        bossBarNameInterceptor = interceptor;
    }

    public static TranslatableTextInterceptor bossBarNameInterceptor() { return bossBarNameInterceptor; }

    /** Called only from AllTranslatorClientCore#init() (client-side only). */
    public static synchronized void installEffectNameInterceptor(TranslatableTextInterceptor interceptor) {
        effectNameInterceptor = interceptor;
    }

    public static TranslatableTextInterceptor effectNameInterceptor() { return effectNameInterceptor; }

    /** Called only from AllTranslatorClientCore#init() (client-side only). */
    public static synchronized void installChatTranslationCoordinator(ChatTranslationCoordinator coordinator) {
        chatTranslationCoordinator = coordinator;
    }

    public static ChatTranslationCoordinator chatTranslationCoordinator() { return chatTranslationCoordinator; }

    /** Called only from AllTranslatorClientCore#init() (client-side only). */
    public static synchronized void installModJarLangTranslationCoordinator(ModJarLangTranslationCoordinator coordinator) {
        modJarLangTranslationCoordinator = coordinator;
    }

    public static ModJarLangTranslationCoordinator modJarLangTranslationCoordinator() { return modJarLangTranslationCoordinator; }

    // Phase 6 accessors (both physical sides; see field comments above).
    public static PlayerTranslationSettingsManager playerTranslationSettingsManager() { return playerTranslationSettingsManager; }
    public static PerPlayerLanguageResolver perPlayerLanguageResolver() { return perPlayerLanguageResolver; }
    public static ServerChatTranslationCoordinator serverChatTranslationCoordinator() { return serverChatTranslationCoordinator; }
    public static TellrawTranslationCoordinator tellrawTranslationCoordinator() { return tellrawTranslationCoordinator; }
    public static AdvancementAnnounceTranslationCoordinator advancementAnnounceCoordinator() { return advancementAnnounceCoordinator; }
    public static TitleTranslationCoordinator titleTranslationCoordinator() { return titleTranslationCoordinator; }

    public static void shutdown() {
        if (executor != null) executor.shutdown();
    }
}
