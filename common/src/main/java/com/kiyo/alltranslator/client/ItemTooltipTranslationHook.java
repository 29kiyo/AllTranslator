package com.kiyo.alltranslator.client;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.TranslatableTextInterceptor;
import dev.architectury.event.events.client.ClientTooltipEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.regex.Pattern;

/**
 * Item tooltip translation (PHASE_INSTRUCTIONS.md Phase 4).
 *
 * register() must only be called from a confirmed client-side entrypoint - see
 * AllTranslatorClientCore.
 *
 * Phase 13 fixes:
 *  - keyless tooltip lines that look like raw identifiers/tags/NBT dumps are
 *    skipped (mod-agnostic pattern match, not modid-based).
 *  - optional " (original name)" suffix on the FIRST tooltip line only (the item
 *    name line), gated by ConfigModel#showOriginalNameOnItems, matching
 *    ItemStackMixin's behavior for consistency. Not applied to subsequent
 *    tooltip/lore lines to avoid visual clutter on multi-line tooltips.
 */
public final class ItemTooltipTranslationHook {

    private ItemTooltipTranslationHook() {}

    private static final Pattern IDENTIFIER_OR_DATA_PATTERN = Pattern.compile(
            "^#?[a-z0-9_.\\-]+:[a-z0-9_./\\-]+$|^\\{.*}$"
    );

    public static void register(TranslatableTextInterceptor interceptor) {
        ClientTooltipEvent.ITEM.register((stack, lines, tooltipContext, flag) -> {
            if (!AllTranslatorCore.configManager().model().translateItemTooltips) {
                return;
            }
            for (int i = 0; i < lines.size(); i++) {
                Component original = lines.get(i);
                String key = extractKey(original);
                if (key == null && looksLikeIdentifierOrData(original.getString())) {
                    continue;
                }
                Component translated = interceptor.intercept(original, key);
                if (translated == original) {
                    continue;
                }
                if (i == 0 && AllTranslatorCore.configManager().model().showOriginalNameOnItems) {
                    String originalPlain = original.getString();
                    MutableComponent withOriginal = translated.copy()
                            .append(Component.literal(" (" + originalPlain + ")").setStyle(translated.getStyle()));
                    // Phase 13 fix: same reasoning as ItemStackMixin - register the
                    // composed suffix string as final so it's never re-translated.
                    TranslatableTextInterceptor.registerKnownOutput(withOriginal.getString());
                    lines.set(i, withOriginal);
                } else {
                    lines.set(i, translated);
                }
            }
        });
    }

    private static boolean looksLikeIdentifierOrData(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return false;
        }
        return IDENTIFIER_OR_DATA_PATTERN.matcher(plainText.trim()).matches();
    }

    /**
     * Real-world bug fix (investigation #5): confirmed via real-world testing that
     * attribute-modifier tooltip lines like attack damage/speed (unlike the armor
     * line, which IS a root-level TranslatableContents) are actually built as a
     * LiteralContents ROOT (whitespace-only, e.g. " ") with the REAL
     * TranslatableContents (e.g. key=attribute.modifier.equals.0) attached as a
     * SIBLING - confirmed via a standalone diagnostic dump of the exact Component
     * tree shape before writing this fix (CLAUDE.md §3/§21). Extended to check the
     * first sibling that is itself a TranslatableContents when the root isn't one,
     * so ItemTooltipTranslationHook's existing-translation-priority path (which
     * depends entirely on a non-null key) also applies to this line shape, exactly
     * like the armor line already benefited from. Only checks ONE level (siblings of
     * the immediate Component, not recursively) - sufficient for every vanilla
     * attribute-modifier tooltip line confirmed so far; a mod nesting keys deeper
     * would still fall through to the pre-existing keyless/API path unchanged.
     */
    private static String extractKey(Component component) {
        if (component.getContents() instanceof TranslatableContents tc) {
            return tc.getKey();
        }
        for (Component sibling : component.getSiblings()) {
            if (sibling.getContents() instanceof TranslatableContents stc) {
                return stc.getKey();
            }
        }
        return null;
    }
}
