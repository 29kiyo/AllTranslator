package com.kiyo.alltranslator.modjarlang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.lang.LocalizedTextResolver;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates: ModJarLangScanner (find candidates) -> per-entry
 * LocalizedTextResolver.resolve(key, sourceText) (existing-translation-file
 * check -> memory/world-save cache -> configured API failover -> original text,
 * the SAME priority chain as every other keyed translation in this mod, per
 * ARCHITECTURE.md §3) -> GeneratedLangPackStore.write(...).
 *
 * Each entry's translation key is the mod's own lang key verbatim (e.g.
 * "structure.ctov.large.village_beach") - NOT null - so a match already present
 * in some OTHER currently-loaded resource pack's data for the target language is
 * picked up for free via the existing ExistingTranslationChecker path, exactly
 * like any other Item/Block/UI key.
 *
 * Runs entirely client-side (this is UI-adjacent JSON content, not chat, per
 * ARCHITECTURE.md §9) - never call this from server-only code. Deliberately does
 * not gate on ConfigModel#translationEnabled itself; the caller (a future
 * opt-in-confirm flow, not yet implemented) decides when this runs at all.
 */
public final class ModJarLangTranslationCoordinator {

    private final LocalizedTextResolver localizedTextResolver;
    private final GeneratedLangPackStore store;

    public ModJarLangTranslationCoordinator(LocalizedTextResolver localizedTextResolver, GeneratedLangPackStore store) {
        this.localizedTextResolver = localizedTextResolver;
        this.store = store;
    }

    /**
     * Translates one candidate mod's en_us.json into targetLangCode, if (and only
     * if) no up-to-date generated file already exists for it. Returns a future
     * that completes with true if a NEW file was written (caller should trigger a
     * resource-pack reload), or false if nothing changed (already up to date, or
     * the source file could not be read/parsed).
     */
    public CompletableFuture<Boolean> translateIfNeeded(ModJarLangCandidate candidate, String targetLangCode) {
        String rawSourceJson;
        try {
            rawSourceJson = Files.readString(candidate.sourceLangFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Mod jar lang: failed to read " + candidate.sourceLangFile()
                    + " for mod " + candidate.modId(), e);
            return CompletableFuture.completedFuture(false);
        }

        String sourceHash = GeneratedLangPackStore.sha256(rawSourceJson);
        if (store.isUpToDate(candidate.modId(), targetLangCode, sourceHash)) {
            return CompletableFuture.completedFuture(false);
        }

        Map<String, String> sourceEntries;
        try {
            JsonObject obj = JsonParser.parseReader(new StringReader(rawSourceJson)).getAsJsonObject();
            sourceEntries = new HashMap<>();
            for (var entry : obj.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    sourceEntries.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (RuntimeException e) {
            AllTranslator.LOGGER.warn("Mod jar lang: failed to parse " + candidate.sourceLangFile()
                    + " for mod " + candidate.modId() + " as a flat string map; skipping", e);
            return CompletableFuture.completedFuture(false);
        }

        Map<String, String> translated = new ConcurrentHashMap<>();
        final Map<String, String> sourceSnapshot = sourceEntries;
        if (sourceSnapshot.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        java.util.function.BiFunction<String, String, CompletableFuture<Void>> translateOne = (key, value) ->
                localizedTextResolver.resolve(key, value)
                        .thenAccept(result -> translated.put(key, result))
                        .exceptionally(ex -> {
                            AllTranslator.LOGGER.warn("Mod jar lang: translation failed for key " + key
                                    + " (mod " + candidate.modId() + "); keeping original text", ex);
                            translated.put(key, value);
                            return null;
                        });

        // Warm-up: an API with no recorded success only accepts ONE in-flight request (ApiState
        // probe reservation, ARCHITECTURE.md 23.1). Firing every key at once would let a single
        // key through and send all the others straight to the original-text fallback, so the
        // first key goes alone and the rest follow once it has finished.
        List<Map.Entry<String, String>> items = new ArrayList<>(sourceSnapshot.entrySet());
        Map.Entry<String, String> first = items.get(0);
        return translateOne.apply(first.getKey(), first.getValue())
                .thenCompose(w -> {
                    List<CompletableFuture<Void>> futures = new ArrayList<>(items.size());
                    for (int i = 1; i < items.size(); i++) {
                        futures.add(translateOne.apply(items.get(i).getKey(), items.get(i).getValue()));
                    }
                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                })
                .thenApply(v -> {
                    long changed = translated.entrySet().stream()
                            .filter(e -> !e.getValue().equals(sourceSnapshot.get(e.getKey())))
                            .count();
                    if (changed * 2 < translated.size()) {
                        AllTranslator.LOGGER.warn("Mod jar lang: fewer than half of the keys were translated for mod "
                                + candidate.modId() + " (API failures?); not writing a pack so it can be retried");
                        return false;
                    }
                    try {
                        store.write(candidate.modId(), targetLangCode, translated, sourceHash);
                        AllTranslator.LOGGER.info("Mod jar lang: generated " + targetLangCode + ".json for mod "
                                + candidate.modId() + " (" + translated.size() + " keys)");
                        return true;
                    } catch (IOException e) {
                        AllTranslator.LOGGER.warn("Mod jar lang: failed to write generated pack for mod "
                                + candidate.modId(), e);
                        return false;
                    }
                });
    }
}
