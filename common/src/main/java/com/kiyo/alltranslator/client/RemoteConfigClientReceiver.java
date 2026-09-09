package com.kiyo.alltranslator.client;

import com.google.gson.Gson;
import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.client.gui.RemoteServerConfigScreen;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.network.ServerConfigPayloads;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 14 (real-world crash fix): the S2C receive-handlers for remote server-config editing
 * must live in a client-only class, never inside common/network's ServerConfigNetworking, which
 * is loaded unconditionally on BOTH physical sides via AllTranslatorCore#init(). The original
 * implementation put a handler lambda directly referencing RemoteServerConfigScreen (a Screen
 * subclass) inside that common class - simply having a class file reference a client-only Screen
 * type is enough to crash a dedicated server at class-verification time ("Cannot load class
 * net.minecraft.client.gui.screens.Screen in environment type SERVER"), even though the specific
 * method containing that reference was never actually invoked there. This restores the same
 * rule ItemStackMixin/EntityMixin/AllTranslatorNetworking#registerClientReceiver() already
 * follow (never let anything loaded on both sides reference a client-only type) by keeping every
 * Screen reference confined to this client-only package, which is never loaded/verified on a
 * dedicated server.
 *
 * Second real-world crash fix (this class specifically): the OpenScreen handler used to call
 * Minecraft.getInstance().execute(() -&gt; mc.gui.setScreen(...)) directly from inside the S2C
 * network receive callback. In real-world testing on a small (scaled ~427x240) window, this
 * reliably crashed with "IllegalArgumentException: Scissor size must be &gt;0, was 292x0" inside
 * vanilla's GuiRenderer - opening the M-key AllTranslatorConfigScreen on the SAME small window
 * never crashes. The confirmed difference: the M-key path opens its Screen from the normal
 * per-tick keybinding poll (AllTranslatorKeyBindings, itself driven by the common
 * ClientTickEvent.CLIENT_POST also used by ChatTranslationCoordinator's own tick-deferred retry
 * queue - see that class's Javadoc for the established pattern this follows), while the network
 * receive callback can fire mid-frame, apparently leaving GuiRenderer's internal scissor/batch
 * state inconsistent when combined with a screen tall enough to be laid out partially or fully
 * off-screen. Rather than call setScreen() directly from the network thread/callback, this class
 * now only records that a screen-open is PENDING (a plain field, overwritten - not queued - since
 * only the latest OpenScreen for this client ever matters) and defers the actual setScreen() call
 * to the next confirmed-safe client tick via onClientTick(), wired from AllTranslatorClientCore
 * exactly like ChatTranslationCoordinator's onClientTick() already is. This puts the M-key path
 * and this path on the same tick-boundary footing.
 *
 * MUST be called only from AllTranslatorClientCore#init() (confirmed physical client) - same
 * requirement as AllTranslatorNetworking#registerClientReceiver().
 */
public final class RemoteConfigClientReceiver {

    private static final Gson GSON = new Gson();

    /** Overwritten (not queued) - only the most recent OpenScreen matters if several arrive before the next tick. */
    private static final AtomicReference<ConfigModel> PENDING_OPEN = new AtomicReference<>();

    private RemoteConfigClientReceiver() {}

    public static void register() {
        NetworkManager.registerReceiver(NetworkManager.s2c(), ServerConfigPayloads.OpenScreen.TYPE,
                ServerConfigPayloads.OpenScreen.STREAM_CODEC,
                (payload, context) -> {
                    ConfigModel remoteModel;
                    try {
                        remoteModel = GSON.fromJson(payload.configJson(), ConfigModel.class);
                    } catch (RuntimeException e) {
                        AllTranslator.LOGGER.warn("RemoteConfigClientReceiver: failed to parse an OpenScreen payload", e);
                        return;
                    }
                    if (remoteModel == null) return;
                    // Deliberately NOT calling setScreen() here - see class Javadoc. Just record
                    // the pending model; onClientTick() (main thread, safe tick boundary) does
                    // the actual screen switch.
                    PENDING_OPEN.set(remoteModel);
                });

        NetworkManager.registerReceiver(NetworkManager.s2c(), ServerConfigPayloads.SaveResult.TYPE,
                ServerConfigPayloads.SaveResult.STREAM_CODEC,
                (payload, context) -> Minecraft.getInstance().execute(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal("[All Translator] " + payload.message()));
                    }
                }));
    }

    /**
     * Must be called once per client tick from a confirmed physical-client entrypoint - same
     * wiring as ChatTranslationCoordinator#onClientTick() (AllTranslatorClientCore via
     * Architectury's common ClientTickEvent.CLIENT_POST).
     */
    public static void onClientTick(Minecraft mc) {
        ConfigModel remoteModel = PENDING_OPEN.get();
        if (remoteModel == null) return;
        // Phase 14 real-world crash fix: mirror AllTranslatorKeyBindings' own guard exactly
        // (client.gui.screen() == null) - only open once no other screen (e.g. the chat input
        // screen that was still closing when the command was sent) is in the way. Without this
        // guard, a real-world small-window crash ("Scissor size must be >0") was observed that
        // never occurred via the M-key path, which has always had this same guard. If a screen
        // is still open, leave PENDING_OPEN set and retry on a later tick instead of dropping it.
        if (mc.gui.screen() != null) return;
        PENDING_OPEN.set(null);
        mc.gui.setScreen(new RemoteServerConfigScreen(null, remoteModel));
    }
}
