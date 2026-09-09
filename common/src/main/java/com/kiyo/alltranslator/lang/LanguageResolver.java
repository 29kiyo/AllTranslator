package com.kiyo.alltranslator.lang;

import com.kiyo.alltranslator.config.ConfigManager;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resolves the effective target language per ARCHITECTURE.md §4:
 *   config.forcedTargetLanguage (explicit user override)
 *     ↓ (if unset/blank)
 *   client-supplied language (wired via setClientLanguageSupplier - not yet connected
 *   to Minecraft.getInstance().options.languageCode() as of Phase 3; that hook-up
 *   happens when a client entry point exists, Phase 4)
 *     ↓ (if unavailable)
 *   DEFAULT_LANGUAGE ("en_us")
 *
 * Per-player (server) resolution is a Phase 6 concern (per-player map, synced from client).
 * This class never throws and never blocks.
 */
public final class LanguageResolver {

    public static final String DEFAULT_LANGUAGE = "en_us";

    private final ConfigManager configManager;
    private volatile Supplier<String> clientLanguageSupplier;

    public LanguageResolver(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /** Wired by the client bootstrap once one exists (Phase 4). Not set = falls through. */
    public void setClientLanguageSupplier(Supplier<String> supplier) {
        this.clientLanguageSupplier = supplier;
    }

    public String resolveTargetLanguage() {
        String forced = configManager.model().forcedTargetLanguage;
        if (forced != null && !forced.isBlank()) {
            return normalize(forced);
        }

        Supplier<String> supplier = clientLanguageSupplier;
        if (supplier != null) {
            try {
                String clientLang = supplier.get();
                if (clientLang != null && !clientLang.isBlank()) {
                    return normalize(clientLang);
                }
            } catch (Exception e) {
                // Never let a client-side lookup failure break translation resolution.
            }
        }

        return DEFAULT_LANGUAGE;
    }

    /**
     * Phase 13 bugfix: the CLIENT's own live display language (Options#languageCode
     * via clientLanguageSupplier), ignoring ConfigModel#forcedTargetLanguage. Needed
     * because vanilla/mod UI text scraped by the keyless widget-translation hooks
     * (ScreenWidgetTranslationHook, LibIpnCompat, UiLibCompat) is always rendered by
     * Minecraft's own active language system - NOT by All Translator's configured
     * target language. If the client's live display language already equals the
     * resolved target language, that scraped text is already in the target language
     * and must not be re-sent to the translation API as if it were untranslated
     * source text. Real-world bug this fixes: already-Japanese block/item names
     * (e.g. "辰砂", "辰砂の階段") captured from a mod's own UI were sent to
     * the LLM and "translated" into slightly different, WRONG Japanese
     * (e.g. "辰砂" -> "赤土", "辰砂の階段" -> "朱砂の階段") - reproduced specifically
     * when the client's Minecraft language and the configured target language were
     * BOTH ja_jp. Falls back to DEFAULT_LANGUAGE if no supplier is wired (server
     * side / not yet set), matching resolveTargetLanguage()'s own fallback.
     */
    public String resolveLiveClientLanguage() {
        Supplier<String> supplier = clientLanguageSupplier;
        if (supplier != null) {
            try {
                String clientLang = supplier.get();
                if (clientLang != null && !clientLang.isBlank()) {
                    return normalize(clientLang);
                }
            } catch (Exception e) {
                // Never let a client-side lookup failure break translation resolution.
            }
        }
        return DEFAULT_LANGUAGE;
    }

    /**
     * Phase 9 addition: best-effort shorthand/alias table so common 2-letter codes (and a
     * couple of well-known non-standard aliases, e.g. "jp" for Japanese) resolve to the
     * actual Minecraft language code without the user needing to know the exact
     * "xx_yy" form. NOT an exhaustive list of every Minecraft-supported language - unknown
     * inputs simply pass through unchanged (normalize() never throws), so any full vanilla
     * code (e.g. "pt_br", "zh_tw") keeps working exactly as before regardless of whether it
     * appears here.
     */
    private static final Map<String, String> COMMON_ALIASES = Map.ofEntries(
            // English variants (language code and common country-code shorthand both accepted)
            Map.entry("en", "en_us"),
            Map.entry("us", "en_us"),
            Map.entry("gb", "en_gb"),
            Map.entry("uk", "en_gb"),
            // Japanese ("jp" is not the correct ISO 639 code but is the overwhelmingly common
            // shorthand users actually type, so it is accepted alongside the correct "ja")
            Map.entry("ja", "ja_jp"),
            Map.entry("jp", "ja_jp"),
            // Korean
            Map.entry("ko", "ko_kr"),
            Map.entry("kr", "ko_kr"),
            // Chinese (defaults to Simplified/mainland; Traditional variants need the full code)
            Map.entry("zh", "zh_cn"),
            Map.entry("cn", "zh_cn"),
            Map.entry("tw", "zh_tw"),
            Map.entry("hk", "zh_hk"),
            // European languages
            Map.entry("fr", "fr_fr"),
            Map.entry("de", "de_de"),
            Map.entry("es", "es_es"),
            Map.entry("mx", "es_mx"),
            Map.entry("pt", "pt_br"),
            Map.entry("br", "pt_br"),
            Map.entry("ru", "ru_ru"),
            Map.entry("it", "it_it"),
            Map.entry("nl", "nl_nl"),
            Map.entry("pl", "pl_pl"),
            Map.entry("sv", "sv_se"),
            Map.entry("fi", "fi_fi"),
            Map.entry("da", "da_dk"),
            Map.entry("no", "no_no"),
            Map.entry("cs", "cs_cz"),
            Map.entry("hu", "hu_hu"),
            Map.entry("ro", "ro_ro"),
            Map.entry("bg", "bg_bg"),
            Map.entry("el", "el_gr"),
            // Middle East / South & Southeast Asia
            Map.entry("he", "he_il"),
            Map.entry("hi", "hi_in"),
            Map.entry("fa", "fa_ir"),
            Map.entry("vi", "vi_vn"),
            Map.entry("th", "th_th"),
            Map.entry("tr", "tr_tr"),
            Map.entry("id", "id_id"),
            Map.entry("ar", "ar_sa")
    );

    public static String normalize(String languageCode) {
        String trimmed = languageCode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return COMMON_ALIASES.getOrDefault(trimmed, trimmed);
    }

    /**
     * Phase 14 (real-world bug: prompt-based LLM providers - OpenAiCompatibleProvider,
     * AnthropicProvider, GeminiProvider - were told to translate into "the language
     * with code 'ja_jp'", and a local 7B model observably failed to reliably follow
     * this: real multiplayer testing produced a Spanish reply ("amigo") for a
     * ja_jp-targeted translation, and other requests silently stayed in English
     * unchanged. A raw Minecraft locale code is not natural language and small
     * models evidently don't always map it to the intended language reliably.
     * DeepL/GoogleCloudV2/GoogleWebFree are unaffected - those are dedicated
     * translation APIs taking a proper language-code PARAMETER, not a natural-
     * language instruction, so this table is only consulted by the three
     * prompt-based providers above.
     *
     * Phase 14 (2nd revision): for codes not in this table, toReadableName() no longer
     * falls straight through to the raw code. It first attempts a dynamic lookup via
     * java.util.Locale (see resolveViaLocale()) and only falls back to the raw code if
     * that also fails to produce a real display name. This is purely a "make sure the
     * LLM understands which language is meant" fix - it does NOT improve or guarantee
     * translation quality for languages the underlying model itself barely knows; that
     * remains a model-capability limitation (see DEVELOPMENT_STATUS.md's local 7B
     * quantized model quality notes).
     */
    private static final Map<String, String> READABLE_NAMES = Map.ofEntries(
            Map.entry("en_us", "English"),
            Map.entry("en_gb", "English"),
            Map.entry("ja_jp", "Japanese"),
            Map.entry("ko_kr", "Korean"),
            Map.entry("zh_cn", "Simplified Chinese"),
            Map.entry("zh_tw", "Traditional Chinese"),
            Map.entry("zh_hk", "Traditional Chinese"),
            Map.entry("fr_fr", "French"),
            Map.entry("de_de", "German"),
            Map.entry("es_es", "Spanish"),
            Map.entry("es_mx", "Spanish"),
            Map.entry("pt_br", "Portuguese"),
            Map.entry("pt_pt", "Portuguese"),
            Map.entry("ru_ru", "Russian"),
            Map.entry("it_it", "Italian"),
            Map.entry("nl_nl", "Dutch"),
            Map.entry("pl_pl", "Polish"),
            Map.entry("sv_se", "Swedish"),
            Map.entry("fi_fi", "Finnish"),
            Map.entry("da_dk", "Danish"),
            Map.entry("no_no", "Norwegian"),
            Map.entry("cs_cz", "Czech"),
            Map.entry("hu_hu", "Hungarian"),
            Map.entry("ro_ro", "Romanian"),
            Map.entry("bg_bg", "Bulgarian"),
            Map.entry("el_gr", "Greek"),
            Map.entry("he_il", "Hebrew"),
            Map.entry("hi_in", "Hindi"),
            Map.entry("fa_ir", "Persian"),
            Map.entry("vi_vn", "Vietnamese"),
            Map.entry("th_th", "Thai"),
            Map.entry("tr_tr", "Turkish"),
            Map.entry("id_id", "Indonesian"),
            Map.entry("ar_sa", "Arabic")
    );

    /**
     * @param normalizedCode already-normalized (normalize()'d) target language code.
     *
     * Three-tier fallback (Phase 14, 2nd revision):
     *   1. READABLE_NAMES table (curated, always wins if present)
     *   2. java.util.Locale dynamic resolution (resolveViaLocale())
     *   3. the raw normalizedCode, unchanged, if both of the above fail
     *
     * Only affects the three prompt-based providers (OpenAiCompatibleProvider,
     * AnthropicProvider, GeminiProvider). DeepL/GoogleCloudV2/GoogleWebFree pass the
     * language code as a dedicated API parameter and never call this method for that
     * purpose.
     */
    public static String toReadableName(String normalizedCode) {
        if (normalizedCode == null) return null;
        String known = READABLE_NAMES.get(normalizedCode);
        if (known != null) return known;
        return resolveViaLocale(normalizedCode);
    }

    /**
     * Phase 14 (2nd revision) dynamic fallback for target-language codes not present in
     * READABLE_NAMES. Verified against actual java.util.Locale behavior (JDK 25, this
     * project's toolchain) via a standalone test program before writing this method
     * (CLAUDE.md §3 - no invented API behavior):
     *
     *   - Minecraft codes use "xx_yy" (underscore, lowercase region); Locale.forLanguageTag
     *     expects BCP 47 "xx-YY" (hyphen). Simply replacing '_' with '-' and handing the
     *     lowercase-region form to forLanguageTag() works correctly - confirmed
     *     Locale.forLanguageTag("ja-jp").getDisplayLanguage(Locale.ENGLISH) returns
     *     "Japanese" even with a lowercase region subtag; case is not significant to the
     *     parser.
     *   - For a language subtag Locale does not recognize (e.g. the test input "xx_yy"),
     *     getDisplayLanguage(Locale.ENGLISH) returns the subtag itself back unchanged
     *     ("xx") rather than throwing or returning blank - confirmed by direct test.
     *     This is the actual failure signal to detect: if the resolved display name is
     *     blank OR case-insensitively equal to the parsed language subtag, Locale did not
     *     actually know this language, so this method falls through to the raw code
     *     rather than handing the LLM a bare 2-3 letter code as though it were a real
     *     language name.
     *   - Real (non-Minecraft-standard) codes resolve correctly, e.g. "km_kh" (Khmer,
     *     confirmed -> "Khmer") and Minecraft's own non-standard "en_pt" (Pirate Speak,
     *     confirmed -> "English", since only the "en" language subtag is meaningful to
     *     Locale; the "pt" here is Minecraft's own regional variant marker, not ISO
     *     Portuguese, so this correctly still reads as English to the LLM).
     *   - An empty string input is guarded separately: Locale.forLanguageTag("") parses to
     *     the special "und" (undefined) locale whose language subtag is itself blank, and
     *     confirmed getDisplayLanguage() also returns blank for it - handled by the blank
     *     check below.
     *
     * Never throws; returns normalizedCode unchanged on any failure to resolve.
     */
    private static String resolveViaLocale(String normalizedCode) {
        try {
            String tag = normalizedCode.replace('_', '-');
            Locale locale = Locale.forLanguageTag(tag);
            String subtag = locale.getLanguage();
            if (subtag.isBlank()) {
                return normalizedCode;
            }
            String display = locale.getDisplayLanguage(Locale.ENGLISH);
            if (display.isBlank() || display.equalsIgnoreCase(subtag)) {
                return normalizedCode;
            }
            return display;
        } catch (Exception e) {
            return normalizedCode;
        }
    }
}
