package com.kiyo.alltranslator.api;

public class TranslationException extends RuntimeException {
    private final ApiFailureType failureType;
    private final int httpStatus;       // -1 if not an HTTP-level failure
    private final long retryAfterSeconds; // -1 if not provided by the provider

    public TranslationException(ApiFailureType failureType, int httpStatus, String message,
                                 Throwable cause, long retryAfterSeconds) {
        super(message, cause);
        this.failureType = failureType;
        this.httpStatus = httpStatus;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public TranslationException(ApiFailureType failureType, int httpStatus, String message) {
        this(failureType, httpStatus, message, null, -1);
    }

    public ApiFailureType failureType() { return failureType; }
    public int httpStatus() { return httpStatus; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
