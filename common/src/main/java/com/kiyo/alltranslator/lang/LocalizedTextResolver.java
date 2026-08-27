package com.kiyo.alltranslator.lang;

import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import com.kiyo.alltranslator.service.TranslationService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * Phase 14 addition: embedded-legacy-color-code handling (see resolve()'s Javadoc
 * below for the real-world bug this fixes and the two earlier approaches that
 * were tried and rejected before landing on this one).
 */
public final class LocalizedTextResolver {

    /**
     * Matches a single legacy Minecraft formatting code: a section sign followed
     * by one hex-digit-or-letter code character (color codes 0-9a-f, plus the
     * k/l/m/n/o/r format/reset codes), case-insensitive.
     */
    private static final Pattern LEGACY_CODE_PATTERN = Pattern.compile("\u00A7[0-9a-fk-orA-FK-OR]");

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
     *
     *                   Phase 14 (real-world bug: Traveler's Backpack tooltip lines like
     *                   "§6Backpack Tier: §4Leather" lost their color entirely after
     *                   translation, and a later line's color code leaked onto the wrong
     *                   words). Root cause: some mods embed raw legacy formatting codes
     *                   directly as literal characters in a Component's plain text
     *                   (rather than via Style), so they reach here as ordinary
     *                   characters inside sourceText and get sent to the translation
     *                   API/LLM like any other text.
     *
     *                   Two marker-based approaches (hiding each code behind a
     *                   private-use-area token before translating, then restoring it
     *                   afterward - both PlaceholderProtector-style, and a custom
     *                   multi-color tag scheme) were tried and BOTH failed against this
     *                   project's local 7B LLM backend: real-world testing (persistent
     *                   cache inspection) showed the model reliably stripped unfamiliar
     *                   private-use characters from its output entirely - worse than the
     *                   original literal "§6" surviving at least partially intact.
     *
     *                   This method instead never sends a legacy code to any translation
     *                   backend at all: if sourceText contains one, it is split into runs
     *                   at each code boundary, each run's plain text is translated
     *                   independently (recursively through this same resolve() method,
     *                   so it still benefits from cache/existing-translation-file lookups
     *                   for that fragment), and the original code characters are spliced
     *                   back in verbatim, in code, between the translated runs. This
     *                   guarantees the codes themselves can never be dropped or garbled by
     *                   a translation backend, at the cost of translating each run without
     *                   the rest of the line as surrounding context.
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

        if (LEGACY_CODE_PATTERN.matcher(sourceText).find()) {
            return resolveWithEmbeddedColorCodes(sourceText);
        }

        TranslationRequest request = new TranslationRequest(sourceText, null, targetLang);
        return translationService.translate(request, true) // persistable=true: this is not chat (§8.1)
                .thenApply(TranslationResult::translatedText);
    }

    private CompletableFuture<String> resolveWithEmbeddedColorCodes(String sourceText) {
        List<Segment> segments = splitByLegacyCodes(sourceText);
        List<CompletableFuture<String>> segmentFutures = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            if (segment.text().isBlank()) {
                segmentFutures.add(CompletableFuture.completedFuture(segment.text()));
            } else {
                // key=null: an individual run is a fragment of a larger line, not
                // itself the full value backing a translation key, so the
                // existing-translation-file lookup (keyed on the WHOLE value)
                // does not apply per-fragment. Still goes through this same
                // resolve() method otherwise, so fragment-level cache/API
                // handling is identical to any other keyless dynamic text.
                segmentFutures.add(resolve(null, segment.text()));
            }
        }
        return CompletableFuture.allOf(segmentFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < segments.size(); i++) {
                        String code = segments.get(i).code();
                        if (code != null) {
                            sb.append(code);
                        }
                        sb.append(segmentFutures.get(i).join());
                    }
                    return sb.toString();
                });
    }

    /** A run of text, together with the legacy code (if any) that immediately precedes it. */
    private record Segment(String code, String text) {}

    private static List<Segment> splitByLegacyCodes(String sourceText) {
        List<Segment> segments = new ArrayList<>();
        Matcher m = LEGACY_CODE_PATTERN.matcher(sourceText);
        int last = 0;
        String currentCode = null;
        while (m.find()) {
            String textBefore = sourceText.substring(last, m.start());
            if (!textBefore.isEmpty()) {
                segments.add(new Segment(currentCode, textBefore));
            }
            currentCode = m.group();
            last = m.end();
        }
        String tail = sourceText.substring(last);
        if (!tail.isEmpty() || currentCode != null) {
            segments.add(new Segment(currentCode, tail));
        }
        return segments;
    }
}
