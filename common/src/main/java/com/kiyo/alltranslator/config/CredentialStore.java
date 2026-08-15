package com.kiyo.alltranslator.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.kiyo.alltranslator.AllTranslator;

/**
 * Holds raw API keys, kept strictly separate from config.json.
 * Never transmit the contents of this store across the network (client -> server).
 */
public final class CredentialStore {

    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<UUID, String> keys = new LinkedHashMap<>();

    public CredentialStore(Path configDir) {
        this.file = configDir.resolve("credentials.json");
    }

    public synchronized void load() {
        keys.clear();
        if (!Files.exists(file)) return;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, String> raw = gson.fromJson(reader, MAP_TYPE);
            if (raw != null) raw.forEach((id, key) -> keys.put(UUID.fromString(id), key));
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Failed to read credentials.json", e);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Map<String, String> raw = new LinkedHashMap<>();
            keys.forEach((id, key) -> raw.put(id.toString(), key));
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(raw, MAP_TYPE, writer);
            }
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Failed to write credentials.json", e);
        }
    }

    public synchronized String getRawKey(UUID credentialId) {
        return credentialId == null ? null : keys.get(credentialId);
    }

    public synchronized UUID putKey(UUID credentialId, String rawKey) {
        UUID id = credentialId != null ? credentialId : UUID.randomUUID();
        keys.put(id, rawKey);
        save();
        return id;
    }

    public synchronized void removeKey(UUID credentialId) {
        if (credentialId != null && keys.remove(credentialId) != null) save();
    }

    @Override
    public String toString() {
        // Never expose raw keys, even accidentally via logging.
        return "CredentialStore{" + keys.size() + " key(s), file=" + file + "}";
    }
}
