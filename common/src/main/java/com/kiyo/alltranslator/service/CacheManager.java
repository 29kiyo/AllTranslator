package com.kiyo.alltranslator.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.kiyo.alltranslator.api.TranslationResult;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.kiyo.alltranslator.AllTranslator;

/**
 * Two-tier cache per ARCHITECTURE.md §8:
 *  - memory LRU: all content types, including chat.
 *  - persistent JSON, one file per target language, chat excluded.
 *
 * The persistent directory is the WORLD SAVE location, which isn't known at
 * construction time (resolved on the loader side in Phase 6, per-world/per-level).
 * Until setPersistentDirectory() is called, persistent lookups/writes are no-ops:
 * translation just falls through to the API every time (safe, just less efficient),
 * never persists anywhere outside the world save.
 *
 * Phase 11 addition (ARCHITECTURE.md §8.4 TTL/pruning, previously undeclared gap):
 * every persisted entry now carries a savedAt timestamp and is expired once older
 * than dynamicTextCacheTtlDays (0 or less = never expires). IMPORTANT CAVEAT: §8.4's
 * original intent was that only keyless "dynamic text" (FTB Quests etc.) gets a TTL,
 * while language-file-derived content (item/block/UI) is effectively permanent - but
 * that distinction requires the category/namespace field §19 defers as not-yet-
 * implemented. Until §19 lands, TTL is applied UNIFORMLY to every persistent entry
 * regardless of origin (documented limitation, not a silent assumption).
 */
public final class CacheManager {

    private static final int DEFAULT_MEMORY_CAPACITY = 2000;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Type mapType = new TypeToken<Map<String, PersistedEntry>>() {}.getType();

    private final Map<String, TranslationResult> memoryCache;
    private final Map<String, Map<String, PersistedEntry>> loadedLanguageFiles = new ConcurrentHashMap<>();

    private volatile Path persistentDirectory;
    private volatile int dynamicTextCacheTtlDays;

    public CacheManager() { this(DEFAULT_MEMORY_CAPACITY); }

    public CacheManager(int memoryCapacity) { this(memoryCapacity, 30); }

    public CacheManager(int memoryCapacity, int dynamicTextCacheTtlDays) {
        int capacity = memoryCapacity > 0 ? memoryCapacity : DEFAULT_MEMORY_CAPACITY;
        this.dynamicTextCacheTtlDays = dynamicTextCacheTtlDays;
        this.memoryCache = java.util.Collections.synchronizedMap(
                new LinkedHashMap<String, TranslationResult>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, TranslationResult> eldest) {
                        return size() > capacity;
                    }
                });
    }

    /** Safe to call at any time, including live from the Config UI's Done button (simple comparison value, no re-layout needed). */
    public void setDynamicTextCacheTtlDays(int days) {
        this.dynamicTextCacheTtlDays = days;
    }

    public int dynamicTextCacheTtlDays() { return dynamicTextCacheTtlDays; }

    // ---- memory tier (all content types, chat included) ----

    public TranslationResult getMemory(String cacheKey) { return memoryCache.get(cacheKey); }

    public void putMemory(String cacheKey, TranslationResult result) { memoryCache.put(cacheKey, result); }

    // ---- persistent tier (chat excluded; caller passes persistable=false for chat) ----

    /** Called once a world save directory is known (Phase 6). */
    public void setPersistentDirectory(Path dir) {
        this.persistentDirectory = dir;
        this.loadedLanguageFiles.clear();
        if (dir != null) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                AllTranslator.LOGGER.warn("Could not create persistent cache directory: " + dir, e);
            }
        }
    }

    public boolean isPersistentEnabled() { return persistentDirectory != null; }

    public String getPersistent(String targetLang, String cacheKey) {
        if (persistentDirectory == null) return null;
        Map<String, PersistedEntry> lang = loadLanguageFile(targetLang);
        PersistedEntry entry = lang.get(cacheKey);
        if (entry == null) return null;
        if (isExpired(entry)) {
            lang.remove(cacheKey);
            writeLanguageFile(targetLang, lang);
            return null;
        }
        return entry.translatedText;
    }

    public void putPersistent(String targetLang, String cacheKey, String sourceText, String translatedText) {
        if (persistentDirectory == null) return;
        Map<String, PersistedEntry> lang = loadLanguageFile(targetLang);
        PersistedEntry entry = new PersistedEntry();
        entry.sourceText = sourceText;
        entry.translatedText = translatedText;
        entry.savedAt = System.currentTimeMillis();
        lang.put(cacheKey, entry);
        writeLanguageFile(targetLang, lang);
    }

    private boolean isExpired(PersistedEntry entry) {
        if (dynamicTextCacheTtlDays <= 0) return false;
        long ttlMillis = dynamicTextCacheTtlDays * 24L * 60L * 60L * 1000L;
        return System.currentTimeMillis() - entry.savedAt > ttlMillis;
    }

    private Map<String, PersistedEntry> loadLanguageFile(String targetLang) {
        return loadedLanguageFiles.computeIfAbsent(targetLang, lang -> {
            Path file = persistentDirectory.resolve(sanitize(lang) + ".json");
            if (!Files.exists(file)) return new LinkedHashMap<>();
            Map<String, PersistedEntry> loaded;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, PersistedEntry> parsed = gson.fromJson(reader, mapType);
                loaded = parsed != null ? new LinkedHashMap<>(parsed) : new LinkedHashMap<>();
            } catch (IOException e) {
                AllTranslator.LOGGER.warn("Failed to read translation cache file: " + file, e);
                return new LinkedHashMap<>();
            }

            // Migration + sweep: entries written before this Phase 11 change have no
            // savedAt (defaults to 0 after Gson deserialization of a missing field).
            // Stamping them "now" avoids instantly mass-expiring a pre-existing world's
            // entire cache the first time TTL pruning runs. Already-expired entries
            // (relevant once this has run at least once before) are dropped here too.
            boolean changed = false;
            Iterator<Map.Entry<String, PersistedEntry>> it = loaded.entrySet().iterator();
            long now = System.currentTimeMillis();
            while (it.hasNext()) {
                PersistedEntry entry = it.next().getValue();
                if (entry.savedAt <= 0) {
                    entry.savedAt = now;
                    changed = true;
                } else if (isExpired(entry)) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) {
                writeLanguageFile(lang, loaded);
            }
            return loaded;
        });
    }

    private synchronized void writeLanguageFile(String targetLang, Map<String, PersistedEntry> data) {
        Path file = persistentDirectory.resolve(sanitize(targetLang) + ".json");
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            gson.toJson(data, mapType, writer);
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Failed to write translation cache file: " + file, e);
        }
    }

    private static String sanitize(String targetLang) {
        return targetLang.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    /** Kept close to Minecraft lang-file style so it's easy to "promote" to a real lang file later. */
    private static final class PersistedEntry {
        String sourceText;
        String translatedText;
        long savedAt; // epoch millis; 0/absent on entries written before Phase 11 (migrated on load)
    }
}
