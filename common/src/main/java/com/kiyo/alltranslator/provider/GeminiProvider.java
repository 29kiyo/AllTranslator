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
import com.kiyo.alltranslator.lang.LanguageResolver;
import com.kiyo.alltranslator.service.InFlightCallRegistry;
import com.kiyo.alltranslator.service.TranslationApiConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Google Gemini API (generateContent - the stable, non-beta endpoint; deliberately
 * NOT the newer "Interactions API" also seen during verification, which is still
 * in Beta as of the docs checked 2026-08-20). Verified against current public docs
 * (ai.google.dev/api) rather than assumed (CLAUDE.md §3).
 *
 * Endpoint shape includes both model and API key location as configuration:
 *  - config.endpoint() is expected to be the FULL generateContent URL for the
 *    desired model, e.g.
 *    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
 *    (the model is part of the path, unlike OpenAI/Anthropic where it's a JSON
 *    body field) - "Default URL" in ApiEditScreen fills a gemini-2.5-flash default;
 *    users must edit the model segment manually for a different model until
 *    model auto-detection work lands.
 *  - Auth: "x-goog-api-key" header (current documented standard; the older
 *    "?key=" query-param form still works per Google's docs but the header form
 *    is preferred and avoids the key ending up in HTTP access logs).
 *
 * Request body: {"contents":[{"parts":[{"text":"..."}]}]}. No native concept of a
 * separate system-prompt role in this basic shape, so the instruction is prepended
 * to the user text itself.
 * Response: candidates[0].content.parts[0].text.
 *
 * extraParams: "timeoutSeconds" (default 30, same convention as other providers).
 */
public final class GeminiProvider implements TranslationProvider {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final HttpClient httpClient;

    public GeminiProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ProviderType type() { return ProviderType.GEMINI; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config, String rawApiKey, InFlightCallRegistry registry) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            CompletableFuture<TranslationResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(new TranslationException(ApiFailureType.CONFIG_ERROR, -1,
                    "No API key configured for " + config.displayName()));
            return failed;
        }
        if (config.endpoint() == null || config.endpoint().isBlank()) {
            CompletableFuture<TranslationResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(new TranslationException(ApiFailureType.CONFIG_ERROR, -1,
                    "No endpoint (model URL) configured for " + config.displayName()));
            return failed;
        }

        String instruction = "Translate the following text into "
                + LanguageResolver.toReadableName(request.targetLang())
                + " (locale code '" + request.targetLang() + "'). Reply with ONLY the translated text, "
                + "no quotes, no explanation, and preserve any placeholder tokens exactly as-is.\n\n"
                + request.sourceText();
        int timeoutSeconds = readTimeoutSeconds(config);

        JsonObject part = new JsonObject();
        part.addProperty("text", instruction);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject body = new JsonObject();
        body.add("contents", contents);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", rawApiKey)
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
                        throw new TranslationException(classify(status, response.body()), status,
                                "HTTP " + status + " from " + config.displayName(), null, retryAfter);
                    }
                    String translated = extractContent(response.body());
                    return new TranslationResult(translated, request.sourceText(), request.targetLang(),
                            config.id(), false, translated.equals(request.sourceText()));
                });
    }

    private static int readTimeoutSeconds(TranslationApiConfig config) {
        String raw = config.extraParams().get("timeoutSeconds");
        if (raw == null || raw.isBlank()) return DEFAULT_TIMEOUT_SECONDS;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_TIMEOUT_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    private static String extractContent(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        return root.getAsJsonArray("candidates")
                .get(0).getAsJsonObject()
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0).getAsJsonObject()
                .get("text").getAsString()
                .trim();
    }

    private static long readRetryAfter(HttpResponse<String> response) {
        try {
            return response.headers().firstValueAsLong("Retry-After").orElse(-1);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Gemini-specific status mapping. Like Google Cloud Translation v2 (Phase 11
     * ARCHITECTURE.md §23.3), rate limit and quota exhaustion both surface as
     * 429 RESOURCE_EXHAUSTED with no reliable machine-distinguishable field, so
     * both are conservatively treated as RATE_LIMITED per ARCHITECTURE.md §6's
     * "provider doesn't distinguish -> assume RATE_LIMITED" fallback policy.
     */
    private static ApiFailureType classify(int status, String body) {
        if (status == 401 || status == 403) return ApiFailureType.AUTH_FAILED;
        if (status == 429) return ApiFailureType.RATE_LIMITED;
        if (status == 400 || status == 404) return ApiFailureType.CONFIG_ERROR;
        if (status >= 500) return ApiFailureType.TEMP_UNAVAILABLE;
        return ApiFailureType.UNKNOWN;
    }

    @Override
    public ApiFailureType classifyError(Throwable error, int httpStatus) {
        if (error instanceof TranslationException) return ((TranslationException) error).failureType();
        if (httpStatus > 0) return classify(httpStatus, null);
        return ApiFailureType.TEMP_UNAVAILABLE;
    }
}
