package com.kiyo.alltranslator.fabric.client;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.ChatTranslationCoordinator;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Fabric-side registration for Phase 5 chat translation.
 *
 * ClientReceiveMessageEvents.CHAT is listen-only (no way to cancel/modify from it -
 * Fabric's own javadoc says to use ALLOW_CHAT for that instead). That is exactly what
 * we want: vanilla displays the original message untouched, and
 * ChatTranslationCoordinator patches it in place later once translation completes.
 */
public final class FabricChatTranslationHook {
    private FabricChatTranslationHook() {}

    public static void register() {
        ChatTranslationCoordinator coordinator = AllTranslatorCore.chatTranslationCoordinator();
        if (coordinator == null) return;

        ClientReceiveMessageEvents.CHAT.register((message, playerChatMessage, sender, boundChatType, timeStamp) -> {
            if (playerChatMessage != null) {
                coordinator.onPlayerChatReceived(playerChatMessage, boundChatType);
            }
        });
    }
}
