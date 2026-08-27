package com.kiyo.alltranslator.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 14 (/alltranslator refresh): lets each TranslationProvider register the
 * raw CompletableFuture returned by HttpClient#sendAsync so an admin can
 * force-cancel genuinely in-flight HTTP calls, e.g. to a local LLM server (LM
 * Studio) that has gotten stuck processing a backlog of requests.
 *
 * IMPORTANT (verified against java.net.http.HttpClient's actual documented
 * behavior, not assumed - CLAUDE.md §3): cancelling a CompletableFuture derived
 * via .thenApply()/.thenCompose() does NOT propagate upstream to cancel the
 * CompletableFuture that HttpClient#sendAsync(...) itself returned. The JDK's
 * CompletableFuture#cancel() only completes THAT specific stage exceptionally
 * with a CancellationException; it does not interrupt or notify whatever is
 * feeding it. Only cancelling the EXACT future object sendAsync() returned
 * actually aborts the underlying HTTP exchange. This registry exists purely so
 * TranslationService can reach that exact object across all seven provider
 * implementations without changing each one's return type.
 *
 * Every TranslationProvider.translate(...) call registers its raw sendAsync()
 * future here immediately after creating it, and unregisters it (via
 * whenComplete, regardless of outcome) once that raw call settles on its own.
 * TranslationService#refresh() calls cancelAll() to force-abort anything still
 * outstanding at that moment.
 */
public final class InFlightCallRegistry {

    private final ConcurrentHashMap<UUID, CompletableFuture<?>> calls = new ConcurrentHashMap<>();

    /** Called by a provider immediately after obtaining the raw sendAsync() future. */
    public UUID register(CompletableFuture<?> rawSendAsyncFuture) {
        UUID id = UUID.randomUUID();
        calls.put(id, rawSendAsyncFuture);
        return id;
    }

    /** Called by a provider (via whenComplete) once the raw call has settled on its own. */
    public void unregister(UUID id) {
        calls.remove(id);
    }

    /**
     * Force-cancels every currently-registered raw HTTP call that isn't already
     * done. Returns how many were actually cancelled, for the command to report
     * back to the user.
     */
    public int cancelAll() {
        int count = 0;
        for (CompletableFuture<?> f : calls.values()) {
            if (!f.isDone()) {
                f.cancel(true);
                count++;
            }
        }
        calls.clear();
        return count;
    }
}
