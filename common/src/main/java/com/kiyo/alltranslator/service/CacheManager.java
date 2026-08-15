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
 */
public final class CacheManager {

    private static final int DEFAULT_MEMORY_CAPACITY = 2000;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Type mapType = new TypeToken<Map<String, PersistedEntry>>() {}.getType();

    private final Map<String, TranslationResult> memoryCache;
    private final Map<String, Map<String, PersistedEntry>> loadedLanguageFiles = new ConcurrentHashMap<>();

    private volatile Path persistentDirectory;

    public CacheManager() { this(DEFAULT_MEMORY_CAPACITY); }

    public CacheManager(int memoryCapacity) {
        int capacity = memoryCapacity > 0 ? memoryCapacity : DEFAULT_MEMORY_CAPACITY;
        this.memoryCache = java.util.Collections.synchronizedMap(
                new LinkedHashMap<String, TranslationResult>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, TranslationResult> eldest) {
                        return size() > capacity;
                    }
                });
    }

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
        PersistedEntry entry = loadLanguageFile(targetLang).get(cacheKey);
        return entry == null ? null : entry.translatedText;
    }

    public void putPersistent(String targetLang, String cacheKey, String sourceText, String translatedText) {
        if (persistentDirectory == null) return;
        Map<String, PersistedEntry> lang = loadLanguageFile(targetLang);
        PersistedEntry entry = new PersistedEntry();
        entry.sourceText = sourceText;
        entry.translatedText = translatedText;
        lang.put(cacheKey, entry);
        writeLanguageFile(targetLang, lang);
    }

    private Map<String, PersistedEntry> loadLanguageFile(String targetLang) {
        return loadedLanguageFiles.computeIfAbsent(targetLang, lang -> {
            Path file = persistentDirectory.resolve(sanitize(lang) + ".json");
            if (!Files.exists(file)) return new LinkedHashMap<>();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, PersistedEntry> loaded = gson.fromJson(reader, mapType);
                return loaded != null ? new LinkedHashMap<>(loaded) : new LinkedHashMap<>();
            } catch (IOException e) {
                AllTranslator.LOGGER.warn("Failed to read translation cache file: " + file, e);
                return new LinkedHashMap<>();
            }
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
    }
}
