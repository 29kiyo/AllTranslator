package com.kiyo.alltranslator.server;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.service.CacheManager;

import dev.architectury.event.events.common.LifecycleEvent;

import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * Phase 6: connects Phase 2's CacheManager persistent tier to the world save directory
 * (ARCHITECTURE.md §8.2), via Architectury's common LifecycleEvent - confirmed against
 * architectury-21.0.7-sources.jar (dev.architectury.event.events.common.LifecycleEvent:
 * SERVER_STARTED/SERVER_STOPPED, both Event&lt;ServerState&gt; with a MinecraftServer param).
 * Works identically for a dedicated server and an integrated (singleplayer) server, since
 * both are MinecraftServer instances - no loader-specific code needed.
 *
 * Path resolution confirmed against 26.2 sources: MinecraftServer#getWorldPath(LevelResource)
 * delegates to LevelStorageSource.LevelStorageAccess#getLevelPath(LevelResource), and
 * LevelResource.ROOT ("." ) resolves to the world save's root directory.
 */
public final class WorldCacheConnector {

    private static boolean installed = false;

    private WorldCacheConnector() {}

    public static synchronized void install(CacheManager cacheManager) {
        if (installed) return;
        installed = true;

        LifecycleEvent.SERVER_STARTED.register(server -> {
            Path worldRoot = server.getWorldPath(LevelResource.ROOT);
            Path translationsDir = worldRoot.resolve(AllTranslator.MOD_ID).resolve("translations");
            cacheManager.setPersistentDirectory(translationsDir);
            AllTranslator.LOGGER.info("All Translator persistent cache connected to world save: " + translationsDir);
        });

        LifecycleEvent.SERVER_STOPPED.register(server -> {
            // Detach so a subsequently loaded world (e.g. singleplayer: leaving to another
            // save) never reuses the previous world's persistent cache directory.
            cacheManager.setPersistentDirectory(null);
        });
    }
}
