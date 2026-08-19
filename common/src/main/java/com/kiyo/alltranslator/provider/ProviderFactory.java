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
        map.put(ProviderType.GOOGLE_WEB_FREE, new GoogleWebFreeProvider(httpClient));
        map.put(ProviderType.DEEPL_COMPATIBLE, new DeepLCompatibleProvider(httpClient));
        map.put(ProviderType.GOOGLE_CLOUD_V2, new GoogleCloudV2Provider(httpClient));
        // CUSTOM left unregistered until a real target API's shape is confirmed.
        return map;
    }
}
