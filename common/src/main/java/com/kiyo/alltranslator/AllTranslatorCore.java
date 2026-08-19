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
    private static ExecutorService executor;

    // Phase 4: installed only by AllTranslatorClientCore#init() (client-side only).
    // Remain null on dedicated servers, which is what keeps ItemStackMixin/EntityMixin
    // safe no-ops on the server per ARCHITECTURE.md §9.
    private static TranslatableTextInterceptor tooltipInterceptor;
    private static TranslatableTextInterceptor itemNameInterceptor;
    private static TranslatableTextInterceptor entityNameInterceptor;

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

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        translationService = new TranslationService(
                apiManager,
                cacheManager,
                credentialStore,
                ProviderFactory.createDefaultProviders(httpClient),
                new PendingRequestMap(),
                executor
        );

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

        // Phase 6: server support. See ARCHITECTURE.md §9 (opt-in server-side chat
        // translation) and §8.2 (persistent cache world-save path).
        playerTranslationSettingsManager = new PlayerTranslationSettingsManager(configDir);
        playerTranslationSettingsManager.load();
        perPlayerLanguageResolver = new PerPlayerLanguageResolver(configManager, playerTranslationSettingsManager);
        serverChatTranslationCoordinator = new ServerChatTranslationCoordinator(translationService, perPlayerLanguageResolver);
        WorldCacheConnector.install(cacheManager);

        // Phase 7: FTB Quests optional compatibility (detection-only skeleton; see
        // compat/ftbquests/FtbQuestsCompat.java for why no real hooks exist yet).
        com.kiyo.alltranslator.compat.ftbquests.FtbQuestsCompat.reportStatus();

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
    public static synchronized void installChatTranslationCoordinator(ChatTranslationCoordinator coordinator) {
        chatTranslationCoordinator = coordinator;
    }

    public static ChatTranslationCoordinator chatTranslationCoordinator() { return chatTranslationCoordinator; }

    // Phase 6 accessors (both physical sides; see field comments above).
    public static PlayerTranslationSettingsManager playerTranslationSettingsManager() { return playerTranslationSettingsManager; }
    public static PerPlayerLanguageResolver perPlayerLanguageResolver() { return perPlayerLanguageResolver; }
    public static ServerChatTranslationCoordinator serverChatTranslationCoordinator() { return serverChatTranslationCoordinator; }

    public static void shutdown() {
        if (executor != null) executor.shutdown();
    }
}
