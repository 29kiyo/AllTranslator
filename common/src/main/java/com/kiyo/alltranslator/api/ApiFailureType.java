package com.kiyo.alltranslator.api;

public enum ApiFailureType {
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    AUTH_FAILED,
    CONFIG_ERROR,
    TEMP_UNAVAILABLE,
    UNKNOWN
}
