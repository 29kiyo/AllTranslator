package com.kiyo.alltranslator.modjarlang;

import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans every currently-loaded mod's own jar/root paths (Architectury's
 * Platform.getMods()/Mod#getFilePaths() - confirmed identical across Fabric and
 * NeoForge via javap, no loader-specific implementation needed; see
 * ARCHITECTURE.md's "Mod jar lang bulk translation" section) for
 * assets/<modId>/lang/en_us.json, and reports which mods do NOT already ship a
 * lang file for the given target language in that SAME location.
 *
 * ASSUMPTION carried over from ModNioPackResources' own use of these same Mod
 * root Paths for direct file access (Fabric loader internals): Files.exists/
 * isRegularFile work directly on these Paths without any extra FileSystem-open
 * step, since the loader already keeps their backing jar filesystem open for the
 * lifetime of the game. Not yet exercised against a real jar-backed Path by this
 * class specifically - first real-world test (running the scanner against CTOV)
 * will confirm this.
 *
 * Deliberately does not consult GeneratedLangPackStore (our own generated-pack
 * output) here - whether a mod ships no target-language file in its OWN jar is
 * true regardless of what we may have already generated for it separately. It is
 * ModJarLangTranslationCoordinator's job to then check the store and skip actual
 * (re-)translation work when a matching up-to-date generated file already exists.
 *
 * AllTranslator's own bundled lang files (assets/alltranslator/lang/*) are
 * naturally excluded: this mod always ships every language it supports itself.
 */
public final class ModJarLangScanner {

    private static final String SOURCE_LANG = "en_us";

    private ModJarLangScanner() {}

    public static List<ModJarLangCandidate> scanForMissingTranslations(String targetLangCode) {
        List<ModJarLangCandidate> candidates = new ArrayList<>();
        for (Mod mod : Platform.getMods()) {
            String modId = mod.getModId();
            for (Path root : mod.getFilePaths()) {
                Path sourcePath = root.resolve("assets").resolve(modId).resolve("lang").resolve(SOURCE_LANG + ".json");
                if (!Files.isRegularFile(sourcePath)) {
                    continue;
                }
                Path targetPath = root.resolve("assets").resolve(modId).resolve("lang").resolve(targetLangCode + ".json");
                if (Files.exists(targetPath)) {
                    // This mod already ships the target language itself; nothing to do.
                    break;
                }
                candidates.add(new ModJarLangCandidate(modId, sourcePath));
                break; // one en_us.json per mod is enough; don't scan remaining root paths.
            }
        }
        return candidates;
    }
}
