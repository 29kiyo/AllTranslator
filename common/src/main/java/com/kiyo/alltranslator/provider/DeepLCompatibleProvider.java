package com.kiyo.alltranslator.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kiyo.alltranslator.api.ApiFailureType;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.api.TranslationException;
import com.kiyo.alltranslator.api.TranslationProvider;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import com.kiyo.alltranslator.service.InFlightCallRegistry;
import com.kiyo.alltranslator.service.TranslationApiConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DeepL API (api.deepl.com for Pro, api-free.deepl.com for Free - the Endpoint URL
 * field in the API config determines which). Request/response/error shapes verified
 * 2026-08 against developers.deepl.com/docs and support.deepl.com's "DeepL API error
 * messages" article (CLAUDE.md §3):
 *
 *   POST <endpoint>  Content-Type: application/json
 *   Authorization: DeepL-Auth-Key <rawApiKey>
 *   { "text": ["<sourceText>"], "target_lang": "<UPPERCASE CODE>" }
 *
 *   200 -> { "translations": [ { "detected_source_language": "EN", "text": "..." } ] }
 *   403 -> auth failed (missing/incorrect key)
 *   404/413/414 -> config/request-shape error
 *   429 -> rate limited
 *   456 -> QUOTA EXCEEDED (DeepL-specific status code, NOT a generic HTTP code -
 *          confirmed by three independent DeepL-authored/DeepL-documented sources)
 *   5xx -> temporary server error
 *
 * IMPORTANT, UNVERIFIED-AGAINST-A-REAL-KEY CAVEAT (Phase 11): this parsing/error-
 * classification logic was verified against a local mock server that reproduces the
 * documented shapes above (tools/mock_translation_server.py, shape=deepl), NOT
 * against the real api.deepl.com/api-free.deepl.com with an actual API key (none
 * available). If DeepL's real behavior diverges from its own documentation in any
 * undocumented way, this has not been caught. Recorded as a known limitation in
 * DEVELOPMENT_STATUS.md rather than silently presented as fully verified.
 */
public final class DeepLCompatibleProvider implements TranslationProvider {

    private final HttpClient httpClient;

    public DeepLCompatibleProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ProviderType type() { return ProviderType.DEEPL_COMPATIBLE; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config, String rawApiKey, InFlightCallRegistry registry) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            CompletableFuture<TranslationResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(new TranslationException(ApiFailureType.CONFIG_ERROR, -1,
                    "No API key configured for " + config.displayName()));
            return failed;
        }

        JsonObject body = new JsonObject();
        JsonArray textArray = new JsonArray();
        textArray.add(request.sourceText());
        body.add("text", textArray);
        body.addProperty("target_lang", mapToDeepLLanguageCode(request.targetLang()));

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "DeepL-Auth-Key " + rawApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        CompletableFuture<HttpResponse<String>> rawCall = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString());
        java.util.UUID callId = registry.register(rawCall);
        return rawCall
                .whenComplete((r, t) -> registry.unregister(callId))
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status != 200) {
                        long retryAfter = readRetryAfter(response);
                        throw new TranslationException(classify(status), status,
                                "HTTP " + status + " from " + config.displayName(), null, retryAfter);
                    }
                    String translated = extractTranslation(response.body());
                    return new TranslationResult(translated, request.sourceText(), request.targetLang(),
                            config.id(), false, translated.equals(request.sourceText()));
                });
    }

    private static String extractTranslation(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            return root.getAsJsonArray("translations").get(0).getAsJsonObject().get("text").getAsString();
        } catch (RuntimeException e) {
            throw new TranslationException(ApiFailureType.TEMP_UNAVAILABLE, -1,
                    "DeepL returned an unexpected response shape", e, -1);
        }
    }

    /**
     * Best-effort mapping from Minecraft locale codes to DeepL's uppercase codes.
     * Verified against DeepL's documented example (target_lang: "DE") for the
     * general uppercase-primary-subtag pattern. NOT independently verified for every
     * DeepL-specific variant requirement (e.g. DeepL requires "EN-US"/"EN-GB" rather
     * than bare "EN" as a TARGET language in current API versions, and "PT-BR"/
     * "PT-PT" rather than bare "PT") - handled here via a small explicit table for
     * the cases confirmed in documentation, with a generic uppercase-primary-subtag
     * fallback for everything else. An unsupported/malformed code most likely
     * surfaces as an HTTP 400 from DeepL itself (a safe, visible CONFIG_ERROR),
     * not a silent wrong translation.
     */
    static String mapToDeepLLanguageCode(String mcLocaleCode) {
        if (mcLocaleCode == null || mcLocaleCode.isBlank()) return "EN-US";
        String lower = mcLocaleCode.toLowerCase(Locale.ROOT);
        Map<String, String> specialCases = Map.of(
                "en_us", "EN-US",
                "en_gb", "EN-GB",
                "pt_br", "PT-BR",
                "pt_pt", "PT-PT",
                "zh_cn", "ZH"
        );
        if (specialCases.containsKey(lower)) return specialCases.get(lower);
        int underscore = lower.indexOf('_');
        String primary = underscore > 0 ? lower.substring(0, underscore) : lower;
        return primary.toUpperCase(Locale.ROOT);
    }

    private static long readRetryAfter(HttpResponse<String> response) {
        try {
            return response.headers().firstValueAsLong("Retry-After").orElse(-1);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ApiFailureType classify(int status) {
        if (status == 403) return ApiFailureType.AUTH_FAILED;
        if (status == 456) return ApiFailureType.QUOTA_EXCEEDED;
        if (status == 429) return ApiFailureType.RATE_LIMITED;
        if (status == 400 || status == 404 || status == 413 || status == 414) return ApiFailureType.CONFIG_ERROR;
        if (status >= 500) return ApiFailureType.TEMP_UNAVAILABLE;
        return ApiFailureType.UNKNOWN;
    }

    @Override
    public ApiFailureType classifyError(Throwable error, int httpStatus) {
        if (error instanceof TranslationException) return ((TranslationException) error).failureType();
        if (httpStatus > 0) return classify(httpStatus);
        return ApiFailureType.TEMP_UNAVAILABLE;
    }
}
