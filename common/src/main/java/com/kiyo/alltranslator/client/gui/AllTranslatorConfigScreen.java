package com.kiyo.alltranslator.client.gui;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.service.TranslationApiConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 8: the single shared configuration screen ARCHITECTURE.md §11/§15 call for.
 *
 * Phase 11: reverted the short-lived "No-Key Providers" sub-screen (which supported
 * both Google Translate Free and LibreTranslate side by side) back to a single
 * on/off toggle here, after LibreTranslate's public instance turned out to require
 * a paid API key (confirmed by hand - HTTP 400 "Visit https://portal.libretranslate.com
 * to get an API key" from the actual endpoint) and was dropped. Google Translate
 * (Free) is still an ordinary TranslationApiConfig entry in ConfigModel#apis -
 * editable/reorderable in "Manage Translation APIs" like any other API - this toggle
 * is just a convenience that creates-or-flips-enabled on that one entry rather than
 * making the user go find it in the API list.
 */
public final class AllTranslatorConfigScreen extends Screen {

    private static final String GOOGLE_FREE_ENDPOINT = "https://translate.googleapis.com/translate_a/single";

    private final Screen parent;
    private final ConfigManager configManager;
    private final ConfigModel model;

    private int fieldX;
    private int fieldWidth;
    private int labelTranslationY;
    private int labelLanguageY;
    private int labelServerChatY;
    private int labelMemCacheY;
    private int labelTtlY;
    private int labelGoogleFreeY;
    private int apiCountLabelY;
    private int apiButtonY;

    private CycleButton<Boolean> translationEnabledButton;
    private EditBox targetLanguageBox;
    private CycleButton<Boolean> serverChatButton;
    private EditBox memoryCacheBox;
    private EditBox ttlBox;
    private CycleButton<Boolean> googleFreeButton;

    public AllTranslatorConfigScreen(Screen parent) {
        super(Component.literal("All Translator"));
        this.parent = parent;
        this.configManager = AllTranslatorCore.configManager();
        this.model = configManager.model();
    }

    private Optional<TranslationApiConfig> findGoogleFree() {
        return model.apis.stream().filter(c -> c.provider() == ProviderType.GOOGLE_WEB_FREE).findFirst();
    }

    @Override
    protected void init() {
        fieldX = this.width / 2 - 100;
        fieldWidth = 200;
        int y = 22;
        int blockHeight = 30;

        labelTranslationY = y;
        translationEnabledButton = CycleButton.onOffBuilder(model.translationEnabled)
                .create(fieldX, y + 10, fieldWidth, 20, Component.literal("Translation"));
        this.addRenderableWidget(translationEnabledButton);
        y += blockHeight;

        labelLanguageY = y;
        targetLanguageBox = new EditBox(this.font, fieldX, y + 10, fieldWidth, 20, Component.literal("Target Language"));
        targetLanguageBox.setMaxLength(16);
        targetLanguageBox.setHint(Component.literal("auto"));
        targetLanguageBox.setValue(model.forcedTargetLanguage == null ? "" : model.forcedTargetLanguage);
        this.addRenderableWidget(targetLanguageBox);
        y += blockHeight;

        labelServerChatY = y;
        serverChatButton = CycleButton.onOffBuilder(model.serverSideChatTranslationEnabled)
                .create(fieldX, y + 10, fieldWidth, 20, Component.literal("Server Chat"));
        this.addRenderableWidget(serverChatButton);
        y += blockHeight;

        labelMemCacheY = y;
        memoryCacheBox = new EditBox(this.font, fieldX, y + 10, fieldWidth, 20, Component.literal("Memory Cache Capacity"));
        memoryCacheBox.setMaxLength(6);
        memoryCacheBox.setValue(String.valueOf(model.memoryCacheCapacity));
        this.addRenderableWidget(memoryCacheBox);
        y += blockHeight;

        labelTtlY = y;
        ttlBox = new EditBox(this.font, fieldX, y + 10, fieldWidth, 20, Component.literal("Dynamic Text Cache TTL (days)"));
        ttlBox.setMaxLength(4);
        ttlBox.setValue(String.valueOf(model.dynamicTextCacheTtlDays));
        this.addRenderableWidget(ttlBox);
        y += blockHeight;

        labelGoogleFreeY = y;
        boolean googleCurrentlyOn = findGoogleFree().map(TranslationApiConfig::enabled).orElse(false);
        googleFreeButton = CycleButton.onOffBuilder(googleCurrentlyOn)
                .create(fieldX, y + 10, fieldWidth, 20, Component.literal("Google Translate (Free)"));
        this.addRenderableWidget(googleFreeButton);
        y += blockHeight;

        apiCountLabelY = y;
        apiButtonY = y + 10;
        this.addRenderableWidget(
                Button.builder(Component.literal("Manage Translation APIs"), button ->
                                this.minecraft.gui.setScreen(new AllTranslatorApiListScreen(this)))
                        .pos(this.width / 2 - 100, apiButtonY)
                        .size(200, 20)
                        .build());

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> onDone())
                        .pos(this.width / 2 - 75, this.height - 24)
                        .size(150, 20)
                        .build());
    }

    private void onDone() {
        applyToModel();
        this.minecraft.gui.setScreen(parent);
    }

    private void applyToModel() {
        model.translationEnabled = translationEnabledButton.getValue();
        model.serverSideChatTranslationEnabled = serverChatButton.getValue();

        String targetLanguage = targetLanguageBox.getValue().trim();
        model.forcedTargetLanguage = targetLanguage.isEmpty() ? null : targetLanguage;

        try {
            model.memoryCacheCapacity = Integer.parseInt(memoryCacheBox.getValue().trim());
        } catch (NumberFormatException ignored) {
        }
        try {
            model.dynamicTextCacheTtlDays = Integer.parseInt(ttlBox.getValue().trim());
        } catch (NumberFormatException ignored) {
        }

        boolean wantGoogleOn = googleFreeButton.getValue();
        Optional<TranslationApiConfig> existing = findGoogleFree();
        if (existing.isPresent()) {
            existing.get().setEnabled(wantGoogleOn);
        } else if (wantGoogleOn) {
            int maxPriority = -1;
            for (TranslationApiConfig cfg : model.apis) maxPriority = Math.max(maxPriority, cfg.priority());
            model.apis.add(new TranslationApiConfig(
                    UUID.randomUUID(), "Google Translate (Free)", ProviderType.GOOGLE_WEB_FREE,
                    GOOGLE_FREE_ENDPOINT, null, maxPriority + 1, true));
        }

        configManager.save();

        if (AllTranslatorCore.cacheManager() != null) {
            AllTranslatorCore.cacheManager().setDynamicTextCacheTtlDays(model.dynamicTextCacheTtlDays);
        }
        if (AllTranslatorCore.apiManager() != null) {
            AllTranslatorCore.apiManager().reload(model.apis);
        }
    }

    @Override
    public void onClose() {
        applyToModel();
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
        super.extractRenderState(drawContext, mouseX, mouseY, delta);
        drawContext.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

        drawContext.text(this.font, "Translation Enabled", fieldX, labelTranslationY, 0xFFAAAAAA);
        drawContext.text(this.font, "Target Language (blank = auto)", fieldX, labelLanguageY, 0xFFAAAAAA);
        drawContext.text(this.font, "Server-Side Chat Translation", fieldX, labelServerChatY, 0xFFAAAAAA);
        drawContext.text(this.font, "Memory Cache Capacity", fieldX, labelMemCacheY, 0xFFAAAAAA);
        drawContext.text(this.font, "Dynamic Text Cache TTL (days)", fieldX, labelTtlY, 0xFFAAAAAA);
        drawContext.text(this.font, "Google Translate (Free)", fieldX, labelGoogleFreeY, 0xFFAAAAAA);

        int apiCount = model.apis.size();
        drawContext.text(this.font, apiCount + " API configuration(s)", fieldX, apiCountLabelY, 0xFFAAAAAA);
    }
}
