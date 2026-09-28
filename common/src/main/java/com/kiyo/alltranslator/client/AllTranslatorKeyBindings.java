package com.kiyo.alltranslator.client;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.client.gui.AllTranslatorConfigScreen;
import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Phase 8: the shared L-keybinding that opens AllTranslatorConfigScreen
 * (ARCHITECTURE.md §11 - same screen/backing ConfigManager as the Mod Menu entry).
 *
 * Registered through Architectury's common dev.architectury.registry.client.keymappings.
 * KeyMappingRegistry.
 *
 * IMPORTANT (bug found during manual Phase 8 testing - keybinding never appeared in
 * NeoForge's Controls screen): verified via javap that Architectury's NeoForge
 * implementation, dev.architectury.registry.client.keymappings.neoforge.
 * KeyMappingRegistryImpl, queues every KeyMapping passed to register() into a static
 * list and only ever hands that list to NeoForge's own key system from ITS OWN
 * RegisterKeyMappingsEvent listener - and that listener is installed by a static
 * initializer that only runs the first time this KeyMappingRegistry class is touched
 * anywhere in the mod. RegisterKeyMappingsEvent fires BEFORE FMLClientSetupEvent, so
 * calling register() only from FMLClientSetupEvent (as this project's other
 * client-only setup does) makes Architectury install its listener too late to catch
 * that event - the KeyMapping is queued but never flushed, so it silently never shows
 * up in Controls. Fixed by splitting keymapping creation from tick-handling:
 * createKeyMapping() is safe to call as early as a mod constructor (see
 * AllTranslatorNeoForge), while registerTickHandler() still only makes sense once
 * called from the confirmed client-only entrypoint.
 */
public final class AllTranslatorKeyBindings {

    private static KeyMapping openConfigKey;
    private static KeyMapping refreshKey;
    private static boolean tickHandlerRegistered = false;

    private AllTranslatorKeyBindings() {
    }

    /**
     * Creates the KeyMapping and registers it via Architectury's common KeyMappingRegistry.
     * Idempotent (safe to call more than once; only the first call has effect).
     *
     * On NeoForge this MUST be called before RegisterKeyMappingsEvent fires - in
     * practice, from the mod constructor (see AllTranslatorNeoForge's Javadoc for why
     * that is safe on a dedicated server: KeyMapping itself never touches GLFW/the
     * window, it is a plain data holder, so instantiating it during the constructor
     * that also runs on dedicated servers causes no harm).
     *
     * On Fabric there is no such timing constraint, so AllTranslatorClientCore#init()
     * calling this (via register()) from onInitializeClient works fine as-is.
     */
    public static synchronized void createKeyMapping() {
        if (openConfigKey != null) return;

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(AllTranslator.MOD_ID, "general"));

        // Phase 14: default changed from L to M per user request. Key name
        // ("key.alltranslator.open_config") deliberately left unchanged - it's
        // just a translation-key string identifying which binding this is in
        // the Controls screen and options.txt, not tied to the physical key
        // itself; renaming it would needlessly reset anyone's existing custom
        // rebind on upgrade.
        openConfigKey = new KeyMapping(
                "key.alltranslator.open_config",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_M,
                category);
        KeyMappingRegistry.register(openConfigKey);

        // Phase 14: /alltranslator refresh, one-key access to the same command
        // (ARCHITECTURE.md/CommandHandlers §13) - the only /alltranslator
        // subcommand a player would plausibly want mid-session rather than via
        // the config screen or chat.
        refreshKey = new KeyMapping(
                "key.alltranslator.refresh",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_K,
                category);
        KeyMappingRegistry.register(refreshKey);

        AllTranslator.LOGGER.info("{} keybindings registered (open config: M, refresh: K)", AllTranslator.MOD_NAME);
    }

    /**
     * Starts watching for the keypress via Architectury's common ClientTickEvent.
     * Idempotent. Must only be called from a confirmed physical-client entrypoint
     * (AllTranslatorClientCore#init()), same discipline as the rest of this project's
     * client-only bootstrap code.
     */
    public static synchronized void registerTickHandler() {
        if (tickHandlerRegistered) return;
        tickHandlerRegistered = true;

        ClientTickEvent.CLIENT_POST.register(client -> {
            if (openConfigKey != null) {
                while (openConfigKey.consumeClick()) {
                    if (client.gui.screen() == null) {
                        client.gui.setScreen(new AllTranslatorConfigScreen(null));
                    }
                }
            }
            if (refreshKey != null) {
                while (refreshKey.consumeClick()) {
                    // Phase 14: only makes sense while actually connected to a
                    // server (singleplayer's integrated server counts - getConnection()
                    // is non-null there too). Silently does nothing otherwise
                    // (e.g. main menu), same as chat commands typed while
                    // disconnected would be impossible to type in the first place.
                    if (client.getConnection() != null) {
                        client.getConnection().sendCommand("alltranslator refresh");
                    }
                }
            }
        });
    }

    /** Convenience for loaders without NeoForge's RegisterKeyMappingsEvent timing
     *  constraint (Fabric): does both steps back-to-back. Also safe to call from
     *  NeoForge's client-setup path, since createKeyMapping() is idempotent. */
    public static void register() {
        createKeyMapping();
        registerTickHandler();
    }
}
