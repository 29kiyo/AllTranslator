package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.ServerChatTranslationCoordinator;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Real-world follow-up fix (Toast/Advancement translation task session, user request):
 * translates /msg, /tell, /w output (all three are the SAME command - confirmed via
 * javap that TellCommand/"tell"/"w" are Brigadier redirects to MsgCommand's own "msg"
 * node, sharing this exact same sendMessage() implementation).
 *
 * Confirmed via javap that MsgCommand#sendMessage(CommandSourceStack,
 * Collection<ServerPlayer>, PlayerChatMessage) - the single private helper backing the
 * command - calls ServerPlayer#sendChatMessage(OutgoingChatMessage, boolean,
 * ChatType.Bound) once per target inside a for-each loop. This is a DIFFERENT call site
 * than PlayerListMixin's target (PlayerList's private broadcastChatMessage loop, used
 * for regular player chat) - /msg builds and sends its own OutgoingChatMessage directly,
 * never going through PlayerList#broadcastChatMessage at all - so PlayerListMixin does
 * NOT already cover this command, and a separate @Redirect here is required.
 *
 * Reuses the EXISTING ServerChatTranslationCoordinator (same per-player
 * enable/language resolution, same async translate-then-withUnsignedContent delivery
 * pattern already used for regular chat) rather than introducing a new coordinator -
 * /msg's underlying PlayerChatMessage carries the same signature/filtering semantics
 * regular chat does, so the existing class's handling applies unchanged.
 *
 * Real-world follow-up fix (user request): gated on the NEW, UNIFIED
 * ConfigModel#translatePrivateMessagesAndTitles flag (grouped with /teammsg and
 * /title per user decision - see that field's Javadoc) rather than the existing
 * ConfigModel#translateChat flag this Mixin originally used. Checked HERE, before
 * ever calling the coordinator, rather than inside ServerChatTranslationCoordinator
 * itself, since that class's own translateChat check must remain specific to ordinary
 * player chat (PlayerListMixin's call site) - reusing the coordinator's translation
 * MECHANISM does not mean reusing its enablement FLAG.
 */
@Mixin(MsgCommand.class)
public abstract class MsgCommandMixin {

    @Redirect(
            method = "sendMessage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;sendChatMessage(Lnet/minecraft/network/chat/OutgoingChatMessage;ZLnet/minecraft/network/chat/ChatType$Bound;)V")
    )
    private static void alltranslator$redirectMsgSend(ServerPlayer recipient, OutgoingChatMessage outgoing, boolean filtered, ChatType.Bound chatType) {
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
