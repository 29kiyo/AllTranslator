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
import com.kiyo.alltranslator.service.TranslationApiConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Works with any OpenAI-compatible chat-completions endpoint.
 * extraParams: "model" (default "gpt-4o-mini"), "systemPrompt" (optional override).
 */
public final class OpenAiCompatibleProvider implements TranslationProvider {

    private final HttpClient httpClient;

    public OpenAiCompatibleProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ProviderType type() { return ProviderType.OPENAI_COMPATIBLE; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config, String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            CompletableFuture<TranslationResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(new TranslationException(ApiFailureType.CONFIG_ERROR, -1,
                    "No API key configured for " + config.displayName()));
            return failed;
        }

        String model = config.extraParams().getOrDefault("model", "gpt-4o-mini");
        String systemPrompt = config.extraParams().getOrDefault("systemPrompt",
                "You are a translation engine. Translate the user's message into the language with code '"
                        + request.targetLang() + "'. Reply with ONLY the translated text, no quotes, no explanation, "
                        + "and preserve any placeholder tokens exactly as-is.");

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", request.sourceText()));
        body.add("messages", messages);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + rawApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
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

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static String extractContent(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        return root.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString()
                .trim();
    }

    private static long readRetryAfter(HttpResponse<String> response) {
        try {
            return response.headers().firstValueAsLong("Retry-After").orElse(-1);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ApiFailureType classify(int status, String body) {
        if (status == 401 || status == 403) return ApiFailureType.AUTH_FAILED;
        if (status == 429) {
            if (body != null && (body.contains("insufficient_quota") || body.contains("\"quota\""))) {
                return ApiFailureType.QUOTA_EXCEEDED;
            }
            return ApiFailureType.RATE_LIMITED;
        }
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
