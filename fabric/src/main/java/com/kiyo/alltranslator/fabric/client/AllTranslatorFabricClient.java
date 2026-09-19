package com.kiyo.alltranslator.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.client.AllTranslatorClientCore;
import com.kiyo.alltranslator.modjarlang.GeneratedLangPackRepositorySource;

public final class AllTranslatorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AllTranslator.LOGGER.info("Loading {} Fabric client entrypoint", AllTranslator.MOD_NAME);
        AllTranslatorClientCore.init();
        FabricChatTranslationHook.register();
        // Mod jar lang bulk translation: register the generated-pack directory as
        // an always-on resource pack source. IMPORTANT (found via javap investigation):
        // PackRepository#addPackFinder does NOT exist in vanilla Minecraft at all - it
        // is a NeoForge-exclusive patch (absent from the plain "official" Mojang-mapped
        // jar, present only in NeoForge's own patched jar). Fabric API instead exposes
        // this capability via its own PackRepositoryMixin injecting a public "sources"
        // Set field directly onto the runtime PackRepository instance (confirmed via
        // javap against fabric-resource-loader-v1's PackRepositoryMixin.class) - so this
        // is accessed reflectively here, same established pattern as this project's
        // WidgetFrameworkCompat adapters (LibIpnCompat etc).
        FabricPackRepositorySourceInjector.inject(
                Minecraft.getInstance().getResourcePackRepository(),
                new GeneratedLangPackRepositorySource(AllTranslatorCore.generatedLangPackStore()));
    }
}
