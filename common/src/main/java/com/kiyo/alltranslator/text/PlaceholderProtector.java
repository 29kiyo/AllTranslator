package com.kiyo.alltranslator.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokenizes placeholders / Minecraft formatting codes before sending text to a
 * translation API, and restores them afterwards (CLAUDE.md §17: "Hello, %player%"
 * must retain "%player%").
 *
 * Recognizes:
 *  - printf-style: %s %d %1$s %2$s ...
 *  - brace-style:  {0} {1} {player} ...
 *  - percent-name: %player% %target% ...
 *  - legacy formatting codes: §0-9a-fk-or
 *
 * Phase 14 finding (CLAUDE.md §21): some mods (confirmed with Traveler's
 * Backpack) DO embed raw legacy formatting codes directly as literal
 * characters inside a Component's own text - Component#getString() includes
 * them, disproving this class's original assumption that Phase 4 never needed
 * it. However, hiding such codes behind THIS class's private-use-area marker
 * tokens was tried and found to actively make things worse against this
 * project's local 7B LLM backend, which reliably strips unfamiliar PUA
 * characters from its output entirely (real-world testing via persistent
 * cache inspection - see LocalizedTextResolver#resolve()'s Javadoc for the
 * fix actually used instead: splitting the source text into per-color-run
 * segments and translating each independently, never sending the codes
 * themselves to any translation backend at all). This class remains
 * available/correct for genuine %placeholder%-style tokens where round-
 * tripping through the API is unavoidable (Phase 5 chat, Phase 7 FTB Quests),
 * but is deliberately NOT used for legacy §-color-code protection.
 */
public final class PlaceholderProtector {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "%\\d+\\$s|%[a-zA-Z_]+%|\\{[a-zA-Z0-9_]+}|%[sd]|\u00A7[0-9a-fk-orA-FK-OR]"
    );

    private PlaceholderProtector() {}

    public record Protected(String text, List<String> tokens) {}

    /** Replaces each placeholder occurrence with a private-use-area token, in order. */
    public static Protected protect(String source) {
        if (source == null || source.isEmpty()) {
            return new Protected(source, List.of());
        }
        List<String> tokens = new ArrayList<>();
        Matcher m = PLACEHOLDER_PATTERN.matcher(source);
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (m.find()) {
            tokens.add(m.group());
            m.appendReplacement(out, Matcher.quoteReplacement("\uE000" + index + "\uE001"));
            index++;
        }
        m.appendTail(out);
        return new Protected(out.toString(), tokens);
    }

    /** Restores tokens produced by {@link #protect(String)} after translation. */
    public static String restore(String translated, List<String> tokens) {
        if (translated == null || tokens.isEmpty()) {
            return translated;
        }
        String result = translated;
        for (int i = 0; i < tokens.size(); i++) {
            result = result.replace("\uE000" + i + "\uE001", tokens.get(i));
        }
        return result;
    }
}
