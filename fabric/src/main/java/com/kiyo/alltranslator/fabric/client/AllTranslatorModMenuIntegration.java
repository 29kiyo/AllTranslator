package com.kiyo.alltranslator.fabric.client;

import com.kiyo.alltranslator.client.gui.AllTranslatorConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu integration entry point (com.terraformersmc.modmenu.api.ModMenuApi,
 * confirmed real and MC 26.2-compatible via modmenu-20.0.1-sources.jar - Phase 7).
 *
 * Registered via the "modmenu" fabric.mod.json entrypoint, which Fabric Loader only
 * invokes when Mod Menu is actually loaded - so this class is never touched (and the
 * compileOnly dependency on ModMenuApi causes no issues) when Mod Menu is absent
 * (CLAUDE.md §16: optional integrations must not crash when the target mod is absent).
 *
 * Phase 8: now opens the real, shared AllTranslatorConfigScreen (common module) -
 * the exact same screen/backing ConfigManager the L-keybinding opens
 * (ARCHITECTURE.md §11/§15's "same Screen instance generation path" requirement).
 * AllTranslatorModMenuPlaceholderScreen (Phase 7's stand-in) is removed as part of
 * this change; nothing else referenced it.
 */
public final class AllTranslatorModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AllTranslatorConfigScreen::new;
    }
}
