package com.kiyo.alltranslator.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.config.CredentialStore;
import com.kiyo.alltranslator.service.ApiManager;
import com.kiyo.alltranslator.service.TranslationApiConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 8: add/edit a single TranslationApiConfig (ARCHITECTURE.md §5/§11).
 *
 * Security note (CLAUDE.md §10/ARCHITECTURE.md §12): the API-key field is always
 * shown blank when editing an existing entry.
 *
 * Phase 13 changes (see class history / DEVELOPMENT_STATUS.md for details of each):
 *  - "Default URL" button, "<" provider-step-back button, "Timeout (sec)" field,
 *    label-color/hint fixes, translatable-key labels.
 *  - "Model" field (extraParams["model"]) + "Fetch Models" button: GETs
 *    {endpoint with trailing "/chat/completions" replaced by "/models"} following
 *    the OpenAI-compatible /v1/models convention (also used by LM Studio and other
 *    local servers) and parses {"data":[{"id":"..."}]}. Only meaningful for
 *    OPENAI_COMPATIBLE(_LOCAL)/ANTHROPIC endpoints that actually expose such an
 *    endpoint; Gemini's model is part of the endpoint URL itself, not a separate
 *    field, so this button is a no-op there (endpoint doesn't end in
 *    "/chat/completions"). Each click cycles to the next fetched model id; a fresh
 *    fetch only happens when the cached list is empty or the endpoint changed
 *    since the last fetch. Uses a short-lived one-off HttpClient (not the shared
 *    translation HttpClient from AllTranslatorCore, which is common-module and has
 *    no client-only UI coupling) since this is a rare, user-initiated, one-shot
 *    GET, not part of the hot translation path.
 *  - (2nd Phase 13 change) provider dropdown now distinguishes OPENAI_COMPATIBLE
 *    ("ChatGPT (OpenAI-compatible)") from the new OPENAI_COMPATIBLE_LOCAL
 *    ("Local (LM Studio, etc)") via displayNameFor() - a UI-only label mapping.
 *    The underlying enum CONSTANT NAMES are deliberately left unchanged
 *    (OPENAI_COMPATIBLE keeps its exact name) because ProviderType is serialized
 *    by name() into every user's config.json (see ConfigManager - plain Gson, no
 *    custom enum adapter); renaming a constant would silently fail to deserialize
 *    every existing saved API entry using it. Only a new constant was added.
 *  - Default URL button: GENERIC_REST correctly does NOT get a default endpoint
 *    (previously miscategorized as OpenAI-shaped in an earlier fix this session -
 *    GenericRestProvider is actually a fully generic flat-JSON REST client driven
 *    by extraParams, not an OpenAI-compatible one, so there is no sensible default
 *    to offer). OPENAI_COMPATIBLE_LOCAL defaults to LM Studio's conventional
 *    localhost port.
 *  - Field availability: endpointBox/modelBox/apiKeyBox are set active/inactive
 *    (and greyed via CommonComponents-style disabled rendering, handled by vanilla
 *    EditBox.setEditable(false)) based on the selected provider's actual
 *    requirements (see FIELD REQUIREMENTS table in requiresApiKey()/usesModelField()
 *    Javadoc below) - GOOGLE_WEB_FREE needs neither key nor model, GEMINI needs a
 *    key but no separate model field (baked into its endpoint URL), etc. This
 *    replaces the previous "all four fields always editable regardless of
 *    provider" behavior.
 */
public final class ApiEditScreen extends Screen {

    private final Screen parent;
    private final TranslationApiConfig existing; // null => creating a new entry

    private int fieldX;
    private int labelNameY;
    private int labelProviderY;
    private int labelEndpointY;
    private int labelKeyY;
    private int labelPriorityY;
    private int labelEnabledY;
    private int labelTimeoutY;
    private int labelModelY;
    private Component labelKeyComponent;
    private Component labelTimeoutComponent;

    private EditBox displayNameBox;
    private CycleButton<ProviderType> providerButton;
    private EditBox endpointBox;
    private EditBox apiKeyBox;
    private EditBox priorityBox;
    private CycleButton<Boolean> enabledButton;
    private EditBox timeoutSecondsBox;
    private EditBox modelBox;
    private Button fetchModelsButton;

    private List<String> fetchedModels = new ArrayList<>();
    private int fetchedModelIndex = -1;
    private String fetchedModelsForEndpoint = null;
    private volatile boolean fetchInProgress = false;

    public ApiEditScreen(Screen parent, TranslationApiConfig existing) {
        super(Component.translatable(existing == null
                ? "gui.alltranslator.apiedit.title_add"
                : "gui.alltranslator.apiedit.title_edit"));
        this.parent = parent;
        this.existing = existing;
    }

    /**
     * UI-only display label for the provider dropdown. Does NOT affect the
     * serialized enum name (ProviderType#name()) - see class Javadoc.
     */
    private static Component displayNameFor(ProviderType type) {
        return switch (type) {
            case OPENAI_COMPATIBLE -> Component.translatable("gui.alltranslator.apiedit.provider_chatgpt");
            case OPENAI_COMPATIBLE_LOCAL -> Component.translatable("gui.alltranslator.apiedit.provider_local");
            case OLLAMA -> Component.translatable("gui.alltranslator.apiedit.provider_ollama");
            case SERVER_PROXY -> Component.translatable("gui.alltranslator.apiedit.provider_server_proxy");
            default -> Component.literal(type.name());
        };
    }

    /**
     * FIELD REQUIREMENTS (verified against each provider's actual translate()
     * implementation, CLAUDE.md §3 - not guessed):
     *   GENERIC_REST            - key required (extraParams-driven, not model-based)
     *   DEEPL_COMPATIBLE        - key required, no model field
     *   OPENAI_COMPATIBLE       - key required
     *   OPENAI_COMPATIBLE_LOCAL - key optional (most local servers accept none)
     *   GOOGLE_WEB_FREE         - no key at all (unofficial, keyless endpoint)
     *   GOOGLE_CLOUD_V2         - key required, no model field
     *   ANTHROPIC               - key required
     *   GEMINI                  - key required, no model field (baked into endpoint URL)
     */
    private static boolean requiresApiKey(ProviderType type) {
        return type != ProviderType.GOOGLE_WEB_FREE && (type != ProviderType.OPENAI_COMPATIBLE_LOCAL && type != ProviderType.OLLAMA)
                && type != ProviderType.SERVER_PROXY;
    }

    private static boolean allowsApiKey(ProviderType type) {
        // OPENAI_COMPATIBLE_LOCAL allows an OPTIONAL key (some local servers do check
        // one) even though it isn't required - only GOOGLE_WEB_FREE has literally no
        // concept of a key at all (unofficial endpoint takes no credential of any kind).
        // SERVER_PROXY (Phase 14) also has no concept of a key - the server uses its
        // own credentials, never a client-supplied one (ARCHITECTURE.md §12).
        return type != ProviderType.GOOGLE_WEB_FREE && type != ProviderType.SERVER_PROXY;
    }

    private static boolean usesModelField(ProviderType type) {
        return type == ProviderType.OPENAI_COMPATIBLE
                || (type == ProviderType.OPENAI_COMPATIBLE_LOCAL || type == ProviderType.OLLAMA)
                || type == ProviderType.ANTHROPIC;
    }

    @Override
    protected void init() {
        fieldX = this.width / 2 - 100;
        int fieldWidth = 200;
        int y = 26;
        int blockHeight = 34;

        labelNameY = y;
        displayNameBox = new EditBox(this.font, fieldX, y + 11, fieldWidth, 20, Component.translatable("gui.alltranslator.apiedit.display_name"));
        displayNameBox.setMaxLength(64);
        displayNameBox.setValue(existing != null ? existing.displayName() : "New API");
        this.addRenderableWidget(displayNameBox);
        y += blockHeight;

        labelProviderY = y;
        ProviderType initialProvider = existing != null ? existing.provider() : ProviderType.GENERIC_REST;
        providerButton = CycleButton.<ProviderType>builder(ApiEditScreen::displayNameFor, initialProvider)
                .withValues(ProviderType.values())
                .create(fieldX, y + 11, 110, 20, Component.translatable("gui.alltranslator.apiedit.provider"),
                        (button, value) -> updateFieldAvailability());
        this.addRenderableWidget(providerButton);
        this.addRenderableWidget(
                Button.builder(Component.literal("<"), button -> onPreviousProvider())
                        .pos(fieldX + 114, y + 11)
                        .size(20, 20)
                        .build());
        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.alltranslator.apiedit.default_url"), button -> onFillDefaultEndpoint())
                        .pos(fieldX + 138, y + 11)
                        .size(62, 20)
                        .build());
        y += blockHeight;

        labelEndpointY = y;
        endpointBox = new EditBox(this.font, fieldX, y + 11, fieldWidth, 20, Component.translatable("gui.alltranslator.apiedit.endpoint_url"));
        endpointBox.setMaxLength(256);
        endpointBox.setValue(existing != null && existing.endpoint() != null ? existing.endpoint() : "");
        this.addRenderableWidget(endpointBox);
        y += blockHeight;

        labelModelY = y;
        modelBox = new EditBox(this.font, fieldX, y + 11, fieldWidth - 76, 20, Component.literal("Model"));
        modelBox.setMaxLength(128);
        modelBox.setValue(existing != null ? existing.extraParams().getOrDefault("model", "") : "");
        modelBox.setTextColorUneditable(0xFF707070);
        this.addRenderableWidget(modelBox);
        fetchModelsButton = Button.builder(Component.translatable("gui.alltranslator.apiedit.fetch_models"), button -> onFetchModels())
                .pos(fieldX + fieldWidth - 72, y + 11)
                .size(72, 20)
                .build();
        this.addRenderableWidget(fetchModelsButton);
        y += blockHeight;

        labelKeyY = y;
        labelKeyComponent = (existing != null && existing.credentialId() != null)
                ? Component.translatable("gui.alltranslator.apiedit.api_key_blank_keep")
                : Component.translatable("gui.alltranslator.apiedit.api_key_blank_none");
        apiKeyBox = new EditBox(this.font, fieldX, y + 11, fieldWidth, 20, Component.translatable("gui.alltranslator.apiedit.api_key"));
        apiKeyBox.setMaxLength(512);
        // Hide the key with '*' once the field loses focus (same length, so the cursor stays valid).
        apiKeyBox.addFormatter((text, displayPos) -> apiKeyBox.isFocused()
                ? net.minecraft.util.FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY)
                : net.minecraft.util.FormattedCharSequence.forward("*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        apiKeyBox.setValue("");
        apiKeyBox.setTextColorUneditable(0xFF707070);
        this.addRenderableWidget(apiKeyBox);
        y += blockHeight;

        labelPriorityY = y;
        priorityBox = new EditBox(this.font, fieldX, y + 11, 60, 20, Component.translatable("gui.alltranslator.apiedit.priority"));
        priorityBox.setMaxLength(6);
        priorityBox.setValue(String.valueOf(existing != null ? existing.priority() : nextPriority()));
        this.addRenderableWidget(priorityBox);
        y += blockHeight;

        labelEnabledY = y;
        enabledButton = CycleButton.onOffBuilder(existing == null || existing.enabled())
                .create(fieldX, y + 11, 100, 20, Component.translatable("gui.alltranslator.apiedit.enabled"));
        this.addRenderableWidget(enabledButton);
        y += blockHeight;

        labelTimeoutY = y;
        labelTimeoutComponent = Component.translatable("gui.alltranslator.apiedit.timeout_seconds");
        timeoutSecondsBox = new EditBox(this.font, fieldX, y + 11, 80, 20, Component.translatable("gui.alltranslator.apiedit.timeout_seconds"));
        timeoutSecondsBox.setMaxLength(6);
        timeoutSecondsBox.setValue(existing != null
                ? existing.extraParams().getOrDefault("timeoutSeconds", "")
                : "");
        this.addRenderableWidget(timeoutSecondsBox);
        y += blockHeight;

        this.addRenderableWidget(
                Button.builder(Component.literal("Save"), button -> onSave())
                        .pos(this.width / 2 - 105, this.height - 28)
                        .size(100, 20)
                        .build());
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, button -> this.minecraft.gui.setScreen(parent))
                        .pos(this.width / 2 + 5, this.height - 28)
                        .size(100, 20)
                        .build());

        updateFieldAvailability();
    }

    /**
     * Enables/disables (greys out, via vanilla EditBox#setEditable) modelBox and
     * apiKeyBox depending on whether the currently-selected provider actually uses
     * them - see requiresApiKey()/allowsApiKey()/usesModelField() Javadoc for the
     * verified per-provider requirements. Called on init() and every provider
     * change so switching providers immediately reflects which fields matter.
     */
    private void updateFieldAvailability() {
        ProviderType provider = providerButton.getValue();

        boolean keyAllowed = allowsApiKey(provider);
        apiKeyBox.setEditable(keyAllowed);
        if (!keyAllowed) {
            apiKeyBox.setValue("");
        }
        labelKeyComponent = !keyAllowed
                ? Component.translatable("gui.alltranslator.apiedit.api_key_not_used")
                : (existing != null && existing.credentialId() != null)
                        ? Component.translatable("gui.alltranslator.apiedit.api_key_blank_keep")
                        : requiresApiKey(provider)
                                ? Component.translatable("gui.alltranslator.apiedit.api_key_blank_none")
                                : Component.translatable("gui.alltranslator.apiedit.api_key_optional");

        boolean modelUsed = usesModelField(provider);
        modelBox.setEditable(modelUsed);
        fetchModelsButton.active = modelUsed;
        if (!modelUsed) {
            modelBox.setValue("");
        }
    }

    private void onPreviousProvider() {
        ProviderType[] values = ProviderType.values();
        ProviderType current = providerButton.getValue();
        int idx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                idx = i;
                break;
            }
        }
        int prevIdx = (idx - 1 + values.length) % values.length;
        providerButton.setValue(values[prevIdx]);
        updateFieldAvailability();
    }

    private void onFillDefaultEndpoint() {
        String defaultEndpoint = defaultEndpointFor(providerButton.getValue());
        if (defaultEndpoint != null) {
            endpointBox.setValue(defaultEndpoint);
        }
    }

    /** Default endpoint per provider (also used by RemoteServerApiEditScreen); null = none. */
    static String defaultEndpointFor(ProviderType provider) {
        return switch (provider) {
            case GOOGLE_CLOUD_V2 -> "https://translation.googleapis.com/language/translate/v2";
            case DEEPL_COMPATIBLE -> "https://api-free.deepl.com/v2/translate";
            case GOOGLE_WEB_FREE -> "https://translate.googleapis.com/translate_a/single";
            case ANTHROPIC -> "https://api.anthropic.com/v1/messages";
            case GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";
            case OPENAI_COMPATIBLE -> "https://api.openai.com/v1/chat/completions";
            // Phase 13: LM Studio's conventional default port/path. Other local
            // servers (llama.cpp's llama-server, Ollama's OpenAI-compat mode, etc)
            // use different ports - this is a documented, editable starting point,
            // not a claim that every local server listens here.
            case OPENAI_COMPATIBLE_LOCAL -> "http://localhost:1234/v1/chat/completions";
            case OLLAMA -> "http://localhost:11434/v1/chat/completions";
            // GENERIC_REST intentionally has no default: it is a fully generic
            // flat-JSON REST client configured entirely via extraParams
            // (requestTemplate/authHeader/authPrefix/responseField), not an
            // OpenAI-shaped endpoint - there is no single sensible URL to suggest.
            case GENERIC_REST -> null;
            // Phase 14: SERVER_PROXY has no endpoint of its own (it talks to whatever
            // Minecraft server is currently joined, over the mod's own C2S/S2C payloads,
            // not a URL).
            case SERVER_PROXY -> null;
        };
    }

    /**
     * If a fresh list is already cached for the current endpoint, just cycles to the
     * next entry (fast, no network call). Otherwise kicks off an async GET to
     * {endpoint with trailing "/chat/completions" replaced by "/models"} and fills
     * the field with the first result once it arrives.
     */
    private void onFetchModels() {
        String endpoint = endpointBox.getValue().trim();
        if (endpoint.isEmpty() || fetchInProgress) return;

        if (endpoint.equals(fetchedModelsForEndpoint) && !fetchedModels.isEmpty()) {
            fetchedModelIndex = (fetchedModelIndex + 1) % fetchedModels.size();
            modelBox.setValue(fetchedModels.get(fetchedModelIndex));
            return;
        }

        String modelsUrl = endpoint.endsWith("/chat/completions")
                ? endpoint.substring(0, endpoint.length() - "/chat/completions".length()) + "/models"
                : endpoint;
        String rawKey = apiKeyBox.getValue();

        fetchInProgress = true;
        fetchModelsButton.setMessage(Component.literal("Fetching..."));

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (!rawKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + rawKey);
        }

        httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    List<String> ids = new ArrayList<>();
                    try {
                        if (response.statusCode() == 200) {
                            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                            JsonArray data = root.getAsJsonArray("data");
                            if (data != null) {
                                for (int i = 0; i < data.size(); i++) {
                                    JsonObject entry = data.get(i).getAsJsonObject();
                                    if (entry.has("id")) {
                                        ids.add(entry.get("id").getAsString());
                                    }
                                }
                            }
                        } else {
                            AllTranslator.LOGGER.warn("Fetch Models: HTTP " + response.statusCode() + " from " + modelsUrl);
                        }
                    } catch (RuntimeException e) {
                        AllTranslator.LOGGER.warn("Fetch Models: failed to parse response from " + modelsUrl, e);
                    }

                    Minecraft.getInstance().execute(() -> {
                        fetchInProgress = false;
                        fetchModelsButton.setMessage(Component.translatable("gui.alltranslator.apiedit.fetch_models"));
                        if (ids.isEmpty()) {
                            return;
                        }
                        fetchedModels = ids;
                        fetchedModelsForEndpoint = endpoint;
                        fetchedModelIndex = 0;
                        modelBox.setValue(ids.get(0));
                    });
                })
                .exceptionally(error -> {
                    AllTranslator.LOGGER.warn("Fetch Models: request to " + modelsUrl + " failed", error);
                    Minecraft.getInstance().execute(() -> {
                        fetchInProgress = false;
                        fetchModelsButton.setMessage(Component.translatable("gui.alltranslator.apiedit.fetch_models"));
                    });
                    return null;
                });
    }

    private int nextPriority() {
        ConfigModel model = AllTranslatorCore.configManager().model();
        int max = -1;
        for (TranslationApiConfig cfg : model.apis) {
            max = Math.max(max, cfg.priority());
        }
        return max + 1;
    }

    private void onSave() {
        String displayName = displayNameBox.getValue().trim();
        if (displayName.isEmpty()) displayName = "Unnamed API";
        ProviderType provider = providerButton.getValue();
        String endpoint = endpointBox.getValue().trim();
        boolean enabled = enabledButton.getValue();
        String rawKey = allowsApiKey(provider) ? apiKeyBox.getValue() : "";
        String timeoutSeconds = timeoutSecondsBox.getValue().trim();
        String modelValue = usesModelField(provider) ? modelBox.getValue().trim() : "";

        int priority;
        try {
            priority = Integer.parseInt(priorityBox.getValue().trim());
        } catch (NumberFormatException e) {
            priority = existing != null ? existing.priority() : nextPriority();
        }

        ConfigManager configManager = AllTranslatorCore.configManager();
        ConfigModel model = configManager.model();
        CredentialStore credentialStore = AllTranslatorCore.credentialStore();
        ApiManager apiManager = AllTranslatorCore.apiManager();

        if (existing == null) {
            UUID credentialId = rawKey.isBlank() ? null : credentialStore.putKey(null, rawKey);
            TranslationApiConfig created = new TranslationApiConfig(
                    UUID.randomUUID(), displayName, provider, endpoint, credentialId, priority, enabled);
            applyExtraParam(created, "timeoutSeconds", timeoutSeconds);
            applyExtraParam(created, "model", modelValue);
            model.apis.add(created);
        } else {
            existing.setDisplayName(displayName);
            existing.setProvider(provider);
            existing.setEndpoint(endpoint);
            existing.setPriority(priority);
            existing.setEnabled(enabled);
            applyExtraParam(existing, "timeoutSeconds", timeoutSeconds);
            applyExtraParam(existing, "model", modelValue);
            if (!rawKey.isBlank()) {
                UUID credentialId = credentialStore.putKey(existing.credentialId(), rawKey);
                existing.setCredentialId(credentialId);
            } else if (!allowsApiKey(provider)) {
                // Phase 13: switching to a keyless provider (GOOGLE_WEB_FREE) clears
                // any previously-stored credential reference rather than silently
                // keeping a now-irrelevant key around.
                existing.setCredentialId(null);
            }
        }

        configManager.save();
        apiManager.reload(model.apis);

        if (parent instanceof AllTranslatorApiListScreen listScreen) {
            listScreen.refresh();
        }
        this.minecraft.gui.setScreen(parent);
    }

    private static void applyExtraParam(TranslationApiConfig config, String key, String value) {
        if (value.isBlank()) {
            config.extraParams().remove(key);
        } else {
            config.extraParams().put(key, value);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
        super.extractRenderState(drawContext, mouseX, mouseY, delta);
        drawContext.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.apiedit.display_name"), fieldX, labelNameY, 0xFFFFFFFF);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.apiedit.provider"), fieldX, labelProviderY, 0xFFFFFFFF);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.apiedit.endpoint_url"), fieldX, labelEndpointY, 0xFFFFFFFF);
        drawContext.text(this.font, "Model (optional, blank = provider default)", fieldX, labelModelY, 0xFFFFFFFF);
        drawContext.text(this.font, labelKeyComponent, fieldX, labelKeyY, 0xFFFFFFFF);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.apiedit.priority"), fieldX, labelPriorityY, 0xFFFFFFFF);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.apiedit.enabled"), fieldX, labelEnabledY, 0xFFFFFFFF);
        drawContext.text(this.font, labelTimeoutComponent, fieldX, labelTimeoutY, 0xFFFFFFFF);
    }
}
