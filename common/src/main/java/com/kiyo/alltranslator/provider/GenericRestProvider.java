package com.kiyo.alltranslator.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kiyo.alltranslator.api.ApiFailureType;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.api.TranslationException;
import com.kiyo.alltranslator.api.TranslationProvider;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import com.kiyo.alltranslator.service.TranslationApiConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Best-effort generic REST provider for simple, flat-JSON translation APIs.
 * Driven entirely by extraParams so it can target different services without new code:
 *
 *   requestTemplate : JSON body with {text} / {targetLang} placeholders
 *                      (default: {"text":"{text}","target_lang":"{targetLang}"})
 *   authHeader      : header name for the key (default "Authorization")
 *   authPrefix      : value prefix before the raw key (default "Bearer ")
 *   responseField   : dotted path (flat objects only) to the translated text
 *                      (default "translatedText")
 *
 * Nested/array response shapes need confirmation against a real target API before
 * relying on them (CLAUDE.md: never invent APIs). The Phase 0-era CUSTOM provider slot
 * for those was removed in Phase 13 (unused, never registered in ProviderFactory) -
 * a future provider for such a shape should be added as its own named ProviderType
 * once a real target API is confirmed, rather than reintroducing a generic "custom" slot.
 */
public final class GenericRestProvider implements TranslationProvider {

    private final HttpClient httpClient;

    public GenericRestProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ProviderType type() { return ProviderType.GENERIC_REST; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config, String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            CompletableFuture<TranslationResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(new TranslationException(ApiFailureType.CONFIG_ERROR, -1,
                    "No API key configured for " + config.displayName()));
            return failed;
        }

        String template = config.extraParams().getOrDefault("requestTemplate",
                "{\"text\":\"{text}\",\"target_lang\":\"{targetLang}\"}");
        String authHeader = config.extraParams().getOrDefault("authHeader", "Authorization");
        String authPrefix = config.extraParams().getOrDefault("authPrefix", "Bearer ");
        String responseField = config.extraParams().getOrDefault("responseField", "translatedText");

        String bodyJson = template
                .replace("{text}", escapeJson(request.sourceText()))
                .replace("{targetLang}", escapeJson(request.targetLang()));

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header(authHeader, authPrefix + rawApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status != 200) {
                        long retryAfter = readRetryAfter(response);
                        throw new TranslationException(classify(status), status,
                                "HTTP " + status + " from " + config.displayName(), null, retryAfter);
                    }
                    String translated = extractField(response.body(), responseField);
                    return new TranslationResult(translated, request.sourceText(), request.targetLang(),
                            config.id(), false, translated.equals(request.sourceText()));
                });
    }

    private static String extractField(String responseBody, String dottedPath) {
        JsonElement current = JsonParser.parseString(responseBody);
        for (String part : dottedPath.split("\\.")) {
            if (current == null || !current.isJsonObject()) {
                throw new TranslationException(ApiFailureType.UNKNOWN, -1,
                        "Unexpected response shape while reading field '" + dottedPath + "'");
            }
            current = ((JsonObject) current).get(part);
        }
        if (current == null || !current.isJsonPrimitive()) {
            throw new TranslationException(ApiFailureType.UNKNOWN, -1,
                    "Response field '" + dottedPath + "' was missing or not a string");
        }
        return current.getAsString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static long readRetryAfter(HttpResponse<String> response) {
        try {
            return response.headers().firstValueAsLong("Retry-After").orElse(-1);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ApiFailureType classify(int status) {
        if (status == 401 || status == 403) return ApiFailureType.AUTH_FAILED;
        if (status == 429) return ApiFailureType.RATE_LIMITED;
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
