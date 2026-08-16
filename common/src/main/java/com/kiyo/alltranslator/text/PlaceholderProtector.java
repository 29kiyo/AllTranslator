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
 * Not yet wired into the Phase 4 hooks (item/entity/tooltip text is translated as
 * plain literal strings extracted via Component#getString(), which do not carry
 * placeholders or formatting codes - those live in the Component's Style, which
 * Phase 4 already preserves separately by copying original.getStyle()). This class
 * exists now because Phase 5 (chat) and dynamic mod text (Phase 7, FTB Quests) will
 * need it for raw text that DOES contain inline placeholders/codes.
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
