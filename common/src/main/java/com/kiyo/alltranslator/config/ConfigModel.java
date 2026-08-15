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
}
