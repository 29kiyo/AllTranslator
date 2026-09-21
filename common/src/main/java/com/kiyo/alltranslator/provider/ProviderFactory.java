package com.kiyo.alltranslator.provider;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.api.TranslationProvider;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import java.net.http.HttpClient;
import java.util.EnumMap;
import java.util.Map;
public final class ProviderFactory {
    private ProviderFactory() {}
    public static Map<ProviderType, TranslationProvider> createDefaultProviders(HttpClient httpClient) {
        Map<ProviderType, TranslationProvider> map = new EnumMap<>(ProviderType.class);
        map.put(ProviderType.OPENAI_COMPATIBLE, new OpenAiCompatibleProvider(httpClient, ProviderType.OPENAI_COMPATIBLE, true));
        // Phase 13: local OpenAI-compatible servers (LM Studio, reportedly llama.cpp -
        // see ProviderType's Javadoc for the verification caveat). Same wire protocol,
        // API key optional.
        map.put(ProviderType.OPENAI_COMPATIBLE_LOCAL, new OpenAiCompatibleProvider(httpClient, ProviderType.OPENAI_COMPATIBLE_LOCAL, false));
        // Phase 14: Ollama (OpenAI-compatible mode). Same protocol, API key optional.
        map.put(ProviderType.OLLAMA, new OpenAiCompatibleProvider(httpClient, ProviderType.OLLAMA, false));
        map.put(ProviderType.GENERIC_REST, new GenericRestProvider(httpClient));
        map.put(ProviderType.GOOGLE_WEB_FREE, new GoogleWebFreeProvider(httpClient));
        map.put(ProviderType.DEEPL_COMPATIBLE, new DeepLCompatibleProvider(httpClient));
        map.put(ProviderType.GOOGLE_CLOUD_V2, new GoogleCloudV2Provider(httpClient));
        map.put(ProviderType.ANTHROPIC, new AnthropicProvider(httpClient));
        map.put(ProviderType.GEMINI, new GeminiProvider(httpClient));
        // Phase 14 (SERVER_PROXY): registered client-side ONLY. Never registered
        // when Platform.getEnvironment() == Env.SERVER (dedicated server), which
        // structurally prevents a dedicated server from ever picking itself as a
        // candidate. Note this does NOT cover singleplayer (integrated server runs
        // in the same CLIENT-environment JVM/ApiManager) - that recursion risk is
        // instead prevented by TranslationService's excludeProvider overload, used
        // by AllTranslatorNetworking's server-side request handler. See
        // DEVELOPMENT_STATUS.md Phase 14 task 4 investigation notes.
        if (Platform.getEnvironment() == Env.CLIENT) {
            map.put(ProviderType.SERVER_PROXY, new com.kiyo.alltranslator.provider.ServerProxyProvider());
        }
        return map;
    }
}
