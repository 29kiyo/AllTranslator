package com.kiyo.alltranslator.client;

import com.kiyo.alltranslator.AllTranslatorCore;
import dev.architectury.networking.NetworkManager;
import com.kiyo.alltranslator.network.PlayerLanguageSyncPayloads;
import net.minecraft.client.Minecraft;

/**
 * Phase 14 (M-key auto-sync, user request): tiny client-only helper that sends this
 * client's own ConfigModel#forcedTargetLanguage to whatever server it is currently
 * connected to, via PlayerLanguageSyncPayloads.Sync. Kept as its own small class (rather
 * than inlined at each call site) since it is called from two different places
 * (AllTranslatorClientCore's login-event listener, and AllTranslatorConfigScreen's save
 * path) that should not otherwise need to know about NetworkManager.canServerReceive(...)
 * plumbing.
 *
 * MUST be client-only (references Minecraft.getInstance()) - never call from common
 * AllTranslatorCore.init() code, same rule as everything else in the client package.
 *
 * Silently no-ops if not currently connected to any server (NetworkManager
 * .canServerReceive(...) returns false) or the connection can't accept this payload type
 * (e.g. a vanilla/other server without this mod) - this is a best-effort convenience
 * sync, not a required handshake, so failure here is never user-visible.
 */
public final class PlayerLanguageSyncClient {

    private PlayerLanguageSyncClient() {}

    /**
     * Sends the CURRENT value of ConfigModel#forcedTargetLanguage (blank/null becomes an
     * empty string on the wire - see PlayerLanguageSyncPayloads.Sync's Javadoc).
     */
    public static void sendCurrentLanguage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        // javap-confirmed signature (architectury-fabric-21.0.7.jar):
        // canServerReceive(CustomPacketPayload.Type<?>) - single-arg overload,
        // no Side parameter (that was an incorrect guess - CLAUDE.md §3 fix).
        if (!NetworkManager.canServerReceive(PlayerLanguageSyncPayloads.Sync.TYPE)) return;

        String forced = AllTranslatorCore.configManager().model().forcedTargetLanguage;
        String toSend = (forced == null) ? "" : forced;
        NetworkManager.sendToServer(new PlayerLanguageSyncPayloads.Sync(toSend));
    }
}
