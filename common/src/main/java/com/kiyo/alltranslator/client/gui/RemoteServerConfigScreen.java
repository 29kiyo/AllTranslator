package com.kiyo.alltranslator.client.gui;

import com.google.gson.Gson;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.network.ServerConfigPayloads;
import com.kiyo.alltranslator.service.TranslationApiConfig;
import dev.architectury.networking.NetworkManager;
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
 * Phase 14: remote mirror of AllTranslatorConfigScreen for editing a SERVER's own config.json
 * over the network (see ServerConfigNetworking's Javadoc for the wire protocol/security model).
 * Opened only via RemoteConfigClientReceiver, which defers the actual setScreen() call to a
 * safe client-tick boundary (mirroring AllTranslatorKeyBindings' own screen==null guard) - see
 * that class's Javadoc for the real-world crash this fixes.
 *
 * Unlike the local screen, there is no live ConfigManager/ApiManager/CredentialStore for the
 * target server in THIS JVM (it's a different process/machine) - every field here reads from
 * and writes to the in-memory `model` instance passed into the constructor (deserialized from
 * the server's OpenScreen payload). The ENTIRE model is serialized and sent as a single
 * ServerConfigPayloads.Save payload only when THIS top-level screen's Done/close fires.
 * Sub-screens (RemoteServerCategoriesScreen, RemoteServerApiListScreen/RemoteServerApiEditScreen)
 * mutate the same shared `model` instance directly and never send anything over the network
 * themselves - a single "OK to apply everything" boundary, unlike each local sub-screen's own
 * immediate-save-to-disk behavior.
 */
public final class RemoteServerConfigScreen extends Screen {

    private static final Gson GSON = new Gson();
    private static final String GOOGLE_FREE_ENDPOINT = "https://translate.googleapis.com/translate_a/single";
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int SECTION_COLOR = 0xFFFFAA00;
    // 34 (not 40, unlike the local AllTranslatorConfigScreen) because this remote screen has
    // one MORE row than the local screen (server-only "Server-Proxy Translation" has no local
    // equivalent). Matches the already-proven-safe value TranslationCategoriesScreen uses.
    private static final int ROW_HEIGHT = 34;
    private static final int COLUMN_WIDTH = 150;
    private static final int COLUMN_GAP = 20;

    private final Screen parent;
    private final ConfigModel model;

    /**
     * Real-world fix: this screen has one MORE field row than the local
     * AllTranslatorConfigScreen (the server-only "Server-Proxy Translation" row). On very
     * small windows, the resulting widget layout overflows the visible screen area, and in
     * that overflow condition vanilla's GuiRenderer/EditBox scissor-clipping computation was
     * reliably observed (real hardware, repeated tests) to throw
     * "IllegalArgumentException: Scissor size must be >0" and crash the game - even after
     * confirming this screen's own coordinate math, widget set, and screen-open timing all
     * match the never-crashing local AllTranslatorConfigScreen as closely as possible. Since
     * a crash is far worse than a screen the admin simply can't use until they resize their
     * window, this screen refuses to lay out its full widget set when the required content
     * height doesn't fit and shows a "please resize your window" message instead.
     */
    private static final int MIN_USABLE_HEIGHT = 300;
    private static final int MIN_USABLE_WIDTH = 400;

    private boolean tooSmall;

    private int leftX;
    private int rightX;

    private int labelTranslationY;
    private int labelLanguageY;
    private int labelServerChatY;
    private int labelMemCacheY;
    private int labelTtlY;
    private int labelMaxConcurrentY;
    private int labelServerProxyY;

    private int labelGoogleFreeY;
    private int labelShowOriginalY;
    private int labelShowOriginalNameY;
    private int labelToastY;
    private int labelToastSoundY;
    private int labelScoreboardY;
    private int labelScoreboardIntervalY;

    private int apiCountLabelY;
    private int apiButtonY;

    private CycleButton<Boolean> translationEnabledButton;
    private EditBox targetLanguageBox;
    private CycleButton<Boolean> serverChatButton;
    private EditBox memoryCacheBox;
    private EditBox ttlBox;
    private EditBox maxConcurrentBox;
    private CycleButton<Boolean> serverProxyButton;

    private CycleButton<Boolean> googleFreeButton;
    private CycleButton<Boolean> showOriginalTextButton;
    private CycleButton<Boolean> showOriginalNameButton;
    private CycleButton<Boolean> toastEnabledButton;
    private CycleButton<Boolean> toastSoundButton;
    private CycleButton<Boolean> scoreboardEnabledButton;
    private EditBox scoreboardIntervalBox;

    public RemoteServerConfigScreen(Screen parent, ConfigModel model) {
        super(Component.translatable("gui.alltranslator.serverconfig.title"));
        this.parent = parent;
        this.model = model;
    }

    private Optional<TranslationApiConfig> findGoogleFree() {
        return model.apis.stream().filter(c -> c.provider() == ProviderType.GOOGLE_WEB_FREE).findFirst();
    }

    @Override
    protected void init() {
        tooSmall = this.height < MIN_USABLE_HEIGHT || this.width < MIN_USABLE_WIDTH;
        if (tooSmall) {
            this.addRenderableWidget(
                    Button.builder(CommonComponents.GUI_BACK, button -> this.minecraft.gui.setScreen(parent))
                            .pos(this.width / 2 - 75, this.height / 2 + 10)
                            .size(150, 20)
                            .build());
            return;
        }

        int totalWidth = COLUMN_WIDTH * 2 + COLUMN_GAP;
        leftX = this.width / 2 - totalWidth / 2;
        rightX = leftX + COLUMN_WIDTH + COLUMN_GAP;

        int topY = 26;
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

        labelMaxConcurrentY = y;
        maxConcurrentBox = new EditBox(this.font, leftX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.max_concurrent_requests"));
        maxConcurrentBox.setMaxLength(3);
        maxConcurrentBox.setValue(String.valueOf(model.maxConcurrentHttpRequests));
        this.addRenderableWidget(maxConcurrentBox);
        y += ROW_HEIGHT;

        labelServerProxyY = y;
        serverProxyButton = CycleButton.onOffBuilder(model.serverProxyTranslationEnabled)
                .create(leftX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.server_proxy"));
        this.addRenderableWidget(serverProxyButton);
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

        labelShowOriginalNameY = y;
        showOriginalNameButton = CycleButton.onOffBuilder(model.showOriginalNameOnItems)
                .create(rightX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.show_original_on_items"));
        this.addRenderableWidget(showOriginalNameButton);
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

        labelScoreboardY = y;
        scoreboardEnabledButton = CycleButton.onOffBuilder(model.scoreboardEnabled)
                .create(rightX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.scoreboard"));
        this.addRenderableWidget(scoreboardEnabledButton);
        y += ROW_HEIGHT;

        labelScoreboardIntervalY = y;
        scoreboardIntervalBox = new EditBox(this.font, rightX, y + 12, COLUMN_WIDTH, 20, Component.translatable("gui.alltranslator.config.scoreboard_interval"));
        scoreboardIntervalBox.setMaxLength(3);
        scoreboardIntervalBox.setValue(String.valueOf(model.scoreboardUpdateIntervalSeconds));
        this.addRenderableWidget(scoreboardIntervalBox);
        y += ROW_HEIGHT;

        int rightColumnBottom = y;

        int bottomOfColumns = Math.max(leftColumnBottom, rightColumnBottom);

        apiCountLabelY = bottomOfColumns;
        apiButtonY = bottomOfColumns + 12;
        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.alltranslator.config.manage_apis"), button -> {
                            applyToModel();
                            this.minecraft.gui.setScreen(new RemoteServerApiListScreen(this, model));
                        })
                        .pos(this.width / 2 - 100, apiButtonY)
                        .size(200, 20)
                        .build());

        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.alltranslator.config.categories"), button -> {
                            applyToModel();
                            this.minecraft.gui.setScreen(new RemoteServerCategoriesScreen(this, model));
                        })
                        .pos(this.width / 2 - 100, apiButtonY + 24)
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
        sendToServer();
        this.minecraft.gui.setScreen(parent);
    }

    private void applyToModel() {
        model.translationEnabled = translationEnabledButton.getValue();
        model.serverSideChatTranslationEnabled = serverChatButton.getValue();
        model.showOriginalTextInChat = showOriginalTextButton.getValue();
        model.showOriginalNameOnItems = showOriginalNameButton.getValue();
        model.apiErrorToastEnabled = toastEnabledButton.getValue();
        model.apiErrorToastSoundEnabled = toastSoundButton.getValue();
        model.serverProxyTranslationEnabled = serverProxyButton.getValue();

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
        model.scoreboardEnabled = scoreboardEnabledButton.getValue();
        try {
            model.scoreboardUpdateIntervalSeconds = Integer.parseInt(scoreboardIntervalBox.getValue().trim());
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
    }

    private void sendToServer() {
        String json = GSON.toJson(model);
        NetworkManager.sendToServer(new ServerConfigPayloads.Save(json));
    }

    @Override
    public void onClose() {
        onDone();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
        super.extractRenderState(drawContext, mouseX, mouseY, delta);
        drawContext.centeredText(this.font, this.title, this.width / 2, 8, LABEL_COLOR);

        if (tooSmall) {
            drawContext.centeredText(this.font, Component.translatable("gui.alltranslator.serverconfig.window_too_small"),
                    this.width / 2, this.height / 2 - 20, LABEL_COLOR);
            return;
        }

        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.translation_enabled"), leftX, labelTranslationY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.target_language_hint"), leftX, labelLanguageY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.server_chat_label"), leftX, labelServerChatY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.memory_cache_capacity"), leftX, labelMemCacheY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.dynamic_text_ttl"), leftX, labelTtlY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.max_concurrent_requests"), leftX, labelMaxConcurrentY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.server_proxy_label"), leftX, labelServerProxyY, SECTION_COLOR);

        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.google_free"), rightX, labelGoogleFreeY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.show_original_in_chat_label"), rightX, labelShowOriginalY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.show_original_on_items"), rightX, labelShowOriginalNameY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.api_error_toast"), rightX, labelToastY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.api_error_toast_sound"), rightX, labelToastSoundY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.scoreboard"), rightX, labelScoreboardY, LABEL_COLOR);
        drawContext.text(this.font, Component.translatable("gui.alltranslator.config.scoreboard_interval"), rightX, labelScoreboardIntervalY, LABEL_COLOR);

        int apiCount = model.apis.size();
        drawContext.centeredText(this.font, Component.translatable("gui.alltranslator.config.api_count", apiCount), this.width / 2, apiCountLabelY, LABEL_COLOR);
    }
}
