package com.kiyo.alltranslator.lang;

import java.util.Objects;

/**
 * Implements ARCHITECTURE.md §3 / CLAUDE.md §3:
 *   "valid existing translation" = key exists in the target language's OWN data,
 *   value non-blank, and NOT identical to the key itself (untranslated stub echoing
 *   the key).
 * If valid, the caller MUST use it directly and skip cache/API entirely.
 *
 * Investigation #6 (Phase 14, real-world bug): this class used to ALSO reject a
 * target-language value that happened to be textually identical to the en_us
 * fallback value, on the theory that identical text meant "the fallback leaked
 * through untranslated". Real-machine logging (ItemTooltipTranslationHook /
 * LocalizedTextResolver diagnostics) proved this wrong for several vanilla ja_jp
 * keys used by attack-damage/speed and some attribute-modifier/potion tooltip
 * lines (e.g. attribute.modifier.equals.0 = "%s %s", attribute.modifier.plus.2 =
 * "+%s%% %s", potion.withAmplifier = "%s %s"): these are genuine, intentional
 * vanilla ja_jp translations that simply happen to be byte-identical to their
 * en_us counterparts (the template has no words to reorder/translate - just
 * format placeholders and, for some, a shared symbol like "%"). The en_us-equality
 * check was therefore silently discarding real existing translations and sending
 * them to the API instead, corrupting output like nested attribute tooltip lines.
 * CompositeLanguageDataSource's own lookupTargetLanguageValue(...) contract already
 * only returns a value that genuinely lives in the target language's own data (see
 * MinecraftLanguageDataSource/CustomLanguageFileManager - neither merges in en_us as
 * a fallback at the LanguageDataSource level), so re-deriving "is this really a
 * fallback" via a text-equality heuristic here was both redundant and wrong. Removed.
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

        return targetValue;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
