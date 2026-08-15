package com.kiyo.alltranslator.service;

import com.kiyo.alltranslator.api.ProviderType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Serialized into config.json. Never holds a raw API key - only a reference (credentialId). */
public final class TranslationApiConfig {
    private UUID id;
    private String displayName;
    private ProviderType provider;
    private String endpoint;
    private UUID credentialId;
    private int priority;
    private boolean enabled;
    private Map<String, String> extraParams = new LinkedHashMap<>();

    public TranslationApiConfig() {
        // for Gson
    }

    public TranslationApiConfig(UUID id, String displayName, ProviderType provider, String endpoint,
                                 UUID credentialId, int priority, boolean enabled) {
        this.id = id;
        this.displayName = displayName;
        this.provider = provider;
        this.endpoint = endpoint;
        this.credentialId = credentialId;
        this.priority = priority;
        this.enabled = enabled;
    }

    public UUID id() { return id; }
    public String displayName() { return displayName; }
    public ProviderType provider() { return provider; }
    public String endpoint() { return endpoint; }
    public UUID credentialId() { return credentialId; }
    public int priority() { return priority; }
    public boolean enabled() { return enabled; }
    public Map<String, String> extraParams() { return extraParams; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public void setPriority(int priority) { this.priority = priority; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setCredentialId(UUID credentialId) { this.credentialId = credentialId; }
    public void setProvider(ProviderType provider) { this.provider = provider; }

    @Override
    public String toString() {
        // Intentionally never includes the raw API key (that lives only in credentials.json).
        return "TranslationApiConfig{id=" + id + ", displayName=" + displayName +
                ", provider=" + provider + ", endpoint=" + endpoint +
                ", priority=" + priority + ", enabled=" + enabled + "}";
    }
}
