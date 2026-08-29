package com.kiyo.alltranslator.service;

import com.kiyo.alltranslator.api.TranslationResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Merges concurrent requests for the same cache key into one in-flight future,
 * so identical simultaneous translations only trigger one API call.
 */
public final class PendingRequestMap {

    private final ConcurrentHashMap<String, CompletableFuture<TranslationResult>> pending = new ConcurrentHashMap<>();

    /**
     * If a request for this key is already in flight, returns its future.
     * Otherwise registers newFuture as the in-flight future and returns null
     * (the caller is now responsible for completing it).
     */
    public CompletableFuture<TranslationResult> getOrRegister(String key, CompletableFuture<TranslationResult> newFuture) {
        CompletableFuture<TranslationResult> existing = pending.putIfAbsent(key, newFuture);
        if (existing != null) return existing;
        newFuture.whenComplete((r, t) -> pending.remove(key, newFuture));
        return null;
    }

    /**
     * Phase 14 (/alltranslator refresh): drops all in-flight duplicate-merge
     * bookkeeping entries. This does NOT itself cancel any CompletableFuture
     * that was registered here - TranslationService#refresh() handles actually
     * aborting the underlying HTTP calls separately via InFlightCallRegistry.
     * This only stops FUTURE duplicate requests for the same cache key from
     * merging onto a stale/abandoned entry; whichever caller still holds a
     * reference to an existing entry still receives its eventual completion (or
     * cancellation) normally.
     */
    public void clear() {
        pending.clear();
    }

    /** Phase 14 (scoreboard): number of distinct requests currently awaiting resolution. */
    public int size() {
        return pending.size();
    }
}
