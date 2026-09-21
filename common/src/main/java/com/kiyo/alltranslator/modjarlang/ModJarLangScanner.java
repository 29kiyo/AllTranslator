package com.kiyo.alltranslator.modjarlang;

import com.kiyo.alltranslator.AllTranslator;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Scans every loaded mod (Architectury Platform.getMods()/Mod#getFilePaths()) for
 * assets/<modId>/lang/en_us.json, and reports which mods do NOT already ship a lang
 * file for the given target language in that SAME location.
 *
 * Mod#getFilePaths() is NOT identical across loaders (verified on a real run):
 * Fabric/dev folders give a directory root, but NeoForge gives the mod's jar FILE
 * itself. Directory roots are read with Files; jar files are read with ZipFile
 * (no FileSystem is opened, so nothing has to be kept open after the scan).
 *
 * Deliberately does not consult GeneratedLangPackStore: whether a mod ships no
 * target-language file in its own jar is true regardless of what we generated.
 */
public final class ModJarLangScanner {

    private static final String SOURCE_LANG = "en_us";

    private ModJarLangScanner() {}

    public static List<ModJarLangCandidate> scanForMissingTranslations(String targetLangCode) {
        List<ModJarLangCandidate> candidates = new ArrayList<>();
        for (Mod mod : Platform.getMods()) {
            String modId = mod.getModId();
            String langDir = "assets/" + modId + "/lang/";
            for (Path root : mod.getFilePaths()) {
                if (Files.isDirectory(root)) {
                    Path dir = root.resolve("assets").resolve(modId).resolve("lang");
                    Path sourcePath = dir.resolve(SOURCE_LANG + ".json");
                    if (!Files.isRegularFile(sourcePath)) {
                        continue;
                    }
                    if (Files.exists(dir.resolve(targetLangCode + ".json"))) {
                        break; // mod already ships the target language
                    }
                    candidates.add(new ModJarLangCandidate(modId, sourcePath, null));
                    break;
                } else if (Files.isRegularFile(root)) {
                    try (ZipFile zip = new ZipFile(root.toFile())) {
                        String sourceEntry = langDir + SOURCE_LANG + ".json";
                        if (zip.getEntry(sourceEntry) == null) {
                            continue;
                        }
                        if (zip.getEntry(langDir + targetLangCode + ".json") != null) {
                            break; // mod already ships the target language
                        }
                        candidates.add(new ModJarLangCandidate(modId, root, sourceEntry));
                        break;
                    } catch (IOException | RuntimeException e) {
                        AllTranslator.LOGGER.warn("Mod jar lang: could not read " + root + " for mod " + modId, e);
                    }
                }
            }
        }
        return candidates;
    }
}
