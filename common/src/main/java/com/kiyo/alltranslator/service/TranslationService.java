package com.kiyo.alltranslator.service;

import com.kiyo.alltranslator.api.ApiFailureType;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.api.TranslationException;
import com.kiyo.alltranslator.api.TranslationProvider;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.config.CredentialStore;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final java.util.concurrent.atomic.AtomicLong cancelEpoch = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Phase 14 (ARCHITECTURE.md §26.4): how candidates are ordered. Read on every
     * translate() call, so a remote config save by an admin takes effect immediately.
     */
    private volatile java.util.function.Supplier<com.kiyo.alltranslator.api.ApiSelectionMode> selectionModeSupplier =
            () -> com.kiyo.alltranslator.api.ApiSelectionMode.PRIORITY_FAILOVER;
    private final AtomicInteger distributeRotation = new AtomicInteger();

    public void setSelectionModeSupplier(java.util.function.Supplier<com.kiyo.alltranslator.api.ApiSelectionMode> supplier) {
        this.selectionModeSupplier = supplier != null
                ? supplier
                : () -> com.kiyo.alltranslator.api.ApiSelectionMode.PRIORITY_FAILOVER;
    }

    /**
     * Phase 14 (scoreboard): tracks how many attempts are CURRENTLY assigned to
     * each API config (from just before an attempt starts through its
     * whenComplete, regardless of success/failure) - purely observational,
     * touches no translation logic. Only ever grows entries lazily via
     * computeIfAbsent(); a config that's never been attempted simply reads as 0
     * via inFlightCountFor() below rather than needing eager initialization.
     */
    private final java.util.concurrent.ConcurrentHashMap<UUID, AtomicInteger> inFlightPerApi = new java.util.concurrent.ConcurrentHashMap<>();

    /** Phase 14 (scoreboard): current in-flight attempt count for one API config. */
    public int inFlightCountFor(UUID configId) {
        AtomicInteger counter = inFlightPerApi.get(configId);
        return counter == null ? 0 : counter.get();
    }

    /** Phase 14 (scoreboard): number of distinct requests currently awaiting resolution across all APIs. */
    public int pendingQueueSize() {
        return pendingRequests.size();
    }

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
        return translate(request, persistable, null);
    }

    /**
     * Phase 14 (SERVER_PROXY): like translate(request, persistable), but skips any
     * configured candidate whose provider type equals excludeProvider (pass null
     * for no exclusion - that's what the two-arg overload above does). Used by
     * AllTranslatorNetworking's server-side SERVER_PROXY request handler so a
     * singleplayer integrated server (where Platform.getEnvironment() is CLIENT -
     * see ProviderFactory - and ApiManager/config.apis are shared with the client
     * half of the same process) never routes a proxy request back through
     * ProviderType.SERVER_PROXY itself.
     */
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, boolean persistable,
                                                            com.kiyo.alltranslator.api.ProviderType excludeProvider) {
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

        List<TranslationApiConfig> candidates = apiManager.getOrderedCandidates(request.purpose());
        if (excludeProvider != null) {
            candidates = candidates.stream()
                    .filter(c -> c.provider() != excludeProvider)
                    .collect(java.util.stream.Collectors.toList());
        }
        candidates = orderForMode(candidates);
        attemptNext(request, candidates.iterator(), cacheKey, persistable, newFuture);
        return newFuture;
    }

    /**
     * Phase 14 (ARCHITECTURE.md §26.4). PRIORITY_FAILOVER (default) keeps ApiManager's
     * priority order untouched. DISTRIBUTE puts the API with the fewest in-flight requests
     * first; ties are broken by rotating the starting position per request, so idle APIs
     * are used in turn. Each API is still tried at most once per request (attemptNext).
     */
    private List<TranslationApiConfig> orderForMode(List<TranslationApiConfig> candidates) {
        if (candidates.size() < 2) {
            return candidates;
        }
        com.kiyo.alltranslator.api.ApiSelectionMode mode = null;
        try {
            mode = selectionModeSupplier.get();
        } catch (RuntimeException e) {
            // A broken supplier must never break translation: fall back to priority order.
        }
        if (mode != com.kiyo.alltranslator.api.ApiSelectionMode.DISTRIBUTE) {
            return candidates;
        }
        int n = candidates.size();
        int start = Math.floorMod(distributeRotation.getAndIncrement(), n);
        java.util.Map<UUID, Integer> load = new java.util.HashMap<>();
        java.util.List<TranslationApiConfig> ordered = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            TranslationApiConfig c = candidates.get((start + i) % n);
            ordered.add(c);
            load.put(c.id(), inFlightCountFor(c.id()));
        }
        // List.sort is stable, so equal loads keep the rotated order.
        ordered.sort(java.util.Comparator.comparingInt((TranslationApiConfig c) -> load.get(c.id())));
        return ordered;
    }

    /**
     * Phase 14 (ARCHITECTURE.md §26.4): a call that succeeded at the HTTP level but returned
     * nothing usable counts as a failure of that API for this request, so the request moves
     * on to the next candidate and nothing bad is cached. Deliberately conservative: a result
     * identical to the source is NOT invalid (proper nouns and short strings legitimately
     * stay the same).
     */
    private static TranslationResult requireValidResult(TranslationRequest request,
                                                         TranslationApiConfig candidate,
                                                         TranslationResult result) {
        String translated = result == null ? null : result.translatedText();
        if (translated == null || translated.isBlank()) {
            throw new TranslationException(ApiFailureType.INVALID_RESPONSE, -1,
                    "empty translation from " + candidate.displayName());
        }
        if (!com.kiyo.alltranslator.text.PlaceholderProtector.allTokensPresent(request.sourceText(), translated)) {
            throw new TranslationException(ApiFailureType.INVALID_RESPONSE, -1,
                    "placeholder token dropped by " + candidate.displayName());
        }
        return result;
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
        AtomicInteger inFlightCounter = inFlightPerApi.computeIfAbsent(candidate.id(), k -> new AtomicInteger());
        int afterIncrement = inFlightCounter.incrementAndGet();
        if (AllTranslatorCore.configManager().model().debugLoggingEnabled) {
            AllTranslator.LOGGER.info("[AT-DEBUG] inFlight++ for " + candidate.displayName()
                    + " (id=" + candidate.id() + ") -> " + afterIncrement);
        }

        // Phase 13 fix: acquire a real in-flight-request permit on asyncExecutor
        // (blocking is fine here - this runs on the dedicated 2-thread translation
        // executor, never the main/render thread) BEFORE kicking off the actual
        // HTTP call, and release it in whenComplete regardless of outcome. This is
        // what actually bounds concurrent HTTP requests - see class Javadoc.
        final long epochAtSchedule = cancelEpoch.get();
        CompletableFuture.runAsync(() -> {
            try {
                inFlightHttpRequests.acquireUninterruptibly();
            } catch (RuntimeException e) {
                // Should not happen (acquireUninterruptibly doesn't throw checked
                // exceptions), but never let permit bookkeeping crash a translation.
            }
        }, asyncExecutor)
                .thenCompose(v -> {
                    if (cancelEpoch.get() != epochAtSchedule) {
                        return CompletableFuture.<TranslationResult>failedFuture(
                                new java.util.concurrent.CancellationException("cancelled while queued"));
                    }
                    return provider.translate(request, candidate, rawKey, inFlightCallRegistry);
                })
                .thenApply(r -> requireValidResult(request, candidate, r))
                .whenComplete((result, error) -> {
                    inFlightHttpRequests.release();
                    int afterDecrement = inFlightCounter.decrementAndGet();
                    if (AllTranslatorCore.configManager().model().debugLoggingEnabled) {
                        AllTranslator.LOGGER.info("[AT-DEBUG] inFlight-- for " + candidate.displayName()
                                + " (id=" + candidate.id() + ") -> " + afterDecrement);
                    }
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
                                + " failed (" + failureType + ")"
                                + (failureType == ApiFailureType.INVALID_RESPONSE && cause != null ? ": " + cause.getMessage() : ""),
                                failureType == ApiFailureType.INVALID_RESPONSE ? null : cause);
                        apiManager.recordFailure(candidate.id(), failureType);

                        // Invalid responses are per-request and can be frequent: log them, but no error toast.
                        BiConsumer<String, String> listener =
                                failureType == ApiFailureType.INVALID_RESPONSE ? null : failureListener;
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
     * Real-world follow-up fix (Toast/Advancement translation task session): synchronous,
     * non-blocking cache-only lookup - checks ONLY the already-populated memory/persistent
     * cache tiers (same cacheKey scheme as translate() above) and returns immediately,
     * NEVER calling any provider/API and NEVER waiting on an in-flight request. Returns
     * null if no cached result exists yet (caller decides what to do - e.g. show the
     * original text this one time while a background translate() call warms the cache for
     * next time).
     *
     * Added for TitleTranslationCoordinator (a /title command's title/subtitle/actionbar
     * Component must be fully resolved synchronously before ServerGamePacketListenerImpl
     * sends it - Brigadier command execution cannot await a CompletableFuture, and CLAUDE.md
     * §7 forbids blocking the main/server thread to wait for one). No other caller in this
     * project currently needs a synchronous peek; every other translation call site
     * (chat/tellraw/advancement announcements/item/entity/tooltip) can wait for the async
     * result and patch/deliver it later, which remains the strongly preferred pattern -
     * this method exists only for the one case where that isn't possible at all.
     */
    public TranslationResult peekCache(TranslationRequest request, boolean persistable) {
        String cacheKey = CacheKeyUtil.hash(request.sourceText(), request.sourceLang(), request.targetLang(), CACHE_VERSION);
        TranslationResult memoryHit = cacheManager.getMemory(cacheKey);
        if (memoryHit != null) {
            return memoryHit;
        }
        if (persistable) {
            String persisted = cacheManager.getPersistent(request.targetLang(), cacheKey);
            if (persisted != null) {
                TranslationResult result = new TranslationResult(persisted, request.sourceText(), request.targetLang(),
                        null, true, persisted.equals(request.sourceText()));
                cacheManager.putMemory(cacheKey, result);
                return result;
            }
        }
        return null;
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

    /**
     * Force-cancels in-flight HTTP calls and drops duplicate-merge bookkeeping,
     * WITHOUT resetting ApiState cooldown/rate-limit status (unlike refresh()).
     * For UI cancel actions (e.g. ModJarLangConfirmScreen) where the goal is only
     * "stop sending requests now", not "pretend every API is healthy again".
     */
    public int cancelInFlightOnly() {
        cancelEpoch.incrementAndGet();
        int cancelledCalls = inFlightCallRegistry.cancelAll();
        pendingRequests.clear();
        return cancelledCalls;
    }

    /** Summary of a refresh() call, for the command to report back to the user. */
    public record RefreshResult(int cancelledCalls, int resetApis) {}
}
