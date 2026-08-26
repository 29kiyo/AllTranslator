package com.kiyo.alltranslator.service;

import com.kiyo.alltranslator.api.ApiFailureType;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApiState {
    private static final long BASE_COOLDOWN_SECONDS = 30L;
    private static final long MAX_SHORT_COOLDOWN_SECONDS = 5 * 60L;
    private static final long QUOTA_COOLDOWN_SECONDS = 60 * 60L;
    /**
     * Phase 13 fix (real-world bug): a single HTTP 400/404 is ambiguous - it can mean
     * genuinely broken configuration (wrong endpoint/model name), but real-world
     * testing showed a local LLM server (LM Studio) under heavy concurrent request
     * load can also return a malformed/rejected 400 for an otherwise-valid request
     * during a burst (observed exactly during a Traveler's Backpack inventory-open
     * translation storm - see DEVELOPMENT_STATUS.md). Give CONFIG_ERROR this many
     * consecutive chances (with a short backoff in between, like TEMP_UNAVAILABLE)
     * before concluding the configuration itself is actually broken and permanently
     * disabling it. Does NOT apply to AUTH_FAILED, which stays immediate - an
     * invalid/rejected API key is unambiguous.
     */
    private static final int CONFIG_ERROR_DISABLE_THRESHOLD = 3;

    private final UUID configId;
    private ApiStatus status = ApiStatus.AVAILABLE;
    private Instant cooldownUntil;
    private Instant lastSuccess;
    private Instant lastFailure;
    private int consecutiveFailures;

    /**
     * Phase 11 fix (real-world bug: a burst of ~8 concurrent item-name translation
     * requests at world join all independently called getOrderedCandidates() before
     * any of them completed and updated status, so all 8 piled onto the SAME
     * not-yet-known-bad API before its first failure was even recorded - 8x more
     * requests than necessary to a config that turns out to be broken, violating
     * CLAUDE.md §24 "no repeated requests to an API known to be unavailable" in
     * spirit even though none of the individual calls was itself a "repeat").
     *
     * Once an API has a confirmed lastSuccess, this reservation is a no-op (full
     * concurrency is fine and desirable for a known-healthy API - translating many
     * different texts in parallel is the normal, wanted case). Only APIs that have
     * NEVER yet succeeded (freshly configured, or so far only ever failed) require
     * single-flight probing: the first concurrent caller "wins" the probe and
     * actually calls the provider; every other concurrent caller for a different
     * cache key immediately moves on to the next candidate instead of piling on.
     */
    private final AtomicBoolean probing = new AtomicBoolean(false);

    public ApiState(UUID configId) {
        this.configId = configId;
    }

    public UUID configId() { return configId; }
    public ApiStatus status() { return status; }
    public Instant cooldownUntil() { return cooldownUntil; }
    public Instant lastSuccess() { return lastSuccess; }
    public Instant lastFailure() { return lastFailure; }
    public int consecutiveFailures() { return consecutiveFailures; }

    /** No periodic health checks: the next translation request past cooldown IS the recovery test. */
    public boolean isUsableNow() {
        if (status == ApiStatus.DISABLED_PERMANENT) return false;
        if (status == ApiStatus.AVAILABLE) return true;
        return cooldownUntil != null && !Instant.now().isBefore(cooldownUntil);
    }

    /**
     * Call before actually invoking this API's provider. Returns true if the caller
     * may proceed (either the API is already confirmed-working and needs no
     * reservation, or this caller won the single-flight probe for an unconfirmed
     * API). Returns false if another concurrent request is already probing this
     * unconfirmed API - the caller should move on to the next candidate rather than
     * wait or also call it. MUST be paired with releaseProbe() (in a finally/
     * whenComplete) whenever this returns true AND a reservation was actually taken
     * (i.e. lastSuccess was null at the time) - see releaseProbe()'s own no-op-safe
     * behavior for the confirmed-API case.
     */
    public boolean tryReserveProbe() {
        if (lastSuccess != null) return true; // confirmed-working API: no single-flight needed
        return probing.compareAndSet(false, true);
    }

    /** Safe to call unconditionally after an attempt completes, even if tryReserveProbe() took the "already confirmed" branch (then this is just a harmless no-op reset). */
    public void releaseProbe() {
        probing.set(false);
    }

    public synchronized void recordSuccess() {
        status = ApiStatus.AVAILABLE;
        cooldownUntil = null;
        consecutiveFailures = 0;
        lastSuccess = Instant.now();
    }

    public synchronized void recordFailure(ApiFailureType failureType) {
        lastFailure = Instant.now();
        consecutiveFailures++;

        switch (failureType) {
            case AUTH_FAILED:
                status = ApiStatus.DISABLED_PERMANENT;
                cooldownUntil = null;
                return;
            case CONFIG_ERROR:
                // Phase 13 fix: see CONFIG_ERROR_DISABLE_THRESHOLD's Javadoc above.
                if (consecutiveFailures >= CONFIG_ERROR_DISABLE_THRESHOLD) {
                    status = ApiStatus.DISABLED_PERMANENT;
                    cooldownUntil = null;
                } else {
                    status = ApiStatus.TEMP_UNAVAILABLE;
                    cooldownUntil = Instant.now().plusSeconds(backoffSeconds());
                }
                return;
            case QUOTA_EXCEEDED:
                status = ApiStatus.QUOTA_EXCEEDED;
                cooldownUntil = Instant.now().plusSeconds(QUOTA_COOLDOWN_SECONDS);
                return;
            case RATE_LIMITED:
                status = ApiStatus.RATE_LIMITED;
                cooldownUntil = Instant.now().plusSeconds(backoffSeconds());
                return;
            case TEMP_UNAVAILABLE:
            case UNKNOWN:
            default:
                status = ApiStatus.TEMP_UNAVAILABLE;
                cooldownUntil = Instant.now().plusSeconds(backoffSeconds());
        }
    }

    /** Provider-supplied Retry-After hint takes priority over the computed exponential backoff. */
    public synchronized void applyRetryAfter(long seconds) {
        if (seconds > 0) {
            cooldownUntil = Instant.now().plusSeconds(Math.min(seconds, MAX_SHORT_COOLDOWN_SECONDS * 12));
        }
    }

    private long backoffSeconds() {
        long seconds = BASE_COOLDOWN_SECONDS * (1L << Math.min(consecutiveFailures - 1, 5));
        return Math.min(seconds, MAX_SHORT_COOLDOWN_SECONDS);
    }
}
