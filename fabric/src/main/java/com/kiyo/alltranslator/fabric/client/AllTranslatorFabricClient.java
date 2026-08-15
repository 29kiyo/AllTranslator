package com.29kiyo.alltranslator.fabric.client;

import net.fabricmc.api.ClientModInitializer;

import com.29kiyo.alltranslator.AllTranslator;

public final class AllTranslatorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // This entrypoint is suitable for setting up client-specific logic,
        // such as translation rendering hooks (added starting Phase 4).
        AllTranslator.LOGGER.info("Loading {} Fabric client entrypoint", AllTranslator.MOD_NAME);
    }
}
