package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.ServerChatTranslationCoordinator;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.commands.TeamMsgCommand;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Real-world follow-up fix (Toast/Advancement translation task session, user request):
 * translates /teammsg (/tm) output.
 *
 * Confirmed via javap that TeamMsgCommand#sendMessage(CommandSourceStack, Entity,
 * PlayerTeam, List<ServerPlayer>, PlayerChatMessage) has the exact same shape as
 * MsgCommand#sendMessage - a per-target loop calling
 * ServerPlayer#sendChatMessage(OutgoingChatMessage, boolean, ChatType.Bound) directly,
 * never through PlayerList#broadcastChatMessage - so this needs its own @Redirect for
 * the same reason MsgCommandMixin does (see that class's Javadoc for the full
 * explanation, which applies identically here).
 *
 * Reuses the EXISTING ServerChatTranslationCoordinator, same reasoning as
 * MsgCommandMixin.
 *
 * Real-world follow-up fix (user request): gated on the NEW, UNIFIED
 * ConfigModel#translatePrivateMessagesAndTitles flag - see MsgCommandMixin's Javadoc
 * for the full reasoning (identical here).
 */
@Mixin(TeamMsgCommand.class)
public abstract class TeamMsgCommandMixin {

    @Redirect(
            method = "sendMessage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;sendChatMessage(Lnet/minecraft/network/chat/OutgoingChatMessage;ZLnet/minecraft/network/chat/ChatType$Bound;)V")
    )
    private static void alltranslator$redirectTeamMsgSend(ServerPlayer recipient, OutgoingChatMessage outgoing, boolean filtered, ChatType.Bound chatType) {
        ServerChatTranslationCoordinator coordinator = AllTranslatorCore.serverChatTranslationCoordinator();
        if (coordinator == null
                || !(outgoing instanceof OutgoingChatMessage.Player playerOutgoing)
                || !AllTranslatorCore.configManager().model().translatePrivateMessagesAndTitles) {
            recipient.sendChatMessage(outgoing, filtered, chatType);
            return;
        }
        coordinator.handleOutgoing(recipient, playerOutgoing.message(), filtered, chatType);
    }
}
