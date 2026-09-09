package com.kiyo.alltranslator.text;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.server.PerPlayerLanguageResolver;
import com.kiyo.alltranslator.service.TranslationService;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 14 (item 7, "tellraw/mod/データパック由来メッセージ翻訳" - Option A per user
 * decision, see DEVELOPMENT_STATUS.md's 方針決定の追記 section): OPT-IN (default ON)
 * per-player translation of /tellraw command output specifically - deliberately NOT a
 * general ServerPlayer#sendSystemMessage hook.
 *
 * SCOPE DECISION (Option A, confirmed with user): ServerPlayer#sendSystemMessage(Component)
 * is used by far more than /tellraw - confirmed via javap against the MC 26.2 merged jar
 * that CommandSourceStack#sendSuccess/#sendFailure, the gamerule logAdminCommands OP
 * broadcast path, and ServerPlayer#sendBuildLimitMessage all funnel through the SAME
 * ServerPlayer#sendSystemMessage(Component) overload /tellraw uses. Hooking that method
 * directly would translate every command's feedback on every invocation - excessive API
 * calls (CLAUDE.md §7) and far outside what the user asked for. Instead this hooks
 * TellRawCommand itself (TellRawCommandMixin), confirmed via javap to be a simple
 * per-target loop:
 *   for (ServerPlayer p : EntityArgument.getPlayers(ctx, "targets"))
 *       p.sendSystemMessage(ComponentArgument.getResolvedComponent(ctx, "message", p));
 * - i.e. exactly one ServerPlayer#sendSystemMessage call per tellraw target, which is the
 * single call site TellRawCommandMixin @Redirects.
 *
 * Structurally mirrors ServerChatTranslationCoordinator (Phase 6) per user decision:
 * per-recipient delayed delivery pending an async translation (Phase 2's
 * TranslationService, multi-API failover, same ApiManager/ApiState/cache machinery).
 * Simpler than that class in one respect: tellraw's Component carries no
 * PlayerChatMessage/signature to preserve, so the translated result is delivered by
 * simply calling sendSystemMessage(...) again with a new Component - no
 * withUnsignedContent(...) analog needed.
 *
 * Enable/language resolution: uses PerPlayerLanguageResolver#isEnabledForSystemMessages()/
 * #resolve() (Phase 14 addition) rather than #isEnabledFor()/the chat-specific gate, since
 * tellraw translation is independent of ConfigModel#serverSideChatTranslationEnabled - a
 * server may want one on without the other.
 *
 * Persistent cache (user decision): unlike chat (ARCHITECTURE.md §8.1, memory-LRU only),
 * tellraw text IS persisted to the world-save cache (persistable=true passed to
 * TranslationService#translate) - tellraw messages are typically fixed/repeated
 * announcement text (unlike free-form chat), so caching across restarts/players has real
 * value. Uses the same flat cache-key scheme as everything else (§8.4) - no category/
 * namespace file segregation, since ARCHITECTURE.md §19's planned category/namespace
 * file-split remains unimplemented project-wide, not just here.
 *
 * UNVERIFIED (flagged per CLAUDE.md §3 rather than silently assumed): whether
 * ServerPlayer#sendSystemMessage(Component) is safe to call on an already-disconnected
 * player has not been confirmed via javap/sources for this class (unlike
 * ServerChatTranslationCoordinator's sendChatMessage, which existing project comments say
 * was confirmed to internally guard on acceptsChatMessages()). This class checks
 * recipient.connection != null defensively before the delayed send as a precaution, but
 * that check itself is not a confirmed substitute for whatever sendSystemMessage may or may
 * not already do internally - worth a follow-up javap check if a disconnect-related
 * exception is ever observed in practice.
 */
public final class TellrawTranslationCoordinator {

    private final TranslationService translationService;
    private final PerPlayerLanguageResolver languageResolver;
    private final ConfigManager configManager;

    /**
     * Guards against the same (recipient, message-instance) pair being processed twice
     * concurrently. Uses System.identityHashCode(message) rather than a
     * PlayerChatMessage-style natural identity (tellraw's plain Component has none) - this
     * is defensive only, mirroring ServerChatTranslationCoordinator's inFlight guard, since
     * TellRawCommandMixin's @Redirect fires exactly once per (recipient, this specific
     * resolved Component) in the normal case.
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public TellrawTranslationCoordinator(TranslationService translationService,
                                          PerPlayerLanguageResolver languageResolver,
                                          ConfigManager configManager) {
        this.translationService = translationService;
        this.languageResolver = languageResolver;
        this.configManager = configManager;
    }

    /**
     * @param recipient one /tellraw target, as TellRawCommandMixin's @Redirect fires once
     *                  per target (see class Javadoc: TellRawCommand's per-target loop).
     * @param message   the resolved Component for THIS recipient (ComponentArgument
     *                  .getResolvedComponent is called per-target with that target as the
     *                  resolving Entity, so selectors like @s already differ per recipient
     *                  before this method ever sees it).
     */
    public void handleOutgoing(ServerPlayer recipient, Component message) {
        if (!languageResolver.isEnabledForSystemMessages(recipient)) {
            recipient.sendSystemMessage(message);
            return;
        }

        String plain = message.getString();
        if (plain.isBlank()) {
            recipient.sendSystemMessage(message);
            return;
        }

        String dedupeKey = recipient.getUUID() + ":" + System.identityHashCode(message);
        if (!inFlight.add(dedupeKey)) {
            // Defensive only - see field Javadoc. Should not normally happen.
            recipient.sendSystemMessage(message);
            return;
        }

        String targetLang = languageResolver.resolve(recipient);
        Style style = message.getStyle();
        PlaceholderProtector.Protected protectedText = PlaceholderProtector.protect(plain);
        TranslationRequest request = new TranslationRequest(protectedText.text(), null, targetLang);

        // persistable=true (user decision): unlike chat, tellraw output is persisted to the
        // world-save cache - see class Javadoc "Persistent cache" section.
        translationService.translate(request, true).whenComplete((result, error) -> {
            inFlight.remove(dedupeKey);

            MinecraftServer server = recipient.level().getServer();
            server.execute(() -> {
                Component outgoing = message;
                if (error != null) {
                    AllTranslator.LOGGER.warn("Tellraw translation failed for a message", error);
                } else if (result != null && !result.noTranslationNeeded()) {
                    String restored = PlaceholderProtector.restore(result.translatedText(), protectedText.tokens());
                    if (restored != null && !restored.equals(plain)) {
                        MutableComponent translatedComponent = Component.literal(restored).setStyle(style);
                        // Reuses the same admin-wide showOriginalTextInChat setting
                        // ServerChatTranslationCoordinator uses for server-side chat mode -
                        // see that class's Javadoc for why this is the server admin's own
                        // setting applied uniformly, not a per-recipient preference.
                        if (configManager.model().showOriginalTextInChat) {
                            translatedComponent = translatedComponent.copy()
                                    .append(Component.literal(" (" + plain + ")").setStyle(style));
                        }
                        outgoing = translatedComponent;
                    }
                }
                // See class Javadoc "UNVERIFIED" note - this null check is a precaution, not
                // a confirmed-necessary guard.
                if (recipient.connection != null) {
                    recipient.sendSystemMessage(outgoing);
                }
            });
        });
    }
}
