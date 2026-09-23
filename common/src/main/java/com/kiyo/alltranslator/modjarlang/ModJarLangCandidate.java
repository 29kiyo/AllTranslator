package com.kiyo.alltranslator.modjarlang;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One mod found to need bulk-translation help for a target language, in one of two modes:
 *
 *  - MISSING: the mod ships no lang file at all for the target language in its own jar/root.
 *    Every key in its en_us.json is a candidate. targetLangFile is null.
 *
 *  - GAP: the mod DOES ship a target-language file, but some of its keys are byte-identical
 *    to their en_us counterpart - i.e. left untranslated by the mod author and copy-pasted
 *    from English rather than genuinely translated (real-world example: Traveler's Backpack's
 *    ja_jp.json leaving "Crafting Upgrade" / "Magnet Upgrade" as literal English while
 *    correctly translating "Crafting"/"Magnet Settings" elsewhere in the SAME file). Only
 *    those gap keys are translated; every other key in the mod's own file is left completely
 *    untouched and continues to be served by the mod's own pack.
 *
 * Verified real-machine (this session): Minecraft's own language loading merges lang data
 * KEY-BY-KEY across all currently loaded resource packs/pack sources (matching this project's
 * own MinecraftLanguageDataSource#loadMergedLanguageData, which does the same putAll-across-
 * sources merge) - a generated pack containing ONLY the gap keys, sitting above the mod's own
 * pack (GeneratedLangPackRepositorySource: required=true, TOP, fixedPosition=true), correctly
 * overrides just those keys while every other key in the mod's own ja_jp.json (e.g.
 * "screen.travelersbackpack.magnet_upgrade") continues to display unaffected.
 *
 * sourceLangFile/targetLangFile are either the actual lang json file itself (zipEntry ==
 * null, directory root - Fabric/dev) or the mod's jar file (zipEntry != null - NeoForge,
 * where Mod#getFilePaths() returns the jar itself). Always read content through
 * readSource()/readTarget(), never Files.readString(...) directly.
 */
public record ModJarLangCandidate(String modId, Path sourceLangFile, String sourceZipEntry,
                                   Path targetLangFile, String targetZipEntry) {

    /** Convenience constructor for the MISSING case (no target-language file at all). */
    public static ModJarLangCandidate missing(String modId, Path sourceLangFile, String sourceZipEntry) {
        return new ModJarLangCandidate(modId, sourceLangFile, sourceZipEntry, null, null);
    }

    /** Convenience constructor for the GAP case (target-language file exists, some keys untranslated). */
    public static ModJarLangCandidate gap(String modId, Path sourceLangFile, String sourceZipEntry,
                                           Path targetLangFile, String targetZipEntry) {
        return new ModJarLangCandidate(modId, sourceLangFile, sourceZipEntry, targetLangFile, targetZipEntry);
    }

    public boolean isGap() {
        return targetLangFile != null;
    }

    public String readSource() throws IOException {
        return readFile(sourceLangFile, sourceZipEntry);
    }

    /** Only valid when isGap() is true. */
    public String readTarget() throws IOException {
        return readFile(targetLangFile, targetZipEntry);
    }

    private static String readFile(Path file, String zipEntry) throws IOException {
        if (zipEntry == null) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(zipEntry);
            if (entry == null) {
                throw new IOException("Entry not found: " + zipEntry + " in " + file);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
