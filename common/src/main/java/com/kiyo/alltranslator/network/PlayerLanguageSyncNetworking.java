package com.kiyo.alltranslator.network;

import com.kiyo.alltranslator.AllTranslatorCore;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * Phase 14 (M-key auto-sync, user request): common (both-physical-side-safe) networking
 * wiring for PlayerLanguageSyncPayloads. Registered unconditionally from AllTranslatorCore
 * #init() - unlike ServerConfigNetworking/AllTranslatorNetworking, this payload is C2S-ONLY
 * (no matching S2C response type), so there is no "registerS2CPayloadType on dedicated
 * server only" step needed here - NetworkManager.registerC2S(...) alone registers both the
 * client's send-capability and the server's receive-handler from this one common call,
 * exactly like AllTranslatorNetworking's Request registration.
 */
public final class PlayerLanguageSyncNetworking {

    private PlayerLanguageSyncNetworking() {}

    /** Called once from AllTranslatorCore#init() (both physical sides). */
    public static void registerCommon() {
        NetworkManager.registerC2S(PlayerLanguageSyncPayloads.Sync.TYPE, PlayerLanguageSyncPayloads.Sync.STREAM_CODEC,
                PlayerLanguageSyncNetworking::handleOnServer);
    }

    private static void handleOnServer(PlayerLanguageSyncPayloads.Sync payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer sender)) {
                return;
            }
            var settingsManager = AllTranslatorCore.playerTranslationSettingsManager();
            if (settingsManager == null) return;
            settingsManager.setAutoSyncedLanguage(sender.getUUID(), payload.languageCode());
        });
    }
}
