package com.kiyo.alltranslator.lang;

import java.util.List;

/**
 * Tries each source in priority order for each lookup; first non-null value wins.
 * Used to layer: user custom lang/ files (highest priority) over Minecraft resource-pack
 * lang files.
 *
 * Investigation #6 (Phase 14): ExistingTranslationChecker previously also compared the
 * target-language value against the default-language value and rejected an exact match
 * as "fallback carried through". That heuristic was removed after real-machine testing
 * showed it misclassified genuine vanilla ja_jp translations that are byte-identical to
 * en_us by coincidence (see ExistingTranslationChecker's Javadoc for the concrete
 * example keys). Each lookupXxxLanguageValue(...) method here only returns a value that
 * genuinely exists in that specific language's own underlying data (neither
 * MinecraftLanguageDataSource nor CustomLanguageFileManager merge in en_us as a
 * fallback), so no equality-based "is this really the target language" re-check is
 * needed at this layer either.
 */
public final class CompositeLanguageDataSource implements LanguageDataSource {

    private final List<LanguageDataSource> sourcesInPriorityOrder;

    public CompositeLanguageDataSource(List<LanguageDataSource> sourcesInPriorityOrder) {
        this.sourcesInPriorityOrder = sourcesInPriorityOrder;
    }

    @Override
    public String lookupTargetLanguageValue(String key, String targetLang) {
        for (LanguageDataSource source : sourcesInPriorityOrder) {
            String value = source.lookupTargetLanguageValue(key, targetLang);
            if (value != null) return value;
        }
        return null;
    }

    @Override
    public String lookupDefaultLanguageValue(String key) {
        for (LanguageDataSource source : sourcesInPriorityOrder) {
            String value = source.lookupDefaultLanguageValue(key);
            if (value != null) return value;
        }
        return null;
    }
}
