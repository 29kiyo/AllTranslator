package com.kiyo.alltranslator.text;

import com.kiyo.alltranslator.lang.LanguageResolver;
import com.kiyo.alltranslator.lang.LocalizedTextResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 4: connects Phase 3's LocalizedTextResolver to live Minecraft Component
 * interception points (item names, tooltips, entity names).
 *
 * Non-blocking by design: never calls CompletableFuture#get()/join(). If a
 * translation isn't ready yet, the original Component is returned unchanged and
 * the async result is picked up on a later call (next frame / next tooltip open),
 * satisfying CLAUDE.md §7 (never block the main thread).
 *
 * Double-translation guard (ARCHITECTURE.md §2): once a translated string is
 * produced, it is remembered in {@link #knownOutputs} so that if that same string
 * is ever fed back in as a "source" (e.g. a mod re-reading the already-translated
 * component), it is passed through unchanged instead of being translated again.
 *
 * One instance per content category (item name / tooltip / entity name) is created
 * in AllTranslatorClientCore, client-side only - see that class for why these must
 * never be constructed from common's AllTranslatorCore.init().
 */
public final class TranslatableTextInterceptor {

    private final LocalizedTextResolver resolver;
    private final LanguageResolver languageResolver;
    private final ConcurrentHashMap<String, CompletableFuture<String>> byCacheKey = new ConcurrentHashMap<>();
    private final Set<String> knownOutputs = ConcurrentHashMap.newKeySet();

    public TranslatableTextInterceptor(LocalizedTextResolver resolver, LanguageResolver languageResolver) {
        this.resolver = resolver;
        this.languageResolver = languageResolver;
    }

    /**
     * @param original the Component as produced by vanilla/mod code.
     * @param key      translation key backing this Component if known (e.g. from
     *                 TranslatableContents#getKey()), or null for keyless/dynamic text
     *                 (custom-named items, renamed entities, etc).
     * @return original, or a new Component with the same Style and translated text.
     */
    public Component intercept(Component original, String key) {
        if (original == null) {
            return null;
        }
        String plain = original.getString();
        if (plain.isBlank() || knownOutputs.contains(plain)) {
            return original;
        }

        String targetLang = languageResolver.resolveTargetLanguage();
        String cacheKey = targetLang + '\u0000' + plain;

        CompletableFuture<String> future = byCacheKey.computeIfAbsent(cacheKey,
                k -> resolver.resolve(key, plain));

        if (!future.isDone()) {
            return original;
        }

        String translated = future.getNow(plain);
        if (translated == null || translated.equals(plain)) {
            return original;
        }

        knownOutputs.add(translated);
        MutableComponent result = Component.literal(translated);
        result.setStyle(original.getStyle());
        return result;
    }

    /** Call when the client's target language changes so stale results aren't reused. */
    public void invalidateAll() {
        byCacheKey.clear();
        knownOutputs.clear();
    }
}
