package com.kiyo.alltranslator.modjarlang;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One mod found to ship an en_us.json lang file but NOT (yet, in its own jar/root)
 * a lang file for some target language. Produced by ModJarLangScanner; consumed by
 * ModJarLangPending and ModJarLangTranslationCoordinator.
 *
 * sourceLangFile is either the en_us.json file itself (zipEntry == null, directory
 * root, e.g. Fabric or a dev folder) or the mod's jar file (zipEntry != null, the
 * NeoForge case: Mod#getFilePaths() returns the jar itself there). Always read the
 * content through readSource(), never Files.readString(sourceLangFile) directly.
 */
public record ModJarLangCandidate(String modId, Path sourceLangFile, String zipEntry) {

    public String readSource() throws IOException {
        if (zipEntry == null) {
            return Files.readString(sourceLangFile, StandardCharsets.UTF_8);
        }
        try (ZipFile zip = new ZipFile(sourceLangFile.toFile())) {
            ZipEntry entry = zip.getEntry(zipEntry);
            if (entry == null) {
                throw new IOException("Entry not found: " + zipEntry + " in " + sourceLangFile);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
