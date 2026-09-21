package com.kiyo.alltranslator.neoforge;

import net.neoforged.bus.api.IEventBus;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.packs.PackType;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import com.kiyo.alltranslator.modjarlang.GeneratedLangPackRepositorySource;
import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.client.AllTranslatorClientCore;
import com.kiyo.alltranslator.text.ChatTranslationCoordinator;

@Mod(AllTranslator.MOD_ID)
public final class AllTranslatorNeoForge {

    public AllTranslatorNeoForge(IEventBus modEventBus) {
        AllTranslator.LOGGER.info("Loading {} on NeoForge", AllTranslator.MOD_NAME);
        // Run our common setup.
        AllTranslator.init(FMLPaths.CONFIGDIR.get().resolve(AllTranslator.MOD_ID));

        // Client-only constructor-time hooks (Mods-screen config factory, KeyMapping)
        // live in a separate class so a dedicated server never loads or verifies it.
        // Doing this inline made the verifier load Screen -> NoClassDefFoundError.
        if (Platform.getEnvironment() == Env.CLIENT) {
            AllTranslatorNeoForgeClient.registerConstructorTimeHooks();
        }

        // Client-only Phase 4 hooks: FMLClientSetupEvent only ever fires on the
        // physical client, so this is a safe place to call AllTranslatorClientCore.
        modEventBus.addListener(this::onClientSetup);
        // Mod jar lang bulk translation: AddPackFindersEvent is the official
        // NeoForge mechanism for this (confirmed via javap:
        // AddPackFindersEvent#addRepositorySource(RepositorySource)). Registered
        // here on modEventBus (fires for BOTH PackType.CLIENT_RESOURCES and
        // PackType.SERVER_DATA, on both physical sides) - the packType check inside
        // onAddPackFinders is what actually keeps this a client-only no-op on a
        // dedicated server, not the registration site itself.
        modEventBus.addListener(this::onAddPackFinders);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        AllTranslatorClientCore.init();
        // Phase 5: chat translation. Registered here (not in a static field / at
        // construction time) for the same reason as AllTranslatorClientCore.init():
        // this must only ever run on the physical client.
        NeoForge.EVENT_BUS.addListener(this::onClientChatReceived);
    }

    private void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return; // never runs on a dedicated server (SERVER_DATA only there)
        }
        event.addRepositorySource(
                new GeneratedLangPackRepositorySource(AllTranslatorCore.generatedLangPackStore()));
    }

    private void onClientChatReceived(ClientChatReceivedEvent.Player event) {
        // Listen-only: never cancels/replaces the event synchronously. Vanilla
        // displays the original message untouched; ChatTranslationCoordinator
        // patches it in place later once the async translation completes.
        ChatTranslationCoordinator coordinator = AllTranslatorCore.chatTranslationCoordinator();
        if (coordinator != null) {
            coordinator.onPlayerChatReceived(event.getPlayerChatMessage(), event.getBoundChatType());
        }
    }
}
