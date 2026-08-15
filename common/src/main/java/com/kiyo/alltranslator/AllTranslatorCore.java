package com.kiyo.alltranslator;

import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.config.CredentialStore;
import com.kiyo.alltranslator.provider.ProviderFactory;
import com.kiyo.alltranslator.service.ApiManager;
import com.kiyo.alltranslator.service.CacheManager;
import com.kiyo.alltranslator.service.PendingRequestMap;
import com.kiyo.alltranslator.service.TranslationService;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.kiyo.alltranslator.AllTranslator;

/**
 * Loader-agnostic wiring for the Translation Core (Phase 2).
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
    private static ExecutorService executor;

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

        AllTranslator.LOGGER.info("All Translator translation core initialized (" + config.apis.size() + " API config(s) loaded)");
    }

    public static TranslationService translationService() { return translationService; }
    public static ApiManager apiManager() { return apiManager; }
    public static CacheManager cacheManager() { return cacheManager; }
    public static ConfigManager configManager() { return configManager; }
    public static CredentialStore credentialStore() { return credentialStore; }

    public static void shutdown() {
        if (executor != null) executor.shutdown();
    }
}
