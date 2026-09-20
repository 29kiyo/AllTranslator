package com.kiyo.alltranslator.neoforge;

import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import com.kiyo.alltranslator.client.AllTranslatorKeyBindings;
import com.kiyo.alltranslator.client.gui.AllTranslatorConfigScreen;

/**
 * Client-only work that must run inside the mod constructor. Kept out of
 * AllTranslatorNeoForge on purpose: the JVM verifier loads Screen while linking any
 * class that contains a lambda returning AllTranslatorConfigScreen, which crashes a
 * dedicated server. This class is only ever loaded when called on the physical client.
 */
final class AllTranslatorNeoForgeClient {

    private AllTranslatorNeoForgeClient() {}

    static void registerConstructorTimeHooks() {
        // Must run while the mod constructor is executing (ModLoadingContext ThreadLocal).
        ModLoadingContext.get().registerExtensionPoint(
                IConfigScreenFactory.class,
                () -> (container, parentScreen) -> new AllTranslatorConfigScreen(parentScreen));

        // Must exist before RegisterKeyMappingsEvent fires (see AllTranslatorKeyBindings Javadoc).
        AllTranslatorKeyBindings.createKeyMapping();
    }
}
