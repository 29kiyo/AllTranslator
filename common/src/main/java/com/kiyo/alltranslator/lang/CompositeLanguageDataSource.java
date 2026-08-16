package com.kiyo.alltranslator.lang;

import java.util.List;

/**
 * Tries each source in priority order for each lookup; first non-null value wins.
 * Used to layer: user custom lang/ files (highest priority) over Minecraft resource-pack
 * lang files. Note: the "not just the en_us fallback carried through" check in
 * ExistingTranslationChecker compares whatever the composite returns for the target value
 * against whatever it returns for the default value - if two DIFFERENT sources happen to
 * produce identical text by coincidence, it could be misclassified as a fallback. Accepted
 * as a rare edge case for now.
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
