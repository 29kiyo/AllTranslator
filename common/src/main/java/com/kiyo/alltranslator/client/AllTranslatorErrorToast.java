package com.kiyo.alltranslator.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Phase 13: shows a recipe-unlocked-style toast (top-right) when a translation
 * API call fails, naming which API failed. Verified against the MC 26.2 merged jar
 * (CLAUDE.md §3).
 *
 * extractRenderState layout/coloring exactly mirrors vanilla SystemToast's own
 * extractRenderState (javap -c -v confirmed byte-for-byte, both fields and the
 * bytecode of extractTextLines/extractRenderState):
 *  - Background: blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, 0, 0,
 *    width(), height()) BEFORE any text (SystemToast's own toast/system.png sprite
 *    reused, not a new custom one).
 *  - Text X is always 18 (SystemToast.TEXT_X_START, a private static final int -
 *    read via javap -v's ConstantValue attribute since javap -c doesn't print
 *    static final primitive values directly). Earlier revisions of this class used
 *    X=8, which overlapped the sprite's built-in "!" icon glyph - that's the
 *    left-side icon baked into toast/system.png itself, not something drawn
 *    separately by this class or SystemToast.
 *  - Title Y is 7 when a message is present (12 when title-only - not used here,
 *    this toast always has a message). Title color is always -256 (0xFFFFFF00,
 *    vanilla's toast title yellow).
 *  - Message Y is 7 + titleLines.size()*12 (one title line here, so Y=19).
 *    Message color is always -1 (0xFFFFFFFF, white) - the earlier revision's
 *    custom dark colors (0xFF552222 / 0xFF000000) were low-contrast against the
 *    toast/system.png background and are why the text was hard to read; both are
 *    now vanilla's own toast colors instead of guessed ones.
 *  - Line spacing between wrapped lines (SystemToast.LINE_SPACING) is 12; not
 *    applicable here since title/message are each rendered as a single line, but
 *    documented for consistency should multi-line support be added later.
 *
 * Other verified details (unchanged from the previous revision):
 *  - Toast#getSoundEvent()'s default implementation (javap -c confirmed) returns
 *    null - a Toast is silent unless overridden. "sound off" maps to that default;
 *    "sound on" returns SoundEvents.EXPERIENCE_ORB_PICKUP.
 *  - ToastManager obtained via Minecraft#gui (Gui) -> Gui#toastManager()
 *    (javap-confirmed).
 *  - title/message use Component.translatable (toast.alltranslator.title /
 *    toast.alltranslator.api_failure) so this mod's own lang files (including
 *    user-supplied config/alltranslator/lang/<code>.json) apply, same as
 *    AllTranslatorConfigScreen's labels - no API translation involved.
 */
public final class AllTranslatorErrorToast implements Toast {

    private static final Identifier BACKGROUND_SPRITE = Identifier.withDefaultNamespace("toast/system");
    private static final int TEXT_X_START = 18;
    private static final int TITLE_COLOR = -256;   // 0xFFFFFF00, vanilla SystemToast title yellow
    private static final int MESSAGE_COLOR = -1;   // 0xFFFFFFFF, vanilla SystemToast message white
    private static final int DISPLAY_TIME_MS = 5000;
    private static final int WIDTH = 160;
    private static final int HEIGHT = 32;

    private final Component title;
    private final Component message;
    private final boolean soundEnabled;
    private long firstDrawTime = -1L;

    public AllTranslatorErrorToast(String apiDisplayName, String reasonText, boolean soundEnabled) {
        this.title = Component.translatable("toast.alltranslator.title");
        this.message = Component.translatable("toast.alltranslator.api_failure", apiDisplayName, reasonText);
        this.soundEnabled = soundEnabled;
    }

    /** Convenience entrypoint: builds and enqueues the toast in one call. */
    public static void show(ToastManager toastManager, String apiDisplayName, String reasonText, boolean soundEnabled) {
        toastManager.addToast(new AllTranslatorErrorToast(apiDisplayName, reasonText, soundEnabled));
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    @Override
    public Visibility getWantedVisibility() {
        if (firstDrawTime < 0) return Visibility.SHOW;
        return System.currentTimeMillis() - firstDrawTime < DISPLAY_TIME_MS ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override
    public void update(ToastManager toastManager, long ignoredTicks) {
        // No per-tick state needed beyond the wall-clock timer in getWantedVisibility().
    }

    @Override
    public SoundEvent getSoundEvent() {
        return soundEnabled ? SoundEvents.EXPERIENCE_ORB_PICKUP : null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, Font font, long ignoredTime) {
        if (firstDrawTime < 0) firstDrawTime = System.currentTimeMillis();
        drawContext.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, 0, 0, width(), height());
        // Single title line + single message line, so titleLines.size() == 1 in
        // vanilla's own Y formula (7 + titleLines.size()*12 == 19).
        drawContext.text(font, title, TEXT_X_START, 7, TITLE_COLOR, false);
        drawContext.text(font, message, TEXT_X_START, 19, MESSAGE_COLOR, false);
    }
}
