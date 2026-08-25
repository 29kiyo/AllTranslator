package com.kiyo.alltranslator.compat.libipn;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.compat.widgetframework.WidgetFrameworkCompat;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import dev.architectury.platform.Platform;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase 13: best-effort translation of libIPN (org.anti_ad.mc)-based config
 * screens' text-bearing widgets (buttons, labels). Covers ANY mod built on the
 * same shared GUI library (Inventory Profiles Next and any other mod using
 * libIPN), not just libIPN's own screens, since they all share libIPN's
 * BaseScreen/Widget classes.
 *
 * IMPORTANT (per user direction): this is a pure runtime-reflection integration.
 * No compile-time dependency on libIPN is added anywhere in the project (no jar
 * committed, no build.gradle change) - this class compiles and the mod builds
 * identically whether or not libIPN is available at build time. All class/method
 * resolution happens lazily at runtime, gated behind Platform.isModLoaded("libipn").
 *
 * CLAUDE.md §3 (source-first): every reflected class/method name below
 * (org.anti_ad.mc.common.gui.screen.BaseScreen#getRootWidget,
 * org.anti_ad.mc.common.gui.widgets.Widget#getText/setText/getChildren,
 * org.anti_ad.mc.common.gui.widgets.glue.ITextFieldWidget) was verified via javap
 * against the actual libIPN-fabric-26.2-6.8.3.jar dev-environment jar before this
 * class was written - none of these signatures are guessed.
 *
 * Deliberately does NOT touch editable text fields: libIPN keeps a widget's
 * user-editable value on a wholly separate property
 * (ITextFieldWidget#getVanillaText/setVanillaText, backed by a real vanilla
 * EditBox), never on Widget#getText/setText (verified via javap on
 * TextFieldWidget/ConfigStringWidget). This class still defensively skips any
 * widget implementing ITextFieldWidget, so a live search box (or any future
 * text-input field) can never have its in-progress text sent to a translation
 * API or overwritten mid-edit.
 *
 * Failure handling: if libIPN is present but its actual API shape doesn't match
 * what was reflected here (e.g. a future libIPN release renames/removes a
 * method), reflection setup fails once at first use, is logged once at WARN, and
 * this adapter becomes a permanent no-op for the rest of the session - it never
 * repeatedly throws/logs on every tick.
 *
 * Known limitation (best-effort, not exhaustive): only walks the widget tree
 * reachable via Widget#getChildren() from BaseScreen#getRootWidget(). If a
 * libIPN-based screen renders some content outside that tree (e.g. a virtualized
 * list widget that only materializes visible rows on render), those rows may not
 * be reached.
 */
public final class LibIpnCompat implements WidgetFrameworkCompat {

    private static final String MOD_ID = "libipn";
    private static final int MAX_WIDGET_DEPTH = 64;

    private static final String BASE_SCREEN_CLASS = "org.anti_ad.mc.common.gui.screen.BaseScreen";
    private static final String WIDGET_CLASS = "org.anti_ad.mc.common.gui.widgets.Widget";
    private static final String TEXT_FIELD_WIDGET_CLASS = "org.anti_ad.mc.common.gui.widgets.glue.ITextFieldWidget";

    private boolean reflectionReady;
    private boolean reflectionFailed;

    private Class<?> baseScreenClass;
    private Class<?> widgetClass;
    private Class<?> textFieldWidgetClass;
    private MethodHandle getRootWidget;
    private MethodHandle getText;
    private MethodHandle setText;
    private MethodHandle getChildren;

    @Override
    public String name() {
        return "libIPN";
    }

    @Override
    public boolean isApplicable() {
        return Platform.isModLoaded(MOD_ID);
    }

    @Override
    public void translateScreen(Screen screen, TranslatableTextInterceptor interceptor, Pattern noisePattern) {
        if (reflectionFailed) {
            return;
        }
        if (!reflectionReady) {
            if (!setupReflection()) {
                return;
            }
        }
        if (!baseScreenClass.isInstance(screen)) {
            return;
        }
        try {
            Object rootWidget = getRootWidget.invoke(screen);
            walk(rootWidget, interceptor, noisePattern, 0);
        } catch (Throwable t) {
            AllTranslator.LOGGER.warn(
                    "libIPN compat: unexpected error walking widget tree, disabling for this session", t);
            reflectionFailed = true;
        }
    }

    private void walk(Object widget, TranslatableTextInterceptor interceptor,
                       Pattern noisePattern, int depth) throws Throwable {
        if (widget == null || depth > MAX_WIDGET_DEPTH) {
            return;
        }
        if (!textFieldWidgetClass.isInstance(widget)) {
            String plain = (String) getText.invoke(widget);
            if (plain != null && !plain.isBlank() && !noisePattern.matcher(plain.trim()).matches()) {
                Component original = Component.literal(plain);
                Component translated = interceptor.intercept(original, null);
                if (translated != original) {
                    setText.invoke(widget, translated.getString());
                }
            }
        }
        @SuppressWarnings("unchecked")
        List<Object> children = (List<Object>) getChildren.invoke(widget);
        if (children != null) {
            for (Object child : children) {
                walk(child, interceptor, noisePattern, depth + 1);
            }
        }
    }

    private boolean setupReflection() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            baseScreenClass = Class.forName(BASE_SCREEN_CLASS);
            widgetClass = Class.forName(WIDGET_CLASS);
            textFieldWidgetClass = Class.forName(TEXT_FIELD_WIDGET_CLASS);

            Method getRootWidgetMethod = baseScreenClass.getMethod("getRootWidget");
            Method getTextMethod = widgetClass.getMethod("getText");
            Method setTextMethod = widgetClass.getMethod("setText", String.class);
            Method getChildrenMethod = widgetClass.getMethod("getChildren");

            getRootWidget = lookup.unreflect(getRootWidgetMethod);
            getText = lookup.unreflect(getTextMethod);
            setText = lookup.unreflect(setTextMethod);
            getChildren = lookup.unreflect(getChildrenMethod);

            reflectionReady = true;
            AllTranslator.LOGGER.info("libIPN detected; screen widget translation compat enabled.");
            return true;
        } catch (Throwable t) {
            AllTranslator.LOGGER.warn(
                    "libIPN detected, but its API did not match what All Translator expects "
                            + "(CLAUDE.md §3: not guessing at an unverified API) - "
                            + "libIPN screen translation will be unavailable this session.", t);
            reflectionFailed = true;
            return false;
        }
    }
}
