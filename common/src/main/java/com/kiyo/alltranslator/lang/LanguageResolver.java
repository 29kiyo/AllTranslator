package com.kiyo.alltranslator.lang;

import com.kiyo.alltranslator.config.ConfigManager;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Resolves the effective target language per ARCHITECTURE.md §4:
 *   config.forcedTargetLanguage (explicit user override)
 *     ↓ (if unset/blank)
 *   client-supplied language (wired via setClientLanguageSupplier - not yet connected
 *   to Minecraft.getInstance().options.languageCode() as of Phase 3; that hook-up
 *   happens when a client entry point exists, Phase 4)
 *     ↓ (if unavailable)
 *   DEFAULT_LANGUAGE ("en_us")
 *
 * Per-player (server) resolution is a Phase 6 concern (per-player map, synced from client).
 * This class never throws and never blocks.
 */
public final class LanguageResolver {

    public static final String DEFAULT_LANGUAGE = "en_us";

    private final ConfigManager configManager;
    private volatile Supplier<String> clientLanguageSupplier;

    public LanguageResolver(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /** Wired by the client bootstrap once one exists (Phase 4). Not set = falls through. */
    public void setClientLanguageSupplier(Supplier<String> supplier) {
        this.clientLanguageSupplier = supplier;
    }

    public String resolveTargetLanguage() {
        String forced = configManager.model().forcedTargetLanguage;
        if (forced != null && !forced.isBlank()) {
            return normalize(forced);
        }

        Supplier<String> supplier = clientLanguageSupplier;
        if (supplier != null) {
            try {
                String clientLang = supplier.get();
                if (clientLang != null && !clientLang.isBlank()) {
                    return normalize(clientLang);
                }
            } catch (Exception e) {
                // Never let a client-side lookup failure break translation resolution.
            }
        }

        return DEFAULT_LANGUAGE;
    }

    public static String normalize(String languageCode) {
        return languageCode.trim().toLowerCase(Locale.ROOT);
    }
}
