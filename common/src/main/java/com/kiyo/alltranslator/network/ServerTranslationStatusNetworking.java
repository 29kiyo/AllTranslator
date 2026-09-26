package com.kiyo.alltranslator.network;

import com.kiyo.alltranslator.config.ConfigModel;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.server.level.ServerPlayer;

/**
 * Registration + send helpers for ServerTranslationStatusPayloads.Status.
 * Type registration is gated to SERVER only (lesson from SERVER_PROXY: a
 * shared-JVM singleplayer client would otherwise double-register the type
 * if this were also called unconditionally from common init).
 */
public final class ServerTranslationStatusNetworking {

    private ServerTranslationStatusNetworking() {}

    public static void registerCommon() {
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(
                    ServerTranslationStatusPayloads.Status.TYPE,
                    ServerTranslationStatusPayloads.Status.STREAM_CODEC
            );
        }
    }

    /** Send the current serverSideChatTranslationEnabled value to one player (e.g. on join). */
    public static void sendToPlayer(ServerPlayer player, ConfigModel config) {
        if (NetworkManager.canPlayerReceive(player, ServerTranslationStatusPayloads.Status.TYPE)) {
            NetworkManager.sendToPlayer(player,
                    new ServerTranslationStatusPayloads.Status(config.serverSideChatTranslationEnabled));
        }
    }

    /** Re-notify every currently connected player (e.g. after a remote config save). */
    public static void broadcastToAll(Iterable<ServerPlayer> players, ConfigModel config) {
        for (ServerPlayer player : players) {
            sendToPlayer(player, config);
        }
    }
}
