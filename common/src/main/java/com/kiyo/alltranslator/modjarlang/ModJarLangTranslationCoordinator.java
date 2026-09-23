package com.kiyo.alltranslator.modjarlang;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.lang.LocalizedTextResolver;

import java.io.IOException;
import java.util.ArrayList;
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
 * GAP candidates (ModJarLangCandidate#isGap()): only the untranslated (gap) keys -
 * ModJarLangHashing#resolveGapKeys - are sent for translation and written to the
 * generated pack. Every other key in the mod's own target-language file is left
 * completely alone; Minecraft's own key-by-key lang merge across resource packs
 * (verified real-machine this session, see ModJarLangCandidate's Javadoc) means the
 * mod's own correctly-translated keys keep coming from the mod's own pack, while
 * only the gap keys are overridden by this generated (required=true, TOP) pack.
 *
 * Runs entirely client-side (this is UI-adjacent JSON content, not chat, per
 * ARCHITECTURE.md §9) - never call this from server-only code. Deliberately does
 * not gate on ConfigModel#translationEnabled itself; the caller (the opt-in-confirm
 * flow, ModJarLangConfirmScreen) decides when this runs at all.
 */
public final class ModJarLangTranslationCoordinator {

    private final LocalizedTextResolver localizedTextResolver;
    private final GeneratedLangPackStore store;

    public ModJarLangTranslationCoordinator(LocalizedTextResolver localizedTextResolver, GeneratedLangPackStore store) {
        this.localizedTextResolver = localizedTextResolver;
        this.store = store;
    }

    /**
     * Translates the keys this candidate actually needs (all of en_us.json for MISSING, only
     * the gap keys for GAP - see ModJarLangHashing#resolveGapKeys) into targetLangCode, if (and
     * only if) no up-to-date generated file already exists for it. Returns a future that
     * completes with true if a NEW file was written (caller should trigger a resource-pack
     * reload), or false if nothing changed (already up to date, or the source/target file
     * could not be read/parsed).
     */
    public CompletableFuture<Boolean> translateIfNeeded(ModJarLangCandidate candidate, String targetLangCode) {
        String contentHash;
        Map<String, String> sourceEntries;
        try {
            contentHash = ModJarLangHashing.contentHash(candidate);
            sourceEntries = ModJarLangHashing.resolveGapKeys(candidate); // for MISSING this is just "all keys"
        } catch (IOException e) {
            AllTranslator.LOGGER.warn("Mod jar lang: failed to read lang files for mod " + candidate.modId(), e);
            return CompletableFuture.completedFuture(false);
        } catch (RuntimeException e) {
            AllTranslator.LOGGER.warn("Mod jar lang: failed to parse lang json for mod "
                    + candidate.modId() + " as a flat string map; skipping", e);
            return CompletableFuture.completedFuture(false);
        }

        if (store.isUpToDate(candidate.modId(), targetLangCode, contentHash)) {
            return CompletableFuture.completedFuture(false);
        }
        if (sourceEntries.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        Map<String, String> translated = new ConcurrentHashMap<>();
        final Map<String, String> sourceSnapshot = sourceEntries;
        // GAP candidates (ModJarLangCandidate#isGap()) must bypass the existing-translation-
        // file check: a gap key's whole reason for being flagged is that the mod's OWN
        // target-language file already has a (untranslated, en_us-identical) value for it -
        // the normal resolve(key, value) would immediately find and return that same value
        // unchanged, never reaching cache/API at all (confirmed real-machine, see
        // resolveForceApi's Javadoc). MISSING candidates keep using the normal keyed
        // resolve(), which still benefits from any existing translation available from some
        // OTHER currently-loaded pack for the same key.
        java.util.function.BiFunction<String, String, CompletableFuture<Void>> translateOne = candidate.isGap()
                ? (key, value) -> localizedTextResolver.resolveForceApi(value)
                        .thenAccept(result -> translated.put(key, result))
                        .exceptionally(ex -> {
                            AllTranslator.LOGGER.warn("Mod jar lang: translation failed for key " + key
                                    + " (mod " + candidate.modId() + "); keeping original text", ex);
                            translated.put(key, value);
                            return null;
                        })
                : (key, value) -> localizedTextResolver.resolve(key, value)
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
                        store.write(candidate.modId(), targetLangCode, translated, contentHash);
                        AllTranslator.LOGGER.info("Mod jar lang: generated " + targetLangCode + ".json for mod "
                                + candidate.modId() + " (" + translated.size() + " keys, "
                                + (candidate.isGap() ? "gap-fill" : "full") + ")");
                        return true;
                    } catch (IOException e) {
                        AllTranslator.LOGGER.warn("Mod jar lang: failed to write generated pack for mod "
                                + candidate.modId(), e);
                        return false;
                    }
                });
    }
}
