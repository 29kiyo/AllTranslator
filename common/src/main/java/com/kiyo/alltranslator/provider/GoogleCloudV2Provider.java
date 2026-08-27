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
import java.util.concurrent.CompletableFuture;

/**
 * Google Cloud Translation API v2 ("Basic" tier, api-key auth - the OFFICIAL paid
 * Google Cloud product, distinct from GoogleWebFreeProvider's unofficial free
 * endpoint). This is the provider Phase 8's DEVELOPMENT_STATUS.md flagged as
 * unsupported ("GenericRestProvider can't parse Google's nested response
 * structure") - this dedicated provider closes that gap.
 *
 * Request/response/error shapes verified 2026-08 against docs.cloud.google.com's
 * "Method: translate" reference and the Cloud Quotas troubleshooting doc (CLAUDE.md
 * §3):
 *
 *   POST <endpoint>  Content-Type: application/json; charset=utf-8
 *   X-goog-api-key: <rawApiKey>
 *   { "q": ["<sourceText>"], "target": "<lowercase code>", "format": "text" }
 *
 *   200 -> { "data": { "translations": [ { "translatedText": "..." } ] } }
 *   400 -> INVALID_ARGUMENT (config/request error)
 *   403 -> PERMISSION_DENIED (auth failed - missing/invalid key)
 *   429 -> RESOURCE_EXHAUSTED - Google's own docs state this single status code
 *          covers BOTH rate limiting and quota exhaustion with no reliable way to
 *          tell them apart from the HTTP layer alone, so both are classified as
 *          RATE_LIMITED (auto-recoverable cooldown) here rather than guessing at
 *          QUOTA_EXCEEDED's longer cooldown - ARCHITECTURE.md §6 already anticipates
 *          exactly this case ("providers that don't map, RATE_LIMITED conservatively").
 *   5xx -> temporary server error
 *
 * Error body shape: { "error": { "code": <int>, "message": "...", "status": "<RPC
 * status string>" } }.
 *
 * SAME CAVEAT AS DeepLCompatibleProvider: verified against a local mock reproducing
 * the documented shape (tools/mock_translation_server.py, shape=google_v2), NOT
 * against the real translation.googleapis.com with an actual billed API key (none
 * available). Recorded as a known limitation, not silently presented as fully
 * verified end-to-end.
 */
public final class GoogleCloudV2Provider implements TranslationProvider {

    private final HttpClient httpClient;

    public GoogleCloudV2Provider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ProviderType type() { return ProviderType.GOOGLE_CLOUD_V2; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config, String rawApiKey, InFlightCallRegistry registry) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            CompletableFuture<TranslationResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(new TranslationException(ApiFailureType.CONFIG_ERROR, -1,
                    "No API key configured for " + config.displayName()));
            return failed;
        }

        JsonObject body = new JsonObject();
        JsonArray qArray = new JsonArray();
        qArray.add(request.sourceText());
        body.add("q", qArray);
        body.addProperty("target", mapToGoogleV2LanguageCode(request.targetLang()));
        body.addProperty("format", "text");

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-goog-api-key", rawApiKey)
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
                        String reason = extractErrorMessage(response.body());
                        String message = "HTTP " + status + " from " + config.displayName()
                                + (reason != null ? ": " + reason : "");
                        throw new TranslationException(classify(status), status, message, null, retryAfter);
                    }
                    String translated = extractTranslation(response.body());
                    return new TranslationResult(translated, request.sourceText(), request.targetLang(),
                            config.id(), false, translated.equals(request.sourceText()));
                });
    }

    private static String extractTranslation(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            return root.getAsJsonObject("data").getAsJsonArray("translations")
                    .get(0).getAsJsonObject().get("translatedText").getAsString();
        } catch (RuntimeException e) {
            throw new TranslationException(ApiFailureType.TEMP_UNAVAILABLE, -1,
                    "Google Cloud Translation v2 returned an unexpected response shape", e, -1);
        }
    }

    private static String extractErrorMessage(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null && error.has("message")) return error.get("message").getAsString();
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /**
     * Best-effort: Google Cloud Translation uses standard lowercase ISO codes
     * ("es", "ja"). Primary-subtag extraction verified against the documented
     * example (target: "es") - not independently verified for every locale
     * variant Google's /languages endpoint actually lists.
     */
    static String mapToGoogleV2LanguageCode(String mcLocaleCode) {
        if (mcLocaleCode == null || mcLocaleCode.isBlank()) return "en";
        String lower = mcLocaleCode.toLowerCase(Locale.ROOT);
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

    private static ApiFailureType classify(int status) {
        if (status == 403) return ApiFailureType.AUTH_FAILED;
        if (status == 429) return ApiFailureType.RATE_LIMITED; // covers both rate-limit AND quota per Google's own docs
        if (status == 400 || status == 404) return ApiFailureType.CONFIG_ERROR;
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
