package com.kiyo.alltranslator.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.kiyo.alltranslator.AllTranslator;

/**
 * Single shared backing store for config.json (general + API settings, no keys).
 * Both the L-key screen and the Mod Menu entry point read/write through this instance.
 */
public final class ConfigManager {


    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private volatile ConfigModel model = new ConfigModel();

    public ConfigManager(Path configDir) {
        this.file = configDir.resolve("config.json");
    }

    public synchronized ConfigModel load() {
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                ConfigModel loaded = gson.fromJson(reader, ConfigModel.class);
                if (loaded != null) model = loaded;
            } catch (IOException e) {
                AllTranslator.LOGGER.warn("Failed to read config.json, using defaults", e);
            }
        } else {
            save(); // write defaults on first run
        }
        return model;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(model, writer);
            }
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Failed to write config.json", e);
        }
    }

    public ConfigModel model() { return model; }
}
