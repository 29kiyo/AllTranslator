package com.kiyo.alltranslator.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.kiyo.alltranslator.AllTranslator;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Phase 6: per-player translation preference storage, kept in the SERVER's own
 * config/alltranslator/player-settings.json (never transmitted to clients; contains no API
 * keys - just booleans/language codes per ARCHITECTURE.md §12/§18). Kept separate from
 * ConfigManager's config.json (server-wide settings) since this grows with the player count
 * and is edited far more frequently.
 */
public final class PlayerTranslationSettingsManager {

    private static final Type MAP_TYPE = new TypeToken<Map<String, PlayerTranslationSettings>>() {}.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<UUID, PlayerTranslationSettings> settings = new ConcurrentHashMap<>();

    public PlayerTranslationSettingsManager(Path configDir) {
        this.file = configDir.resolve("player-settings.json");
    }

    public synchronized void load() {
        settings.clear();
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, PlayerTranslationSettings> raw = gson.fromJson(reader, MAP_TYPE);
            if (raw != null) {
                raw.forEach((id, value) -> {
                    if (value != null && !value.isDefault()) {
                        settings.put(UUID.fromString(id), value);
                    }
                });
            }
        } catch (IOException | IllegalArgumentException e) {
            AllTranslator.LOGGER.warn("Failed to read player-settings.json", e);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Map<String, PlayerTranslationSettings> raw = new LinkedHashMap<>();
            settings.forEach((id, value) -> raw.put(id.toString(), value));
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(raw, MAP_TYPE, writer);
            }
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Failed to write player-settings.json", e);
        }
    }

    public PlayerTranslationSettings get(UUID playerId) {
        return settings.getOrDefault(playerId, PlayerTranslationSettings.DEFAULT);
    }

    /** null clears the override (falls back to the server-wide switch). */
    public synchronized void setEnabled(UUID playerId, Boolean enabled) {
        update(playerId, existing -> new PlayerTranslationSettings(enabled, existing.languageOverride(), existing.autoSyncedLanguage()));
    }

    /** null/blank clears the override (falls back to the player's own client language). */
    public synchronized void setLanguageOverride(UUID playerId, String languageOverride) {
        String normalized = (languageOverride == null || languageOverride.isBlank()) ? null : languageOverride;
        update(playerId, existing -> new PlayerTranslationSettings(existing.enabled(), normalized, existing.autoSyncedLanguage()));
    }

    /**
     * Phase 14 (M-key auto-sync): sets/clears autoSyncedLanguage - see that
     * field's Javadoc on PlayerTranslationSettings for why this is kept separate
     * from setLanguageOverride() above rather than reusing it. null/blank clears
     * the auto-synced value (e.g. the client cleared their M-key language box
     * back to "auto").
     */
    public synchronized void setAutoSyncedLanguage(UUID playerId, String autoSyncedLanguage) {
        String normalized = (autoSyncedLanguage == null || autoSyncedLanguage.isBlank()) ? null : autoSyncedLanguage;
        update(playerId, existing -> new PlayerTranslationSettings(existing.enabled(), existing.languageOverride(), normalized));
    }

    private void update(UUID playerId, UnaryOperator<PlayerTranslationSettings> updater) {
        PlayerTranslationSettings updated = updater.apply(get(playerId));
        if (updated.isDefault()) {
            settings.remove(playerId);
        } else {
            settings.put(playerId, updated);
        }
        save();
    }
}
