package com.kiyo.alltranslator.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Exposes ChatComponent's private message history so ChatTranslationCoordinator can
 * patch an already-displayed chat line in place once its async translation completes
 * (PHASE_INSTRUCTIONS.md Phase 5). Confirmed via 26.2 sources
 * (neoforge-26.2.0.52-beta-sources.jar) that ChatComponent#allMessages /
 * #refreshTrimmedMessages() have no public equivalent.
 *
 * alltranslator$getAllMessages() returns the SAME list instance the field holds (no
 * copy) - callers must mutate it via the returned reference (e.g. ListIterator#set),
 * mirroring the exact pattern vanilla's own ChatComponent#deleteMessageOrDelay() uses.
 * No setter is exposed on purpose: we only ever replace entries in place, never
 * reassign the field.
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {

    @Accessor("allMessages")
    List<GuiMessage> alltranslator$getAllMessages();

    @Invoker("refreshTrimmedMessages")
    void alltranslator$refreshTrimmedMessages();
}
