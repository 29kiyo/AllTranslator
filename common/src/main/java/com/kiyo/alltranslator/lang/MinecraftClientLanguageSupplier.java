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
 * UNVERIFIED FIELD ACCESS: `options.languageCode` is used here as a public field per the
 * Phase 0 architecture decision. If Loom reports it's not a field (e.g. it's now a getter),
 * paste the compiler error and it'll be corrected against your mappings.
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
