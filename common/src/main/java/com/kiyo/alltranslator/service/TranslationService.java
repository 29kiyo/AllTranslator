package com.kiyo.alltranslator.service;

import com.kiyo.alltranslator.api.ApiFailureType;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.api.TranslationException;
import com.kiyo.alltranslator.api.TranslationProvider;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import com.kiyo.alltranslator.config.CredentialStore;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import com.kiyo.alltranslator.AllTranslator;

/**
 * Orchestrates: memory cache -> persistent cache (non-chat) -> priority-ordered API
 * failover chain -> original text fallback.
 *
 * Existing-translation-file lookup (Phase 3, ExistingTranslationChecker) happens
 * BEFORE this service is ever called - this class only knows about cache/API.
 */
public final class TranslationService {

    private static final int CACHE_VERSION = 1;

    private final ApiManager apiManager;
    private final CacheManager cacheManager;
    private final CredentialStore credentialStore;
    private final Map<ProviderType, TranslationProvider> providers;
    private final PendingRequestMap pendingRequests;
    private final Executor asyncExecutor;

    public TranslationService(ApiManager apiManager,
                               CacheManager cacheManager,
                               CredentialStore credentialStore,
                               Map<ProviderType, TranslationProvider> providers,
                               PendingRequestMap pendingRequests,
                               Executor asyncExecutor) {
        this.apiManager = apiManager;
        this.cacheManager = cacheManager;
        this.credentialStore = credentialStore;
        this.providers = providers;
        this.pendingRequests = pendingRequests;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * @param persistable false for chat (memory-only per ARCHITECTURE.md §8.1); true for everything else.
     */
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, boolean persistable) {
        if (request.sourceText().isBlank()) {
            return CompletableFuture.completedFuture(TranslationResult.original(request.sourceText(), request.targetLang()));
        }

        String cacheKey = CacheKeyUtil.hash(request.sourceText(), request.sourceLang(), request.targetLang(), CACHE_VERSION);

        TranslationResult memoryHit = cacheManager.getMemory(cacheKey);
        if (memoryHit != null) {
            return CompletableFuture.completedFuture(memoryHit);
        }

        if (persistable) {
            String persisted = cacheManager.getPersistent(request.targetLang(), cacheKey);
            if (persisted != null) {
                TranslationResult result = new TranslationResult(persisted, request.sourceText(), request.targetLang(),
                        null, true, persisted.equals(request.sourceText()));
                cacheManager.putMemory(cacheKey, result);
                return CompletableFuture.completedFuture(result);
            }
        }

        CompletableFuture<TranslationResult> newFuture = new CompletableFuture<>();
        CompletableFuture<TranslationResult> existing = pendingRequests.getOrRegister(cacheKey, newFuture);
        if (existing != null) return existing;

        List<TranslationApiConfig> candidates = apiManager.getOrderedCandidates();
        attemptNext(request, candidates.iterator(), cacheKey, persistable, newFuture);
        return newFuture;
    }

    private void attemptNext(TranslationRequest request,
                              Iterator<TranslationApiConfig> candidateIterator,
                              String cacheKey,
                              boolean persistable,
                              CompletableFuture<TranslationResult> resultFuture) {
        if (!candidateIterator.hasNext()) {
            // All configured APIs failed, or none configured/available: fall back to original text.
            // Deliberately NOT cached, so a future request retries once an API recovers.
            resultFuture.complete(TranslationResult.original(request.sourceText(), request.targetLang()));
            return;
        }

        TranslationApiConfig candidate = candidateIterator.next();
        TranslationProvider provider = providers.get(candidate.provider());
        if (provider == null) {
            AllTranslator.LOGGER.warn("No provider implementation registered for " + candidate.provider()
                    + "; skipping " + candidate.displayName());
            attemptNext(request, candidateIterator, cacheKey, persistable, resultFuture);
            return;
        }

        String rawKey = credentialStore.getRawKey(candidate.credentialId());

        CompletableFuture.supplyAsync(() -> null, asyncExecutor)
                .thenCompose(v -> provider.translate(request, candidate, rawKey))
                .whenComplete((result, error) -> {
                    if (error == null) {
                        apiManager.recordSuccess(candidate.id());
                        TranslationResult tagged = new TranslationResult(result.translatedText(), request.sourceText(),
                                request.targetLang(), candidate.id(), false,
                                result.translatedText().equals(request.sourceText()));
                        cacheManager.putMemory(cacheKey, tagged);
                        if (persistable) {
                            cacheManager.putPersistent(request.targetLang(), cacheKey, request.sourceText(), tagged.translatedText());
                        }
                        resultFuture.complete(tagged);
                    } else {
                        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                        ApiFailureType failureType = (cause instanceof TranslationException)
                                ? ((TranslationException) cause).failureType()
                                : provider.classifyError(cause, -1);

                        AllTranslator.LOGGER.warn("Translation via " + candidate.displayName()
                                + " failed (" + failureType + ")", cause);
                        apiManager.recordFailure(candidate.id(), failureType);

                        if (cause instanceof TranslationException) {
                            long retryAfter = ((TranslationException) cause).retryAfterSeconds();
                            if (retryAfter > 0) {
                                ApiState state = apiManager.getState(candidate.id());
                                if (state != null) state.applyRetryAfter(retryAfter);
                            }
                        }

                        attemptNext(request, candidateIterator, cacheKey, persistable, resultFuture);
                    }
                });
    }
}
