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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import com.kiyo.alltranslator.AllTranslator;

/**
 * Orchestrates: memory cache -> persistent cache (non-chat) -> priority-ordered API
 * failover chain -> original text fallback.
 *
 * Existing-translation-file lookup (Phase 3, ExistingTranslationChecker) happens
 * BEFORE this service is ever called - this class only knows about cache/API.
 *
 * Phase 13 fix: real-world testing against a local LM Studio server (4 internal
 * inference slots) showed dozens of simultaneous HTTP requests firing at once
 * (e.g. opening a large inventory triggers many tooltip translations together),
 * overwhelming the server and causing a cascade of request timeouts. The cause:
 * routing through asyncExecutor (a fixed 2-thread pool, see AllTranslatorCore) only
 * serializes the moment translate() is *invoked* - provider.translate() itself
 * calls HttpClient#sendAsync, which returns immediately without blocking, so the
 * executor thread is freed again right away and the next queued translation starts
 * its own HTTP call almost immediately after. The 2-thread executor was therefore
 * not actually bounding how many HTTP requests were in flight at once. A Semaphore
 * (MAX_CONCURRENT_HTTP_REQUESTS permits) now gates actual request dispatch: a
 * permit is acquired right before provider.translate() is called and released in
 * whenComplete(), regardless of success/failure, so this bounds true concurrent
 * in-flight HTTP calls across all APIs combined. Phase 14: now configurable via
 * ConfigModel#maxConcurrentHttpRequests / setMaxConcurrentHttpRequests(int)
 * below (was hardcoded here originally - see git history for the Phase 13 fix
 * this class Javadoc otherwise still describes accurately).
 */
public final class TranslationService {

    private static final int CACHE_VERSION = 1;

    private final ApiManager apiManager;
    private final CacheManager cacheManager;
    private final CredentialStore credentialStore;
    private final Map<ProviderType, TranslationProvider> providers;
    private final PendingRequestMap pendingRequests;
    private final Executor asyncExecutor;
    private final ResizableSemaphore inFlightHttpRequests;

    /**
     * Phase 14 (/alltranslator refresh): registry of raw in-flight HTTP calls
     * across all providers, so an admin can force-cancel requests already sent
     * to an overloaded/stuck server (e.g. a local LLM). See
     * InFlightCallRegistry's own Javadoc for why cancelling a .thenApply()-
     * derived stage alone would NOT have worked.
     */
    private final InFlightCallRegistry inFlightCallRegistry = new InFlightCallRegistry();

    /**
     * Phase 13: optional failure listener (API display name, failure type as a
     * short string) for surfacing translation failures to the user, e.g. as a
     * toast. Null by default (server/dedicated-server TranslationService instances
     * never set this). Wired to an actual client-side toast in
     * AllTranslatorClientCore - this class itself has no client-only dependency,
     * matching the existing ChatTranslationCoordinator injection pattern.
     */
    private volatile BiConsumer<String, String> failureListener;

    public void setFailureListener(BiConsumer<String, String> failureListener) {
        this.failureListener = failureListener;
    }

    public TranslationService(ApiManager apiManager,
                               CacheManager cacheManager,
                               CredentialStore credentialStore,
                               Map<ProviderType, TranslationProvider> providers,
                               PendingRequestMap pendingRequests,
                               Executor asyncExecutor,
                               int initialMaxConcurrentHttpRequests) {
        this.apiManager = apiManager;
        this.cacheManager = cacheManager;
        this.credentialStore = credentialStore;
        this.providers = providers;
        this.pendingRequests = pendingRequests;
        this.asyncExecutor = asyncExecutor;
        this.inFlightHttpRequests = new ResizableSemaphore(Math.max(1, initialMaxConcurrentHttpRequests));
    }

    /**
     * Phase 14: lets the config screen change the simultaneous in-flight HTTP
     * request limit at runtime without restarting the mod. See
     * ResizableSemaphore's Javadoc for how this is done safely on top of
     * java.util.concurrent.Semaphore, which has no built-in "set total permits"
     * operation.
     */
    public void setMaxConcurrentHttpRequests(int max) {
        inFlightHttpRequests.setTotalPermits(max);
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

        ApiState state = apiManager.getState(candidate.id());
        boolean tookReservation = state != null && state.lastSuccess() == null;
        if (state != null && !state.tryReserveProbe()) {
            // This API has never yet succeeded, and another concurrent request (for a
            // different cache key) is already probing it right now. Rather than also
            // call it (Phase 11 fix - see ApiState#tryReserveProbe javadoc), move on
            // to the next candidate immediately. That other in-flight probe will
            // update this API's real status for everyone once it completes.
            attemptNext(request, candidateIterator, cacheKey, persistable, resultFuture);
            return;
        }

        String rawKey = credentialStore.getRawKey(candidate.credentialId());

        // Phase 13 fix: acquire a real in-flight-request permit on asyncExecutor
        // (blocking is fine here - this runs on the dedicated 2-thread translation
        // executor, never the main/render thread) BEFORE kicking off the actual
        // HTTP call, and release it in whenComplete regardless of outcome. This is
        // what actually bounds concurrent HTTP requests - see class Javadoc.
        CompletableFuture.runAsync(() -> {
            try {
                inFlightHttpRequests.acquireUninterruptibly();
            } catch (RuntimeException e) {
                // Should not happen (acquireUninterruptibly doesn't throw checked
                // exceptions), but never let permit bookkeeping crash a translation.
            }
        }, asyncExecutor)
                .thenCompose(v -> provider.translate(request, candidate, rawKey, inFlightCallRegistry))
                .whenComplete((result, error) -> {
                    inFlightHttpRequests.release();
                    if (tookReservation && state != null) {
                        state.releaseProbe();
                    }
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

                        if (cause instanceof CancellationException) {
                            // Phase 14 (/alltranslator refresh): this specific HTTP call was
                            // force-cancelled by an admin, not a genuine provider failure. Don't
                            // record this against the API's health/cooldown state, and don't
                            // cascade into trying the next candidate for THIS request either -
                            // refresh is an explicit "stop now" signal, so honor it by falling
                            // back to the original text immediately, same as candidate
                            // exhaustion. A later, fresh translate() call (new request) will
                            // retry normally once whatever caused the pile-up has cleared.
                            resultFuture.complete(TranslationResult.original(request.sourceText(), request.targetLang()));
                            return;
                        }

                        ApiFailureType failureType = (cause instanceof TranslationException)
                                ? ((TranslationException) cause).failureType()
                                : provider.classifyError(cause, -1);

                        AllTranslator.LOGGER.warn("Translation via " + candidate.displayName()
                                + " failed (" + failureType + ")", cause);
                        apiManager.recordFailure(candidate.id(), failureType);

                        BiConsumer<String, String> listener = failureListener;
                        if (listener != null) {
                            try {
                                listener.accept(candidate.displayName(), failureType.toString());
                            } catch (RuntimeException e) {
                                AllTranslator.LOGGER.warn("Translation failure listener threw", e);
                            }
                        }

                        if (cause instanceof TranslationException) {
                            long retryAfter = ((TranslationException) cause).retryAfterSeconds();
                            if (retryAfter > 0 && state != null) {
                                state.applyRetryAfter(retryAfter);
                            }
                        }

                        attemptNext(request, candidateIterator, cacheKey, persistable, resultFuture);
                    }
                });
    }

    /**
     * Phase 14: backing implementation for /alltranslator refresh. See
     * ApiManager#refreshAll(), PendingRequestMap#clear(), and
     * InFlightCallRegistry#cancelAll() for what each piece actually does.
     */
    public RefreshResult refresh() {
        int cancelledCalls = inFlightCallRegistry.cancelAll();
        pendingRequests.clear();
        int resetApis = apiManager.refreshAll();
        return new RefreshResult(cancelledCalls, resetApis);
    }

    /** Summary of a refresh() call, for the command to report back to the user. */
    public record RefreshResult(int cancelledCalls, int resetApis) {}
}
