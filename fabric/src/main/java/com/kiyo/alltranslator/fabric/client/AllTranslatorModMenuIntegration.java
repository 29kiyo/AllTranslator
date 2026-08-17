package com.kiyo.alltranslator.fabric.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Phase 7: Mod Menu integration entry point (com.terraformersmc.modmenu.api.ModMenuApi,
 * confirmed real and MC 26.2-compatible via modmenu-20.0.1-sources.jar).
 *
 * Registered via the "modmenu" fabric.mod.json entrypoint, which Fabric Loader only
 * invokes when Mod Menu is actually loaded - so this class is never touched (and the
 * compileOnly dependency on ModMenuApi causes no issues) when Mod Menu is absent
 * (CLAUDE.md §16: optional integrations must not crash when the target mod is absent).
 *
 * See AllTranslatorModMenuPlaceholderScreen for why this opens a placeholder instead
 * of the not-yet-built AllTranslatorConfigScreen (Phase 8).
 */
public final class AllTranslatorModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AllTranslatorModMenuPlaceholderScreen::new;
    }
}
