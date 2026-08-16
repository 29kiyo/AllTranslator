package com.kiyo.alltranslator.lang;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.kiyo.alltranslator.AllTranslator;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets users hand-author their own translation overrides, independent of Minecraft resource
 * packs: config/alltranslator/lang/<code>.json - same flat "key": "value" shape as vanilla
 * lang files (translation key -> value), so an existing Minecraft/mod lang file can be copied
 * in directly as a starting point and hand-corrected.
 *
 * These take priority over resource-pack lang files in ExistingTranslationChecker (see the
 * CompositeLanguageDataSource wiring in AllTranslatorCore): a user's own correction/addition
 * always wins over what a resource pack provides.
 *
 * A starter lang/ja_jp.json (empty JSON object) is created on first run only, so there's a
 * concrete example in place without the user having to guess the folder location or format.
 * It is never overwritten once it exists.
 */
public final class CustomLanguageFileManager implements LanguageDataSource {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final Path langDir;
    private final Map<String, Map<String, String>> loaded = new ConcurrentHashMap<>();

    public CustomLanguageFileManager(Path configDir) {
        this.langDir = configDir.resolve("lang");
    }

    /** Creates lang/ (+ default ja_jp.json starter, first run only) and loads whatever's present. */
    public void initialize() {
        try {
            Files.createDirectories(langDir);
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Could not create custom language directory: " + langDir, e);
            return;
        }

        Path defaultJapanese = langDir.resolve("ja_jp.json");
        if (!Files.exists(defaultJapanese)) {
            try (Writer writer = Files.newBufferedWriter(defaultJapanese, StandardCharsets.UTF_8)) {
                GSON.toJson(Map.of(), MAP_TYPE, writer);
                AllTranslator.LOGGER.info("Created default custom language file: " + defaultJapanese);
            } catch (IOException e) {
                AllTranslator.LOGGER.warn("Could not create default ja_jp.json", e);
            }
        }

        reload();
    }

    /** Re-scans lang/. Call after the user edits/adds a file (e.g. a config-screen reload button, Phase 8). */
    public synchronized void reload() {
        loaded.clear();
        if (!Files.isDirectory(langDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(langDir, "*.json")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                String langCode = LanguageResolver.normalize(fileName.substring(0, fileName.length() - ".json".length()));
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Map<String, String> parsed = GSON.fromJson(reader, MAP_TYPE);
                    if (parsed != null && !parsed.isEmpty()) {
                        loaded.put(langCode, parsed);
                    }
                } catch (IOException | JsonSyntaxException e) {
                    AllTranslator.LOGGER.warn("Failed to parse custom language file: " + file, e);
                }
            }
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Failed to list custom language directory: " + langDir, e);
        }
    }

    @Override
    public String lookupTargetLanguageValue(String key, String targetLang) {
        Map<String, String> data = loaded.get(LanguageResolver.normalize(targetLang));
        return data == null ? null : data.get(key);
    }

    @Override
    public String lookupDefaultLanguageValue(String key) {
        Map<String, String> data = loaded.get(LanguageResolver.DEFAULT_LANGUAGE);
        return data == null ? null : data.get(key);
    }
}
