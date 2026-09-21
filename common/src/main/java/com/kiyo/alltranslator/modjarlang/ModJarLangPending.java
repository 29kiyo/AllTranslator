package com.kiyo.alltranslator.modjarlang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * A mod whose bundled en_us.json still needs a generated translation, plus its key count
 * (shown in the confirmation screen so the user can see how many API requests it implies).
 */
public record ModJarLangPending(ModJarLangCandidate candidate, int keyCount) {

    /**
     * "minecraft" is a real entry in Platform#getMods() and its jar bundles en_us.json but not
     * the other languages; it must never be offered for bulk translation.
     */
    private static final Set<String> EXCLUDED_MOD_IDS = Set.of("minecraft");

    public String modId() {
        return candidate.modId();
    }

    /**
     * Candidates that (a) lack the target language in their own jar, (b) are not excluded /
     * ignored, and (c) have no up-to-date generated pack yet (so mods translated in an earlier
     * session are not offered again on every launch).
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
                String raw = c.readSource();
                if (store.isUpToDate(modId, targetLang, GeneratedLangPackStore.sha256(raw))) {
                    continue;
                }
                JsonObject obj = JsonParser.parseReader(new StringReader(raw)).getAsJsonObject();
                int count = 0;
                for (var entry : obj.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        count++;
                    }
                }
                if (count > 0) {
                    result.add(new ModJarLangPending(c, count));
                }
            } catch (Exception e) {
                // Unreadable / non-flat en_us.json: not offered. The coordinator logs the same
                // problem if it is ever attempted directly.
            }
        }
        return result;
    }
}
