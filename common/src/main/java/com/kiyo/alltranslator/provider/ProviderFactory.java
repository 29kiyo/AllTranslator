package com.kiyo.alltranslator.provider;

import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.api.TranslationProvider;

import java.net.http.HttpClient;
import java.util.EnumMap;
import java.util.Map;

public final class ProviderFactory {

    private ProviderFactory() {}

    public static Map<ProviderType, TranslationProvider> createDefaultProviders(HttpClient httpClient) {
        Map<ProviderType, TranslationProvider> map = new EnumMap<>(ProviderType.class);
        map.put(ProviderType.OPENAI_COMPATIBLE, new OpenAiCompatibleProvider(httpClient));
        map.put(ProviderType.GENERIC_REST, new GenericRestProvider(httpClient));
        // DEEPL_COMPATIBLE / CUSTOM left unregistered until their real request/response
        // shapes are confirmed against actual target APIs (CLAUDE.md: never invent APIs).
        return map;
    }
}
