package com.kiyo.alltranslator.client.gui;

import com.kiyo.alltranslator.config.ConfigModel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Phase 14: remote mirror of TranslationCategoriesScreen. Mutates the shared ConfigModel
 * instance directly (no network call of its own) - see RemoteServerConfigScreen's Javadoc for
 * why only that top-level screen's Done actually sends a Save payload to the server.
 *
 * Real-world follow-up fix (user request): brought up to date with every category toggle
 * TranslationCategoriesScreen (the local Mkey screen) already had - this remote mirror had
 * fallen behind (missing translateSystemMessages/translateAdvancementToasts/
 * translateRecipeToasts entirely) - and switched to the same two-column layout for the
 * same reason (too many toggles for one column without scrolling).
 */
public final class RemoteServerCategoriesScreen extends Screen {

    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int ROW_HEIGHT = 34;
    private static final int WIDTH = 190;
    private static final int COLUMN_GAP = 20;
    private static final int TOP_Y = 40;

    private final Screen parent;
    private final ConfigModel model;

    private CycleButton<Boolean> itemNamesButton;
    private CycleButton<Boolean> itemTooltipsButton;
    private CycleButton<Boolean> entityNamesButton;
    private CycleButton<Boolean> chatButton;
    private CycleButton<Boolean> systemMessagesButton;
    private CycleButton<Boolean> privateMessagesAndTitlesButton;
    private CycleButton<Boolean> advancementToastsButton;
    private CycleButton<Boolean> recipeToastsButton;
    private CycleButton<Boolean> otherScreensButton;

    public RemoteServerCategoriesScreen(Screen parent, ConfigModel model) {
        super(Component.translatable("gui.alltranslator.categories.title"));
        this.parent = parent;
        this.model = model;
    }

    @Override
    protected void init() {
        int leftX = this.width / 2 - WIDTH - COLUMN_GAP / 2;
        int rightX = this.width / 2 + COLUMN_GAP / 2;
        int leftY = TOP_Y;
        int rightY = TOP_Y;

        itemNamesButton = CycleButton.onOffBuilder(model.translateItemNames)
                .create(leftX, leftY, WIDTH, 20, Component.translatable("gui.alltranslator.categories.item_names"));
        this.addRenderableWidget(itemNamesButton);
        leftY += ROW_HEIGHT;

        itemTooltipsButton = CycleButton.onOffBuilder(model.translateItemTooltips)
                .create(leftX, leftY, WIDTH, 20, Component.translatable("gui.alltranslator.categories.item_tooltips"));
        this.addRenderableWidget(itemTooltipsButton);
        leftY += ROW_HEIGHT;

        entityNamesButton = CycleButton.onOffBuilder(model.translateEntityNames)
                .create(leftX, leftY, WIDTH, 20, Component.translatable("gui.alltranslator.categories.entity_names"));
        this.addRenderableWidget(entityNamesButton);
        leftY += ROW_HEIGHT;

        chatButton = CycleButton.onOffBuilder(model.translateChat)
                .create(leftX, leftY, WIDTH, 20, Component.translatable("gui.alltranslator.categories.chat"));
        this.addRenderableWidget(chatButton);
        leftY += ROW_HEIGHT;

        systemMessagesButton = CycleButton.onOffBuilder(model.translateSystemMessages)
                .create(leftX, leftY, WIDTH, 20, Component.translatable("gui.alltranslator.categories.system_messages"));
        this.addRenderableWidget(systemMessagesButton);
        leftY += ROW_HEIGHT;

        privateMessagesAndTitlesButton = CycleButton.onOffBuilder(model.translatePrivateMessagesAndTitles)
                .create(rightX, rightY, WIDTH, 20, Component.translatable("gui.alltranslator.categories.private_messages_and_titles"));
        this.addRenderableWidget(privateMessagesAndTitlesButton);
        rightY += ROW_HEIGHT;

        advancementToastsButton = CycleButton.onOffBuilder(model.translateAdvancementToasts)
                .create(rightX, rightY, WIDTH, 20, Component.translatable("gui.alltranslator.categories.advancement_toasts"));
        this.addRenderableWidget(advancementToastsButton);
        rightY += ROW_HEIGHT;

        recipeToastsButton = CycleButton.onOffBuilder(model.translateRecipeToasts)
                .create(rightX, rightY, WIDTH, 20, Component.translatable("gui.alltranslator.categories.recipe_toasts"));
        this.addRenderableWidget(recipeToastsButton);
        rightY += ROW_HEIGHT;

        otherScreensButton = CycleButton.onOffBuilder(model.translateOtherModScreens)
                .create(rightX, rightY, WIDTH, 20, Component.translatable("gui.alltranslator.categories.other_screens"));
        this.addRenderableWidget(otherScreensButton);
        rightY += ROW_HEIGHT;

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
        model.translateItemNames = itemNamesButton.getValue();
        model.translateItemTooltips = itemTooltipsButton.getValue();
        model.translateEntityNames = entityNamesButton.getValue();
        model.translateChat = chatButton.getValue();
        model.translateSystemMessages = systemMessagesButton.getValue();
        model.translatePrivateMessagesAndTitles = privateMessagesAndTitlesButton.getValue();
        model.translateAdvancementToasts = advancementToastsButton.getValue();
        model.translateRecipeToasts = recipeToastsButton.getValue();
        model.translateOtherModScreens = otherScreensButton.getValue();
    }

    @Override
    public void onClose() {
        onDone();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
        super.extractRenderState(drawContext, mouseX, mouseY, delta);
        drawContext.centeredText(this.font, this.title, this.width / 2, 8, LABEL_COLOR);
    }
}
