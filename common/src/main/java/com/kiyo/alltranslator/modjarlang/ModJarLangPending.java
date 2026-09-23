package com.kiyo.alltranslator.modjarlang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * A mod whose bundled lang data needs bulk-translation help, plus the number of keys that will
 * actually be sent for translation (shown in the confirmation screen). For a MISSING candidate
 * this is every key in en_us.json; for a GAP candidate (ModJarLangCandidate#isGap()) this is
 * only the untranslated (target-value == source-value) keys - see ModJarLangCandidate's Javadoc.
 */
public record ModJarLangPending(ModJarLangCandidate candidate, int keyCount) {

    /**
     * Loader/game pseudo-mods and infrastructure library mods that ship only en_us.json and
     * have no user-facing translatable content of their own; never offered for bulk
     * translation. "minecraft"/"neoforge" are loader pseudo-mods (Platform#getMods()).
     * "fabric-data-attachment-api-v1"/"fabric-resource-loader-v1" are Fabric API sub-modules
     * with no display text. "forgeconfigapiport" is a NeoForge/Forge config-API compatibility
     * shim mod with no UI of its own.
     */
    private static final Set<String> EXCLUDED_MOD_IDS = Set.of(
            "minecraft",
            "neoforge",
            "fabric-data-attachment-api-v1",
            "fabric-resource-loader-v1",
            "forgeconfigapiport"
    );

    public String modId() {
        return candidate.modId();
    }

    /**
     * Candidates that (a) have missing or gap keys for the target language in their own jar,
     * (b) are not excluded/ignored, and (c) have no up-to-date generated pack yet (so mods
     * translated in an earlier session, for the current state of both their source AND
     * target-language file, are not offered again on every launch).
     */
    public static List<ModJarLangPending> collect(String targetLang, GeneratedLangPackStore store,
                                                  Collection<String> ignoredModIds) {
        List<ModJarLangPending> result = new ArrayList<>();
        for (ModJarLangCandidate c : ModJarLangScanner.scanForMissingTranslations(targetLang)) {
            String modId = c.modId();
            if (EXCLUDED_MOD_IDS.contains(modId)) {
                continue;
            }
            if (ignoredModIds != null && ignoredModIds.contains(modId)) {
                continue;
            }
            try {
                String contentHash = ModJarLangHashing.contentHash(c);
                if (store.isUpToDate(modId, targetLang, contentHash)) {
                    continue;
                }
                var gapKeys = ModJarLangHashing.resolveGapKeys(c);
                int count = c.isGap() ? gapKeys.size() : ModJarLangHashing.countSourceKeys(c);
                if (count > 0) {
                    result.add(new ModJarLangPending(c, count));
                }
            } catch (Exception e) {
                // Unreadable / non-flat lang json: not offered. The coordinator logs the same
                // problem if it is ever attempted directly.
            }
        }
        return result;
    }
}
