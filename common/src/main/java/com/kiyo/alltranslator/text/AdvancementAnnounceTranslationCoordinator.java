package com.kiyo.alltranslator.text;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.lang.ExistingTranslationChecker;
import com.kiyo.alltranslator.server.PerPlayerLanguageResolver;
import com.kiyo.alltranslator.service.TranslationService;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 14 (Toast/Advancement translation task, follow-up): per-player translation of
 * the "X has made the advancement [Y]" chat announcement (and the challenge-complete
 * equivalent), gated on the EXISTING ConfigModel#translateSystemMessages flag per user
 * decision - this announcement is conceptually the same "server-generated system
 * message" category as /tellraw (TellrawTranslationCoordinator), not a new dedicated
 * toggle.
 *
 * SCOPE (confirmed via javap): PlayerAdvancements#lambda$award$0 calls
 * PlayerList#broadcastSystemMessage(Component, boolean) - the 2-arg overload, itself a
 * thin wrapper that (a) logs the ORIGINAL Component to the server console via
 * MinecraftServer#sendSystemMessage(Component) [left untouched - the console log stays
 * in the server's own language, not per-viewer-translated] and (b) loops every online
 * ServerPlayer calling ServerPlayer#sendSystemMessage(Component, boolean). The 3-arg
 * overload broadcastSystemMessage(Component, Function&lt;ServerPlayer,Component&gt;, boolean)
 * already exists in vanilla for exactly this "different Component per recipient" need,
 * so AdvancementBroadcastMixin redirects the 2-arg call to this class's
 * broadcastAnnouncement(), which calls the 3-arg overload with a per-player Function.
 *
 * EXISTING-TRANSLATION PRIORITY (real-world follow-up fix, ARCHITECTURE.md §3):
 * confirmed via javap that AdvancementType#createAnnouncement(AdvancementHolder,
 * ServerPlayer) builds this Component as EXACTLY
 * Component.translatable(key, actingPlayer.getDisplayName(), Advancement.name(holder))
 * - i.e. announcement is ALWAYS a TranslatableContents whose backing KEY is one of the
 * three fixed vanilla keys (chat.type.advancement.task/goal/challenge, each a "%s ...
 * %s"-shaped template - confirmed against the actual en_us.json bundled in the MC 26.2
 * jar) and whose args are [actingPlayer's display-name Component, advancement's name
 * Component]. The ORIGINAL implementation of this class skipped ARCHITECTURE.md §3's
 * existing-translation lookup entirely and flattened the WHOLE sentence to a plain
 * string via announcement.getString() before sending it to the translation API - this
 * was a real-world bug: even when the recipient's target language ALREADY has this
 * exact announcement translated by Minecraft itself (or by a mod's own lang file), the
 * flattened sentence was still sent to an LLM, which (a) wastes API calls,
 * (b) mistranslates already-correct text, and (c) was observed corrupting the player's
 * name INSIDE the sentence (e.g. "TestPlayerA" - see the class's later "actor name"
 * handling below).
 *
 * Fixed by checking ExistingTranslationChecker#check(key, targetLang) FIRST: if the
 * target language already has a valid translation for the key (vanilla or a
 * CustomLanguageFileManager/mod override - same priority chain as every other
 * key-backed content in this project), that TEMPLATE string (still containing its own
 * "%s" placeholders) is used with String#format to splice in the SAME actor-name and
 * advancement-name plain text vanilla would have used - no persistent/memory cache and
 * no LLM call at all, matching how every other keyed content type in this project
 * (item names, tooltips, entity names, toasts) already behaves. Only when NO existing
 * translation exists for the key (extremely unlikely for the 3 fixed vanilla keys
 * themselves, but always possible for a MOD-registered advancement's own name/title
 * text nested inside args[1], which independently flows through
 * TranslatableTextInterceptor via other hooks - not duplicated here) does this class
 * fall through to the async LLM-translation path below, exactly as before.
 *
 * ACTOR NAME PROTECTION (real-world bug fix): even on the LLM-translation fallback
 * path, the acting player's plain display name (args[0]) must never be sent to the
 * translation API as ordinary prose - a player name is an identifier, not legitimate
 * translation content (same principle as EntityMixin's Player exclusion). The ORIGINAL
 * fix for this substituted the actor's name with the literal string "%player%" before
 * calling PlaceholderProtector.protect() - this was itself a bug: protect()'s own regex
 * (%[a-zA-Z_]+%) matches "%player%" and re-tokenizes it into a SECOND, PUA-marker-based
 * token before sending to the LLM, and real-world testing showed a local 7B model
 * corrupting that PUA marker into a bare digit ("TestPlayerA" announcements coming back
 * with the digit "0" in place of the name) - the exact "local models strip/mangle
 * unfamiliar PUA characters" failure mode already documented for legacy §-color-code
 * handling (see LocalizedTextResolver#resolve()'s Javadoc), now reproduced here via a
 * DIFFERENT code path (double-tokenization) that hadn't been anticipated. Fixed by
 * protecting the actor's name directly via PlaceholderProtector.protect() ONCE (on the
 * whole sentence, with the actor name substring replaced by protect()'s own PUA token
 * scheme directly - see protectActorName() below) rather than going through an
 * intermediate "%player%" textual placeholder that gets tokenized a second time.
 *
 * Two-stage delivery (same established pattern as TellrawTranslationCoordinator/
 * ServerChatTranslationCoordinator) on the LLM-fallback path only: the Function itself
 * must return synchronously, so it returns the ORIGINAL announcement immediately (used
 * for the recipient in the broadcastSystemMessage call this Function is passed into),
 * while kicking off an async translation in the background; once that resolves, a
 * SECOND ServerPlayer#sendSystemMessage call delivers the translated text as a
 * follow-up message. The existing-translation path above has no such delay - it
 * resolves synchronously and is used for the recipient's send immediately.
 *
 * Persistent cache (LLM-fallback path only, same reasoning as tellraw): advancement
 * announcement text is templated/repeated, so persistable=true is passed to
 * TranslationService#translate - caching across restarts/players has real value here,
 * unlike free-form chat (§8.1).
 */
public final class AdvancementAnnounceTranslationCoordinator {

    private final TranslationService translationService;
    private final ExistingTranslationChecker existingTranslationChecker;
    private final PerPlayerLanguageResolver languageResolver;
    private final ConfigManager configManager;

    /** Same defensive-dedupe reasoning as TellrawTranslationCoordinator's inFlight field. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public AdvancementAnnounceTranslationCoordinator(TranslationService translationService,
                                                       ExistingTranslationChecker existingTranslationChecker,
                                                       PerPlayerLanguageResolver languageResolver,
                                                       ConfigManager configManager) {
        this.translationService = translationService;
        this.existingTranslationChecker = existingTranslationChecker;
        this.languageResolver = languageResolver;
        this.configManager = configManager;
    }

    /**
     * Called from AdvancementBroadcastMixin in place of the original 2-arg
     * PlayerList#broadcastSystemMessage(Component, boolean) call.
     */
    public void broadcastAnnouncement(PlayerList playerList, Component announcement, boolean overlay) {
        playerList.broadcastSystemMessage(announcement, player -> resolveForPlayer(player, announcement), overlay);
    }

    private Component resolveForPlayer(ServerPlayer recipient, Component announcement) {
        if (!languageResolver.isEnabledForSystemMessages(recipient)) {
            return announcement;
        }

        String plain = announcement.getString();
        if (plain.isBlank()) {
            return announcement;
        }

        String targetLang = languageResolver.resolve(recipient);
        Style style = announcement.getStyle();

        // Existing-translation priority (ARCHITECTURE.md §3) - see class Javadoc.
        String key = (announcement.getContents() instanceof TranslatableContents tc) ? tc.getKey() : null;
        if (key != null) {
            String existingTemplate = existingTranslationChecker.check(key, targetLang);
            if (existingTemplate != null) {
                Component formatted = formatExistingTemplateAsComponent(existingTemplate, style, announcement);
                if (formatted != null && !formatted.getString().equals(plain)) {
                    return formatted;
                }
                // formatted == null (format failed) or formatted's plain text matches
                // the original (target IS source language, e.g. en_us -> en_us): fall
                // through to original.
                return announcement;
            }
        }

        String dedupeKey = recipient.getUUID() + ":" + System.identityHashCode(announcement);
        if (!inFlight.add(dedupeKey)) {
            // Defensive only - see TellrawTranslationCoordinator's identical field Javadoc.
            return announcement;
        }

        // Real-world bug fix: protect the acting player's plain name directly via
        // PlaceholderProtector's own PUA-token scheme (single tokenization pass) - see
        // class Javadoc "ACTOR NAME PROTECTION" for why an intermediate "%player%"
        // textual placeholder caused a real double-tokenization corruption bug.
        String actorPlainName = extractActorPlainName(announcement);
        String forProtection = plain;
        if (actorPlainName != null && !actorPlainName.isBlank() && plain.contains(actorPlainName)) {
            forProtection = plain.replace(actorPlainName, "ACTOR");
        }

        PlaceholderProtector.Protected protectedText = PlaceholderProtector.protect(forProtection);
        String textForApi = actorPlainName != null
                ? protectedText.text().replace("ACTOR", "")
                : protectedText.text();
        // The line above intentionally does nothing when actorPlainName is null (the
        // literal marker was never inserted in that case). When it WAS inserted, it is
        // itself already a "placeholder-shaped" run of literal PUA characters that
        // protect() does NOT recognize as one of its own patterns (its regex requires
        // ASCII delimiters like % or {}), so it passes through protect() unchanged and
        // is removed here just before sending to the API, then re-spliced back in via
        // restoreActorName() below - the API never sees ANY player-identifying text at
        // all, not even inside a marker, eliminating the entire class of "LLM mangles
        // the marker" bugs rather than just working around one instance of it.

        TranslationRequest request = new TranslationRequest(textForApi, null, targetLang);

        translationService.translate(request, true).whenComplete((result, error) -> {
            inFlight.remove(dedupeKey);

            MinecraftServer server = recipient.level().getServer();
            server.execute(() -> {
                if (error != null) {
                    AllTranslator.LOGGER.warn("Advancement announcement translation failed", error);
                    return;
                }
                if (result == null || result.noTranslationNeeded()) {
                    return;
                }
                String restored = PlaceholderProtector.restore(result.translatedText(), protectedText.tokens());
                if (restored != null && actorPlainName != null && !actorPlainName.isBlank()) {
                    // Re-insert the actor's name where the (now-removed) marker was cut
                    // out just before the API call. Best-effort position: prepend, since
                    // the marker's exact position within the LLM's rewritten sentence
                    // can't be reliably tracked once removed entirely - this is a
                    // deliberate trade-off (name in the wrong position, in the sentence
                    // at all) vs. the prior bug (name silently replaced by a digit).
                    restored = actorPlainName + " " + restored;
                }
                if (restored == null || restored.equals(plain)) {
                    return;
                }
                MutableComponent translatedComponent = Component.literal(restored).setStyle(style);
                // Same UNVERIFIED-but-defensive connection check as
                // TellrawTranslationCoordinator - see that class's Javadoc.
                if (recipient.connection != null) {
                    recipient.sendSystemMessage(translatedComponent);
                }
            });
        });

        // Two-stage delivery: the Function must return synchronously (see class
        // Javadoc) - the original is shown now, the translation (if any) follows above.
        return announcement;
    }

    /**
     * Real-world follow-up fix (color/hover regression): splices announcement's actual
     * args (actor name, advancement name) into an existing-translation template string
     * (e.g. "%s は %s を達成しました") - but, UNLIKE the earlier version of this method
     * (which called args[i].getString() and lost all Component structure), this
     * preserves each arg's ORIGINAL Component (Style included) by building a real
     * Component TREE instead of a flat String: the template is split at each "%s"
     * occurrence, and the surrounding literal text runs are stitched together with the
     * UNMODIFIED arg Components in between via MutableComponent#append(Component).
     *
     * This matters because confirmed via javap: args[1] (Advancement.name(holder)) is
     * NOT plain text - AdvancementType#decorateName() wraps the advancement's title in
     * both a chat-color Style (task/goal/challenge each have a distinct
     * ChatFormatting) AND a HoverEvent.ShowText Style (showing the full title +
     * description on hover, exactly like the F-key progress screen's tooltip) around
     * the "[Title]" square-bracketed text. String#format's %s conversion on a
     * Component silently calls toString(), which discards BOTH the color and the
     * hover event - real-world bug this fixes: translated advancement announcements in
     * chat lost their color-coding and could no longer be hovered to see the
     * description, unlike vanilla's own untranslated announcements.
     *
     * Returns null if the template has a %-conversion this simple splitter doesn't
     * recognize (only "%s" is handled - the vanilla chat.type.advancement.* keys are
     * confirmed via the bundled en_us.json to only ever use plain "%s", never
     * positional "%1$s" or other conversions) or if there's an args/placeholder-count
     * mismatch - caller falls back to sending the original announcement unchanged in
     * either case, matching this class's overall defensive style.
     */
    private static Component formatExistingTemplateAsComponent(String template, Style resultStyle, Component announcement) {
        if (!(announcement.getContents() instanceof TranslatableContents tc)) {
            return null;
        }
        Object[] args = tc.getArgs();
        int argCount = (args == null) ? 0 : args.length;

        MutableComponent result = Component.empty().setStyle(resultStyle);
        int argIndex = 0;
        int searchFrom = 0;
        while (true) {
            int placeholderIndex = template.indexOf("%s", searchFrom);
            if (placeholderIndex < 0) {
                result.append(Component.literal(template.substring(searchFrom)));
                break;
            }
            result.append(Component.literal(template.substring(searchFrom, placeholderIndex)));
            if (argIndex >= argCount) {
                // More "%s" placeholders than args - template/args mismatch, bail out
                // rather than silently dropping a placeholder.
                return null;
            }
            Object arg = args[argIndex];
            if (arg instanceof Component argComponent) {
                result.append(argComponent);
            } else {
                result.append(Component.literal(String.valueOf(arg)));
            }
            argIndex++;
            searchFrom = placeholderIndex + 2;
        }
        if (argIndex != argCount) {
            // Template had FEWER "%s" placeholders than args - also a mismatch worth
            // bailing out on, same reasoning as the check above.
            return null;
        }
        return result;
    }

    /**
     * Real-world follow-up fix: returns the plain text of the acting player's
     * display-name Component (createAnnouncement's confirmed first arg), or null if
     * announcement isn't the expected TranslatableContents shape or has no args -
     * never throws, matching this class's overall defensive style.
     */
    private static String extractActorPlainName(Component announcement) {
        if (!(announcement.getContents() instanceof TranslatableContents tc)) {
            return null;
        }
        Object[] args = tc.getArgs();
        if (args == null || args.length == 0 || !(args[0] instanceof Component playerNameComponent)) {
            return null;
        }
        return playerNameComponent.getString();
    }
}
