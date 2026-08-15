package com.kiyo.alltranslator.service;

import com.kiyo.alltranslator.api.ApiFailureType;

import java.time.Instant;
import java.util.UUID;

public final class ApiState {
    private static final long BASE_COOLDOWN_SECONDS = 30L;
    private static final long MAX_SHORT_COOLDOWN_SECONDS = 5 * 60L;
    private static final long QUOTA_COOLDOWN_SECONDS = 60 * 60L;

    private final UUID configId;
    private ApiStatus status = ApiStatus.AVAILABLE;
    private Instant cooldownUntil;
    private Instant lastSuccess;
    private Instant lastFailure;
    private int consecutiveFailures;

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
            case CONFIG_ERROR:
                status = ApiStatus.DISABLED_PERMANENT;
                cooldownUntil = null;
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
