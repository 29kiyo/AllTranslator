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
 * Works with any OpenAI-compatible chat-completions endpoint.
 * extraParams: "model" (default "gpt-4o-mini"), "systemPrompt" (optional override),
 * "timeoutSeconds" (optional override, see Phase 13 note below).
 *
 * Phase 13 fix: the HTTP request timeout was hardcoded to 20 seconds. This is fine
 * for hosted cloud APIs but far too short for local LLM servers (e.g. LM Studio)
 * running a 7B+ model with partial GPU offload, where even a short translation can
 * take well over 20s on first generation. Found via real-world testing (LM Studio
 * on a 6GB-VRAM GPU, 26/29 layers offloaded, request timed out at the old 20s
 * limit). Now configurable per-API via extraParams["timeoutSeconds"]; falls back to
 * DEFAULT_TIMEOUT_SECONDS (30) if unset or unparsable, which keeps prior behavior
 * for already-working cloud-API configs roughly the same order of magnitude while
 * giving local-LLM users a documented way to raise it (e.g. 120) without a code
 * change. Not yet exposed in ApiEditScreen's UI (extraParams in general has no
 * generic editor there) - README documents the manual config.json edit for now.
 *
 * Phase 13 (2nd change): now backs BOTH ProviderType.OPENAI_COMPATIBLE (cloud,
 * e.g. official OpenAI - API key required) and ProviderType.OPENAI_COMPATIBLE_LOCAL
 * (local servers like LM Studio, or reportedly llama.cpp's llama-server which is
 * documented upstream as speaking the same protocol but was NOT independently
 * verified here - see ProviderType's Javadoc). Both share the exact same wire
 * protocol (OpenAI chat-completions JSON shape), so a single implementation class
 * is instantiated twice by ProviderFactory - once per ProviderType, each with its
 * own `type` and `requireApiKey` flag - rather than duplicating this class. Most
 * local servers accept requests with no Authorization header at all (or ignore an
 * arbitrary placeholder key), so requireApiKey=false simply skips the "no key"
 * failure and omits the Authorization header when no key is configured, instead of
 * hard-requiring one like the cloud path.
 */
public final class OpenAiCompatibleProvider implements TranslationProvider {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final HttpClient httpClient;
    private final ProviderType type;
    private final boolean requireApiKey;

    public OpenAiCompatibleProvider(HttpClient httpClient) {
        this(httpClient, ProviderType.OPENAI_COMPATIBLE, true);
    }

    public OpenAiCompatibleProvider(HttpClient httpClient, ProviderType type, boolean requireApiKey) {
        this.httpClient = httpClient;
        this.type = type;
        this.requireApiKey = requireApiKey;
    }

    @Override
    public ProviderType type() { return type; }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config, String rawApiKey, InFlightCallRegistry registry) {
        boolean hasKey = rawApiKey != null && !rawApiKey.isBlank();
        if (requireApiKey && !hasKey) {
            CompletableFuture<TranslationResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(new TranslationException(ApiFailureType.CONFIG_ERROR, -1,
                    "No API key configured for " + config.displayName()));
            return failed;
        }

        String model = config.extraParams().getOrDefault("model", "gpt-4o-mini");
        String systemPrompt = config.extraParams().getOrDefault("systemPrompt",
                "You are a translation engine. Translate the user's message into "
                        + LanguageResolver.toReadableName(request.targetLang())
                        + " (locale code '" + request.targetLang() + "'). Reply with ONLY the translated text, "
                        + "no quotes, no explanation, and preserve any placeholder tokens exactly as-is.");
        int timeoutSeconds = readTimeoutSeconds(config);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", request.sourceText()));
        body.add("messages", messages);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json");
        // Phase 13: only send Authorization when a key is actually configured - many
        // local servers (e.g. LM Studio) neither require nor expect this header, and
        // sending "Bearer null"/"Bearer " would be actively wrong, not just harmless.
        if (hasKey) {
            builder.header("Authorization", "Bearer " + rawApiKey);
        }
        HttpRequest httpRequest = builder
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
                        // Phase 13 fix: include a truncated response body snippet in the
                        // exception message so a future CONFIG_ERROR/TEMP_UNAVAILABLE burst
                        // can be diagnosed from the game log alone, without needing to
                        // separately correlate timestamps against the LLM server's own log
                        // (see DEVELOPMENT_STATUS.md for the investigation this fixes).
                        throw new TranslationException(classify(status, response.body()), status,
                                "HTTP " + status + " from " + config.displayName()
                                        + " - body: " + truncate(response.body(), 200),
                                null, retryAfter);
                    }
                    String translated;
                    try {
                        translated = extractContent(response.body());
                    } catch (RuntimeException e) {
                        // Phase 14 (200+error-body classification, ARCHITECTURE.md 26.4): some
                        // servers (e.g. LM Studio hitting an unknown endpoint) return HTTP 200
                        // with an error JSON shape instead of the expected {"choices":[...]}.
                        // Previously this JSON-navigation failure propagated as a bare
                        // RuntimeException, which TranslationService's classifyError(cause, -1)
                        // then mapped to TEMP_UNAVAILABLE (the httpStatus <= 0 fallback) instead
                        // of the more accurate INVALID_RESPONSE ("JSON as read failed, or an
                        // expected field is missing" is an INVALID_RESPONSE condition per
                        // 26.4's judged-conservatively list).
                        throw new TranslationException(ApiFailureType.INVALID_RESPONSE, status,
                                "HTTP 200 but response body was not a valid chat-completions "
                                        + "JSON shape from " + config.displayName()
                                        + " - body: " + truncate(response.body(), 200));
                    }
                    return new TranslationResult(translated, request.sourceText(), request.targetLang(),
                            config.id(), false, translated.equals(request.sourceText()));
                });
    }

    /**
     * Reads extraParams["timeoutSeconds"] if present and a valid positive integer;
     * otherwise returns DEFAULT_TIMEOUT_SECONDS. Never throws on malformed input -
     * a typo'd config value should fall back safely rather than crash translation.
     */
    private static int readTimeoutSeconds(TranslationApiConfig config) {
        String raw = config.extraParams().get("timeoutSeconds");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_TIMEOUT_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
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

    private static String truncate(String s, int maxLen) {
        if (s == null) return "(no body)";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= maxLen ? oneLine : oneLine.substring(0, maxLen) + "...";
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
