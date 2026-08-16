package com.kiyo.alltranslator.lang;

/**
 * Abstraction over "does key K have a translated value in language L's own loaded data".
 * Kept free of Minecraft types so ExistingTranslationChecker stays independently testable;
 * the real Minecraft-backed implementation is MinecraftLanguageDataSource.
 */
public interface LanguageDataSource {

    /**
     * @return the raw value for {@code key} in {@code targetLang}'s OWN language data
     *         (not merged/inherited from en_us), or null if that language has no entry
     *         for the key at all.
     */
    String lookupTargetLanguageValue(String key, String targetLang);

    /**
     * @return the raw value for {@code key} in the default fallback language (en_us),
     *         used to detect "value is just the en_us fallback carried through" -
     *         which ARCHITECTURE.md §3 / CLAUDE.md §3 say must NOT count as a valid
     *         existing translation.
     */
    String lookupDefaultLanguageValue(String key);
}
