package com.kiyo.alltranslator.api;

/**
 * Phase 14 (ARCHITECTURE.md §26.4): how TranslationService orders the candidate APIs for
 * a request. Stored in config.json by constant NAME - do not rename. A missing or
 * unknown value is treated as PRIORITY_FAILOVER.
 */
public enum ApiSelectionMode {
    /** Default: try APIs in priority order; the next one is used only when the previous one fails. */
    PRIORITY_FAILOVER,
    /** Spread requests over the usable APIs (fewest in-flight first); a failed request moves on to another API. */
    DISTRIBUTE
}
