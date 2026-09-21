package com.kiyo.alltranslator.api;

/**
 * What a translation request is for. ApiManager uses it to keep providers that are only
 * suitable for low-volume traffic (currently GOOGLE_WEB_FREE, an unofficial keyless
 * endpoint that is rate limited per IP) away from bulk work such as item names,
 * tooltips and mod language files.
 */
public enum TranslationPurpose {
    /** Player chat and private chat messages (/msg, /tell, /w, /teammsg). */
    CHAT,
    /** Everything else. This is the default for a request built without a purpose. */
    OTHER
}
