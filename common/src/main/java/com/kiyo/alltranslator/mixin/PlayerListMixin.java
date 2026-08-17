package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.ServerChatTranslationCoordinator;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Phase 6: per-player server-side chat translation (opt-in, ARCHITECTURE.md §9).
 *
 * Targets PlayerList's PRIVATE
 * broadcastChatMessage(PlayerChatMessage, Predicate, ServerPlayer, ChatType.Bound) overload -
 * confirmed via 26.2 sources this is what BOTH public broadcastChatMessage(..., ServerPlayer, ...)
 * and broadcastChatMessage(..., CommandSourceStack, ...) delegate to, and is exactly where the
 * per-recipient delivery loop lives (one ServerPlayer#sendChatMessage(...) call per online
 * player). fabric-message-api-v1's own PlayerListMixin hooks the same private method's public
 * callers at HEAD only (for allow/observe, not per-recipient content swapping) - this class
 * instead @Redirects the per-recipient send call itself, which is what per-player *content*
 * substitution requires.
 *
 * Applies on BOTH physical client (singleplayer's integrated server) and dedicated server,
 * since PlayerList is a shared (non-client-only) vanilla class - registered in
 * alltranslator.mixins.json's "mixins" array (not "client"), same reasoning as
 * ItemStackMixin/EntityMixin being safe on the server: AllTranslatorCore's server-side
 * coordinator reference is always non-null wherever AllTranslatorCore.init() has run (both
 * sides), but only actually translates anything when
 * ConfigModel#serverSideChatTranslationEnabled is explicitly turned on - off by default, so
 * this is a no-op passthrough out of the box.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Redirect(
            method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;sendChatMessage(Lnet/minecraft/network/chat/OutgoingChatMessage;ZLnet/minecraft/network/chat/ChatType$Bound;)V")
    )
    private void alltranslator$redirectSendChatMessage(ServerPlayer recipient, OutgoingChatMessage outgoing, boolean filtered, ChatType.Bound chatType) {
        ServerChatTranslationCoordinator coordinator = AllTranslatorCore.serverChatTranslationCoordinator();
        if (coordinator == null || !(outgoing instanceof OutgoingChatMessage.Player playerOutgoing)) {
            recipient.sendChatMessage(outgoing, filtered, chatType);
            return;
        }
        coordinator.handleOutgoing(recipient, playerOutgoing.message(), filtered, chatType);
    }
}
