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
import java.util.concurrent.CompletableFuture;

/**
 * Anthropic Messages API (Claude). Verified against current public docs
 * (docs.anthropic.com / platform.claude.com, checked 2026-08-20) rather than
 * assumed from OpenAI-compatible conventions, since the two formats differ in
 * several load-bearing ways (CLAUDE.md §3):
 *  - Auth header is "x-api-key", NOT "Authorization: Bearer".
 *  - A mandatory "anthropic-version" header is required (400 without it).
 *  - Request body has no top-level "system" role inside messages[] (system prompt
 *    is a separate top-level "system" string field).
 *  - "max_tokens" is a required field (no server-side default).
 *  - Response text lives at content[0].text (an array of content blocks), not
 *    choices[0].message.content like OpenAI.
 *
 * extraParams: "model" (default "claude-haiku-4-5" - the smallest/cheapest current
 * model, appropriate for a translation workload), "timeoutSeconds" (default 30,
 * same convention as OpenAiCompatibleProvider's Phase 13 fix), "maxTokens"
 * (default "1024", generous for translation-length outputs).
 */
public final class AnthropicProvider implements TranslationProvider {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient httpClient;

    public AnthropicProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ProviderType type() { return ProviderType.ANTHROPIC; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config, String rawApiKey, InFlightCallRegistry registry) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            CompletableFuture<TranslationResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(new TranslationException(ApiFailureType.CONFIG_ERROR, -1,
                    "No API key configured for " + config.displayName()));
            return failed;
        }

        String model = config.extraParams().getOrDefault("model", "claude-haiku-4-5");
        String maxTokens = config.extraParams().getOrDefault("maxTokens", "1024");
        String systemPrompt = config.extraParams().getOrDefault("systemPrompt",
                "You are a translation engine. Translate the user's message into the language with code '"
                        + request.targetLang() + "'. Reply with ONLY the translated text, no quotes, no explanation, "
                        + "and preserve any placeholder tokens exactly as-is.");
        int timeoutSeconds = readTimeoutSeconds(config);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", Integer.parseInt(maxTokens));
        body.addProperty("system", systemPrompt);
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", request.sourceText());
        messages.add(userMessage);
        body.add("messages", messages);

        String endpoint = (config.endpoint() == null || config.endpoint().isBlank())
                ? "https://api.anthropic.com/v1/messages"
                : config.endpoint();

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("x-api-key", rawApiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
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
        return root.getAsJsonArray("content")
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
     * Anthropic-specific status mapping (verified against current docs):
     * 401 = auth failed; 403 = permission (treated as auth failed, config edit
     * needed); 429 = rate limit; 400 = malformed request/config error;
     * 529 = Anthropic-specific "overloaded" (treated as temp unavailable, distinct
     * from a generic 5xx but same handling); other 5xx = temp unavailable.
     */
    private static ApiFailureType classify(int status, String body) {
        if (status == 401 || status == 403) return ApiFailureType.AUTH_FAILED;
        if (status == 429) return ApiFailureType.RATE_LIMITED;
        if (status == 400 || status == 404) return ApiFailureType.CONFIG_ERROR;
        if (status == 529) return ApiFailureType.TEMP_UNAVAILABLE;
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
