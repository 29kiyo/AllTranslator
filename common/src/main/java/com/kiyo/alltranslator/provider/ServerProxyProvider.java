package com.kiyo.alltranslator.provider;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.api.ApiFailureType;
import com.kiyo.alltranslator.api.ProviderType;
import com.kiyo.alltranslator.api.TranslationException;
import com.kiyo.alltranslator.api.TranslationProvider;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import com.kiyo.alltranslator.network.ClientProxyResponseRegistry;
import com.kiyo.alltranslator.network.ServerProxyPayloads;
import com.kiyo.alltranslator.service.InFlightCallRegistry;
import com.kiyo.alltranslator.service.TranslationApiConfig;
import dev.architectury.networking.NetworkManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 14 (SERVER_PROXY, ARCHITECTURE.md §12): client-only TranslationProvider
 * that asks the currently-joined server to translate on our behalf, using the
 * SERVER's own configured API/credentials instead of ours. Only ever registered
 * client-side (see ProviderFactory's Platform.getEnvironment() guard) - never on
 * a dedicated server. This does NOT by itself prevent a singleplayer integrated
 * server from selecting SERVER_PROXY as a candidate for its own outgoing
 * translate() calls (see TranslationService's excludeProvider overload, used by
 * AllTranslatorNetworking's server-side request handler, for that half of the
 * fix).
 *
 * Deliberately does NOT use InFlightCallRegistry (that's for raw HTTP
 * sendAsync() futures being force-cancellable by /alltranslator refresh - this
 * provider makes no HTTP call at all, only a Minecraft network payload
 * round-trip, so refresh cannot meaningfully cancel it mid-flight; the
 * registry parameter is accepted only to satisfy TranslationProvider's
 * interface and is otherwise unused here).
 */
public final class ServerProxyProvider implements TranslationProvider {

    private static final AtomicLong NEXT_REQUEST_ID = new AtomicLong();

    @Override
    public ProviderType type() {
        return ProviderType.SERVER_PROXY;
    }

    @Override
    public CompletableFuture<TranslationResult> translate(TranslationRequest request, TranslationApiConfig config,
                                                            String rawApiKey, InFlightCallRegistry registry) {
        if (!NetworkManager.canServerReceive(ServerProxyPayloads.Request.TYPE)) {
            return CompletableFuture.failedFuture(new TranslationException(ApiFailureType.CONFIG_ERROR, -1,
                    "Joined server does not support All Translator's server-proxy translation feature "
                            + "(not running All Translator, or serverProxyTranslationEnabled is off)."));
        }

        long requestId = NEXT_REQUEST_ID.incrementAndGet();
        CompletableFuture<ServerProxyPayloads.Response> responseFuture = ClientProxyResponseRegistry.register(requestId);

        int timeoutSeconds = AllTranslatorCore.configManager().model().serverProxyTimeoutSeconds;

        NetworkManager.sendToServer(new ServerProxyPayloads.Request(
                requestId,
                request.sourceText(),
                request.sourceLang() == null ? "" : request.sourceLang(),
                request.targetLang()));

        return responseFuture
                // Deliberately finite: a request the server never answers (disconnect,
                // feature disabled after we already checked canServerReceive, server-side
                // bug) must not leave this future pending forever - see this class's
                // Javadoc and DEVELOPMENT_STATUS.md's STUCK_TRACKING investigation notes
                // for why an unbounded wait here would be a structural risk.
                .orTimeout(Math.max(1, timeoutSeconds), TimeUnit.SECONDS)
                .whenComplete((r, e) -> ClientProxyResponseRegistry.cancel(requestId))
                .thenApply(response -> {
                    if (!response.success()) {
                        throw new TranslationException(ApiFailureType.CONFIG_ERROR, -1,
                                "Server declined the proxy translation request "
                                        + "(feature disabled server-side, or the server's own translate attempt failed).");
                    }
                    return new TranslationResult(response.translatedText(), request.sourceText(), request.targetLang(),
                            config.id(), false, response.noTranslationNeeded());
                });
    }

    @Override
    public ApiFailureType classifyError(Throwable error, int httpStatus) {
        if (error instanceof TimeoutException) {
            return ApiFailureType.TEMP_UNAVAILABLE;
        }
        return ApiFailureType.CONFIG_ERROR;
    }
}
