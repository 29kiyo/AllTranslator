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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 *    on the main thread via Minecraft#execute + an Accessor/Invoker Mixin on
 *    ChatComponent (ChatComponent has no public API to replace a past line - see
 *    ChatComponentAccessor).
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
 *
 * Known limitations (documented, not silently skipped - see DEVELOPMENT_STATUS.md):
 *  - If a message (signed or unsigned) scrolls out of ChatComponent's 100-entry
 *    history before the async translation completes, the patch is silently skipped.
 *  - System/disguised chat (no PlayerChatMessage at all - death messages, /me via
 *    disguised chat, etc.) is out of scope for Phase 5.
 */
public final class ChatTranslationCoordinator {

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
            mc.execute(() -> patchDisplayedLine(
                    mc, signature, expectedOriginalLine, tickAtReceipt, boundChatType, restored, bodyStyle, plain));
        });
    }

    private void patchDisplayedLine(Minecraft mc, @Nullable MessageSignature signature, String expectedOriginalLine,
                                     int tickAtReceipt, ChatType.Bound boundChatType, String translatedBody, Style bodyStyle,
                                     String originalPlainForSuffix) {
        if (mc.player == null) return; // disconnected before translation finished

        ChatComponent chat = mc.gui.hud.getChat();
        ChatComponentAccessor accessor = (ChatComponentAccessor) chat;
        List<GuiMessage> messages = accessor.alltranslator$getAllMessages();

        int targetIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            GuiMessage existing = messages.get(i);
            if (signature != null) {
                if (signature.equals(existing.signature())) {
                    targetIndex = i;
                    break;
                }
            } else if (existing.signature() == null
                    && existing.addedTime() >= tickAtReceipt
                    && !patchedUnsigned.contains(existing)
                    && existing.content().getString().equals(expectedOriginalLine)) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex == -1) {
            // Scrolled out of history, or (unsigned case) no matching line found yet - skip.
            return;
        }

        GuiMessage existing = messages.get(targetIndex);
        if (signature == null) {
            markPatched(existing);
        }

        MutableComponent translatedComponent = Component.literal(translatedBody).setStyle(bodyStyle);
        // Phase 13: optional "(original text)" suffix, OFF by default (ConfigModel
        // #showOriginalTextInChat). originalPlainForSuffix is the pre-translation
        // plain body (no username/formatting), matching what was actually sent to
        // the translation API - not expectedOriginalLine, which includes the
        // decorated username and would look wrong repeated inline.
        if (configManager.model().showOriginalTextInChat) {
            translatedComponent = translatedComponent.copy()
                    .append(Component.literal(" (" + originalPlainForSuffix + ")").setStyle(bodyStyle));
        }
        Component redecorated = boundChatType.decorate(translatedComponent);
        messages.set(targetIndex, new GuiMessage(existing.addedTime(), redecorated, existing.signature(), existing.source(), existing.tag()));
        accessor.alltranslator$refreshTrimmedMessages();
    }

    private void markPatched(GuiMessage message) {
        patchedUnsigned.add(message);
        patchedUnsignedOrder.addLast(message);
        if (patchedUnsignedOrder.size() > 100) {
            patchedUnsigned.remove(patchedUnsignedOrder.removeFirst());
        }
    }
}
