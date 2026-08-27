package com.kiyo.alltranslator.api;

import com.kiyo.alltranslator.service.InFlightCallRegistry;
import com.kiyo.alltranslator.service.TranslationApiConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public interface TranslationProvider {

    ProviderType type();

    /**
     * Phase 14 (/alltranslator refresh): `registry` lets an implementation
     * register the raw HTTP call future it's about to make (see
     * InFlightCallRegistry's Javadoc for why it must be the RAW sendAsync()
     * future, not a .thenApply()-derived stage) so an admin can force-cancel
     * genuinely in-flight requests, e.g. to an overloaded local LLM server.
     * Implementations that fail before making any HTTP call (missing API key,
     * missing endpoint) simply never touch it.
     */
    CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config, String rawApiKey, InFlightCallRegistry registry);

    /**
     * Batch translation. Default implementation falls back to sequential single
     * translations for providers that don't support native batching.
     */
    default CompletableFuture<List<TranslationResult>> translateBatch(List<TranslationRequest> requests,
                                                                        TranslationApiConfig config,
                                                                        String rawApiKey,
                                                                        InFlightCallRegistry registry) {
        List<CompletableFuture<TranslationResult>> futures = requests.stream()
                .map(req -> translate(req, config, rawApiKey, registry))
                .collect(Collectors.toList());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }

    /** Fallback classification for throwables that aren't already a TranslationException. */
    ApiFailureType classifyError(Throwable error, int httpStatus);
}
