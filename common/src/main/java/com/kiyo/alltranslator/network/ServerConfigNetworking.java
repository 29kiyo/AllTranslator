package com.kiyo.alltranslator.network;

import com.google.gson.Gson;
import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.service.TranslationApiConfig;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * Phase 14: remote server-config editing (/alltranslator config), built on the same C2S/S2C
 * payload pattern SERVER_PROXY established (see AllTranslatorNetworking's Javadoc for the
 * registration-order pitfall this class deliberately follows the same fix for: a DEDICATED
 * server needs registerS2CPayloadType() for the S2C types it sends, since it never calls the
 * client-only registerReceiver() that would otherwise perform that registration itself).
 *
 * Security (ARCHITECTURE.md §12, CLAUDE.md §10): the JSON exchanged here is exactly what
 * ConfigManager already persists to config.json - provider/endpoint/priority/enabled/
 * credentialId (a reference UUID, never a raw key) per API entry. No raw API key EVER crosses
 * this network path in either direction; RemoteServerApiEditScreen has no key field at all. A
 * remotely-added API entry is created with credentialId == null and stays inert until an admin
 * with direct access to the server machine attaches a key there (credentials.json or the local
 * M-key screen) - a deliberate limitation, not a gap.
 *
 * Permission is checked TWICE, independently: once when /alltranslator config is invoked
 * (CommandHandlers, Commands.LEVEL_GAMEMASTERS via Brigadier), and again here inside
 * handleSaveOnServer() using the sender's own live ServerPlayer#permissions() - a modified
 * client could otherwise send a Save payload without ever having run the command at all.
 *
 * IMPORTANT (real-world crash fix, Phase 14): this class must NEVER reference any client-only
 * type (Screen or any subclass, Minecraft, etc), even inside a method that is only ever called
 * from the client - this class is loaded on BOTH physical sides via AllTranslatorCore#init(),
 * and simply having such a reference in its bytecode crashes a dedicated server at
 * class-verification time. The client-side S2C receive handlers (which do need to open a
 * Screen) live in the client-only RemoteConfigClientReceiver instead.
 */
public final class ServerConfigNetworking {

    private static final Gson GSON = new Gson();

    private ServerConfigNetworking() {}

    /** Called once from AllTranslatorCore#init() (both physical sides). */
    public static void registerCommon() {
        if (Platform.getEnvironment() == Env.SERVER) {
            NetworkManager.registerS2CPayloadType(ServerConfigPayloads.OpenScreen.TYPE, ServerConfigPayloads.OpenScreen.STREAM_CODEC);
            NetworkManager.registerS2CPayloadType(ServerConfigPayloads.SaveResult.TYPE, ServerConfigPayloads.SaveResult.STREAM_CODEC);
        }
        NetworkManager.registerC2S(ServerConfigPayloads.Save.TYPE, ServerConfigPayloads.Save.STREAM_CODEC,
                ServerConfigNetworking::handleSaveOnServer);
    }

    /** Called from CommandHandlers' /alltranslator config executor (already permission-gated there). */
    public static void sendConfigScreen(ServerPlayer player) {
        ConfigManager configManager = AllTranslatorCore.configManager();
        String json = GSON.toJson(configManager.model());
        NetworkManager.sendToPlayer(player, new ServerConfigPayloads.OpenScreen(json));
    }

    /**
     * The client is never trusted with credential references. An entry keeps its
     * credentialId only if the same id already exists on the server with the same
     * provider and endpoint; anything else (new entry, changed provider or endpoint)
     * is detached and must be re-attached by an admin on the server machine. Without
     * this, an OP could point a keyed entry at an endpoint of their own.
     */
    private static void sanitizeCredentialRefs(ConfigModel current, ConfigModel incoming) {
        if (incoming.apis == null) {
            return;
        }
        for (TranslationApiConfig in : incoming.apis) {
            if (in == null) {
                continue;
            }
            TranslationApiConfig old = null;
            if (current.apis != null) {
                for (TranslationApiConfig c : current.apis) {
                    if (c != null && c.id() != null && c.id().equals(in.id())) {
                        old = c;
                        break;
                    }
                }
            }
            boolean same = old != null
                    && old.provider() == in.provider()
                    && java.util.Objects.equals(old.endpoint(), in.endpoint());
            java.util.UUID keep = same ? old.credentialId() : null;
            if (in.credentialId() != null && !java.util.Objects.equals(in.credentialId(), keep)) {
                AllTranslator.LOGGER.warn("ServerConfigNetworking: detached the credential reference of API entry '"
                        + in.displayName() + "' (new entry, or provider/endpoint changed)");
            }
            in.setCredentialId(keep);
        }
    }

    private static void handleSaveOnServer(ServerConfigPayloads.Save payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            if (!(context.getPlayer() instanceof ServerPlayer sender)) {
                return;
            }
            if (!Commands.LEVEL_GAMEMASTERS.check(sender.permissions())) {
                AllTranslator.LOGGER.warn("ServerConfigNetworking: rejected a Save payload from "
                        + sender.getGameProfile().name() + " - insufficient permission.");
                NetworkManager.sendToPlayer(sender, new ServerConfigPayloads.SaveResult(false,
                        "You do not have permission to change the server configuration."));
                return;
            }

            ConfigModel parsed;
            try {
                parsed = GSON.fromJson(payload.configJson(), ConfigModel.class);
            } catch (RuntimeException e) {
                AllTranslator.LOGGER.warn("ServerConfigNetworking: failed to parse a Save payload", e);
                NetworkManager.sendToPlayer(sender, new ServerConfigPayloads.SaveResult(false, "Malformed config data."));
                return;
            }
            if (parsed == null) {
                NetworkManager.sendToPlayer(sender, new ServerConfigPayloads.SaveResult(false, "Empty config data."));
                return;
            }

            ConfigManager configManager = AllTranslatorCore.configManager();
            sanitizeCredentialRefs(configManager.model(), parsed);
            configManager.replaceModel(parsed);
            configManager.save();

            // Same post-save reload steps AllTranslatorConfigScreen#applyToModel() performs
            // locally, so a remote save takes effect immediately without a server restart.
            if (AllTranslatorCore.cacheManager() != null) {
                AllTranslatorCore.cacheManager().setDynamicTextCacheTtlDays(parsed.dynamicTextCacheTtlDays);
            }
            if (AllTranslatorCore.apiManager() != null) {
                AllTranslatorCore.apiManager().reload(parsed.apis);
            }
            if (AllTranslatorCore.translationService() != null) {
                AllTranslatorCore.translationService().setMaxConcurrentHttpRequests(parsed.maxConcurrentHttpRequests);
            }
            com.kiyo.alltranslator.server.ScoreboardManager.refreshEnabledState();

            AllTranslator.LOGGER.info("ServerConfigNetworking: config saved remotely by " + sender.getGameProfile().name());
            NetworkManager.sendToPlayer(sender, new ServerConfigPayloads.SaveResult(true, "Server configuration saved."));
        });
    }

}
