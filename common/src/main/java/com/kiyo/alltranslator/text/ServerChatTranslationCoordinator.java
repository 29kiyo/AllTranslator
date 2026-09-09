package com.kiyo.alltranslator.text;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.server.PerPlayerLanguageResolver;
import com.kiyo.alltranslator.service.TranslationService;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6: OPT-IN server-side per-player chat translation (ARCHITECTURE.md §9's "サーバー側翻訳
 * モード"). Off by default - see ConfigModel#serverSideChatTranslationEnabled. Default chat
 * translation remains Phase 5's client-side coordinator, which needs no server support at all.
 *
 * Hooked from mixin/PlayerListMixin, which @Redirects the per-recipient
 * ServerPlayer#sendChatMessage(...) call inside PlayerList's private
 * broadcastChatMessage(PlayerChatMessage, Predicate, ServerPlayer, ChatType.Bound) loop
 * (confirmed against 26.2 sources: this private method is what both public
 * broadcastChatMessage overloads delegate to, and is exactly where the per-recipient delivery
 * loop lives - one ServerPlayer#sendChatMessage(...) call per online player).
 *
 * Per-recipient delivery, never touching the vanilla PlayerChatMessage's signature:
 *  - The sender never has their own message translated (translating what you just typed, in
 *    your own language, back to your own language is pointless) - detected via
 *    PlayerChatMessage#sender(), no need to separately track the ServerPlayer sender instance.
 *  - Phase 14 EXCEPTION (real-world bug found in singleplayer): the sender-skip rule above
 *    combines badly with ChatTranslationCoordinator's own Phase 13 self-guard (a client whose
 *    LOCAL config.json has serverSideChatTranslationEnabled=true skips its own Phase 5
 *    client-side translation, trusting the server to handle it) when sender and recipient are
 *    the SAME single player, as they always are in singleplayer: the client skips translating
 *    because "the server will do it," and the server skips translating because "don't
 *    translate the sender's own message" - so nothing ever translates the player's own chat
 *    for themselves. Since the ONLY way to read your own translated chat at all is via this
 *    server-side path when you're also the only recipient, the sender-skip rule is relaxed
 *    specifically when the server currently has exactly one online player: that lone player IS
 *    both the sender and the only intended recipient, so translating their own message for
 *    their own reading is not the "pointless self-translation" case the rule exists to avoid -
 *    it is the only way they ever see a translation at all. Any second player joining
 *    immediately restores the normal (server-side) sender-skip behavior for ordinary
 *    multiplayer, since "am I the only online player" is re-evaluated per message.
 *  - Recipients with server-side translation OFF (server-wide switch off, or their own opt-out
 *    via PlayerTranslationSettingsManager) are sent the untouched OutgoingChatMessage
 *    immediately, exactly as vanilla would - completely unaffected by this feature being
 *    enabled for other players.
 *  - Recipients with it ON: delivery to THAT recipient is held until the async translation
 *    (Phase 2's TranslationService, multi-API failover, memory-LRU only per §8.1) completes,
 *    then sent via PlayerChatMessage#withUnsignedContent(...) (the same vanilla mechanism chat
 *    filters/decorators use to substitute displayed text without touching signedContent()/the
 *    signature) wrapped in a fresh OutgoingChatMessage.Player.
 *
 * This is a deliberate, documented difference from Phase 5: unlike the client-side coordinator
 * (which never delays vanilla's own display), here delivery TO THIS SPECIFIC RECIPIENT is
 * delayed by one async translation round-trip. Every other recipient (including the sender) is
 * sent their copy immediately and is completely unaffected, since each recipient's packet is
 * sent independently in the vanilla loop.
 *
 * KNOWN LIMITATION (see DEVELOPMENT_STATUS.md): if a recipient has BOTH this server-side mode
 * active for them AND their own client still running Phase 5's client-side
 * ChatTranslationCoordinator, the message will be translated twice (server, then client-side
 * again on the already-translated text). There is currently no server->client signal telling a
 * client "this server already translated your chat, skip your own client-side chat
 * translation" - that would require a new custom S2C payload, which needs its own from-source
 * verification of MC 26.2's CustomPacketPayload/StreamCodec API before implementing (out of
 * scope for this phase per CLAUDE.md §3 - no speculative API usage; flagged for Phase 7/8).
 */
public final class ServerChatTranslationCoordinator {

    private final TranslationService translationService;
    private final PerPlayerLanguageResolver languageResolver;
    private final ConfigManager configManager;

    /** Guards against the same (recipient, message) pair being processed twice concurrently. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ServerChatTranslationCoordinator(TranslationService translationService,
                                             PerPlayerLanguageResolver languageResolver,
                                             ConfigManager configManager) {
        this.translationService = translationService;
        this.languageResolver = languageResolver;
        this.configManager = configManager;
    }

    /**
     * @param recipient the player this specific OutgoingChatMessage delivery is for
     *                  (PlayerListMixin's @Redirect fires once per recipient, per the vanilla
     *                  per-player loop in PlayerList#broadcastChatMessage).
     * @param message   the original (untranslated, still fully signed) PlayerChatMessage.
     * @param filtered  whether profanity-filtering should apply to this recipient (same flag
     *                  vanilla's own OutgoingChatMessage.Player#sendToPlayer uses).
     * @param chatType  the same ChatType.Bound vanilla used for this broadcast.
     */
    public void handleOutgoing(ServerPlayer recipient, PlayerChatMessage message, boolean filtered, ChatType.Bound chatType) {
        if (!configManager.model().translateChat) {
            recipient.sendChatMessage(new OutgoingChatMessage.Player(message), filtered, chatType);
            return;
        }
        if (recipient.getUUID().equals(message.sender()) && !isSoleOnlinePlayer(recipient)) {
            recipient.sendChatMessage(new OutgoingChatMessage.Player(message), filtered, chatType);
            return;
        }

        if (!languageResolver.isEnabledFor(recipient)) {
            recipient.sendChatMessage(new OutgoingChatMessage.Player(message), filtered, chatType);
            return;
        }

        String plain = message.decoratedContent().getString();
        if (plain.isBlank()) {
            recipient.sendChatMessage(new OutgoingChatMessage.Player(message), filtered, chatType);
            return;
        }

        String dedupeKey = recipient.getUUID() + ":"
                + (message.signature() != null ? message.signature().toString() : message.link().toString());
        if (!inFlight.add(dedupeKey)) {
            // Already in flight for this exact (recipient, message) pair - drop this duplicate
            // call rather than deliver twice. Should not normally happen (the vanilla loop
            // calls us once per recipient) - defensive only, mirrors Phase 5's inFlight guard.
            return;
        }

        String targetLang = languageResolver.resolve(recipient);
        Style bodyStyle = message.decoratedContent().getStyle();
        PlaceholderProtector.Protected protectedText = PlaceholderProtector.protect(plain);
        TranslationRequest request = new TranslationRequest(protectedText.text(), null, targetLang);

        translationService.translate(request, false).whenComplete((result, error) -> {
            inFlight.remove(dedupeKey);

            MinecraftServer server = recipient.level().getServer();
            server.execute(() -> {
                PlayerChatMessage outgoing = message;
                if (error != null) {
                    AllTranslator.LOGGER.warn("Server-side chat translation failed for a message", error);
                } else if (result != null && !result.noTranslationNeeded()) {
                    String restored = PlaceholderProtector.restore(result.translatedText(), protectedText.tokens());
                    if (restored != null && !restored.equals(plain)) {
                        MutableComponent translatedComponent = Component.literal(restored).setStyle(bodyStyle);
                        // Phase 13 (multiplayer real-translation verification):
                        // ARCHITECTURE.md's server-side mode has no per-recipient knowledge of
                        // each client's own showOriginalTextInChat preference (that flag lives
                        // only in each client's local config.json and is never synced to the
                        // server - would need a new C2S payload, out of scope per CLAUDE.md §3).
                        // Known, documented limitation: this uses the SERVER ADMIN's own
                        // showOriginalTextInChat setting (configManager.model(), the server's
                        // local config) applied uniformly to every recipient of server-side
                        // translation, rather than each individual recipient's own preference.
                        if (configManager.model().showOriginalTextInChat) {
                            translatedComponent = translatedComponent.copy()
                                    .append(Component.literal(" (" + plain + ")").setStyle(bodyStyle));
                        }
                        outgoing = message.withUnsignedContent(translatedComponent);
                    }
                }

                // ServerPlayer#sendChatMessage already guards on acceptsChatMessages() internally
                // (confirmed against 26.2 sources), so no separate disconnect check is needed here.
                recipient.sendChatMessage(new OutgoingChatMessage.Player(outgoing), filtered, chatType);
            });
        });
    }

    /**
     * Phase 14: true if `player` is currently the only entry in the server's online
     * player list - see the sender-skip exception in this class's Javadoc for why
     * this matters. Cheap (a size() call on the already-maintained online-player
     * list, no iteration/allocation beyond what getPlayers() itself does).
     */
    private static boolean isSoleOnlinePlayer(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server != null && server.getPlayerList().getPlayers().size() == 1;
    }
}
