package com.kiyo.alltranslator.network;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/**
 * Phase 14 (SERVER_PROXY, ARCHITECTURE.md §12): common (both-physical-side-safe)
 * networking wiring for the "translate via the server's own API credentials"
 * opt-in feature. A client's own API key is never sent to the server, and the
 * server's API key is never sent to a client - only plain-text "translate this"
 * requests and "here's the translated text" responses cross the wire.
 *
 * registerCommon() is safe to call unconditionally from AllTranslatorCore.init()
 * (both dedicated server and client/integrated server) because the server-side
 * receive handler below only references ServerPlayer/MinecraftServer-adjacent
 * common classes - same established pattern as
 * ServerChatTranslationCoordinator's wiring in AllTranslatorCore. The CLIENT-side
 * receiver (for the S2C response) is registered separately by
 * registerClientReceiver(), called only from AllTranslatorClientCore.init(), per
 * that class's existing "client-only wiring goes here, never in the common
 * init()" rule.
 *
 * NOT YET VERIFIED AT RUNTIME (flagging honestly, CLAUDE.md §23): whether
 * NetworkManager.canServerReceive(...)/sendToServer(...) being called from
 * TranslationService's asyncExecutor thread (not the client main/render thread)
 * works correctly, or needs to be marshalled onto the client thread first. This
 * should be checked the first time ServerProxyProvider is actually exercised
 * in-game; if it misbehaves, wrap the relevant calls in
 * net.minecraft.client.Minecraft.getInstance().execute(...) from
 * ServerProxyProvider instead (that file is currently common/client-agnostic on
 * its face - doing so would require moving it or its call sites client-side).
 */
public final class AllTranslatorNetworking {

    private AllTranslatorNetworking() {}

    /** Called once from AllTranslatorCore#init() (both physical sides). */
    public static void registerCommon() {
        // Phase 14 crash fix (real-world bug, see DEVELOPMENT_STATUS.md): only a
        // DEDICATED server needs this call. NetworkManager.registerReceiver(...)
        // (called from registerClientReceiver() below, client-only) already
        // performs full type registration AND receiver attachment in one go
        // (confirmed via the actual crash stack: NetworkAggregator.registerReceiver
        // -> registerS2CReceiver -> PayloadTypeRegistryImpl.register) - calling
        // registerS2CPayloadType here unconditionally duplicated that registration
        // on the SAME registry in a client/singleplayer JVM (common init runs
        // there too), throwing IllegalArgumentException("already registered") the
        // moment AllTranslatorClientCore.init() ran registerClientReceiver()
        // afterward. A dedicated server never calls registerClientReceiver() (it's
        // physical-client-only), so it still needs this call to be ABLE to send
        // the payload at all.
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(ServerProxyPayloads.Response.TYPE, ServerProxyPayloads.Response.STREAM_CODEC);
        }

        // registerC2S registers BOTH the send-capability (client) and the
        // receive-handler (server) from this one common call, since the receiver
        // below only touches common (non-client-only) classes.
        NetworkManager.registerC2S(ServerProxyPayloads.Request.TYPE, ServerProxyPayloads.Request.STREAM_CODEC,
                AllTranslatorNetworking::handleRequestOnServer);
    }

    private static void handleRequestOnServer(ServerProxyPayloads.Request payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            var configManager = AllTranslatorCore.configManager();
            if (configManager == null || !configManager.model().serverProxyTranslationEnabled) {
                sendFailure(context, payload.requestId());
                return;
            }
            var translationService = AllTranslatorCore.translationService();
            if (translationService == null) {
                sendFailure(context, payload.requestId());
                return;
            }
            String sourceLang = (payload.sourceLang() == null || payload.sourceLang().isEmpty())
                    ? null : payload.sourceLang();
            TranslationRequest request = new TranslationRequest(payload.sourceText(), sourceLang, payload.targetLang());
            // persistable=false: this keyless proxy path carries no category/namespace
            // info (ARCHITECTURE.md §19), so it can't be safely filed into the
            // world-save persistent cache - memory-only, same as chat (§8.1).
            // excludeProvider=SERVER_PROXY: prevents a singleplayer integrated server
            // (Platform.getEnvironment() is CLIENT there - see ProviderFactory) from
            // routing this request back through SERVER_PROXY itself. See
            // TranslationService's excludeProvider overload Javadoc.
            CompletableFuture<TranslationResult> future =
                    translationService.translate(request, false, ProviderType.SERVER_PROXY);
            future.whenComplete((result, error) -> {
                if (error != null || result == null) {
                    AllTranslator.LOGGER.warn("SERVER_PROXY: translation failed while serving a proxy request", error);
                    sendFailure(context, payload.requestId());
                    return;
                }
                if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
                    NetworkManager.sendToPlayer(serverPlayer,
                            new ServerProxyPayloads.Response(payload.requestId(), true,
                                    result.translatedText(), result.noTranslationNeeded()));
                }
            });
        });
    }

    private static void sendFailure(NetworkManager.PacketContext context, long requestId) {
        if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
            NetworkManager.sendToPlayer(serverPlayer, new ServerProxyPayloads.Response(requestId, false, "", false));
        }
    }

    /**
     * Client-only: registers the receive-handler for the S2C response. MUST be
     * called only from AllTranslatorClientCore.init() (confirmed physical
     * client), never from registerCommon()/AllTranslatorCore.init() - see this
     * class's own Javadoc.
     */
    public static void registerClientReceiver() {
        NetworkManager.registerReceiver(NetworkManager.s2c(), ServerProxyPayloads.Response.TYPE,
                ServerProxyPayloads.Response.STREAM_CODEC,
                (payload, context) -> ClientProxyResponseRegistry.complete(payload));
    }
}
