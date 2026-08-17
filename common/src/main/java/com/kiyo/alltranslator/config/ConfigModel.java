package com.kiyo.alltranslator.config;

import com.kiyo.alltranslator.service.TranslationApiConfig;

import java.util.ArrayList;
import java.util.List;

/** Serialized as config/alltranslator/config.json. Contains no API keys - see CredentialStore. */
public final class ConfigModel {
    public boolean translationEnabled = true;
    public String forcedTargetLanguage = null; // null = use client/player language

    public int memoryCacheCapacity = 2000;
    public int dynamicTextCacheTtlDays = 30; // Phase 3+, dynamic/keyless content

    public List<TranslationApiConfig> apis = new ArrayList<>();

    /**
     * Phase 6, ARCHITECTURE.md §9: opt-in "server-side translation mode" for chat. Off by
     * default - the default chat translation path remains Phase 5's client-side
     * ChatTranslationCoordinator, which needs no server-side config at all. When an admin turns
     * this on (by hand-editing this file until Phase 8's config UI exists), the server
     * additionally translates chat per-recipient using the SERVER's own
     * TranslationService/credentials (never a client's - see CredentialStore and
     * ARCHITECTURE.md §12), for players who haven't individually opted out via
     * PlayerTranslationSettingsManager.
     */
    public boolean serverSideChatTranslationEnabled = false;
}
