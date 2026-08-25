package com.kiyo.alltranslator.client.gui;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.config.CredentialStore;
import com.kiyo.alltranslator.service.ApiManager;
import com.kiyo.alltranslator.service.ApiState;
import com.kiyo.alltranslator.service.TranslationApiConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 8: API list management (ARCHITECTURE.md §11 - add/edit/remove/enable-disable/
 * priority/status). Deliberately implemented as a plain paginated button stack rather
 * than ContainerObjectSelectionList/AbstractSelectionList: this project's source-first
 * rule (CLAUDE.md §3) means every API surface used here must be verified via javap
 * first, and AbstractSelectionList$Entry's rendering/interaction contract was not
 * verified during this phase. A fixed-size page of plain Buttons uses only APIs
 * already verified for AllTranslatorConfigScreen, at the cost of a "Prev/Next" control
 * instead of a scrollbar for large API counts (acceptable: CLAUDE.md's own §5 example
 * shows 2-3 APIs as the typical case).
 */
public final class AllTranslatorApiListScreen extends Screen {

    private static final int PAGE_SIZE = 6;
    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private final ConfigManager configManager;
    private final ApiManager apiManager;
    private final CredentialStore credentialStore;

    private int page = 0;
    private List<TranslationApiConfig> sorted;

    public AllTranslatorApiListScreen(Screen parent) {
        super(Component.translatable("gui.alltranslator.apilist.title"));
        this.parent = parent;
        this.configManager = AllTranslatorCore.configManager();
        this.apiManager = AllTranslatorCore.apiManager();
        this.credentialStore = AllTranslatorCore.credentialStore();
    }

    /** Public wrapper so other screens in this package (e.g. ApiEditScreen) can
     *  request a refresh without needing protected access to Screen#rebuildWidgets()
     *  itself - Java only grants that to same-package code when the *declaring* class
     *  (net.minecraft.client.gui.screens.Screen) is in a different package, which it
     *  is here. */
    public void refresh() {
        this.rebuildWidgets();
    }

    @Override
    protected void init() {
        ConfigModel model = configManager.model();
        sorted = new ArrayList<>(model.apis);
        sorted.sort(Comparator.comparingInt(TranslationApiConfig::priority));

        int maxPage = Math.max(0, (sorted.size() - 1) / PAGE_SIZE);
        if (page > maxPage) page = maxPage;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, sorted.size());

        int rowLabelX = 20;
        int rowLabelWidth = this.width - 240;
        int upX = this.width - 210;
        int downX = this.width - 186;
        int toggleX = this.width - 160;
        int removeX = this.width - 90;
        int y = 30;

        for (int i = start; i < end; i++) {
            final int index = i;
            TranslationApiConfig cfg = sorted.get(index);
            int rowY = y;

            this.addRenderableWidget(
                    Button.builder(rowLabel(cfg), button ->
                                    this.minecraft.gui.setScreen(new ApiEditScreen(this, cfg)))
                            .pos(rowLabelX, rowY)
                            .size(rowLabelWidth, 20)
                            .build());

            boolean canMoveUp = index > 0;
            boolean canMoveDown = index < sorted.size() - 1;

            this.addRenderableWidget(
                    Button.builder(Component.literal("^"), button -> swapPriority(index, index - 1))
                            .pos(upX, rowY)
                            .size(20, 20)
                            .build())
                    .active = canMoveUp;

            this.addRenderableWidget(
                    Button.builder(Component.literal("v"), button -> swapPriority(index, index + 1))
                            .pos(downX, rowY)
                            .size(20, 20)
                            .build())
                    .active = canMoveDown;

            this.addRenderableWidget(
                    Button.builder(Component.translatable(cfg.enabled() ? "gui.alltranslator.apilist.disable" : "gui.alltranslator.apilist.enable"), button -> {
                                cfg.setEnabled(!cfg.enabled());
                                persistAndReload();
                                this.rebuildWidgets();
                            })
                            .pos(toggleX, rowY)
                            .size(66, 20)
                            .build());

            this.addRenderableWidget(
                    Button.builder(Component.translatable("gui.alltranslator.apilist.remove"), button -> {
                                configManager.model().apis.remove(cfg);
                                if (cfg.credentialId() != null) {
                                    credentialStore.removeKey(cfg.credentialId());
                                }
                                persistAndReload();
                                this.rebuildWidgets();
                            })
                            .pos(removeX, rowY)
                            .size(66, 20)
                            .build());

            y += ROW_HEIGHT;
        }

        int bottomY = this.height - 54;
        Button prevButton = this.addRenderableWidget(
                Button.builder(Component.translatable("gui.alltranslator.apilist.prev"), button -> {
                            page--;
                            this.rebuildWidgets();
                        })
                        .pos(this.width / 2 - 105, bottomY)
                        .size(70, 20)
                        .build());
        prevButton.active = page > 0;

        Button nextButton = this.addRenderableWidget(
                Button.builder(Component.translatable("gui.alltranslator.apilist.next"), button -> {
                            page++;
                            this.rebuildWidgets();
                        })
                        .pos(this.width / 2 + 35, bottomY)
                        .size(70, 20)
                        .build());
        nextButton.active = page < maxPage;

        this.addRenderableWidget(
                Button.builder(Component.translatable("gui.alltranslator.apilist.add_api"), button ->
                                this.minecraft.gui.setScreen(new ApiEditScreen(this, null)))
                        .pos(this.width / 2 - 205, bottomY)
                        .size(90, 20)
                        .build());

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_BACK, button -> this.minecraft.gui.setScreen(parent))
                        .pos(this.width / 2 + 115, bottomY)
                        .size(90, 20)
                        .build());

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> this.minecraft.gui.setScreen(parent))
                        .pos(this.width / 2 - 75, this.height - 28)
                        .size(150, 20)
                        .build());
    }

    private Component rowLabel(TranslationApiConfig cfg) {
        ApiState state = apiManager.getState(cfg.id());
        String status = state == null ? "UNINITIALIZED" : state.status().name();
        String enabled = cfg.enabled() ? "" : " (disabled)";
        return Component.literal(
                cfg.displayName() + "  [" + cfg.provider() + "]  priority " + cfg.priority()
                        + "  - " + status + enabled);
    }

    private void swapPriority(int indexA, int indexB) {
        TranslationApiConfig a = sorted.get(indexA);
        TranslationApiConfig b = sorted.get(indexB);
        int priorityA = a.priority();
        a.setPriority(b.priority());
        b.setPriority(priorityA);
        persistAndReload();
        this.rebuildWidgets();
    }

    private void persistAndReload() {
        configManager.save();
        apiManager.reload(configManager.model().apis);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
        super.extractRenderState(drawContext, mouseX, mouseY, delta);
        drawContext.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        if (sorted.isEmpty()) {
            drawContext.centeredText(this.font, Component.translatable("gui.alltranslator.apilist.no_apis"),
                    this.width / 2, this.height / 2 - 30, 0xFFFFFFFF);
        }
    }
}
