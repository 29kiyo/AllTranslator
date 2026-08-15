package com.kiyo.alltranslator.api;

import java.util.Objects;

public final class TranslationRequest {
    private final String sourceText;
    private final String sourceLang; // nullable: unknown source language
    private final String targetLang;

    public TranslationRequest(String sourceText, String sourceLang, String targetLang) {
        this.sourceText = Objects.requireNonNull(sourceText, "sourceText");
        this.sourceLang = sourceLang;
        this.targetLang = Objects.requireNonNull(targetLang, "targetLang");
    }

    public String sourceText() { return sourceText; }
    public String sourceLang() { return sourceLang; }
    public String targetLang() { return targetLang; }
}
