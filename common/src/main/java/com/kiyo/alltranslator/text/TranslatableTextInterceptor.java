package com.kiyo.alltranslator.text;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.lang.LanguageResolver;
import com.kiyo.alltranslator.lang.LocalizedTextResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.IllegalFormatException;
import java.util.Locale;
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
 * Phase 13 CRITICAL FIX (real-world testing): knownOutputs/byCacheKey used to be
 * per-instance, and AllTranslatorClientCore creates one instance PER content
 * category (item name / tooltip / entity name / screen widget). This caused actual
 * translation corruption in practice: ItemStackMixin's getHoverName() hook runs
 * FIRST and returns an already-translated, now-KEYLESS Component (e.g. the
 * already-correct Japanese "作業台", via the existing-translation path, §3). MC
 * itself then reuses getHoverName()'s result as the tooltip's first line. Because
 * ItemTooltipTranslationHook uses a SEPARATE TranslatableTextInterceptor instance
 * with its own empty knownOutputs, it had no way to know "作業台" was already a
 * final translated string - the Component now had no key (getContents() is no
 * longer TranslatableContents), so the existing-translation lookup (§3) was
 * skipped entirely, and the ALREADY-JAPANESE text was sent to the translation API
 * as if it were untranslated source text - observed corrupting entries like
 * "オークの木" into "オーキの木" via an LLM backend "translating" already-correct
 * Japanese into slightly different Japanese. Fixed by making knownOutputs (and,
 * for the same reason, byCacheKey) STATIC / shared across every
 * TranslatableTextInterceptor instance in the JVM, so a string translated via any
 * one instance (item name, tooltip, entity name, or screen widget) is immediately
 * recognized as "already final" by every other instance too - closing this gap
 * structurally rather than only for the specific item-name/tooltip pairing found
 * during testing.
 *
 * One instance per content category (item name / tooltip / entity name / screen
 * widget) is still created in AllTranslatorClientCore, client-side only - see that
 * class for why these must never be constructed from common's AllTranslatorCore.init() -
 * but they now cooperate via shared static state rather than being fully isolated.
 *
 * Phase 14 note: embedded legacy §-color-code handling (e.g. Traveler's Backpack
 * tooltip lines like "§6Backpack Tier: §4Leather") lives in
 * LocalizedTextResolver#resolve(), NOT here - see that class's Javadoc. This class
 * stays a thin, format-agnostic Component<->String bridge.
 */
public final class TranslatableTextInterceptor {
    private final LocalizedTextResolver resolver;
    private final LanguageResolver languageResolver;

    /** Phase 13 fix: shared across ALL instances (see class Javadoc). */
    private static final ConcurrentHashMap<String, CompletableFuture<String>> byCacheKey = new ConcurrentHashMap<>();
    /** Phase 13 fix: shared across ALL instances (see class Javadoc). */
    private static final Set<String> knownOutputs = ConcurrentHashMap.newKeySet();
    /** Phase 14 diagnostic only (temporary): first-seen timestamp per not-yet-done cache key. */


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
        if (!AllTranslatorCore.configManager().model().translationEnabled) {
            return original;
        }
        String plain = original.getString();
        if (plain.isBlank() || knownOutputs.contains(plain)) {
            return original;
        }
        String targetLang = languageResolver.resolveTargetLanguage();
        // Phase 13 bugfix: keyless text (key == null) captured from a mod's own
        // already-rendered UI (ScreenWidgetTranslationHook / LibIpnCompat /
        // UiLibCompat) is rendered in Minecraft's LIVE display language, not
        // necessarily untranslated en_us source text. If that live language
        // already equals the resolved target language, treat it as already-final
        // rather than sending it to the translation API - see
        // LanguageResolver#resolveLiveClientLanguage's Javadoc for the real-world
        // corruption bug this prevents. Key-based lookups are unaffected: they
        // already go through ExistingTranslationChecker's own "target IS source"
        // branch (ARCHITECTURE.md §3).
        if (key == null && languageResolver.resolveLiveClientLanguage().equals(targetLang)) {
            knownOutputs.add(plain);
            return original;
        }
        // Bugfix (2026-09-24, real-world "garbled/mismatched tooltip" reports in
        // Creative inventory): the cacheKey previously ignored `key` entirely, so
        // computeIfAbsent() would silently reuse an already-completed future from a
        // totally different translation key whenever two DIFFERENT keyed lookups
        // happened to share the same plain source text (common for short generic
        // template fragments, e.g. attribute-modifier lines, which recur across many
        // unrelated items/mods). That meant a keyed call's own ExistingTranslationChecker
        // result was skipped in favor of whichever unrelated key won the race. Including
        // `key` in the cacheKey isolates every keyed lookup from every other one, while
        // key == null (chat / color-code fragments / other genuinely keyless dynamic
        // text) still shares by plain text alone, preserving the original intent of
        // not re-requesting identical dynamic strings twice.
        String cacheKey = targetLang + '\u0000' + (key == null ? "" : key + '\u0000') + plain;
        CompletableFuture<String> future = byCacheKey.computeIfAbsent(cacheKey,
                k -> resolver.resolve(key, plain));
        if (!future.isDone()) {
            return original;
        }
        String translated = future.getNow(plain);
        if (translated == null || translated.equals(plain)) {
            // Phase 13 fix (real-world bug, e.g. Traveler's Backpack "Lantern Upgrade"
            // and several sibling upgrades never translating): this branch is hit both
            // by legitimately-untranslated text (source == target language, already
            // cached by TranslationService as noTranslationNeeded) AND by the "all API
            // candidates exhausted" fallback (TranslationService.attemptNext(), which
            // deliberately does NOT cache that outcome so a later request can retry -
            // see its own comment). Leaving the completed future sitting in this static
            // byCacheKey map forever silently converted the second case into a
            // permanent failure: any request that lost the single-flight probe race
            // (ApiState#tryReserveProbe, Phase 11) against another concurrent request
            // for the one-and-only configured API never got a second chance, because
            // intercept() short-circuits on future.isDone() before resolver.resolve()
            // is ever called again. Removing the entry here lets the next call replay
            // resolve() -> TranslationService.translate(): the legitimately-untranslated
            // case will simply hit TranslationService's own (source-language) cache
            // again immediately (no extra API calls), while the exhausted-fallback case
            // gets a genuine new attempt through attemptNext(), matching the documented
            // "no future request permanently blocked" intent of ARCHITECTURE.md §7/§23.1.
            byCacheKey.remove(cacheKey, future);
            return original;
        }
        translated = reapplyArgsIfNeeded(original, translated, targetLang);
        knownOutputs.add(translated);
        MutableComponent result = Component.literal(translated);
        result.setStyle(original.getStyle());
        return result;
    }

    /**
     * Real-world bug fix (investigation #5 follow-up): mirrors
     * ItemTooltipTranslationHook#extractKey()'s sibling-aware key detection - some
     * tooltip lines (e.g. attack damage/speed) have a whitespace-only LiteralContents
     * ROOT with the actual TranslatableContents (holding the args this method needs)
     * attached as a sibling, rather than being a root-level TranslatableContents like
     * the armor line. Without this, reapplyArgsIfNeeded would find no args for such
     * lines and leave the existing-translation template's "%s %s" placeholders
     * unfilled/displayed literally.
     */
    private static TranslatableContents findTranslatableContents(Component component) {
        if (component.getContents() instanceof TranslatableContents tc) {
            return tc;
        }
        for (Component sibling : component.getSiblings()) {
            if (sibling.getContents() instanceof TranslatableContents stc) {
                return stc;
            }
        }
        return null;
    }

    private static String reapplyArgsIfNeeded(Component original, String translated, String targetLang) {
        if (translated.indexOf('%') < 0) {
            return translated;
        }
        TranslatableContents tc = findTranslatableContents(original);
        if (tc == null) {
            return translated;
        }
        Object[] args = tc.getArgs();
        if (args == null || args.length == 0) {
            return translated;
        }
        // Real-world bug fix (Toast/Advancement translation task session follow-up):
        // confirmed via real-world testing (potion tooltip lines like
        // "potion.withAmplifier" whose args are themselves nested TranslatableContents,
        // e.g. "effect.minecraft.slowness"/"potion.potency.3") that passing a raw
        // Component element straight into String#format's "%s" conversion invokes
        // Component/TranslatableContents's own Object#toString() (e.g. literally
        // "translation{key='effect.minecraft.slowness', args=[]}"), NOT the resolved
        // display text.
        //
        // Second, separate real-world bug fix (attribute.modifier.plus.0, e.g. "+2
        // Armor" never becoming "防御力 +2"): even after flattening arg Components to
        // plain text, a naive Component#getString() resolves using the CLIENT's own
        // live display language (whatever locale the client's Font/I18n is currently
        // using), NOT this interceptor's actual target language - confirmed via
        // real-world testing that a nested arg Component (e.g. attribute.name.armor)
        // always came back as English "Armor" even when targetLang was ja_jp, because
        // the client itself was running in en_us. Fixed by resolveArgPlainText() below:
        // for any arg that is ITSELF a TranslatableContents, this looks up ITS OWN key
        // against ExistingTranslationChecker for targetLang FIRST (same
        // ARCHITECTURE.md §3 priority every other keyed value in this project already
        // gets), falling back to plain getString() only if no existing translation is
        // found for that nested key (e.g. a mod-added attribute with no ja_jp entry -
        // acceptable degraded behavior, matching pre-existing behavior for such cases;
        // fully translating an arbitrary nested arg via the async API is out of scope
        // for this synchronous formatting step).
        Object[] plainArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            plainArgs[i] = resolveArgPlainText(args[i], targetLang);
        }
        try {
            return String.format(Locale.ROOT, translated, plainArgs);
        } catch (IllegalFormatException e) {
            return translated;
        }
    }

    /**
     * Resolves a single TranslatableContents arg to TARGET-language plain text - see
     * reapplyArgsIfNeeded's Javadoc for the real-world bug this fixes. Recurses (bounded
     * by MAX_ARG_DEPTH) to handle a nested arg that itself has its own args (e.g. a nested
     * template), falling back to plain getString() (client's live language) wherever
     * no existing translation is found for a given key - never throws, matching this
     * class's overall defensive style.
     */
    private static Object resolveArgPlainText(Object arg, String targetLang) {
        return resolveArgPlainText(arg, targetLang, 0);
    }

    /** Nesting limit, e.g. potion.withDuration > potion.withAmplifier > effect name. */
    private static final int MAX_ARG_DEPTH = 8;

    private static Object resolveArgPlainText(Object arg, String targetLang, int depth) {
        if (!(arg instanceof Component c)) {
            return arg;
        }
        if (depth > MAX_ARG_DEPTH) {
            return c.getString();
        }
        if (!(c.getContents() instanceof TranslatableContents nestedTc)) {
            return c.getString();
        }
        String existing = AllTranslatorCore.existingTranslationChecker().check(nestedTc.getKey(), targetLang);
        if (existing == null) {
            return c.getString();
        }
        Object[] nestedArgs = nestedTc.getArgs();
        if (existing.indexOf('%') < 0 || nestedArgs == null || nestedArgs.length == 0) {
            return existing;
        }
        Object[] nestedPlainArgs = new Object[nestedArgs.length];
        for (int i = 0; i < nestedArgs.length; i++) {
            // Recurse (bounded by MAX_ARG_DEPTH): a potion effect line nests the
            // effect name two levels deep (withDuration > withAmplifier > name).
            nestedPlainArgs[i] = resolveArgPlainText(nestedArgs[i], targetLang, depth + 1);
        }
        try {
            return String.format(Locale.ROOT, existing, nestedPlainArgs);
        } catch (IllegalFormatException e) {
            return existing;
        }
    }

    /**
     * Phase 13 fix: registers an externally-composed final display string (e.g.
     * "translated (original)" from the showOriginalNameOnItems suffix feature) as
     * already-final, so it is never re-fed into the translation pipeline as if it
     * were untranslated source text. Callers that build a Component OUTSIDE of
     * intercept() itself (i.e. anything appending a suffix to intercept()'s result)
     * MUST call this with the exact final getString() value, or that composed
     * string can be re-sent to the translation API on a later call - this is
     * exactly the bug found in real-world testing when the (original name) suffix
     * was added without this registration.
     */
    public static void registerKnownOutput(String finalDisplayText) {
        if (finalDisplayText != null && !finalDisplayText.isBlank()) {
            knownOutputs.add(finalDisplayText);
        }
    }

    /** Call when the client's target language changes so stale results aren't reused. */
    public void invalidateAll() {
        byCacheKey.clear();
        knownOutputs.clear();
    }
}
