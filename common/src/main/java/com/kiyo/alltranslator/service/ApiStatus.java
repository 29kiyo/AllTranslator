package com.kiyo.alltranslator.service;

public enum ApiStatus {
    AVAILABLE,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    AUTH_FAILED,
    CONFIG_ERROR,
    TEMP_UNAVAILABLE,
    DISABLED_PERMANENT
}
