package com.kiyo.alltranslator.api;

public enum ProviderType {
    GENERIC_REST,
    DEEPL_COMPATIBLE,
    /** Cloud OpenAI-compatible endpoint (e.g. official OpenAI API). API key required. */
    OPENAI_COMPATIBLE,
    /**
     * Phase 13: local OpenAI-compatible server (e.g. LM Studio, or reportedly
     * llama.cpp's llama-server, which is documented upstream as exposing the same
     * /v1/chat/completions shape - NOT independently verified in this project,
     * since no llama.cpp install was available to test against; only LM Studio was
     * actually exercised end-to-end, see DEVELOPMENT_STATUS.md). Same wire protocol
     * as OPENAI_COMPATIBLE (both are handled by OpenAiCompatibleProvider), but kept
     * as a separate enum constant so the UI can default to a localhost endpoint and
     * treat the API key as optional (most local servers accept any/no key) without
     * changing OPENAI_COMPATIBLE's existing cloud-oriented default/required-key
     * behavior for already-configured users.
     */
    OPENAI_COMPATIBLE_LOCAL,
    /**
     * Phase 14: Ollama, through its OpenAI-compatible /v1/chat/completions endpoint
     * (default port 11434). Same wire protocol as OPENAI_COMPATIBLE_LOCAL (handled by
     * OpenAiCompatibleProvider, API key optional), kept as its own constant so the UI
     * can show a separate name and default URL. NOT verified against a real Ollama
     * install in this project.
     */
    OLLAMA,
    GOOGLE_WEB_FREE,
    GOOGLE_CLOUD_V2,
    ANTHROPIC,
    GEMINI,
    /**
     * Phase 14: client-only pseudo-provider that asks the currently-joined
     * server to translate on our behalf using the SERVER's own configured
     * API/credentials, so individual clients don't each need their own key.
     * Never registered server-side (ProviderFactory) and never receiver-side
     * self-selectable (TranslationService's excludeProvider overload) - see
     * ServerProxyProvider's Javadoc and DEVELOPMENT_STATUS.md Phase 14 task 4.
     */
    SERVER_PROXY
}
