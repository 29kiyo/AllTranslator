package com.kiyo.alltranslator.modjarlang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helpers for ModJarLangPending/ModJarLangTranslationCoordinator: computing the
 * up-to-date hash and resolving which keys actually need translating for a candidate,
 * covering both the MISSING and GAP cases (ModJarLangCandidate#isGap()).
 */
final class ModJarLangHashing {

    private ModJarLangHashing() {}

    /**
     * Hash used for GeneratedLangPackStore's up-to-date check. For MISSING, this is just the
     * source (en_us) content, same as before. For GAP, the mod's OWN target-language file is
     * also folded in, so that if the mod author later fixes some of the untranslated keys
     * themselves, the changed target file invalidates the cached generated pack and the
     * (now smaller) set of remaining gap keys is recomputed.
     */
    static String contentHash(ModJarLangCandidate candidate) throws IOException {
        String source = candidate.readSource();
        if (!candidate.isGap()) {
            return GeneratedLangPackStore.sha256(source);
        }
        String target = candidate.readTarget();
        return GeneratedLangPackStore.sha256(source + '\u0000' + target);
    }

    static int countSourceKeys(ModJarLangCandidate candidate) throws IOException {
        return parseFlat(candidate.readSource()).size();
    }

    /** For a GAP candidate: source entries whose value is byte-identical to the target's own value. */
    static Map<String, String> resolveGapKeys(ModJarLangCandidate candidate) throws IOException {
        if (!candidate.isGap()) {
            return parseFlat(candidate.readSource());
        }
        Map<String, String> source = parseFlat(candidate.readSource());
        Map<String, String> target = parseFlat(candidate.readTarget());
        Map<String, String> gaps = new LinkedHashMap<>();
        for (var e : source.entrySet()) {
            String targetValue = target.get(e.getKey());
            if (targetValue != null && targetValue.equals(e.getValue())) {
                gaps.put(e.getKey(), e.getValue());
            }
        }
        return gaps;
    }

    private static Map<String, String> parseFlat(String rawJson) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonObject obj = JsonParser.parseReader(new StringReader(rawJson)).getAsJsonObject();
        for (var entry : obj.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return result;
    }
}
