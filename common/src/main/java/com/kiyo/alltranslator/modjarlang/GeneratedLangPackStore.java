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
 * On-disk storage for mod-jar-lang bulk translation output: one directory per
 * source modId under config/alltranslator/generated_lang_packs/, each laid out
 * as a standalone resource pack:
 *
 *   generated_lang_packs/<modId>/pack.mcmeta
 *   generated_lang_packs/<modId>/assets/<modId>/lang/<targetLangCode>.json
 *   generated_lang_packs/<modId>/.source-hashes.json   (targetLangCode -> sha256 of the
 *                                                        source en_us.json used to generate it)
 *
 * Multiple target languages accumulate side-by-side in the same per-mod pack
 * directory over a client's lifetime (e.g. ja_jp.json and ko_kr.json both present
 * simultaneously) exactly like a mod bundling many languages itself (inventoryhud.fabric
 * ships 18) - nothing is ever deleted by this class.
 *
 * pack_format is hardcoded to 88 (MC 26.2's resource_major, confirmed via
 * version.json inside the merged client jar during this feature's investigation).
 * Will need revisiting if/when the project's target MC version changes.
 */
public final class GeneratedLangPackStore {

    private static final int PACK_FORMAT = 88;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path baseDir;

    public GeneratedLangPackStore(Path configDir) {
        this.baseDir = configDir.resolve("generated_lang_packs");
    }

    public Path baseDir() {
        return baseDir;
    }

    /** True if a generated file for this modId+targetLang already exists AND was built from the current source content. */
    public boolean isUpToDate(String modId, String targetLangCode, String sourceHash) {
        Path targetFile = modPackDir(modId).resolve("assets").resolve(modId).resolve("lang")
                .resolve(targetLangCode + ".json");
        if (!Files.isRegularFile(targetFile)) return false;
        Map<String, String> hashes = readHashes(modId);
        return sourceHash.equals(hashes.get(targetLangCode));
    }

    public void write(String modId, String targetLangCode, Map<String, String> translatedEntries, String sourceHash) throws IOException {
        Path packDir = modPackDir(modId);
        Files.createDirectories(packDir);
        writePackMcmetaIfMissing(packDir, modId);

        Path langDir = packDir.resolve("assets").resolve(modId).resolve("lang");
        Files.createDirectories(langDir);
        Path targetFile = langDir.resolve(targetLangCode + ".json");
        JsonObject obj = new JsonObject();
        // TreeMap: stable, sorted key order across regenerations (easier to diff/review by hand).
        new TreeMap<>(translatedEntries).forEach(obj::addProperty);
        try (Writer w = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
            GSON.toJson(obj, w);
        }

        Map<String, String> hashes = readHashes(modId);
        hashes.put(targetLangCode, sourceHash);
        writeHashes(modId, hashes);
    }

    /** Every per-mod pack directory currently on disk, for GeneratedLangPackRepositorySource to expose to Minecraft. */
    public List<Path> listGeneratedPackDirs() {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) return result;
        try (var stream = Files.list(baseDir)) {
            stream.filter(Files::isDirectory).forEach(result::add);
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Failed to list generated_lang_packs directory", e);
        }
        return result;
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

    private Path modPackDir(String modId) {
        return baseDir.resolve(modId);
    }

    private Map<String, String> readHashes(String modId) {
        Path hashFile = modPackDir(modId).resolve(".source-hashes.json");
        if (!Files.isRegularFile(hashFile)) return new TreeMap<>();
        try (Reader r = Files.newBufferedReader(hashFile, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            Map<String, String> map = new TreeMap<>();
            for (var entry : obj.entrySet()) {
                map.put(entry.getKey(), entry.getValue().getAsString());
            }
            return map;
        } catch (IOException | RuntimeException e) {
            AllTranslator.LOGGER.warn("Failed to read .source-hashes.json for " + modId + "; treating as empty", e);
            return new TreeMap<>();
        }
    }

    private void writeHashes(String modId, Map<String, String> hashes) throws IOException {
        Path hashFile = modPackDir(modId).resolve(".source-hashes.json");
        JsonObject obj = new JsonObject();
        hashes.forEach(obj::addProperty);
        try (Writer w = Files.newBufferedWriter(hashFile, StandardCharsets.UTF_8)) {
            GSON.toJson(obj, w);
        }
    }

    private void writePackMcmetaIfMissing(Path packDir, String modId) throws IOException {
        Path mcmeta = packDir.resolve("pack.mcmeta");
        if (Files.isRegularFile(mcmeta)) return;
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", PACK_FORMAT);
        pack.addProperty("description", "All Translator: generated translations for " + modId);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        try (Writer w = Files.newBufferedWriter(mcmeta, StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
    }
}
