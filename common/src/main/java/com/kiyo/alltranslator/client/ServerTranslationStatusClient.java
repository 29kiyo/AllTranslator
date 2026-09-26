package com.kiyo.alltranslator.client;

import com.kiyo.alltranslator.network.ServerTranslationStatusPayloads;
import dev.architectury.networking.NetworkManager;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client-side holder for the server-told "is server-side chat translation
 * active for me" flag. ChatTranslationCoordinator reads this instead of its
 * own local config.json value, so an admin-only server-side toggle is no
 * longer invisible to a client that never set the same local flag.
 * Defaults to false (do own client-side translation) until told otherwise,
 * or after a disconnect/reset - matching pre-fix behavior for unmodded/older
 * servers that never send this payload at all.
 */
public final class ServerTranslationStatusClient {

    private static final AtomicBoolean SERVER_SIDE_ACTIVE = new AtomicBoolean(false);

    private ServerTranslationStatusClient() {}

    public static void registerReceiver() {
        NetworkManager.registerReceiver(
                NetworkManager.s2c(),
                ServerTranslationStatusPayloads.Status.TYPE,
                ServerTranslationStatusPayloads.Status.STREAM_CODEC,
                (payload, context) -> SERVER_SIDE_ACTIVE.set(payload.active())
        );
    }

    public static boolean isServerSideActive() {
        return SERVER_SIDE_ACTIVE.get();
    }

    public static void reset() {
        SERVER_SIDE_ACTIVE.set(false);
    }
}
