package com.kiyo.alltranslator.mixin;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.text.AdvancementAnnounceTranslationCoordinator;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Phase 14 (Toast/Advancement translation task, follow-up): per-player translation of
 * the "X has made the advancement [Y]" chat announcement - see
 * AdvancementAnnounceTranslationCoordinator's class Javadoc for the full scope decision
 * and why this redirects the 2-arg PlayerList#broadcastSystemMessage overload
 * specifically (confirmed via javap: PlayerAdvancements#lambda$award$0's ONLY call to
 * PlayerList, gated behind DisplayInfo#shouldAnnounceChat() and the vanilla
 * SHOW_ADVANCEMENT_MESSAGES game rule, both of which remain fully in effect since this
 * Mixin only redirects what happens AFTER that gate, never bypasses it).
 *
 * Registered in alltranslator.mixins.json's "mixins" array (not "client"), same
 * reasoning as TellRawCommandMixin/PlayerListMixin: PlayerAdvancements only ever
 * actually executes server-side (dedicated server or singleplayer's integrated
 * server), but the Mixin class itself must remain loadable on the physical client too.
 *
 * Known fragility (documented per CLAUDE.md §21, same acknowledged risk
 * TellRawCommandMixin already carries for its own synthetic lambda target): the target
 * method name "lambda$award$0" is a javac-generated synthetic name tied to
 * PlayerAdvancements#award()'s exact current source structure. Confirmed present via
 * javap against the actual MC 26.2 merged-deobf jar at implementation time; if a future
 * MC version's javac output renumbers or restructures this lambda, this Mixin will
 * fail to apply (a loud, visible failure - not silent wrong behavior).
 */
@Mixin(PlayerAdvancements.class)
public abstract class AdvancementBroadcastMixin {

    @Redirect(
            method = "lambda$award$0",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V")
    )
    private void alltranslator$redirectAdvancementAnnouncement(
            PlayerList playerList, Component announcement, boolean overlay,
            AdvancementHolder advancementHolder, DisplayInfo displayInfo) {
        AdvancementAnnounceTranslationCoordinator coordinator = AllTranslatorCore.advancementAnnounceCoordinator();
        if (coordinator == null) {
            playerList.broadcastSystemMessage(announcement, overlay);
            return;
        }
        coordinator.broadcastAnnouncement(playerList, announcement, overlay);
    }
}
