package com.kiyo.alltranslator.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class CacheKeyUtil {

    private CacheKeyUtil() {}

    /** key = hash(sourceText + sourceLangIfKnown + targetLang + cacheVersion), per ARCHITECTURE.md §8.4. */
    public static String hash(String sourceText, String sourceLang, String targetLang, int cacheVersion) {
        String raw = cacheVersion + "\u0000" +
                (sourceLang == null ? "" : sourceLang) + "\u0000" +
                targetLang + "\u0000" +
                sourceText;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JVM", e);
        }
    }
}
