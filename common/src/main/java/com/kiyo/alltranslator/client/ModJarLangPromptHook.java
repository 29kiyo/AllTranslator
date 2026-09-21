package com.kiyo.alltranslator.client;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.client.gui.ModJarLangConfirmScreen;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.modjarlang.ModJarLangPending;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Opens ModJarLangConfirmScreen the first time the title screen is showing for each target
 * language in a game session (so changing the target language and coming back to the title
 * screen offers the mods again). Driven by Architectury ClientTickEvent.CLIENT_POST (same
 * pattern as RemoteConfigClientReceiver). Fully client-local: no server involvement.
 */
public final class ModJarLangPromptHook {

    private static final Set<String> promptedLanguages = new HashSet<>();

    private ModJarLangPromptHook() {}

    public static void onClientTick(Minecraft client) {
        Screen current = client.gui.screen();
        if (!(current instanceof TitleScreen)) {
            return;
        }
        ConfigModel model = AllTranslatorCore.configManager().model();
        if (!model.translationEnabled || !model.modJarLangPromptEnabled) {
            return;
        }
        if (AllTranslatorCore.modJarLangTranslationCoordinator() == null
                || AllTranslatorCore.generatedLangPackStore() == null) {
            return;
        }
        String lang = AllTranslatorCore.languageResolver().resolveTargetLanguage();
        if (!promptedLanguages.add(lang)) {
            return; // one attempt per target language per session, whatever the outcome
        }
        List<ModJarLangPending> pending = ModJarLangPending.collect(
                lang, AllTranslatorCore.generatedLangPackStore(), model.modJarLangIgnoredMods);
        if (pending.isEmpty()) {
            return;
        }
        client.gui.setScreen(new ModJarLangConfirmScreen(current, lang, pending));
    }

    /**
     * Manual entry point (config screen button): same screen, but the ignore list is not
     * applied, so mods unchecked earlier can be picked again. No pending mods is shown as
     * an empty state by the screen itself.
     */
    public static void openManually(Minecraft client, Screen parent) {
        if (AllTranslatorCore.modJarLangTranslationCoordinator() == null
                || AllTranslatorCore.generatedLangPackStore() == null) {
            return;
        }
        String lang = AllTranslatorCore.languageResolver().resolveTargetLanguage();
        List<ModJarLangPending> pending = ModJarLangPending.collect(
                lang, AllTranslatorCore.generatedLangPackStore(), java.util.List.of());
        client.gui.setScreen(new ModJarLangConfirmScreen(parent, lang, pending));
    }
}
