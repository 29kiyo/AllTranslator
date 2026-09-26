package com.kiyo.alltranslator.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C: server tells this client whether server-side chat translation is
 * currently active for them (ARCHITECTURE.md section20.2 permanent fix).
 * No API key, no language code - a single boolean.
 */
public final class ServerTranslationStatusPayloads {

    private ServerTranslationStatusPayloads() {}

    public record Status(boolean active) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Status> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("alltranslator", "server_translation_status"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Status> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, Status::active,
                        Status::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
