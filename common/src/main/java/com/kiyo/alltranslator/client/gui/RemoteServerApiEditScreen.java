package com.kiyo.alltranslator.client.gui;

import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.service.TranslationApiConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Phase 14: remote mirror of ApiEditScreen. Deliberately has NO API-key field at all (not even
 * a disabled/greyed one) - see ServerConfigNetworking's Javadoc for why a key can never cross
 * this network path in either direction. A newly-created entry here always has credentialId ==
 * null; an edited existing entry keeps whatever credentialId it already had (never cleared,
 * never set here) - the server's own credentials.json is the only place a key can be attached
 * to an entry, by an admin with direct file/local-M-key access to that machine.
 */
public final class RemoteServerApiEditScreen extends Screen {

    private final Screen parent;
    private final ConfigModel model;
    private final TranslationApiConfig existing; // null => creating a new entry

    private int fieldX;
    private int labelNameY;
    private int labelProviderY;
    private int labelEndpointY;
    private int labelModelY;
    private int labelPriorityY;
    private int labelEnabledY;
    private int labelTimeoutY;
    private int labelKeyNoticeY;

    private EditBox displayNameBox;
    private CycleButton<ProviderType> providerButton;
    private EditBox endpointBox;
    private EditBox priorityBox;
    private CycleButton<Boolean> enabledButton;
    private EditBox timeoutSecondsBox;
    private EditBox modelBox;

    public RemoteServerApiEditScreen(Screen parent, ConfigModel model, TranslationApiConfig existing) {
        super(Component.translatable(existing == null
                ? "gui.alltranslator.apiedit.title_add"
                : "gui.alltranslator.apiedit.title_edit"));
        this.parent = parent;
        this.model = model;
        this.existing = existing;
    }

    private static boolean usesModelField(ProviderType type) {
        return type == ProviderType.OPENAI_COMPATIBLE
                || type == ProviderType.OPENAI_COMPATIBLE_LOCAL
                || type == ProviderType.ANTHROPIC;
    }

    private static Component displayNameFor(ProviderType type) {
        return switch (type) {
            case OPENAI_COMPATIBLE -> Component.translatable("gui.alltranslator.apiedit.provider_chatgpt");
            case OPENAI_COMPATIBLE_LOCAL -> Component.translatable("gui.alltranslator.apiedit.provider_local");
            case SERVER_PROXY -> Component.translatable("gui.alltranslator.apiedit.provider_server_proxy");
            default -> Component.literal(type.name());
        };
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
        providerButton = CycleButton.<ProviderType>builder(RemoteServerApiEditScreen::displayNameFor, initialProvider)
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
        modelBox = new EditBox(this.font, fieldX, y + 11, fieldWidth, 20, Component.literal("Model"));
        modelBox.setMaxLength(128);
        modelBox.setValue(existing != null ? existing.extraParams().getOrDefault("model", "") : "");
        modelBox.setTextColorUneditable(0xFF707070);
        this.addRenderableWidget(modelBox);
        y += blockHeight;

        labelKeyNoticeY = y;
        y += 20;

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
        providerButton.setValue(values[(idx - 1 + values.length) % values.length]);
        updateFieldAvailability();
    }

    private void onFillDefaultEndpoint() {
        String defaultEndpoint = ApiEditScreen.defaultEndpointFor(providerButton.getValue());
        if (defaultEndpoint != null) {
            endpointBox.setValue(defaultEndpoint);
        }
    }

    private void updateFieldAvailability() {
        boolean modelUsed = usesModelField(providerButton.getValue());
        modelBox.setEditable(modelUsed);
        if (!modelUsed) {
            modelBox.setValue("");
        }
    }

    private int nextPriority() {
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
        String timeoutSeconds = timeoutSecondsBox.getValue().trim();
        String modelValue = usesModelField(provider) ? modelBox.getValue().trim() : "";

        int priority;
        try {
            priority = Integer.parseInt(priorityBox.getValue().trim());
        } catch (NumberFormatException e) {
            priority = existing != null ? existing.priority() : nextPriority();
        }

        if (existing == null) {
            TranslationApiConfig created = new TranslationApiConfig(
                    UUID.randomUUID(), displayName, provider, endpoint, null, priority, enabled);
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
        }

        if (parent instanceof RemoteServerApiListScreen listScreen) {
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
        drawContext.text(this.font, Component.translatable("gui.alltranslator.apiedit.remote_key_notice"), fieldX, labelKeyNoticeY, 0xFFFFAA00);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.apiedit.priority"), fieldX, labelPriorityY, 0xFFFFFFFF);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.apiedit.enabled"), fieldX, labelEnabledY, 0xFFFFFFFF);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.apiedit.timeout_seconds"), fieldX, labelTimeoutY, 0xFFFFFFFF);
    }
}
