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
}
