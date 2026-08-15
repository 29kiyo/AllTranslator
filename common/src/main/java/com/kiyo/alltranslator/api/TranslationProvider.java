package com.kiyo.alltranslator.api;

import com.kiyo.alltranslator.service.TranslationApiConfig;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public interface TranslationProvider {

    ProviderType type();

    CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config, String rawApiKey);

    /**
     * Batch translation. Default implementation falls back to sequential single
     * translations for providers that don't support native batching.
     */
    default CompletableFuture<List<TranslationResult>> translateBatch(List<TranslationRequest> requests,
                                                                        TranslationApiConfig config,
                                                                        String rawApiKey) {
        List<CompletableFuture<TranslationResult>> futures = requests.stream()
                .map(req -> translate(req, config, rawApiKey))
                .collect(Collectors.toList());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }

    /** Fallback classification for throwables that aren't already a TranslationException. */
    ApiFailureType classifyError(Throwable error, int httpStatus);
}
