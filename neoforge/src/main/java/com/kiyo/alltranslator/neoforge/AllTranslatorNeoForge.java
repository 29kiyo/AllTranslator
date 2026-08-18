package com.kiyo.alltranslator.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.client.AllTranslatorClientCore;
import com.kiyo.alltranslator.client.AllTranslatorKeyBindings;
import com.kiyo.alltranslator.client.gui.AllTranslatorConfigScreen;
import com.kiyo.alltranslator.text.ChatTranslationCoordinator;

@Mod(AllTranslator.MOD_ID)
public final class AllTranslatorNeoForge {

    public AllTranslatorNeoForge(IEventBus modEventBus) {
        AllTranslator.LOGGER.info("Loading {} on NeoForge", AllTranslator.MOD_NAME);
        // Run our common setup.
        AllTranslator.init(FMLPaths.CONFIGDIR.get().resolve(AllTranslator.MOD_ID));

        // Phase 8: NeoForge's "Mods" screen -> Config button, ARCHITECTURE.md §15.1.
        // Registered here (constructor), NOT inside onClientSetup below, because
        // ModLoadingContext's active-container ThreadLocal (verified via javap:
        // ModLoadingContext.get()/registerExtensionPoint(Class, Supplier)) is only
        // populated while this mod's constructor is running - by the time
        // FMLClientSetupEvent fires it is no longer valid. This is a genuine exception
        // to this project's "client-only code only inside FMLClientSetupEvent" rule
        // (AllTranslatorClientCore's own Javadoc), forced by the extension-point API's
        // own contract rather than a relaxation of that rule: only a Supplier<Screen>
        // reference is created here, and per normal JVM lambda semantics that lambda's
        // body (which touches client-only Screen classes) is never invoked unless/until
        // a client actually opens the Mods/Config screen, so this stays safe on a
        // dedicated server exactly like the existing FtbQuestsCompat/language-supplier
        // guards elsewhere in the project.
        ModLoadingContext.get().registerExtensionPoint(
                IConfigScreenFactory.class,
                () -> (container, parentScreen) -> new AllTranslatorConfigScreen(parentScreen));

        // Phase 8 fix (see AllTranslatorKeyBindings' Javadoc for the full javap-verified
        // explanation): Architectury's NeoForge KeyMappingRegistryImpl only installs its
        // own RegisterKeyMappingsEvent listener the first time this class is touched, and
        // that event fires before FMLClientSetupEvent. Creating the KeyMapping here in the
        // constructor - not in onClientSetup below - guarantees Architectury's listener is
        // installed in time. KeyMapping itself never touches GLFW/the window (verified via
        // javap: it is a plain data holder), so constructing it here is safe even though
        // this constructor also runs on a dedicated server.
        AllTranslatorKeyBindings.createKeyMapping();

        // Client-only Phase 4 hooks: FMLClientSetupEvent only ever fires on the
        // physical client, so this is a safe place to call AllTranslatorClientCore.
        modEventBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        AllTranslatorClientCore.init();
        // Phase 5: chat translation. Registered here (not in a static field / at
        // construction time) for the same reason as AllTranslatorClientCore.init():
        // this must only ever run on the physical client.
        NeoForge.EVENT_BUS.addListener(this::onClientChatReceived);
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
