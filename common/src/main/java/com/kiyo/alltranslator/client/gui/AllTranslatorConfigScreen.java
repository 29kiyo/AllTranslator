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
 * Phase 13 layout rewrite: moved from a single centered column to a two-column
 * layout (left/right, each its own X origin and independent Y cursor) since the
 * growing number of Phase 11-13 settings made the single-column version too tall
 * on smaller GUI-scale windows. Label color changed from 0xFFAAAAAA (grey, hard to
 * read per user feedback) to 0xFFFFFFFF (white, matching the title). Vertical
 * spacing between rows increased (24 -> 34) so label text and the widget below it
 * no longer visually crowd each other.
 */
public final class AllTranslatorConfigScreen extends Screen {

    private static final String GOOGLE_FREE_ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int ROW_HEIGHT = 40;
    private static final int COLUMN_WIDTH = 150;
    private static final int COLUMN_GAP = 20;

    private final Screen parent;
    private final ConfigManager configManager;
    private final ConfigModel model;

    private int leftX;
    private int rightX;

    private int labelTranslationY;
    private int labelLanguageY;
    private int labelServerChatY;
    private int labelMemCacheY;
    private int labelTtlY;
    private int labelShowOriginalNameY;
    private int labelMaxConcurrentY;

    private int labelGoogleFreeY;
    private int labelShowOriginalY;
    private int labelToastY;
    private int labelToastSoundY;
    private int labelOtherScreensY;

    private int apiCountLabelY;
    private int apiButtonY;

    private CycleButton<Boolean> translationEnabledButton;
    private EditBox targetLanguageBox;
    private CycleButton<Boolean> serverChatButton;
    private EditBox memoryCacheBox;
    private EditBox ttlBox;
    private CycleButton<Boolean> showOriginalNameButton;
    private EditBox maxConcurrentBox;

    private CycleButton<Boolean> googleFreeButton;
    private CycleButton<Boolean> showOriginalTextButton;
    private CycleButton<Boolean> toastEnabledButton;
    private CycleButton<Boolean> toastSoundButton;
    private CycleButton<Boolean> otherScreensButton;

    public AllTranslatorConfigScreen(Screen parent) {
        super(Component.translatable("gui.alltranslator.config.title"));
        this.parent = parent;
        this.configManager = AllTranslatorCore.configManager();
        this.model = configManager.model();
    }

    private Optional<TranslationApiConfig> findGoogleFree() {
        return model.apis.stream().filter(c -> c.provider() == ProviderType.GOOGLE_WEB_FREE).findFirst();
    }

    @Override
    protected void init() {
        int totalWidth = COLUMN_WIDTH * 2 + COLUMN_GAP;
        leftX = this.width / 2 - totalWidth / 2;
        rightX = leftX + COLUMN_WIDTH + COLUMN_GAP;

        int topY = 26;

        // ----- left column -----
        int y = topY;

        labelTranslationY = y;
        translationEnabledButton = CycleButton.onOffBuilder(model.translationEnabled)
                .create(leftX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.translation"));
        this.addRenderableWidget(translationEnabledButton);
        y += ROW_HEIGHT;

        labelLanguageY = y;
        targetLanguageBox = new EditBox(this.font, leftX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.target_language"));
        targetLanguageBox.setMaxLength(16);
        targetLanguageBox.setHint(Component.literal("auto"));
        targetLanguageBox.setValue(model.forcedTargetLanguage == null ? "" : model.forcedTargetLanguage);
        this.addRenderableWidget(targetLanguageBox);
        y += ROW_HEIGHT;

        labelServerChatY = y;
        serverChatButton = CycleButton.onOffBuilder(model.serverSideChatTranslationEnabled)
                .create(leftX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.server_chat"));
        this.addRenderableWidget(serverChatButton);
        y += ROW_HEIGHT;

        labelMemCacheY = y;
        memoryCacheBox = new EditBox(this.font, leftX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.memory_cache_capacity"));
        memoryCacheBox.setMaxLength(6);
        memoryCacheBox.setValue(String.valueOf(model.memoryCacheCapacity));
        this.addRenderableWidget(memoryCacheBox);
        y += ROW_HEIGHT;

        labelTtlY = y;
        ttlBox = new EditBox(this.font, leftX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.dynamic_text_ttl"));
        ttlBox.setMaxLength(4);
        ttlBox.setValue(String.valueOf(model.dynamicTextCacheTtlDays));
        this.addRenderableWidget(ttlBox);
        y += ROW_HEIGHT;

        labelShowOriginalNameY = y;
        showOriginalNameButton = CycleButton.onOffBuilder(model.showOriginalNameOnItems)
                .create(leftX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.show_original_on_items"));
        this.addRenderableWidget(showOriginalNameButton);
        y += ROW_HEIGHT;

        labelMaxConcurrentY = y;
        maxConcurrentBox = new EditBox(this.font, leftX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.max_concurrent_requests"));
        maxConcurrentBox.setMaxLength(3);
        maxConcurrentBox.setValue(String.valueOf(model.maxConcurrentHttpRequests));
        this.addRenderableWidget(maxConcurrentBox);
        y += ROW_HEIGHT;

        int leftColumnBottom = y;

        // ----- right column -----
        y = topY;

        labelGoogleFreeY = y;
        boolean googleCurrentlyOn = findGoogleFree().map(TranslationApiConfig::enabled).orElse(false);
        googleFreeButton = CycleButton.onOffBuilder(googleCurrentlyOn)
                .create(rightX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.google_free"));
        this.addRenderableWidget(googleFreeButton);
        y += ROW_HEIGHT;

        labelShowOriginalY = y;
        showOriginalTextButton = CycleButton.onOffBuilder(model.showOriginalTextInChat)
                .create(rightX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.show_original_in_chat"));
        this.addRenderableWidget(showOriginalTextButton);
        y += ROW_HEIGHT;

        labelToastY = y;
        toastEnabledButton = CycleButton.onOffBuilder(model.apiErrorToastEnabled)
                .create(rightX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.api_error_toast"));
        this.addRenderableWidget(toastEnabledButton);
        y += ROW_HEIGHT;

        labelToastSoundY = y;
        toastSoundButton = CycleButton.onOffBuilder(model.apiErrorToastSoundEnabled)
                .create(rightX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.api_error_toast_sound"));
        this.addRenderableWidget(toastSoundButton);
        y += ROW_HEIGHT;

        labelOtherScreensY = y;
        otherScreensButton = CycleButton.onOffBuilder(model.translateOtherModScreens)
                .create(rightX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.translate_other_screens"));
        this.addRenderableWidget(otherScreensButton);
        y += ROW_HEIGHT;

        int rightColumnBottom = y;

        int bottomOfColumns = Math.max(leftColumnBottom, rightColumnBottom);

        apiCountLabelY = bottomOfColumns;
        apiButtonY = bottomOfColumns + 12;
        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.alltranslator.config.manage_apis"), button ->
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
        model.showOriginalTextInChat = showOriginalTextButton.getValue();
        model.apiErrorToastEnabled = toastEnabledButton.getValue();
        model.apiErrorToastSoundEnabled = toastSoundButton.getValue();
        model.translateOtherModScreens = otherScreensButton.getValue();
        model.showOriginalNameOnItems = showOriginalNameButton.getValue();

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
        try {
            model.maxConcurrentHttpRequests = Integer.parseInt(maxConcurrentBox.getValue().trim());
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
        if (AllTranslatorCore.translationService() != null) {
            AllTranslatorCore.translationService().setMaxConcurrentHttpRequests(model.maxConcurrentHttpRequests);
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
        drawContext.centeredText(this.font, this.title, this.width / 2, 8, LABEL_COLOR);

        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.translation_enabled"), leftX, labelTranslationY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.target_language_hint"), leftX, labelLanguageY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.server_chat_label"), leftX, labelServerChatY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.memory_cache_capacity"), leftX, labelMemCacheY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.dynamic_text_ttl"), leftX, labelTtlY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.show_original_on_items"), leftX, labelShowOriginalNameY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.max_concurrent_requests"), leftX, labelMaxConcurrentY, LABEL_COLOR);

        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.google_free"), rightX, labelGoogleFreeY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.show_original_in_chat_label"), rightX, labelShowOriginalY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.api_error_toast"), rightX, labelToastY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.api_error_toast_sound"), rightX, labelToastSoundY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.translate_other_screens"), rightX, labelOtherScreensY, LABEL_COLOR);

        int apiCount = model.apis.size();
        drawContext.centeredText(this.font, Component.translatable("gui.alltranslator.config.api_count", apiCount), this.width / 2, apiCountLabelY, LABEL_COLOR);
    }
}
