package com.kiyo.alltranslator.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.client.AllTranslatorClientCore;

public final class AllTranslatorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AllTranslator.LOGGER.info("Loading {} Fabric client entrypoint", AllTranslator.MOD_NAME);
        AllTranslatorClientCore.init();
        FabricChatTranslationHook.register();
    }
}
