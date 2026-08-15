package com.29kiyo.alltranslator.neoforge;

import net.neoforged.fml.common.Mod;

import com.29kiyo.alltranslator.AllTranslator;

@Mod(AllTranslator.MOD_ID)
public final class AllTranslatorNeoForge {
    public AllTranslatorNeoForge() {
        AllTranslator.LOGGER.info("Loading {} on NeoForge", AllTranslator.MOD_NAME);

        // Run our common setup.
        AllTranslator.init();
    }
}
