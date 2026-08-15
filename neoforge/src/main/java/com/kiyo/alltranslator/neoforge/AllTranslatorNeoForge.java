package com.kiyo.alltranslator.neoforge;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import com.kiyo.alltranslator.AllTranslator;

@Mod(AllTranslator.MOD_ID)
public final class AllTranslatorNeoForge {
    public AllTranslatorNeoForge() {
        AllTranslator.LOGGER.info("Loading {} on NeoForge", AllTranslator.MOD_NAME);
        // Run our common setup.
        AllTranslator.init(FMLPaths.CONFIGDIR.get().resolve(AllTranslator.MOD_ID));
    }
}
