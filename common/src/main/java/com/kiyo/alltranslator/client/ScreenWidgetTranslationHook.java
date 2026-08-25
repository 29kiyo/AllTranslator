package com.kiyo.alltranslator.client;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.compat.libipn.LibIpnCompat;
import com.kiyo.alltranslator.compat.uilib.UiLibCompat;
import com.kiyo.alltranslator.compat.widgetframework.WidgetFrameworkCompat;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import dev.architectury.event.events.client.ClientTickEvent;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase 13: best-effort translation of other mods' Screen widgets (buttons, on/off
 * toggles, labels, etc), extending Phase 4's item/tooltip/entity translation scope.
 *
 * Phase 13 fixes (real-world testing, see DEVELOPMENT_STATUS.md for full history):
 *  - Only KEYLESS Components are ever touched (TranslatableContents is skipped
 *    entirely) - see prior revision's Javadoc for the world-creation-screen
 *    corruption this fixes.
 *  - NOISE_PATTERN additionally skips: bare symbols/punctuation-only strings (e.g.
 *    a lone ">" pagination arrow button, sent to the translation API for no
 *    reason in testing), and dotted lowercase identifier-looking strings (e.g.
 *    "libipn.common.gui.config.title") - some mods set a raw translation-key
 *    STRING as a widget's literal message text (bypassing Component.translatable
 *    entirely, so this hook has no way to route it through the normal key-based
 *    existing-translation path); sending that raw key string to a translation API
 *    is worse than useless, so it's filtered out and left as-is.
 *  - Mods rendering their own custom Screen/Widget implementation (not reachable
 *    via Screen#children()/AbstractWidget below) can be covered via
 *    WIDGET_FRAMEWORK_ADAPTERS - see WidgetFrameworkCompat's Javadoc. Currently
 *    registered: LibIpnCompat (libIPN / org.anti_ad.mc, e.g. Inventory Profiles
 *    Next's config screen), UiLibCompat (UI Lib / com.daqem.uilib). This was
 *    previously a documented known limitation for libIPN specifically; it remains
 *    a best-effort limitation for any OTHER custom UI framework not yet
 *    investigated/registered here (e.g. MaLiLib, owo-lib's Braid/owo.ui layers,
 *    Sodium's config API, Cloth Config, YACL - all investigated in Phase 13 and
 *    found to have no PUBLIC API for reading/rewriting displayed text or
 *    enumerating children at runtime; see DEVELOPMENT_STATUS.md for the
 *    per-library findings and rationale).
 */
public final class ScreenWidgetTranslationHook {

    private static final String OWN_PACKAGE_PREFIX = "com.kiyo.alltranslator";

    /**
     * Skips: strings with no letters at all (symbols/punctuation like ">", "<",
     * "#01600B8C"), and dotted lowercase.snake_case-looking identifiers (likely a
     * raw unrouted translation key, e.g. "libipn.common.gui.config.title").
     *
     * Package-visible (not private) so WidgetFrameworkCompat implementations can
     * reuse the exact same filter instead of duplicating the regex (CLAUDE.md:
     * avoid unnecessary duplication of working logic).
     */
    static final Pattern NOISE_PATTERN = Pattern.compile(
            "^[^\\p{L}]*$|^[a-z0-9_]+(\\.[a-z0-9_]+){2,}$"
    );

    /**
     * Extension point for custom (non-AbstractWidget) Screen/Widget frameworks -
     * see WidgetFrameworkCompat. Add new adapters here as they are investigated
     * and verified (CLAUDE.md §3) against a real target mod jar.
     */
    private static final List<WidgetFrameworkCompat> WIDGET_FRAMEWORK_ADAPTERS = List.of(
            new LibIpnCompat(),
            new UiLibCompat()
    );

    private ScreenWidgetTranslationHook() {}

    public static void register(TranslatableTextInterceptor interceptor) {
        ClientTickEvent.CLIENT_POST.register(minecraft -> {
            Screen screen = minecraft.gui.screen();
            if (screen == null) return;
            if (screen.getClass().getPackageName().startsWith(OWN_PACKAGE_PREFIX)) return;
            if (!AllTranslatorCore.configManager().model().translationEnabled) return;
            if (!AllTranslatorCore.configManager().model().translateOtherModScreens) return;

            for (GuiEventListener child : screen.children()) {
                if (!(child instanceof AbstractWidget widget)) continue;
                Component original = widget.getMessage();
                if (original == null) continue;
                if (original.getContents() instanceof TranslatableContents) continue;

                String plain = original.getString();
                if (plain == null || plain.isBlank() || NOISE_PATTERN.matcher(plain.trim()).matches()) {
                    continue;
                }

                Component translated = interceptor.intercept(original, null);
                if (translated != original) {
                    widget.setMessage(translated);
                }
            }

            for (WidgetFrameworkCompat adapter : WIDGET_FRAMEWORK_ADAPTERS) {
                if (adapter.isApplicable()) {
                    adapter.translateScreen(screen, interceptor, NOISE_PATTERN);
                }
            }
        });
    }
}
