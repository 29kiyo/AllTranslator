package com.kiyo.alltranslator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (platform-agnostic) entry point for All Translator.
 *
 * Platform bootstrap classes (fabric/neoforge) call {@link #init()} once
 * their own loader has finished its own initialization.
 *
 * Phase 1 scope: project skeleton, mod id, shared logger only.
 * Translation services are wired up starting in Phase 2.
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
     */
    public static void init() {
        LOGGER.info("{} common initialization complete (mod id: {})", MOD_NAME, MOD_ID);
    }
}
