package com.kiyo.alltranslator.lang;

import java.util.Objects;

/**
 * Implements ARCHITECTURE.md §3 / CLAUDE.md §3:
 *   "valid existing translation" = key exists in the target language's OWN data,
 *   value non-blank, and NOT identical to (a) the en_us fallback value or
 *   (b) the key itself (untranslated stub echoing the key).
 * If valid, the caller MUST use it directly and skip cache/API entirely.
 */
public final class ExistingTranslationChecker {

    private final LanguageDataSource dataSource;

    public ExistingTranslationChecker(LanguageDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return the existing valid translation, or null if none (caller falls through to cache/API). */
    public String check(String key, String targetLang) {
        if (dataSource == null || key == null || targetLang == null) return null;

        String normalizedTarget = LanguageResolver.normalize(targetLang);

        // Target IS the source language: the "translation" is just the original text.
        if (LanguageResolver.DEFAULT_LANGUAGE.equals(normalizedTarget)) {
            String defaultValue = dataSource.lookupDefaultLanguageValue(key);
            return isBlank(defaultValue) ? null : defaultValue;
        }

        String targetValue = dataSource.lookupTargetLanguageValue(key, normalizedTarget);
        if (isBlank(targetValue)) return null;
        if (Objects.equals(targetValue, key)) return null; // untranslated stub echoing the key

        String defaultValue = dataSource.lookupDefaultLanguageValue(key);
        if (Objects.equals(targetValue, defaultValue)) return null; // en_us fallback carried through, not a real translation

        return targetValue;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
