package com.kiyo.alltranslator.network;

import com.kiyo.alltranslator.AllTranslator;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Phase 14 (M-key auto-sync, user request): a single, deliberately minimal C2S payload that
 * lets a client push its own local ConfigModel#forcedTargetLanguage (M-key screen) to the
 * server as a per-player convenience default - see
 * PlayerTranslationSettings#autoSyncedLanguage's Javadoc for the full priority-order
 * reasoning (an explicit /alltranslator language command always still wins).
 *
 * Same verification basis as ServerProxyPayloads/ServerConfigPayloads (javap against MC
 * 26.2's actual CustomPacketPayload/StreamCodec, CLAUDE.md §3). Deliberately carries ONLY a
 * language code string - no API keys or other config ever crosses this path (ARCHITECTURE.md
 * §12/§18).
 */
public final class PlayerLanguageSyncPayloads {

    private PlayerLanguageSyncPayloads() {}

    /**
     * C2S: sent (a) on ClientPlayerEvent.CLIENT_PLAYER_JOIN and (b) whenever
     * AllTranslatorConfigScreen is saved while already connected to a server. An empty
     * string means "the M-key language box is blank (auto)" - distinguished from "field
     * absent" since this is the only value ever sent, not an Optional-style payload.
     */
    public record Sync(String languageCode) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Sync> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AllTranslator.MOD_ID, "player_language_sync"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Sync::languageCode,
                Sync::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
