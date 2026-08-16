package com.kiyo.alltranslator.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.client.AllTranslatorClientCore;

@Mod(AllTranslator.MOD_ID)
public final class AllTranslatorNeoForge {

    public AllTranslatorNeoForge(IEventBus modEventBus) {
        AllTranslator.LOGGER.info("Loading {} on NeoForge", AllTranslator.MOD_NAME);
        // Run our common setup.
        AllTranslator.init(FMLPaths.CONFIGDIR.get().resolve(AllTranslator.MOD_ID));
        // Client-only Phase 4 hooks: FMLClientSetupEvent only ever fires on the
        // physical client, so this is a safe place to call AllTranslatorClientCore.
        modEventBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        AllTranslatorClientCore.init();
    }
}
