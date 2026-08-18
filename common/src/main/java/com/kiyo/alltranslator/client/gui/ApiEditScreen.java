package com.kiyo.alltranslator.client.gui;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.config.CredentialStore;
import com.kiyo.alltranslator.service.ApiManager;
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
 * Phase 8: add/edit a single TranslationApiConfig (ARCHITECTURE.md §5/§11).
 *
 * Security note (CLAUDE.md §10/ARCHITECTURE.md §12): the API-key field is always
 * shown blank when editing an existing entry - the raw key is never read back out
 * of CredentialStore for display. Leaving it blank on Save means "keep the existing
 * key"; typing a new value replaces it. This avoids ever rendering a stored secret
 * back onto the screen.
 *
 * Layout: label-above-widget, same reasoning/fix as AllTranslatorConfigScreen (a
 * label-left layout overlapped the widget column for long labels during manual
 * testing).
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

    private EditBox displayNameBox;
    private CycleButton<ProviderType> providerButton;
    private EditBox endpointBox;
    private EditBox apiKeyBox;
    private EditBox priorityBox;
    private CycleButton<Boolean> enabledButton;

    public ApiEditScreen(Screen parent, TranslationApiConfig existing) {
        super(Component.literal(existing == null ? "All Translator - Add API" : "All Translator - Edit API"));
        this.parent = parent;
        this.existing = existing;
    }

    @Override
    protected void init() {
        fieldX = this.width / 2 - 100;
        int fieldWidth = 200;
        int y = 30;
        int blockHeight = 36;

        labelNameY = y;
        displayNameBox = new EditBox(this.font, fieldX, y + 11, fieldWidth, 20, Component.literal("Display Name"));
        displayNameBox.setMaxLength(64);
        displayNameBox.setValue(existing != null ? existing.displayName() : "New API");
        this.addRenderableWidget(displayNameBox);
        y += blockHeight;

        labelProviderY = y;
        ProviderType initialProvider = existing != null ? existing.provider() : ProviderType.GENERIC_REST;
        providerButton = CycleButton.<ProviderType>builder(pt -> Component.literal(pt.name()), initialProvider)
                .withValues(ProviderType.values())
                .create(fieldX, y + 11, fieldWidth, 20, Component.literal("Provider"));
        this.addRenderableWidget(providerButton);
        y += blockHeight;

        labelEndpointY = y;
        endpointBox = new EditBox(this.font, fieldX, y + 11, fieldWidth, 20, Component.literal("Endpoint URL"));
        endpointBox.setMaxLength(256);
        endpointBox.setValue(existing != null && existing.endpoint() != null ? existing.endpoint() : "");
        this.addRenderableWidget(endpointBox);
        y += blockHeight;

        labelKeyY = y;
        apiKeyBox = new EditBox(this.font, fieldX, y + 11, fieldWidth, 20, Component.literal("API Key"));
        apiKeyBox.setMaxLength(512);
        apiKeyBox.setValue("");
        apiKeyBox.setHint(Component.literal(
                existing != null && existing.credentialId() != null ? "(leave blank to keep current key)" : "required"));
        this.addRenderableWidget(apiKeyBox);
        y += blockHeight;

        labelPriorityY = y;
        priorityBox = new EditBox(this.font, fieldX, y + 11, 60, 20, Component.literal("Priority"));
        priorityBox.setMaxLength(6);
        priorityBox.setValue(String.valueOf(existing != null ? existing.priority() : nextPriority()));
        this.addRenderableWidget(priorityBox);
        y += blockHeight;

        labelEnabledY = y;
        enabledButton = CycleButton.onOffBuilder(existing == null || existing.enabled())
                .create(fieldX, y + 11, 100, 20, Component.literal("Enabled"));
        this.addRenderableWidget(enabledButton);
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
        String rawKey = apiKeyBox.getValue();

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
            model.apis.add(created);
        } else {
            existing.setDisplayName(displayName);
            existing.setProvider(provider);
            existing.setEndpoint(endpoint);
            existing.setPriority(priority);
            existing.setEnabled(enabled);
            if (!rawKey.isBlank()) {
                UUID credentialId = credentialStore.putKey(existing.credentialId(), rawKey);
                existing.setCredentialId(credentialId);
            }
        }

        configManager.save();
        apiManager.reload(model.apis);

        if (parent instanceof AllTranslatorApiListScreen listScreen) {
            listScreen.refresh();
        }
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
        super.extractRenderState(drawContext, mouseX, mouseY, delta);
        drawContext.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        drawContext.text(this.font, "Display Name", fieldX, labelNameY, 0xFFAAAAAA);
        drawContext.text(this.font, "Provider", fieldX, labelProviderY, 0xFFAAAAAA);
        drawContext.text(this.font, "Endpoint URL", fieldX, labelEndpointY, 0xFFAAAAAA);
        drawContext.text(this.font, "API Key", fieldX, labelKeyY, 0xFFAAAAAA);
        drawContext.text(this.font, "Priority", fieldX, labelPriorityY, 0xFFAAAAAA);
        drawContext.text(this.font, "Enabled", fieldX, labelEnabledY, 0xFFAAAAAA);
    }
}
