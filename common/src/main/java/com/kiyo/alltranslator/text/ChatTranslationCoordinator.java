package com.kiyo.alltranslator.text;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.lang.LanguageResolver;
import com.kiyo.alltranslator.mixin.ChatComponentAccessor;
import com.kiyo.alltranslator.service.TranslationService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.Style;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Phase 5: client-side chat translation.
 *
 * Design (ARCHITECTURE.md ??9/??10, CLAUDE.md ??7/??12/??17):
 *  - Never blocks the main thread and never delays vanilla's own chat display: this
 *    class is only ever invoked as a LISTENER (Fabric ClientReceiveMessageEvents.CHAT
 *    / NeoForge ClientChatReceivedEvent.Player observed but not cancelled by the
 *    loader hookup - see fabric/neoforge modules), so vanilla shows the original
 *    message exactly as it always would have.
 *  - Translation runs asynchronously via Phase 2's TranslationService (multi-API
 *    failover reused as-is, persistable=false so only the memory-LRU tier is used
 *    per ARCHITECTURE.md ??8.1 - chat is intentionally excluded from the world-save
 *    persistent cache).
 *  - Once translation completes, the already-displayed chat line is patched in place
 *    via an Accessor/Invoker Mixin on ChatComponent (ChatComponent has no public API
 *    to replace a past line - see ChatComponentAccessor). See "Retry queue" below for
 *    how/when this patch attempt actually runs.
 *  - Only the message BODY (PlayerChatMessage#decoratedContent(), no username) is
 *    sent for translation. The full displayed line is rebuilt afterwards via the
 *    SAME ChatType.Bound#decorate(...) vanilla used originally, never by string-
 *    splicing an already-composed "username: message" line.
 *  - The underlying PlayerChatMessage/signature used for signing, filtering, and
 *    deletion-by-signature is never modified - only the GuiMessage's *displayed*
 *    Component is swapped, exactly like vanilla's own filterMask redaction does.
 *  - Placeholders/formatting codes inside the raw text are preserved via
 *    PlaceholderProtector (protect before sending, restore after).
 *
 * Re-locating the displayed line after async translation completes:
 *  - SIGNED messages (message.signature() != null): matched exactly by
 *    MessageSignature. Reliable - this is the common case on premium/online-mode
 *    servers with a working session service.
 *  - UNSIGNED messages (message.signature() == null - observed in practice on
 *    singleplayer/LAN and whenever the client can't fetch a profile key pair, e.g.
 *    "Failed to retrieve profile key pair" / HTTP 401 from the session service):
 *    best-effort content match. At receipt time we precompute the EXACT full line
 *    vanilla will display (boundChatType.decorate(originalBody)) plus the current
 *    GUI tick, then later search allMessages for an unsigned, not-yet-patched entry
 *    added at or after that tick whose displayed content string equals that exact
 *    line. Because the compared string includes the decorated username, a false
 *    match requires the SAME sender sending byte-identical text more than once
 *    before the first occurrence's translation completes - and even then the only
 *    consequence is applying a (correct, since the text is identical) translation to
 *    the wrong one of two identical-looking lines. Already-patched entries are
 *    tracked by identity (not equals - GuiMessage is a record) so the same line is
 *    never re-matched twice.
 *  - allMessages stores the NEWEST message at index 0 (ChatComponent#addMessage
 *    ToDisplayQueue calls List#addFirst - confirmed via javap against the MC 26.2
 *    merged jar). The match scan below therefore runs OLDEST-to-NEWEST (from the
 *    end of the list), so when the exact same text is sent twice in a row and
 *    translations complete out of send-order, each translation still prefers the
 *    OLDEST still-unpatched candidate - matching actual send order in the common
 *    case (Phase 13 fix, "Plan B" per DEVELOPMENT_STATUS.md).
 *
 * Retry queue (Phase 13 fix, real-world bug): a translation that resolves via a
 * memory-cache hit (e.g. the exact same text translated moments earlier) completes
 * essentially synchronously - fast enough that vanilla's OWN bookkeeping (appending
 * the just-received line to ChatComponent#allMessages) can still be in progress or
 * not yet reached at all, depending on the exact event-firing order relative to
 * vanilla's own chat display code (which is not something CLAUDE.md §3 permits
 * assuming without per-version verification, and can plausibly differ between
 * Fabric/NeoForge). A single immediate match attempt could therefore permanently
 * miss a line that would have matched moments later - confirmed via real-world
 * testing (repeated identical chat text's later occurrences reliably failed to
 * match on the very first attempt). Instead of attempting the match once
 * synchronously, a completed translation is enqueued and retried once per client
 * tick (see {@link #onClientTick(Minecraft)}, wired from AllTranslatorClientCore via
 * Architectury's common ClientTickEvent.CLIENT_POST) for up to
 * MAX_PATCH_RETRY_TICKS ticks before being given up on and logged.
 *
 * Known limitations (documented, not silently skipped - see DEVELOPMENT_STATUS.md):
 *  - If a message (signed or unsigned) scrolls out of ChatComponent's 100-entry
 *    history before the async translation completes (or before the retry budget
 *    below is exhausted), the patch is silently skipped.
 *  - System/disguised chat (no PlayerChatMessage at all - death messages, /me via
 *    disguised chat, etc.) is out of scope for Phase 5.
 */
public final class ChatTranslationCoordinator {

    /**
     * Max number of client ticks a completed translation will keep retrying to find
     * its matching displayed line before being given up on. 20 ticks is ~1 second at
     * the vanilla 20 TPS client tick rate - comfortably longer than the gap between
     * this coordinator's chat-receive listener firing and vanilla appending the line
     * to ChatComponent#allMessages ever ought to take, while still bounded so a
     * genuinely-unmatchable patch (e.g. the line already scrolled out of the
     * 100-entry history) does not accumulate forever in {@link #pendingPatches}.
     */
    private static final int MAX_PATCH_RETRY_TICKS = 20;

    private final TranslationService translationService;
    private final LanguageResolver languageResolver;
    private final ConfigManager configManager;

    /** Guards against the exact same in-flight request being processed twice concurrently. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Identity-based (not equals-based) record of GuiMessage instances already patched
     * via the unsigned best-effort matcher, so an identical later occurrence of the
     * same text isn't matched to an already-translated line. Bounded to 100 entries,
     * matching ChatComponent#allMessages's own history cap. Main-thread only.
     */
    private final Set<GuiMessage> patchedUnsigned = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<GuiMessage> patchedUnsignedOrder = new ArrayDeque<>();

    /**
     * Completed translations awaiting a matching displayed line - see "Retry queue"
     * in the class Javadoc. Added to from the async translation-completion callback
     * (any thread), drained only from {@link #onClientTick(Minecraft)} (main thread).
     */
    private final Queue<PendingPatch> pendingPatches = new ConcurrentLinkedQueue<>();

    /** Mutable holder for a single completed-translation patch attempt in flight; see {@link #pendingPatches}. */
    private static final class PendingPatch {
        final @Nullable MessageSignature signature;
        final String expectedOriginalLine;
        final int tickAtReceipt;
        final ChatType.Bound boundChatType;
        final String translatedBody;
        final Style bodyStyle;
        final String originalPlainForSuffix;
        /** Main-thread only (only ever read/written from onClientTick). */
        int attempts;

        PendingPatch(@Nullable MessageSignature signature, String expectedOriginalLine, int tickAtReceipt,
                     ChatType.Bound boundChatType, String translatedBody, Style bodyStyle, String originalPlainForSuffix) {
            this.signature = signature;
            this.expectedOriginalLine = expectedOriginalLine;
            this.tickAtReceipt = tickAtReceipt;
            this.boundChatType = boundChatType;
            this.translatedBody = translatedBody;
            this.bodyStyle = bodyStyle;
            this.originalPlainForSuffix = originalPlainForSuffix;
        }
    }

    public ChatTranslationCoordinator(TranslationService translationService,
                                       LanguageResolver languageResolver,
                                       ConfigManager configManager) {
        this.translationService = translationService;
        this.languageResolver = languageResolver;
        this.configManager = configManager;
    }

    /**
     * @param playerChatMessage the full chat message as received (Fabric: 2nd param of
     *                          ClientReceiveMessageEvents.Chat; NeoForge:
     *                          ClientChatReceivedEvent.Player#getPlayerChatMessage()).
     *                          May be unsigned (signature() == null).
     * @param boundChatType     the same ChatType.Bound vanilla used to decorate the message -
     *                          required both to re-decorate the translated body with
     *                          username/formatting intact, and (for unsigned messages) to
     *                          compute the exact original displayed line for later matching.
     */
    public void onPlayerChatReceived(PlayerChatMessage playerChatMessage, ChatType.Bound boundChatType) {
        if (playerChatMessage == null || boundChatType == null) return;
        if (!configManager.model().translationEnabled) return;
        // Phase 13 (real-world multiplayer testing, DEVELOPMENT_STATUS.md): confirmed the
        // double-translation scenario ARCHITECTURE.md §20.2 already flagged as a known
        // limitation actually happens (observed a recipient's already-server-translated text
        // being re-translated a second time client-side, with a local LLM's non-determinism
        // making the double-translation visibly obvious across repeated identical inputs).
        // A full fix needs a new server->client S2C payload telling this client "the server
        // already translated your chat, skip your own client-side pass" - that requires
        // from-source verification of MC 26.2's CustomPacketPayload/StreamCodec API first
        // (CLAUDE.md §3, out of scope for Phase 13) and is left for Phase 14+.
        //
        // Partial, no-new-network-required mitigation in the meantime: if THIS client's own
        // local config already has serverSideChatTranslationEnabled turned on (the same flag
        // used to opt in to Phase 6's server-side mode), assume the server may be translating
        // for it and skip this client-side pass entirely, rather than risk re-translating
        // already-translated text. This does not cover every case (e.g. a server admin
        // enabling server-side mode without every client also flipping their own local copy
        // of the same flag), but it is a reasonable, purely-local self-guard using information
        // the client already has, and it directly addresses the reproduced scenario above.
        if (configManager.model().serverSideChatTranslationEnabled) {
            AllTranslator.LOGGER.debug(
                    "Skipping client-side chat translation: serverSideChatTranslationEnabled is "
                            + "set locally, assuming the server may already translate this message "
                            + "(see ARCHITECTURE.md §20.2 known limitation).");
            return;
        }

        Component originalBody = playerChatMessage.decoratedContent();
        String plain = originalBody.getString();
        if (plain.isBlank()) return;

        MessageSignature signature = playerChatMessage.signature();
        Minecraft mc = Minecraft.getInstance();

        // Precomputed up front (cheap) regardless of signature presence, since it's
        // needed as the matching key in the unsigned case and is harmless overhead
        // (one Component decoration) otherwise.
        String expectedOriginalLine = boundChatType.decorate(originalBody).getString();
        int tickAtReceipt = mc.gui.hud.getGuiTicks();

        String dedupeKey = signature != null
                ? "sig:" + signature
                : "unsigned:" + expectedOriginalLine + ':' + tickAtReceipt;
        if (!inFlight.add(dedupeKey)) return;

        String targetLang = languageResolver.resolveTargetLanguage();
        PlaceholderProtector.Protected protectedText = PlaceholderProtector.protect(plain);
        TranslationRequest request = new TranslationRequest(protectedText.text(), null, targetLang);

        translationService.translate(request, false).whenComplete((result, error) -> {
            inFlight.remove(dedupeKey);
            if (error != null) {
                AllTranslator.LOGGER.warn("Chat translation failed for a message", error);
                return;
            }
            if (result == null || result.noTranslationNeeded()) return;

            String restored = PlaceholderProtector.restore(result.translatedText(), protectedText.tokens());
            if (restored == null || restored.equals(plain)) return;

            Style bodyStyle = originalBody.getStyle();
            // Phase 13 fix: enqueue for the retry loop instead of a single immediate
            // mc.execute() attempt - see "Retry queue" in the class Javadoc for why a
            // single attempt is not reliable, especially on a memory-cache-hit
            // translation that completes almost synchronously.
            pendingPatches.add(new PendingPatch(
                    signature, expectedOriginalLine, tickAtReceipt, boundChatType, restored, bodyStyle, plain));
        });
    }

    /**
     * Must be called once per client tick from a confirmed physical-client entrypoint
     * (wired from AllTranslatorClientCore via Architectury's common
     * ClientTickEvent.CLIENT_POST, which already runs on the main/client thread - see
     * AllTranslatorKeyBindings for the established pattern this follows). Drains
     * {@link #pendingPatches}, retrying each entry's match attempt; entries that
     * still don't match are re-queued (with an incremented attempt count) for the
     * next tick, up to {@link #MAX_PATCH_RETRY_TICKS} attempts, after which they are
     * dropped and logged. See "Retry queue" in the class Javadoc.
     */
    public void onClientTick(Minecraft mc) {
        if (pendingPatches.isEmpty()) return;

        // Snapshot the current size so we process each entry present at the start of
        // this tick exactly once, instead of potentially looping forever over
        // entries re-added to the tail of the same queue within this same tick.
        int snapshotSize = pendingPatches.size();
        for (int i = 0; i < snapshotSize; i++) {
            PendingPatch patch = pendingPatches.poll();
            if (patch == null) break;

            if (tryPatchDisplayedLine(mc, patch)) {
                continue;
            }

            patch.attempts++;
            if (patch.attempts >= MAX_PATCH_RETRY_TICKS) {
                AllTranslator.LOGGER.warn(
                        "Chat translation patch: gave up after {} ticks, no matching line found for '{}'",
                        MAX_PATCH_RETRY_TICKS, patch.translatedBody);
            } else {
                pendingPatches.add(patch);
            }
        }
    }

    /**
     * Single match-and-patch attempt for one pending entry.
     *
     * @return true if the entry is fully resolved (either successfully patched, or
     *         abandoned because the player disconnected) and should be dropped by
     *         the caller; false if no matching line was found THIS attempt and the
     *         caller should retry on a later tick.
     */
    private boolean tryPatchDisplayedLine(Minecraft mc, PendingPatch patch) {
        if (mc.player == null) return true; // disconnected before a match was found - nothing more to do

        ChatComponent chat = mc.gui.hud.getChat();
        ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
        List<GuiMessage> messages = accessor.alltranslator$getAllMessages();

        int targetIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            GuiMessage existing = messages.get(i);
            if (patch.signature != null) {
                if (patch.signature.equals(existing.signature())) {
                    targetIndex = i;
                    break;
                }
            } else if (existing.signature() == null
                    && existing.addedTime() >= patch.tickAtReceipt
                    && !patchedUnsigned.contains(existing)
                    && existing.content().getString().equals(patch.expectedOriginalLine)) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex == -1) {
            return false; // not found yet (or genuinely scrolled out) - caller decides whether to retry
        }

        GuiMessage existing = messages.get(targetIndex);
        if (patch.signature == null) {
            markPatched(existing);
        }

        MutableComponent translatedComponent = Component.literal(patch.translatedBody).setStyle(patch.bodyStyle);
        // Phase 13: optional "(original text)" suffix, OFF by default (ConfigModel
        // #showOriginalTextInChat). originalPlainForSuffix is the pre-translation
        // plain body (no username/formatting), matching what was actually sent to
        // the translation API - not expectedOriginalLine, which includes the
        // decorated username and would look wrong repeated inline.
        if (configManager.model().showOriginalTextInChat) {
            translatedComponent = translatedComponent.copy()
                    .append(Component.literal(" (" + patch.originalPlainForSuffix + ")").setStyle(patch.bodyStyle));
        }
        Component redecorated = patch.boundChatType.decorate(translatedComponent);
        messages.set(targetIndex, new GuiMessage(existing.addedTime(), redecorated, existing.signature(), existing.source(), existing.tag()));
        accessor.alltranslator$refreshTrimmedMessages();
        return true;
    }

    private void markPatched(GuiMessage message) {
        patchedUnsigned.add(message);
        patchedUnsignedOrder.addLast(message);
        if (patchedUnsignedOrder.size() > 100) {
            patchedUnsigned.remove(patchedUnsignedOrder.removeFirst());
        }
    }
}
