package com.kiyo.alltranslator.modjarlang;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kiyo.alltranslator.AllTranslator;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * On-disk storage for mod-jar-lang bulk translation output. ONE combined resource pack
 * rooted at config/alltranslator/generated_lang_packs/ :
 *
 *   generated_lang_packs/pack.mcmeta
 *   generated_lang_packs/assets/<modId>/lang/<targetLangCode>.json
 *   generated_lang_packs/.source-hashes.json   ({ modId: { targetLangCode: sha256 of the source
 *                                                en_us.json the file was generated from } })
 *
 * Earlier versions used one pack directory per mod (generated_lang_packs/<modId>/...), which
 * showed one resource-pack entry per mod. Such legacy directories are moved into this layout
 * on construction (migrateLegacyLayout). Nothing generated is ever deleted by this class
 * except the emptied legacy directories.
 *
 * pack_format is hardcoded to 88 (MC 26.2's resource_major, confirmed via version.json inside
 * the merged client jar). Needs revisiting if the target MC version changes.
 */
public final class GeneratedLangPackStore {

    private static final int PACK_FORMAT = 88;
    /** Upper bound for min_format/max_format; revisit when the target MC version changes. */
    private static final int PACK_MAX_FORMAT = 99;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path baseDir;

    public GeneratedLangPackStore(Path configDir) {
        this.baseDir = configDir.resolve("generated_lang_packs");
        migrateLegacyLayout();
        ensurePackIcon();
    }

    public Path baseDir() {
        return baseDir;
    }

    /** True if a generated file for this modId+targetLang already exists AND was built from the current source content. */
    public boolean isUpToDate(String modId, String targetLangCode, String sourceHash) {
        if (!Files.isRegularFile(langDir(modId).resolve(targetLangCode + ".json"))) return false;
        Map<String, String> hashes = readAllHashes().get(modId);
        return hashes != null && sourceHash.equals(hashes.get(targetLangCode));
    }

    public void write(String modId, String targetLangCode, Map<String, String> translatedEntries, String sourceHash) throws IOException {
        Path langDir = langDir(modId);
        Files.createDirectories(langDir);
        writePackMcmetaIfMissing();
        ensurePackIcon();

        Path targetFile = langDir.resolve(targetLangCode + ".json");
        JsonObject obj = new JsonObject();
        // TreeMap: stable, sorted key order across regenerations (easier to diff/review by hand).
        new TreeMap<>(translatedEntries).forEach(obj::addProperty);
        try (Writer w = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
            GSON.toJson(obj, w);
        }

        Map<String, Map<String, String>> all = readAllHashes();
        all.computeIfAbsent(modId, k -> new TreeMap<>()).put(targetLangCode, sourceHash);
        writeAllHashes(all);
    }

    /** Root of the single combined pack, or null while nothing has been generated yet. */
    public Path combinedPackRoot() {
        if (Files.isDirectory(baseDir.resolve("assets")) && Files.isRegularFile(baseDir.resolve("pack.mcmeta"))) {
            return baseDir;
        }
        return null;
    }

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }

    /** Writes pack.png (the mod icon, bundled as pack_icon.png) so the resource pack screen shows it. */
    private void ensurePackIcon() {
        Path icon = baseDir.resolve("pack.png");
        if (!Files.isRegularFile(baseDir.resolve("pack.mcmeta")) || Files.isRegularFile(icon)) return;
        try (java.io.InputStream in = GeneratedLangPackStore.class.getResourceAsStream("/assets/alltranslator/pack_icon.png")) {
            if (in == null) {
                AllTranslator.LOGGER.warn("Mod jar lang: pack_icon.png resource not found; pack has no icon");
                return;
            }
            Files.copy(in, icon);
        } catch (IOException | RuntimeException e) {
            AllTranslator.LOGGER.warn("Mod jar lang: failed to write pack.png", e);
        }
    }

    private Path langDir(String modId) {
        return baseDir.resolve("assets").resolve(modId).resolve("lang");
    }

    private Path hashFile() {
        return baseDir.resolve(".source-hashes.json");
    }

    private Map<String, Map<String, String>> readAllHashes() {
        Map<String, Map<String, String>> result = new TreeMap<>();
        Path f = hashFile();
        if (!Files.isRegularFile(f)) return result;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            for (var mod : root.entrySet()) {
                Map<String, String> langs = new TreeMap<>();
                for (var l : mod.getValue().getAsJsonObject().entrySet()) {
                    langs.put(l.getKey(), l.getValue().getAsString());
                }
                result.put(mod.getKey(), langs);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            AllTranslator.LOGGER.warn("Failed to read .source-hashes.json; treating as empty", e);
            return new TreeMap<>();
        }
    }

    private void writeAllHashes(Map<String, Map<String, String>> all) throws IOException {
        JsonObject root = new JsonObject();
        all.forEach((modId, langs) -> {
            JsonObject o = new JsonObject();
            langs.forEach(o::addProperty);
            root.add(modId, o);
        });
        try (Writer w = Files.newBufferedWriter(hashFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
    }

    private void writePackMcmetaIfMissing() throws IOException {
        Path mcmeta = baseDir.resolve("pack.mcmeta");
        // An older file with only pack_format shows a red frame on MC 26.x, so it is rewritten.
        if (Files.isRegularFile(mcmeta) && Files.readString(mcmeta, StandardCharsets.UTF_8).contains("min_format")) return;
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", PACK_FORMAT);
        pack.addProperty("min_format", PACK_FORMAT);
        pack.addProperty("max_format", PACK_MAX_FORMAT);
        pack.addProperty("description", "All Translator: generated translations");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        try (Writer w = Files.newBufferedWriter(mcmeta, StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
    }

    /** Moves legacy per-mod pack directories (generated_lang_packs/<modId>/assets/<modId>/lang) into the combined layout. */
    private void migrateLegacyLayout() {
        if (!Files.isDirectory(baseDir)) return;
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(baseDir)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Failed to list generated_lang_packs directory", e);
            return;
        }
        for (Path dir : dirs) {
            String modId = dir.getFileName().toString();
            if (modId.equals("assets")) continue;
            Path legacyLang = dir.resolve("assets").resolve(modId).resolve("lang");
            if (!Files.isDirectory(legacyLang)) continue;
            try {
                migrateOne(modId, dir, legacyLang);
            } catch (IOException | RuntimeException e) {
                AllTranslator.LOGGER.warn("Mod jar lang: failed to migrate legacy pack for " + modId, e);
            }
        }
        try {
            if (Files.isDirectory(baseDir.resolve("assets"))) writePackMcmetaIfMissing();
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Mod jar lang: failed to write pack.mcmeta", e);
        }
    }

    private void migrateOne(String modId, Path legacyDir, Path legacyLang) throws IOException {
        Path target = langDir(modId);
        Files.createDirectories(target);
        List<Path> files;
        try (var s = Files.list(legacyLang)) {
            files = s.toList();
        }
        for (Path f : files) {
            Path dest = target.resolve(f.getFileName().toString());
            if (!Files.exists(dest)) Files.move(f, dest);
        }
        Path legacyHashes = legacyDir.resolve(".source-hashes.json");
        if (Files.isRegularFile(legacyHashes)) {
            Map<String, Map<String, String>> all = readAllHashes();
            Map<String, String> mine = all.computeIfAbsent(modId, k -> new TreeMap<>());
            try (Reader r = Files.newBufferedReader(legacyHashes, StandardCharsets.UTF_8)) {
                for (var e : JsonParser.parseReader(r).getAsJsonObject().entrySet()) {
                    mine.putIfAbsent(e.getKey(), e.getValue().getAsString());
                }
            }
            writeAllHashes(all);
            Files.delete(legacyHashes);
        }
        // Best-effort cleanup; a non-empty directory is simply left alone.
        Path[] leftovers = { legacyDir.resolve("pack.mcmeta"), legacyLang, legacyLang.getParent(),
                legacyDir.resolve("assets"), legacyDir };
        for (Path p : leftovers) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // not empty: keep
            }
        }
    }
}
