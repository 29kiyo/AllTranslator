package com.kiyo.alltranslator.fabric;

import net.fabricmc.api.ModInitializer;

import com.kiyo.alltranslator.AllTranslator;

public final class AllTranslatorFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        AllTranslator.LOGGER.info("Loading {} on Fabric", AllTranslator.MOD_NAME);

        // Run our common setup.
        AllTranslator.init();
    }
}
