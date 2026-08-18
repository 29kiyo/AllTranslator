package com.kiyo.alltranslator.lang;

import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.minecraft.client.Minecraft;

import java.util.function.Supplier;

/**
 * Supplies the client's own Minecraft language option (Options#languageCode), per
 * ARCHITECTURE.md §4. Safe to construct/call on a dedicated server: short-circuits via
 * Platform.getEnvironment() before ever touching the client-only Minecraft class.
 *
 * FIELD ACCESS CONFIRMED (Phase 10): `options.languageCode` compiles and has been
 * exercised without error across every Fabric/NeoForge runClient session from Phase 4
 * through Phase 9 (client always reported "en_us" in those test environments - a
 * non-default client language value has not specifically been exercised yet, so treat
 * that one scenario as still worth a quick manual check, not the field access itself).
 */
public final class MinecraftClientLanguageSupplier implements Supplier<String> {

    @Override
    public String get() {
        if (Platform.getEnvironment() != Env.CLIENT) return null;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) return null;
        return client.options.languageCode;
    }
}
