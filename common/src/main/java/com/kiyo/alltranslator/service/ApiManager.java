package com.kiyo.alltranslator.service;

import com.kiyo.alltranslator.api.ApiFailureType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks configured translation APIs and their runtime state, and selects
 * candidates in priority order for sequential failover (not round-robin).
 */
public final class ApiManager {

    private final Map<UUID, TranslationApiConfig> configs = new ConcurrentHashMap<>();
    private final Map<UUID, ApiState> states = new ConcurrentHashMap<>();

    public synchronized void reload(List<TranslationApiConfig> newConfigs) {
        configs.clear();
        for (TranslationApiConfig cfg : newConfigs) {
            configs.put(cfg.id(), cfg);
            states.computeIfAbsent(cfg.id(), ApiState::new);
        }
        states.keySet().retainAll(configs.keySet());
    }

    /** Priority-ascending, enabled, and currently usable (available or cooldown expired). */
    public List<TranslationApiConfig> getOrderedCandidates() {
        List<TranslationApiConfig> result = new ArrayList<>();
        for (TranslationApiConfig cfg : configs.values()) {
            if (!cfg.enabled()) continue;
            ApiState state = states.get(cfg.id());
            if (state == null || state.isUsableNow()) {
                result.add(cfg);
            }
        }
        result.sort(Comparator.comparingInt(TranslationApiConfig::priority));
        return result;
    }

    public ApiState getState(UUID configId) { return states.get(configId); }
    public TranslationApiConfig getConfig(UUID configId) { return configs.get(configId); }

    public void recordSuccess(UUID configId) {
        ApiState state = states.get(configId);
        if (state != null) state.recordSuccess();
    }

    public void recordFailure(UUID configId, ApiFailureType failureType) {
        states.computeIfAbsent(configId, ApiState::new).recordFailure(failureType);
    }

    /** Call this when the user edits/re-enables a DISABLED_PERMANENT API from the config UI (Phase 8). */
    public void resetState(UUID configId) {
        states.put(configId, new ApiState(configId));
    }
}
