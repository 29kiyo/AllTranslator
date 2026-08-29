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
     * Falls back to returning the code itself, unchanged, for anything not in this
     * (intentionally non-exhaustive - matches COMMON_ALIASES's own scope) table, so
     * an unrecognized/less-common target language still gets SOME instruction
     * rather than none.
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

    /** @param normalizedCode already-normalized (normalize()'d) target language code. */
    public static String toReadableName(String normalizedCode) {
        if (normalizedCode == null) return null;
        return READABLE_NAMES.getOrDefault(normalizedCode, normalizedCode);
    }
}
