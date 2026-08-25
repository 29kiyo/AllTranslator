package com.kiyo.alltranslator.compat.uilib;

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
 * Phase 13: best-effort translation of "UI Lib" (com.daqem.uilib, modid "uilib")
 * -based screens' text-bearing components (labels, buttons that route through
 * AbstractTextComponent). Covers any mod built on UI Lib's IComponent tree, not
 * just UI Lib's own screens.
 *
 * Pure runtime-reflection integration, same discipline as LibIpnCompat: no
 * compile-time dependency on UI Lib is added anywhere in the project (no jar
 * committed, no build.gradle change). All class/method resolution happens
 * lazily at runtime, gated behind Platform.isModLoaded("uilib").
 *
 * CLAUDE.md §3 (source-first): every reflected class/method name below
 * (com.daqem.uilib.gui.AbstractScreen#getComponents,
 * com.daqem.uilib.gui.component.AbstractComponent#getComponents,
 * com.daqem.uilib.gui.component.text.AbstractTextComponent#getText/setText) was
 * verified via javap against the actual uilib-fabric-21.1.1.jar dev-environment
 * jar before this class was written - none of these signatures are guessed.
 *
 * Deliberately does NOT touch editable text fields: UI Lib's EditBoxWidget
 * extends vanilla EditBox directly and is a wholly separate class hierarchy from
 * AbstractTextComponent (verified via javap) - it is never an instance of
 * AbstractTextComponent, so it is structurally unreachable by the
 * instanceof-gated walk below, without needing an explicit exclusion check.
 *
 * Only PUBLIC methods are used (getComponents()/getText()/setText(Component) are
 * all public on AbstractComponent/AbstractTextComponent) - no reflection into
 * protected/private fields, consistent with the project's standing rule that
 * this class of optional-mod compat must not depend on non-public internals
 * (see MaLiLib and owo-lib, which were both investigated and rejected for this
 * exact reason during Phase 13).
 *
 * Failure handling: if UI Lib is present but its actual API shape doesn't match
 * what was reflected here, reflection setup fails once at first use, is logged
 * once at WARN, and this adapter becomes a permanent no-op for the rest of the
 * session.
 *
 * Known limitation (best-effort): only walks IComponent#getComponents() /
 * IWidget children reachable from AbstractScreen#getComponents(). Vanilla
 * AbstractWidget-based UI Lib widgets (e.g. ButtonWidget, which extends vanilla
 * Button) are NOT walked here - they are already covered by
 * ScreenWidgetTranslationHook's existing Screen#children()/AbstractWidget loop,
 * since UI Lib's AbstractScreen#children() (inherited from vanilla Screen) still
 * includes them.
 */
public final class UiLibCompat implements WidgetFrameworkCompat {

    private static final String MOD_ID = "uilib";
    private static final int MAX_DEPTH = 64;

    private static final String ABSTRACT_SCREEN_CLASS = "com.daqem.uilib.gui.AbstractScreen";
    private static final String ICOMPONENT_CLASS = "com.daqem.uilib.api.component.IComponent";
    private static final String ABSTRACT_TEXT_COMPONENT_CLASS = "com.daqem.uilib.gui.component.text.AbstractTextComponent";

    private boolean reflectionReady;
    private boolean reflectionFailed;

    private Class<?> abstractScreenClass;
    private Class<?> abstractTextComponentClass;
    private MethodHandle screenGetComponents;
    private MethodHandle componentGetComponents;
    private MethodHandle getText;
    private MethodHandle setText;

    @Override
    public String name() {
        return "UI Lib";
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
        if (!abstractScreenClass.isInstance(screen)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object> topLevel = (List<Object>) screenGetComponents.invoke(screen);
            if (topLevel != null) {
                for (Object component : topLevel) {
                    walk(component, interceptor, noisePattern, 0);
                }
            }
        } catch (Throwable t) {
            AllTranslator.LOGGER.warn(
                    "UI Lib compat: unexpected error walking component tree, disabling for this session", t);
            reflectionFailed = true;
        }
    }

    private void walk(Object component, TranslatableTextInterceptor interceptor,
                       Pattern noisePattern, int depth) throws Throwable {
        if (component == null || depth > MAX_DEPTH) {
            return;
        }
        if (abstractTextComponentClass.isInstance(component)) {
            Object rawText = getText.invoke(component);
            if (rawText instanceof Component original) {
                String plain = original.getString();
                if (plain != null && !plain.isBlank() && !noisePattern.matcher(plain.trim()).matches()) {
                    Component translated = interceptor.intercept(original, null);
                    if (translated != original) {
                        setText.invoke(component, translated);
                    }
                }
            }
        }
        @SuppressWarnings("unchecked")
        List<Object> children = (List<Object>) componentGetComponents.invoke(component);
        if (children != null) {
            for (Object child : children) {
                walk(child, interceptor, noisePattern, depth + 1);
            }
        }
    }

    private boolean setupReflection() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            abstractScreenClass = Class.forName(ABSTRACT_SCREEN_CLASS);
            Class<?> iComponentClass = Class.forName(ICOMPONENT_CLASS);
            abstractTextComponentClass = Class.forName(ABSTRACT_TEXT_COMPONENT_CLASS);

            Method screenGetComponentsMethod = abstractScreenClass.getMethod("getComponents");
            Method componentGetComponentsMethod = iComponentClass.getMethod("getComponents");
            Method getTextMethod = abstractTextComponentClass.getMethod("getText");
            Method setTextMethod = abstractTextComponentClass.getMethod("setText", Component.class);

            screenGetComponents = lookup.unreflect(screenGetComponentsMethod);
            componentGetComponents = lookup.unreflect(componentGetComponentsMethod);
            getText = lookup.unreflect(getTextMethod);
            setText = lookup.unreflect(setTextMethod);

            reflectionReady = true;
            AllTranslator.LOGGER.info("UI Lib detected; screen component translation compat enabled.");
            return true;
        } catch (Throwable t) {
            AllTranslator.LOGGER.warn(
                    "UI Lib detected, but its API did not match what All Translator expects "
                            + "(CLAUDE.md §3: not guessing at an unverified API) - "
                            + "UI Lib screen translation will be unavailable this session.", t);
            reflectionFailed = true;
            return false;
        }
    }
}
