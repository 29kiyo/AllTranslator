package com.kiyo.alltranslator.client.gui;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.config.ConfigModel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Phase 8: the single shared configuration screen ARCHITECTURE.md §11/§15 call for.
 * Both the L-keybinding (AllTranslatorKeyBindings, common) and Mod Menu's "configure"
 * button (fabric AllTranslatorModMenuIntegration) open exactly this class, backed by
 * the same ConfigManager instance (AllTranslatorCore.configManager()) - there is no
 * separate "Mod Menu screen" any more (AllTranslatorModMenuPlaceholderScreen is
 * removed as part of this Phase).
 *
 * Screen/GuiGraphicsExtractor/Button/CycleButton/EditBox APIs verified against MC
 * 26.2's actual merged jar via javap (not guessed) - CLAUDE.md §3. In particular:
 * extractRenderState(GuiGraphicsExtractor, ...) replaces the pre-26.2 render(...),
 * and CycleButton/EditBox expose plain getValue()/getValue() accessors that let us
 * batch-apply changes on Done rather than mutating the live ConfigModel per keystroke.
 *
 * Layout: each field is a label row drawn in extractRenderState directly above its
 * widget (same X column, stacked vertically) rather than a label-left/widget-right
 * layout - a label-left layout was tried first and long labels (e.g. "Dynamic Text
 * Cache TTL (days)") visibly overlapped the widget column at common GUI scales, found
 * during manual testing. Stacking removes any label-length-dependent overlap risk.
 *
 * Toggle widgets (CycleButton) behave like vanilla's Options screens: changing them
 * is visible immediately in the widget itself, but nothing is written to config.json
 * (or reloaded into ApiManager) until Done is pressed or the screen is closed.
 *
 * Known limitation (documented, not a bug): editing memoryCacheCapacity /
 * dynamicTextCacheTtlDays here only updates config.json. CacheManager's actual
 * capacity-mutation API was not verified against source during this phase (out of
 * scope for Phase 8 - Config UI), so a live re-wire is intentionally not attempted;
 * the new values take effect on next launch. This mirrors the project's existing
 * practice of never invoking unverified APIs (CLAUDE.md §3/§21).
 */
public final class AllTranslatorConfigScreen extends Screen {

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
    private int apiCountLabelY;
    private int apiButtonY;

    private CycleButton<Boolean> translationEnabledButton;
    private EditBox targetLanguageBox;
    private CycleButton<Boolean> serverChatButton;
    private EditBox memoryCacheBox;
    private EditBox ttlBox;

    public AllTranslatorConfigScreen(Screen parent) {
        super(Component.literal("All Translator"));
        this.parent = parent;
        this.configManager = AllTranslatorCore.configManager();
        this.model = configManager.model();
    }

    @Override
    protected void init() {
        fieldX = this.width / 2 - 100;
        fieldWidth = 200;
        int y = 30;
        int blockHeight = 36;

        labelTranslationY = y;
        translationEnabledButton = CycleButton.onOffBuilder(model.translationEnabled)
                .create(fieldX, y + 11, fieldWidth, 20, Component.literal("Translation"));
        this.addRenderableWidget(translationEnabledButton);
        y += blockHeight;

        labelLanguageY = y;
        targetLanguageBox = new EditBox(this.font, fieldX, y + 11, fieldWidth, 20, Component.literal("Target Language"));
        targetLanguageBox.setMaxLength(16);
        targetLanguageBox.setHint(Component.literal("auto"));
        targetLanguageBox.setValue(model.forcedTargetLanguage == null ? "" : model.forcedTargetLanguage);
        this.addRenderableWidget(targetLanguageBox);
        y += blockHeight;

        labelServerChatY = y;
        serverChatButton = CycleButton.onOffBuilder(model.serverSideChatTranslationEnabled)
                .create(fieldX, y + 11, fieldWidth, 20, Component.literal("Server Chat"));
        this.addRenderableWidget(serverChatButton);
        y += blockHeight;

        labelMemCacheY = y;
        memoryCacheBox = new EditBox(this.font, fieldX, y + 11, fieldWidth, 20, Component.literal("Memory Cache Capacity"));
        memoryCacheBox.setMaxLength(6);
        memoryCacheBox.setValue(String.valueOf(model.memoryCacheCapacity));
        this.addRenderableWidget(memoryCacheBox);
        y += blockHeight;

        labelTtlY = y;
        ttlBox = new EditBox(this.font, fieldX, y + 11, fieldWidth, 20, Component.literal("Dynamic Text Cache TTL (days)"));
        ttlBox.setMaxLength(4);
        ttlBox.setValue(String.valueOf(model.dynamicTextCacheTtlDays));
        this.addRenderableWidget(ttlBox);
        y += blockHeight + 6;

        apiCountLabelY = y;
        apiButtonY = y + 12;
        this.addRenderableWidget(
                Button.builder(Component.literal("Manage Translation APIs"), button ->
                                this.minecraft.gui.setScreen(new AllTranslatorApiListScreen(this)))
                        .pos(this.width / 2 - 100, apiButtonY)
                        .size(200, 20)
                        .build());

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> onDone())
                        .pos(this.width / 2 - 75, this.height - 28)
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
            // Keep the previously-saved value; invalid input is silently discarded rather
            // than crashing the screen (CLAUDE.md has no numeric-validation UX guidance,
            // this is the conservative choice).
        }
        try {
            model.dynamicTextCacheTtlDays = Integer.parseInt(ttlBox.getValue().trim());
        } catch (NumberFormatException ignored) {
        }

        configManager.save();
    }

    @Override
    public void onClose() {
        applyToModel();
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
        super.extractRenderState(drawContext, mouseX, mouseY, delta);
        drawContext.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        drawContext.text(this.font, "Translation Enabled", fieldX, labelTranslationY, 0xFFAAAAAA);
        drawContext.text(this.font, "Target Language (blank = auto)", fieldX, labelLanguageY, 0xFFAAAAAA);
        drawContext.text(this.font, "Server-Side Chat Translation", fieldX, labelServerChatY, 0xFFAAAAAA);
        drawContext.text(this.font, "Memory Cache Capacity", fieldX, labelMemCacheY, 0xFFAAAAAA);
        drawContext.text(this.font, "Dynamic Text Cache TTL (days)", fieldX, labelTtlY, 0xFFAAAAAA);

        int apiCount = model.apis.size();
        drawContext.text(this.font, apiCount + " API configuration(s)", fieldX, apiCountLabelY, 0xFFAAAAAA);
    }
}
