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
import com.kiyo.alltranslator.service.ApiManager;
import com.kiyo.alltranslator.service.CacheManager;
import com.kiyo.alltranslator.service.PendingRequestMap;
import com.kiyo.alltranslator.service.TranslationService;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import com.kiyo.alltranslator.text.ChatTranslationCoordinator;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.kiyo.alltranslator.AllTranslator;

/**
 * Loader-agnostic wiring for the Translation Core (Phase 2), Language Files (Phase 3),
 * and Minecraft Text hooks (Phase 4 - see AllTranslatorClientCore for the client-only
 * half of Phase 4's wiring).
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

        cacheManager = new CacheManager(config.memoryCacheCapacity);

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

    public static void shutdown() {
        if (executor != null) executor.shutdown();
    }
}
