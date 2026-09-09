package com.kiyo.alltranslator.network;

import com.kiyo.alltranslator.AllTranslator;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Phase 14 (SERVER_PROXY, ARCHITECTURE.md §12): C2S/S2C payload records for the
 * "translate via the server's own API credentials" opt-in feature. Verified
 * against MC 26.2's actual net.minecraft.network.protocol.common.custom.CustomPacketPayload
 * / net.minecraft.network.codec.StreamCodec via javap (CLAUDE.md §3) before writing
 * this - see DEVELOPMENT_STATUS.md Phase 14 task 4 investigation notes.
 *
 * sourceLang uses "" (empty string) on the wire for "unknown source language",
 * since StreamCodec's String codec has no null representation - see
 * AllTranslatorNetworking's request handler for the "" -> null conversion back
 * on the receiving side.
 */
public final class ServerProxyPayloads {

    private ServerProxyPayloads() {}

    public record Request(long requestId, String sourceText, String sourceLang, String targetLang)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Request> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AllTranslator.MOD_ID, "server_proxy_request"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Request::requestId,
                ByteBufCodecs.STRING_UTF8, Request::sourceText,
                ByteBufCodecs.STRING_UTF8, Request::sourceLang,
                ByteBufCodecs.STRING_UTF8, Request::targetLang,
                Request::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Response(long requestId, boolean success, String translatedText, boolean noTranslationNeeded)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Response> TYPE =
                new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(AllTranslator.MOD_ID, "server_proxy_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Response> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Response::requestId,
                ByteBufCodecs.BOOL, Response::success,
                ByteBufCodecs.STRING_UTF8, Response::translatedText,
                ByteBufCodecs.BOOL, Response::noTranslationNeeded,
                Response::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
