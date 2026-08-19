package com.kiyo.alltranslator.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.kiyo.alltranslator.api.ApiFailureType;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.api.TranslationException;
import com.kiyo.alltranslator.api.TranslationProvider;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import com.kiyo.alltranslator.service.TranslationApiConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ARCHITECTURE.md §22: last-resort fallback using the free, UNOFFICIAL Google Translate
 * web endpoint (translate.googleapis.com/translate_a/single?client=gtx). Not the official
 * Cloud Translation API - no key, no SLA, no documented contract. Off by default
 * an ordinary, priority-orderable TranslationApiConfig entry like any other provider.
 * the flag is on, so it's tried once every user-configured API has failed or none exist.
 *
 * Response shape verified 2026-08 by hand (actual curl output, not assumed - CLAUDE.md §3):
 *   [[["<translated>","<original>",null,null,10]],null,"<detectedSrc>", ...]
 * i.e. a top-level array whose [0] is an array of per-sentence segments; each segment's
 * [0] is that sentence's translated text. Multi-sentence input yields multiple segments,
 * concatenated here in order. This is NOT a documented contract and Google can change it
 * without notice - parsing failures are treated as TEMP_UNAVAILABLE (auto-recoverable via
 * ApiState cooldown), never as a crash.
 *
 * Also verified: an unrecognized target language code (e.g. "zz") still returns HTTP 200
 * with a JSON body, NOT an error - so success detection here is shape-based (did we get a
 * translated segment string back), not just status-code-based.
 */
public final class GoogleWebFreeProvider implements TranslationProvider {

    private final HttpClient httpClient;

    public GoogleWebFreeProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ProviderType type() { return ProviderType.GOOGLE_WEB_FREE; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config, String rawApiKey) {
        String googleLang = mapToGoogleLanguageCode(request.targetLang());
        String encodedText = URLEncoder.encode(request.sourceText(), StandardCharsets.UTF_8);
        String url = config.endpoint() + "?client=gtx&sl=auto&tl=" + googleLang + "&dt=t&q=" + encodedText;

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status != 200) {
                        long retryAfter = readRetryAfter(response);
                        throw new TranslationException(classify(status), status,
                                "HTTP " + status + " from free Google Translate endpoint", null, retryAfter);
                    }
                    String translated = extractTranslation(response.body());
                    return new TranslationResult(translated, request.sourceText(), request.targetLang(),
                            config.id(), false, translated.equals(request.sourceText()));
                });
    }

    private static String extractTranslation(String responseBody) {
        try {
            JsonElement root = JsonParser.parseString(responseBody);
            JsonArray segments = root.getAsJsonArray().get(0).getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (JsonElement segmentElement : segments) {
                JsonArray segment = segmentElement.getAsJsonArray();
                if (segment.size() > 0 && !segment.get(0).isJsonNull()) {
                    sb.append(segment.get(0).getAsString());
                }
            }
            if (sb.length() == 0) {
                throw new TranslationException(ApiFailureType.TEMP_UNAVAILABLE, -1,
                        "Free Google Translate endpoint returned no translated segments (unexpected/changed response shape)");
            }
            return sb.toString();
        } catch (TranslationException e) {
            throw e;
        } catch (RuntimeException e) {
            // Malformed/unexpected JSON (e.g. an HTML error page instead of the usual array) -
            // treat as a temporary failure rather than crashing, since this is an
            // unofficial/undocumented endpoint that can change shape without notice.
            throw new TranslationException(ApiFailureType.TEMP_UNAVAILABLE, -1,
                    "Free Google Translate endpoint returned an unexpected response shape", e, -1);
        }
    }

    /**
     * Best-effort mapping from Minecraft locale codes (e.g. "ja_jp") to the codes this
     * endpoint expects (e.g. "ja"). Verified against a real request only for the primary-
     * subtag case ("ja_jp" -> "ja" was confirmed to work by hand). The zh/pt special-casing
     * below follows Google's documented BCP-47-style codes for those languages but has NOT
     * been independently verified against this specific unofficial endpoint - flagged here
     * rather than assumed correct (CLAUDE.md §3).
     */
    static String mapToGoogleLanguageCode(String mcLocaleCode) {
        if (mcLocaleCode == null || mcLocaleCode.isBlank()) return "en";
        String lower = mcLocaleCode.toLowerCase(Locale.ROOT);
        Map<String, String> specialCases = Map.of(
                "zh_cn", "zh-CN",
                "zh_tw", "zh-TW",
                "pt_br", "pt",
                "pt_pt", "pt"
        );
        if (specialCases.containsKey(lower)) return specialCases.get(lower);
        int underscore = lower.indexOf('_');
        return underscore > 0 ? lower.substring(0, underscore) : lower;
    }

    private static long readRetryAfter(HttpResponse<String> response) {
        try {
            return response.headers().firstValueAsLong("Retry-After").orElse(-1);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 403/429 map to RATE_LIMITED (not AUTH_FAILED) even though 403 often signals "auth"
     * elsewhere: this endpoint takes no credential, so there is nothing a user could fix in
     * config to recover, and DISABLED_PERMANENT would leave this fallback stuck off with no
     * UI to re-enable it (it isn't a user-editable TranslationApiConfig - see
     * a user-editable API list entry, but there is no UI concept of "auto-recover a deleted entry" - so cooldown-based auto-recovery is the only sensible behavior
     * here.
     */
    private static ApiFailureType classify(int status) {
        if (status == 403 || status == 429) return ApiFailureType.RATE_LIMITED;
        return ApiFailureType.TEMP_UNAVAILABLE;
    }

    @Override
    public ApiFailureType classifyError(Throwable error, int httpStatus) {
        if (error instanceof TranslationException) return ((TranslationException) error).failureType();
        if (httpStatus > 0) return classify(httpStatus);
        return ApiFailureType.TEMP_UNAVAILABLE;
    }
}
