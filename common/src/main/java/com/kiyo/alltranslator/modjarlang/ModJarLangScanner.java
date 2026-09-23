package com.kiyo.alltranslator.modjarlang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kiyo.alltranslator.AllTranslator;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Scans every loaded mod (Architectury Platform.getMods()/Mod#getFilePaths()) for
 * assets/<modId>/lang/en_us.json, and reports two kinds of candidates for the given target
 * language - see ModJarLangCandidate's Javadoc for the MISSING vs GAP distinction.
 *
 * Mod#getFilePaths() is NOT identical across loaders (verified on a real run): Fabric/dev
 * folders give a directory root, but NeoForge gives the mod's jar FILE itself. Directory
 * roots are read with Files; jar files are read with ZipFile (no FileSystem is opened, so
 * nothing has to be kept open after the scan).
 *
 * Deliberately does not consult GeneratedLangPackStore here: whether a mod has missing/gap
 * keys in its own jar is true regardless of what we already generated - ModJarLangPending is
 * where the "already up to date, don't re-offer" filtering happens.
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
                    Path targetPath = dir.resolve(targetLangCode + ".json");
                    if (!Files.isRegularFile(targetPath)) {
                        candidates.add(ModJarLangCandidate.missing(modId, sourcePath, null));
                    } else {
                        addIfHasGap(candidates, modId, sourcePath, null, targetPath, null);
                    }
                    break;
                } else if (Files.isRegularFile(root)) {
                    try (ZipFile zip = new ZipFile(root.toFile())) {
                        String sourceEntry = langDir + SOURCE_LANG + ".json";
                        if (zip.getEntry(sourceEntry) == null) {
                            continue;
                        }
                        String targetEntry = langDir + targetLangCode + ".json";
                        if (zip.getEntry(targetEntry) == null) {
                            candidates.add(ModJarLangCandidate.missing(modId, root, sourceEntry));
                        } else {
                            addIfHasGap(candidates, modId, root, sourceEntry, root, targetEntry);
                        }
                        break;
                    } catch (IOException | RuntimeException e) {
                        AllTranslator.LOGGER.warn("Mod jar lang: could not read " + root + " for mod " + modId, e);
                    }
                }
            }
        }
        return candidates;
    }

    /**
     * A "gap key" is one whose target-language value is byte-identical to its en_us value -
     * i.e. left untranslated / copy-pasted from English by the mod author. Only added as a
     * candidate if at least one such key exists; a mod that fully translated its own file
     * (even if some values coincidentally match en_us for a genuinely untranslatable reason,
     * e.g. a proper noun) is not flagged solely on that basis by THIS heuristic - see the
     * class-level trade-off discussion in DEVELOPMENT_STATUS.md for why this is treated as
     * acceptable here (opt-in, user-reviewable, unlike ExistingTranslationChecker's stricter
     * no-heuristic contract for vanilla/keyed lookups).
     */
    private static void addIfHasGap(List<ModJarLangCandidate> candidates, String modId,
                                     Path sourceFile, String sourceEntry, Path targetFile, String targetEntry) {
        try {
            ModJarLangCandidate probe = ModJarLangCandidate.gap(modId, sourceFile, sourceEntry, targetFile, targetEntry);
            JsonObject sourceJson = JsonParser.parseReader(new StringReader(probe.readSource())).getAsJsonObject();
            JsonObject targetJson = JsonParser.parseReader(new StringReader(probe.readTarget())).getAsJsonObject();
            for (var entry : sourceJson.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) continue;
                String key = entry.getKey();
                if (!targetJson.has(key) || !targetJson.get(key).isJsonPrimitive()) continue;
                String sourceValue = entry.getValue().getAsString();
                String targetValue = targetJson.get(key).getAsString();
                if (sourceValue.equals(targetValue)) {
                    candidates.add(probe);
                    return;
                }
            }
        } catch (IOException | RuntimeException e) {
            AllTranslator.LOGGER.warn("Mod jar lang: could not compare source/target lang for mod " + modId, e);
        }
    }
}
