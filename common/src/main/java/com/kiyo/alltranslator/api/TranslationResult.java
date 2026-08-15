package com.kiyo.alltranslator.api;

import java.util.UUID;

public final class TranslationResult {
    private final String translatedText;
    private final String sourceText;
    private final String targetLang;
    private final UUID providerConfigId; // null if fallback-to-original / not attributable
    private final boolean fromCache;
    private final boolean noTranslationNeeded;

    public TranslationResult(String translatedText, String sourceText, String targetLang,
                              UUID providerConfigId, boolean fromCache, boolean noTranslationNeeded) {
        this.translatedText = translatedText;
        this.sourceText = sourceText;
        this.targetLang = targetLang;
        this.providerConfigId = providerConfigId;
        this.fromCache = fromCache;
        this.noTranslationNeeded = noTranslationNeeded;
    }

    /** Used when all APIs fail/are unavailable, or the text is blank: fall back to original text. */
    public static TranslationResult original(String sourceText, String targetLang) {
        return new TranslationResult(sourceText, sourceText, targetLang, null, false, true);
    }

    public String translatedText() { return translatedText; }
    public String sourceText() { return sourceText; }
    public String targetLang() { return targetLang; }
    public UUID providerConfigId() { return providerConfigId; }
    public boolean fromCache() { return fromCache; }
    public boolean noTranslationNeeded() { return noTranslationNeeded; }
}
