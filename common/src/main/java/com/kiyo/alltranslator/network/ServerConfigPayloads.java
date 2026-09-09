package com.kiyo.alltranslator.network;

import com.kiyo.alltranslator.AllTranslator;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Phase 14 (remote server config): C2S/S2C payload records for /alltranslator config. Same
 * verification basis as ServerProxyPayloads (javap against MC 26.2's actual CustomPacketPayload/
 * StreamCodec, CLAUDE.md §3) - see that class's Javadoc. The entire ConfigModel is round-tripped
 * as a single JSON string via the existing STRING_UTF8 codec rather than introducing a new,
 * unverified structured/list codec.
 */
public final class ServerConfigPayloads {

    private ServerConfigPayloads() {}

    /** S2C: pushed to the command invoker only, in response to /alltranslator config. */
    public record OpenScreen(String configJson) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<OpenScreen> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AllTranslator.MOD_ID, "server_config_open"));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreen> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenScreen::configJson,
                OpenScreen::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** C2S: sent when RemoteServerConfigScreen is closed/Done. */
    public record Save(String configJson) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Save> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AllTranslator.MOD_ID, "server_config_save"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Save> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Save::configJson,
                Save::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** S2C: result notification after a Save is processed (success/failure + a human-readable reason). */
    public record SaveResult(boolean success, String message) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SaveResult> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AllTranslator.MOD_ID, "server_config_save_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SaveResult> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, SaveResult::success,
                ByteBufCodecs.STRING_UTF8, SaveResult::message,
                SaveResult::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
