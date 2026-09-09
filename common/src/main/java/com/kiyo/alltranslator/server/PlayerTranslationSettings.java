package com.kiyo.alltranslator.server;

/**
 * Per-player override state (Phase 6). Persisted in config/alltranslator/player-settings.json,
 * keyed by player UUID. Both fields are nullable - "unset" means "inherit the server default":
 *  - enabled == null: follow the server-wide serverSideChatTranslationEnabled switch (ConfigModel).
 *  - enabled == false: this player has explicitly opted out of server-side chat translation,
 *    regardless of the server-wide switch.
 *  - languageOverride == null: use the player's own client-reported language
 *    (ServerPlayer#getLanguage(), itself already per-player - see ARCHITECTURE.md §4 revision)
 *    or the config-wide forcedTargetLanguage.
 *  - languageOverride != null: an explicit per-player target language override, taking
 *    priority over the player's own client language (but still below the admin's global
 *    forcedTargetLanguage).
 *
 * No command currently sets these (Phase 9); for now they can only be edited by hand-editing
 * player-settings.json while the server is offline, or programmatically by a future config UI
 * (Phase 8) / commands (Phase 9).
 */
public final class PlayerTranslationSettings {

    public static final PlayerTranslationSettings DEFAULT = new PlayerTranslationSettings(null, null, null);

    // Not final: Gson populates these fields directly via reflection when deserializing
    // player-settings.json (it does not go through the constructor below), which on modern
    // JVMs otherwise triggers "final field mutated reflectively" warnings (and will be
    // outright blocked in a future Java release). Immutability is still enforced at the API
    // level: there are no setters, only the constructor and the getters below - callers
    // outside this class can't mutate an instance either way.
    private Boolean enabled;
    private String languageOverride;
    /**
     * Phase 14 (M-key auto-sync, user request): distinct from languageOverride
     * above, which is set ONLY via the explicit /alltranslator language command
     * and always takes priority. This field is set automatically whenever the
     * client's OWN local ConfigModel#forcedTargetLanguage (M-key screen) is
     * non-blank, via a new lightweight C2S payload (PlayerLanguageSyncPayloads),
     * sent on login and whenever that screen is saved. Reasoning (user request):
     * remembering to separately run /alltranslator language after already
     * setting a language in the familiar M-key screen is easy to forget, and for
     * a lone player who is effectively both "the server admin" and "the only
     * player" there is no real reason those two settings should require two
     * separate actions. Kept as a SEPARATE field from languageOverride (rather
     * than writing directly into it) specifically so an explicit
     * /alltranslator language command - a deliberate, individually-typed choice
     * - is never silently overwritten by a later, merely-incidental M-key save
     * (e.g. opening the M-key screen for an unrelated setting and hitting Done
     * with an old value still in the language box). See
     * PerPlayerLanguageResolver#resolve() for the full priority order this
     * participates in.
     */
    private String autoSyncedLanguage;

    public PlayerTranslationSettings(Boolean enabled, String languageOverride, String autoSyncedLanguage) {
        this.enabled = enabled;
        this.languageOverride = languageOverride;
        this.autoSyncedLanguage = autoSyncedLanguage;
    }

    public Boolean enabled() { return enabled; }
    public String languageOverride() { return languageOverride; }
    public String autoSyncedLanguage() { return autoSyncedLanguage; }

    public boolean isDefault() { return enabled == null && languageOverride == null && autoSyncedLanguage == null; }
}
