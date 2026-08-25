package com.kiyo.alltranslator.compat.widgetframework;

import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import net.minecraft.client.gui.screens.Screen;

import java.util.regex.Pattern;

/**
 * Phase 13: extension point for mods that render their own Screen/Widget
 * implementation instead of vanilla AbstractWidget, so ScreenWidgetTranslationHook
 * (which only walks Screen#children() / AbstractWidget) cannot reach their text.
 *
 * Each implementation is entirely reflection-based (no compile-time dependency on
 * the target mod's classes - CLAUDE.md §3/§16: optional mod compat must not become
 * a hard dependency, and no Maven coordinate for these mods' exact dev-tested
 * versions could be confirmed with certainty). isApplicable(screen) MUST be cheap
 * and MUST NOT throw - implementations are responsible for guarding their own
 * Class.forName/reflection calls and failing closed (log once, return false/no-op)
 * if the target mod's API doesn't match what was verified via javap at
 * implementation time.
 *
 * Registered adapters are tried in order for every ScreenWidgetTranslationHook
 * tick; each adapter is expected to internally gate on its own
 * Platform.isModLoaded(...) check (or equivalent) so unrelated adapters are cheap
 * no-ops when their target mod isn't installed.
 */
public interface WidgetFrameworkCompat {

    /** Human-readable name for logging (e.g. "libIPN"). */
    String name();

    /** True if the target mod/framework is installed. Must be cheap; called every tick. */
    boolean isApplicable();

    /**
     * Attempt to translate the given screen's custom widget tree, if this
     * screen belongs to this framework. Must not throw - internal reflection
     * failures should be logged once (not spammed) and treated as a no-op.
     */
    void translateScreen(Screen screen, TranslatableTextInterceptor interceptor, Pattern noisePattern);
}
