package com.kiyo.alltranslator.lang;

import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import com.kiyo.alltranslator.service.TranslationService;

import java.util.concurrent.CompletableFuture;

/**
 * Phase 3 orchestrator for translation-key-backed and dynamic language content
 * (items, blocks, UI strings, mod lang files, FTB Quests text, etc).
 *
 * Priority chain per ARCHITECTURE.md §3:
 *   existing valid translation (ExistingTranslationChecker, sync, no cache/API used at all)
 *     ↓ miss
 *   TranslationService.translate(..., persistable=true)   (memory -> world-save cache -> API -> original)
 *
 * Not yet attached to any live Minecraft hook (ItemStack#getHoverName etc - Phase 4). This is
 * the mechanism Phase 4 calls into once the interception points exist.
 */
public final class LocalizedTextResolver {

    private final ExistingTranslationChecker existingTranslationChecker;
    private final TranslationService translationService;
    private final LanguageResolver languageResolver;

    public LocalizedTextResolver(ExistingTranslationChecker existingTranslationChecker,
                                  TranslationService translationService,
                                  LanguageResolver languageResolver) {
        this.existingTranslationChecker = existingTranslationChecker;
        this.translationService = translationService;
        this.languageResolver = languageResolver;
    }

    /**
     * @param key        the Minecraft/mod translation key (e.g. "item.mymod.foo"), or null if
     *                   sourceText is keyless dynamic content (FTB Quests custom text etc) -
     *                   existing-translation lookup is skipped entirely for null keys.
     * @param sourceText the en_us / fallback text to translate if no existing translation is found.
     */
    public CompletableFuture<String> resolve(String key, String sourceText) {
        String targetLang = languageResolver.resolveTargetLanguage();

        if (key != null) {
            String existing = existingTranslationChecker.check(key, targetLang);
            if (existing != null) {
                return CompletableFuture.completedFuture(existing);
            }
        }

        if (sourceText == null || sourceText.isBlank()) {
            return CompletableFuture.completedFuture(sourceText);
        }

        TranslationRequest request = new TranslationRequest(sourceText, null, targetLang);
        return translationService.translate(request, true) // persistable=true: this is not chat (§8.1)
                .thenApply(TranslationResult::translatedText);
    }
}
