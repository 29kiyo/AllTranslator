package com.kiyo.alltranslator.fabric.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Phase 7: minimal placeholder shown from Mod Menu's "configure" button.
 *
 * ARCHITECTURE.md §11/§15 call for this to open the SAME AllTranslatorConfigScreen
 * instance the L-keybinding will eventually open, backed by the shared ConfigManager.
 * That real config screen doesn't exist yet (Phase 8 - CLAUDE.md §24 forbids starting
 * future phases early), so this is an honest, minimal stand-in: it just confirms the
 * mod is loaded and points at manual config.json editing, and will be replaced outright
 * by AllTranslatorConfigScreen when Phase 8 implements it (this class can then be
 * deleted).
 *
 * Screen/Button/GuiGraphicsExtractor API here was verified against MC 26.2's actual
 * merged jar (javap) and against Mod Menu's own ModsScreen.java source (a real,
 * working MC 26.2 Screen implementation) - not guessed. MC 26.2 replaced the familiar
 * render(GuiGraphics, ...) with extractRenderState(GuiGraphicsExtractor, ...).
 */
public final class AllTranslatorModMenuPlaceholderScreen extends Screen {

    private final Screen parent;

    public AllTranslatorModMenuPlaceholderScreen(Screen parent) {
        super(Component.literal("All Translator"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> this.minecraft.gui.setScreen(parent))
                        .pos(this.width / 2 - 75, this.height - 28)
                        .size(150, 20)
                        .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, int mouseX, int mouseY, float delta) {
        super.extractRenderState(drawContext, mouseX, mouseY, delta);
        drawContext.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        drawContext.centeredText(this.font,
                Component.literal("A full configuration screen is coming in a future update."),
                this.width / 2, this.height / 2 - 10, 0xFFAAAAAA);
        drawContext.centeredText(this.font,
                Component.literal("For now, edit config/alltranslator/config.json directly."),
                this.width / 2, this.height / 2 + 4, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
