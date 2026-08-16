package com.kiyo.alltranslator.lang;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.kiyo.alltranslator.AllTranslator;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-only LanguageDataSource backed by real Minecraft resources.
 *
 * MC 26.2 note: ResourceLocation was renamed to Identifier in 26.2 (confirmed via the
 * NeoForged 26.1->26.2 migration primer, which uses Identifier.fromNamespaceAndPath(...)
 * throughout). ResourceManager#listResources / Resource#open were not called out as changed
 * in that primer, so they're assumed stable - if compileJava still fails here, paste the error.
 *
 * Why not just Language.getInstance()? Because it only holds the client's currently ACTIVE
 * language, pre-merged with the en_us fallback baked in at load time - it cannot tell us
 * whether a key is genuinely present in an arbitrary target language's own file, which is
 * exactly what ARCHITECTURE.md §3 requires us to distinguish.
 */
public final class MinecraftLanguageDataSource implements LanguageDataSource {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    // One merged key->value map per language code, rebuilt on invalidate() (resource reload).
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /** Guards every Minecraft-class touch below; short-circuits before Minecraft.getInstance() on servers. */
    public boolean isAvailable() {
        return Platform.getEnvironment() == Env.CLIENT && Minecraft.getInstance() != null;
    }

    /** Call from a client resource-reload listener (wired in Phase 4) to drop stale merged data. */
    public void invalidate() {
        cache.clear();
    }

    @Override
    public String lookupTargetLanguageValue(String key, String targetLang) {
        Map<String, String> data = mergedLanguageData(targetLang);
        return data == null ? null : data.get(key);
    }

    @Override
    public String lookupDefaultLanguageValue(String key) {
        Map<String, String> data = mergedLanguageData(LanguageResolver.DEFAULT_LANGUAGE);
        return data == null ? null : data.get(key);
    }

    private Map<String, String> mergedLanguageData(String targetLang) {
        if (!isAvailable()) return null;
        String normalized = LanguageResolver.normalize(targetLang);
        return cache.computeIfAbsent(normalized, this::loadMergedLanguageData);
    }

    private Map<String, String> loadMergedLanguageData(String langCode) {
        Map<String, String> merged = new ConcurrentHashMap<>();
        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        String targetFileName = "/" + langCode + ".json";
        try {
            Map<Identifier, Resource> resources = resourceManager.listResources(
                    "lang", location -> location.getPath().endsWith(targetFileName));
            for (Resource resource : resources.values()) {
                try (Reader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                    Map<String, String> parsed = GSON.fromJson(reader, MAP_TYPE);
                    if (parsed != null) merged.putAll(parsed);
                } catch (Exception e) {
                    AllTranslator.LOGGER.warn("Failed to parse lang resource for " + langCode, e);
                }
            }
        } catch (Exception e) {
            AllTranslator.LOGGER.warn("Failed to list lang resources for " + langCode, e);
        }
        return merged;
    }
}
