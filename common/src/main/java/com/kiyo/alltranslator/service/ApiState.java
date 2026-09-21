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
    /**
     * Phase 14 (ARCHITECTURE.md §26.4): an "invalid response" (empty result, dropped
     * placeholder token) is a per-request problem, not proof the API is down, so it does
     * not touch consecutiveFailures. Only this many in a row (a success resets the count)
     * put the API on a short cooldown. The value 5 is provisional, not derived from data.
     */
    private static final int INVALID_RESPONSE_COOLDOWN_THRESHOLD = 5;

    private final UUID configId;
    private ApiStatus status = ApiStatus.AVAILABLE;
    private Instant cooldownUntil;
    private Instant lastSuccess;
    private Instant lastFailure;
    private int consecutiveFailures;
    private int consecutiveInvalid;

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
        consecutiveInvalid = 0;
    }

    /**
     * Phase 14 (/alltranslator refresh): clears cooldown/rate-limit/temp-
     * unavailable state back to AVAILABLE WITHOUT touching lastSuccess/
     * lastFailure or the Phase 11 single-flight probing flag - deliberately
     * distinct from recordSuccess(). This does not mean "a translation actually
     * succeeded," it means "an admin wants this API to get another immediate
     * chance." An API that has never yet had a real success still goes through
     * the normal single-flight probe path (tryReserveProbe()) on its very next
     * attempt after this, which is the correct, safe behavior - refresh should
     * not fake confidence the API hasn't actually earned yet.
     */
    public synchronized void forceAvailable() {
        consecutiveInvalid = 0;
        status = ApiStatus.AVAILABLE;
        cooldownUntil = null;
        consecutiveFailures = 0;
    }

    public synchronized void recordFailure(ApiFailureType failureType) {
        if (failureType == ApiFailureType.INVALID_RESPONSE) {
            recordInvalidResponse();
            return;
        }
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

    /**
     * Phase 14 (ARCHITECTURE.md §26.4): see INVALID_RESPONSE_COOLDOWN_THRESHOLD. Does not
     * change the status until the threshold is reached, and does not count toward
     * consecutiveFailures or lastSuccess.
     */
    private void recordInvalidResponse() {
        lastFailure = Instant.now();
        consecutiveInvalid++;
        if (consecutiveInvalid >= INVALID_RESPONSE_COOLDOWN_THRESHOLD) {
            consecutiveInvalid = 0;
            status = ApiStatus.TEMP_UNAVAILABLE;
            cooldownUntil = Instant.now().plusSeconds(BASE_COOLDOWN_SECONDS);
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
