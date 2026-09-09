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
 * Language priority (Phase 14 revision - see below for why the order changed from the
 * original Phase 6 design):
 *   1. per-player languageOverride (PlayerTranslationSettingsManager, set via /alltranslator
 *      language - an explicit, individually-chosen preference)
 *   2. config.forcedTargetLanguage (server admin's default for everyone who HASN'T personally
 *      overridden - ConfigManager)
 *   3. ServerPlayer#clientInformation().language() (vanilla-synced, always present, works for
 *      any client)
 *   4. LanguageResolver.DEFAULT_LANGUAGE ("en_us")
 *
 * Phase 14 change (real-world request): originally forcedTargetLanguage outranked the
 * per-player override entirely, making it impossible for any individual player to opt out of
 * an admin's server-wide language even via an explicit command - e.g. an admin standardizing
 * the server on "ja" made it impossible for one English-speaking player to read chat in
 * English via /alltranslator language en, since resolve() returned "ja" before ever
 * consulting the per-player override. forcedTargetLanguage now acts as the server's default
 * for players who haven't personally chosen a language, rather than an unconditional
 * override - matching how "forced" is used elsewhere in this codebase (a starting point,
 * not an ceiling).
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
        String override = playerSettings.get(player.getUUID()).languageOverride();
        if (override != null && !override.isBlank()) {
            return LanguageResolver.normalize(override);
        }

        // Phase 14 (M-key auto-sync, user request): a value the client itself
        // pushed via its own M-key ConfigModel#forcedTargetLanguage - see
        // PlayerTranslationSettings#autoSyncedLanguage's Javadoc. Deliberately
        // ranked BELOW the explicit /alltranslator language override above (an
        // individually-typed command always wins) but ABOVE the server admin's
        // own forcedTargetLanguage default below - this is still a personal,
        // player-specific signal, just one the player set via a different (more
        // familiar) screen instead of typing a command.
        String autoSynced = playerSettings.get(player.getUUID()).autoSyncedLanguage();
        if (autoSynced != null && !autoSynced.isBlank()) {
            return LanguageResolver.normalize(autoSynced);
        }

        String forced = configManager.model().forcedTargetLanguage;
        if (forced != null && !forced.isBlank()) {
            return LanguageResolver.normalize(forced);
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

    /**
     * Phase 14 (item 7, tellraw translation): mirrors isEnabledFor() above but
     * gated on ConfigModel#translateSystemMessages instead of
     * #serverSideChatTranslationEnabled - tellraw translation is deliberately
     * independent of the optional server-side CHAT mode (a server may want one
     * on without the other). Shares the same per-player opt-out flag
     * (PlayerTranslationSettingsManager#enabled()) rather than introducing a
     * separate one, on the reasoning that a player who has opted OUT of
     * server-side translation for themselves most likely wants that to apply
     * uniformly, not per-feature.
     */
    public boolean isEnabledForSystemMessages(ServerPlayer player) {
        ConfigModel model = configManager.model();
        if (!model.translationEnabled) return false;
        if (!model.translateSystemMessages) return false;
        Boolean perPlayer = playerSettings.get(player.getUUID()).enabled();
        return perPlayer == null || perPlayer;
    }
}
