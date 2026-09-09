package com.kiyo.alltranslator.network;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 14 (SERVER_PROXY): client-only static registry matching outgoing
 * ServerProxyPayloads.Request ids to the CompletableFuture waiting on the
 * matching S2C Response. Static-shared, same pattern as
 * TranslatableTextInterceptor's byCacheKey/knownOutputs (see that class's
 * Javadoc) - there is exactly one client per JVM, so this is safe.
 *
 * Every future registered here MUST eventually be removed either by complete()
 * (normal path) or cancel() (timeout/cleanup path) - see ServerProxyProvider,
 * which always does one or the other via whenComplete(). An entry that is
 * neither completed nor cancelled would leak memory for the rest of the
 * session; this is the same category of concern
 * TranslatableTextInterceptor's (currently unresolved, separately tracked)
 * STUCK_TRACKING diagnostic exists for a different future map, but here it is
 * structurally avoided by ServerProxyProvider's own finite orTimeout(...).
 */
public final class ClientProxyResponseRegistry {

    private static final ConcurrentHashMap<Long, CompletableFuture<ServerProxyPayloads.Response>> PENDING =
            new ConcurrentHashMap<>();

    private ClientProxyResponseRegistry() {}

    public static CompletableFuture<ServerProxyPayloads.Response> register(long requestId) {
        CompletableFuture<ServerProxyPayloads.Response> future = new CompletableFuture<>();
        PENDING.put(requestId, future);
        return future;
    }

    /** Called on timeout/completion cleanup so a stale id can't later be "completed" for nothing. */
    public static void cancel(long requestId) {
        PENDING.remove(requestId);
    }

    /** Called from the client's S2C receiver (AllTranslatorNetworking#registerClientReceiver). */
    public static void complete(ServerProxyPayloads.Response response) {
        CompletableFuture<ServerProxyPayloads.Response> future = PENDING.remove(response.requestId());
        if (future != null) {
            future.complete(response);
        }
        // A null future here just means the request already timed out client-side
        // and was removed by ServerProxyProvider's cancel() - a legitimately late
        // response, not an error.
    }
}
