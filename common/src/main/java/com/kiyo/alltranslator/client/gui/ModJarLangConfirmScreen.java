package com.kiyo.alltranslator.client.gui;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.modjarlang.ModJarLangPending;
import com.kiyo.alltranslator.modjarlang.ModJarLangTranslationCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in confirmation for bulk translation of other mods' bundled en_us.json files.
 * One screen, three phases: CONFIRM (scrollable checkbox list) -> RUNNING (per-mod progress,
 * mods translated one after another) -> DONE (optional resource reload).
 *
 * Mods the user unchecks are remembered in ConfigModel#modJarLangIgnoredMods so they are not
 * offered again automatically. Translation itself is asynchronous (never blocks the render
 * thread); the async side only updates the atomic/volatile fields read here every frame.
 */
public final class ModJarLangConfirmScreen extends Screen {

    private enum Phase { CONFIRM, RUNNING, DONE }

    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int SUBTLE_COLOR = 0xFFAAAAAA;
    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 56;
    private static final int FOOTER_HEIGHT = 56;

    private final Screen parent;
    private final String targetLang;
    private final List<ModJarLangPending> pending;
    private final Set<String> selected = new LinkedHashSet<>();

    private volatile Phase phase = Phase.CONFIRM;
    private volatile boolean cancelRequested = false;
    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger generated = new AtomicInteger();
    private int totalToRun = 0;

    private ModList list;
    private Button selectAllButton;
    private Button selectNoneButton;
    private Button translateButton;
    private Button laterButton;
    private Button neverButton;
    private Button cancelButton;
    private Button reloadButton;
    private Button closeButton;

    public ModJarLangConfirmScreen(Screen parent, String targetLang, List<ModJarLangPending> pending) {
        super(Component.translatable("gui.alltranslator.modjarlang.title"));
        this.parent = parent;
        this.targetLang = targetLang;
        this.pending = pending;
        for (ModJarLangPending p : pending) {
            selected.add(p.modId());
        }
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int listWidth = Math.min(320, this.width - 20);
        int listHeight = Math.max(40, this.height - LIST_TOP - FOOTER_HEIGHT);
        int row1 = this.height - 52;
        int row2 = this.height - 28;

        list = new ModList(this.minecraft, listWidth, listHeight, LIST_TOP);
        list.setX(cx - listWidth / 2);
        this.addRenderableWidget(list);

        selectAllButton = Button.builder(Component.translatable("gui.alltranslator.modjarlang.select_all"),
                b -> setAllSelected(true)).pos(cx - 104, row1).size(100, 20).build();
        this.addRenderableWidget(selectAllButton);

        selectNoneButton = Button.builder(Component.translatable("gui.alltranslator.modjarlang.select_none"),
                b -> setAllSelected(false)).pos(cx + 4, row1).size(100, 20).build();
        this.addRenderableWidget(selectNoneButton);

        translateButton = Button.builder(Component.empty(), b -> startTranslation())
                .pos(cx - 179, row2).size(150, 20).build();
        this.addRenderableWidget(translateButton);

        laterButton = Button.builder(Component.translatable("gui.alltranslator.modjarlang.later"),
                b -> closeScreen()).pos(cx - 25, row2).size(100, 20).build();
        this.addRenderableWidget(laterButton);

        neverButton = Button.builder(Component.translatable("gui.alltranslator.modjarlang.never"),
                b -> onNever()).pos(cx + 79, row2).size(100, 20).build();
        this.addRenderableWidget(neverButton);

        cancelButton = Button.builder(Component.translatable("gui.alltranslator.modjarlang.cancel"),
                b -> cancelRequested = true).pos(cx - 75, row2).size(150, 20).build();
        this.addRenderableWidget(cancelButton);

        reloadButton = Button.builder(Component.translatable("gui.alltranslator.modjarlang.reload"),
                b -> applyAndReload()).pos(cx - 154, row2).size(150, 20).build();
        this.addRenderableWidget(reloadButton);

        closeButton = Button.builder(CommonComponents.GUI_DONE, b -> closeScreen())
                .pos(cx + 4, row2).size(150, 20).build();
        this.addRenderableWidget(closeButton);

        list.replaceEntries(buildEntries());
        refreshTranslateButton();
        syncWidgetVisibility();
    }

    private List<ModEntry> buildEntries() {
        List<ModEntry> entries = new ArrayList<>(pending.size());
        for (ModJarLangPending p : pending) {
            entries.add(new ModEntry(p));
        }
        return entries;
    }

    private void setAllSelected(boolean value) {
        selected.clear();
        if (value) {
            for (ModJarLangPending p : pending) {
                selected.add(p.modId());
            }
        }
        // Checkbox has no public setter for its state, so rebuild the rows from `selected`.
        list.replaceEntries(buildEntries());
        refreshTranslateButton();
    }

    private void refreshTranslateButton() {
        if (translateButton == null) {
            return;
        }
        translateButton.setMessage(Component.translatable(
                "gui.alltranslator.modjarlang.translate_selected", selected.size()));
        translateButton.active = !selected.isEmpty();
    }

    private void syncWidgetVisibility() {
        boolean confirm = phase == Phase.CONFIRM;
        boolean running = phase == Phase.RUNNING;
        boolean done = phase == Phase.DONE;
        list.visible = confirm;
        list.active = confirm;
        selectAllButton.visible = confirm;
        selectNoneButton.visible = confirm;
        translateButton.visible = confirm;
        laterButton.visible = confirm;
        neverButton.visible = confirm;
        cancelButton.visible = running;
        reloadButton.visible = done && generated.get() > 0;
        closeButton.visible = done;
    }

    private void onNever() {
        AllTranslatorCore.configManager().model().modJarLangPromptEnabled = false;
        AllTranslatorCore.configManager().save();
        closeScreen();
    }

    private void startTranslation() {
        List<ModJarLangPending> todo = new ArrayList<>();
        ConfigModel model = AllTranslatorCore.configManager().model();
        for (ModJarLangPending p : pending) {
            if (selected.contains(p.modId())) {
                todo.add(p);
            } else if (!model.modJarLangIgnoredMods.contains(p.modId())) {
                model.modJarLangIgnoredMods.add(p.modId()); // unchecked = do not offer again automatically
            }
        }
        AllTranslatorCore.configManager().save();
        if (todo.isEmpty()) {
            closeScreen();
            return;
        }

        totalToRun = todo.size();
        processed.set(0);
        generated.set(0);
        phase = Phase.RUNNING;

        ModJarLangTranslationCoordinator coordinator = AllTranslatorCore.modJarLangTranslationCoordinator();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ModJarLangPending p : todo) {
            chain = chain.thenCompose(v -> {
                if (cancelRequested) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                return coordinator.translateIfNeeded(p.candidate(), targetLang)
                        .<Void>handle((written, ex) -> {
                            if (ex == null && Boolean.TRUE.equals(written)) {
                                generated.incrementAndGet();
                            }
                            processed.incrementAndGet();
                            return null;
                        });
            });
        }
        chain.whenComplete((v, ex) -> phase = Phase.DONE);
    }

    private void applyAndReload() {
        Minecraft mc = this.minecraft;
        closeScreen();
        mc.reloadResourcePacks();
    }

    private void closeScreen() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        closeScreen();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        syncWidgetVisibility();
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        g.centeredText(this.font, this.title, cx, 8, LABEL_COLOR);
        switch (phase) {
            case CONFIRM -> {
                int y = 22;
                for (FormattedCharSequence line : this.font.split(
                        Component.translatable("gui.alltranslator.modjarlang.description"), this.width - 40)) {
                    g.centeredText(this.font, line, cx, y, SUBTLE_COLOR);
                    y += 10;
                }
            }
            case RUNNING -> g.centeredText(this.font, Component.translatable(
                    "gui.alltranslator.modjarlang.progress", processed.get(), totalToRun),
                    cx, this.height / 2 - 10, LABEL_COLOR);
            case DONE -> g.centeredText(this.font, Component.translatable(
                    "gui.alltranslator.modjarlang.done", generated.get(), totalToRun - generated.get()),
                    cx, this.height / 2 - 10, LABEL_COLOR);
        }
    }

    private final class ModList extends ContainerObjectSelectionList<ModEntry> {
        ModList(Minecraft mc, int width, int height, int y) {
            super(mc, width, height, y, ROW_HEIGHT);
        }
    }

    private final class ModEntry extends ContainerObjectSelectionList.Entry<ModEntry> {
        private final Checkbox checkbox;

        ModEntry(ModJarLangPending item) {
            String modId = item.modId();
            Component label = Component.literal(modId + " (" + item.keyCount() + ")");
            this.checkbox = Checkbox.builder(label, ModJarLangConfirmScreen.this.font)
                    .selected(selected.contains(modId))
                    .onValueChange((box, value) -> {
                        if (value) {
                            selected.add(modId);
                        } else {
                            selected.remove(modId);
                        }
                        refreshTranslateButton();
                    })
                    .build();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
            checkbox.setX(getContentX());
            checkbox.setY(getContentY());
            checkbox.extractRenderState(g, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(checkbox);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(checkbox);
        }
    }
}
