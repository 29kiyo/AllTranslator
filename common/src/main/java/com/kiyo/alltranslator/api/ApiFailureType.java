package com.kiyo.alltranslator.api;

public enum ApiFailureType {
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    AUTH_FAILED,
    CONFIG_ERROR,
    TEMP_UNAVAILABLE,
    UNKNOWN,
    /**
     * The call succeeded at the HTTP level but the result is unusable (empty, or a
     * placeholder token was dropped). Raised by TranslationService, not by providers.
     */
    INVALID_RESPONSE
}
