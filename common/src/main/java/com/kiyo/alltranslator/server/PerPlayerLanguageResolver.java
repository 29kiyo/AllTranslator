package com.kiyo.alltranslator.server;

import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.lang.LanguageResolver;

import net.minecraft.server.level.ServerPlayer;

/**
 * Phase 6: server-side per-player language + enable/disable resolution for the OPTIONAL
 * server-side chat translation mode (ARCHITECTURE.md §9). Not used by Phase 3/4 (existing-
 * translation lookups) or Phase 5 (client-side chat), which keep using LanguageResolver's
 * client-only resolution as-is.
 *
 * Language priority (revises ARCHITECTURE.md §4's original "client sends a language code via
 * a custom payload" plan - unnecessary, since MC 26.2 vanilla already syncs this automatically
 * for every client, modded or not, via ServerboundClientInformationPacket ->
 * ServerPlayer#clientInformation()#language()):
 *   1. config.forcedTargetLanguage (server admin global override, ConfigManager)
 *   2. per-player languageOverride (PlayerTranslationSettingsManager)
 *   3. ServerPlayer#clientInformation().language() (vanilla-synced, always present, works for
 *      any client)
 *   4. LanguageResolver.DEFAULT_LANGUAGE ("en_us")
 *
 * IMPORTANT (confirmed at compile time, not just from sources): ServerPlayer#getLanguage() and
 * its backing `language` field only become public via NeoForge's access transformer, applied
 * to the loader-specific patched Minecraft jar. The common module compiles against the
 * un-transformed (Architectury/Fabric-mapped) jar, where that field/getter is private - so
 * getLanguage() is NOT usable from common code, even though it is confirmed to exist and work
 * at runtime on NeoForge. ServerPlayer#clientInformation() (returning the ClientInformation
 * record, which has a public language() accessor) is public on both loaders without any AT,
 * so that is used instead - same underlying vanilla-synced data, common-safe accessor.
 */
public final class PerPlayerLanguageResolver {

    private final ConfigManager configManager;
    private final PlayerTranslationSettingsManager playerSettings;

    public PerPlayerLanguageResolver(ConfigManager configManager, PlayerTranslationSettingsManager playerSettings) {
        this.configManager = configManager;
        this.playerSettings = playerSettings;
    }

    public String resolve(ServerPlayer player) {
        String forced = configManager.model().forcedTargetLanguage;
        if (forced != null && !forced.isBlank()) {
            return LanguageResolver.normalize(forced);
        }

        String override = playerSettings.get(player.getUUID()).languageOverride();
        if (override != null && !override.isBlank()) {
            return LanguageResolver.normalize(override);
        }

        String vanilla = player.clientInformation().language();
        if (vanilla != null && !vanilla.isBlank()) {
            return LanguageResolver.normalize(vanilla);
        }

        return LanguageResolver.DEFAULT_LANGUAGE;
    }

    /** Server-wide switch AND per-player opt-out, per PHASE_INSTRUCTIONS.md Phase 6. */
    public boolean isEnabledFor(ServerPlayer player) {
        ConfigModel model = configManager.model();
        if (!model.translationEnabled) return false; // mod-wide master switch (shared with Phase 4/5)
        if (!model.serverSideChatTranslationEnabled) return false; // server-side chat mode opt-in (§9)
        Boolean perPlayer = playerSettings.get(player.getUUID()).enabled();
        return perPlayer == null || perPlayer;
    }
}
