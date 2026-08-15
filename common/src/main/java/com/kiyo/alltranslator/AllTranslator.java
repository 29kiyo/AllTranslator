package com.kiyo.alltranslator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Common (platform-agnostic) entry point for All Translator.
 *
 * Platform bootstrap classes (fabric/neoforge) call {@link #init(Path)} once
 * their own loader has finished its own initialization, passing that loader's
 * config directory.
 *
 * Phase 2: wires up AllTranslatorCore (config/credentials/cache/API manager/
 * translation service). MC text hooks are not implemented yet (Phase 4+).
 */
public final class AllTranslator {
    public static final String MOD_ID = "alltranslator";
    public static final String MOD_NAME = "All Translator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private AllTranslator() {
    }

    /**
     * Runs shared (loader-independent) startup logic.
     * Called from every platform's entry point.
     *
     * @param configDir loader-specific config directory (e.g. .../config/alltranslator)
     */
    public static void init(Path configDir) {
        AllTranslatorCore.init(configDir);
        LOGGER.info("{} common initialization complete (mod id: {})", MOD_NAME, MOD_ID);
    }
}
