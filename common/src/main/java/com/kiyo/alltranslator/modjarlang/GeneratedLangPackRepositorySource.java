package com.kiyo.alltranslator.modjarlang;

import com.kiyo.alltranslator.AllTranslator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Exposes the single combined pack under config/alltranslator/generated_lang_packs/
 * (GeneratedLangPackStore#combinedPackRoot) to Minecraft as an always-on (required=true,
 * no user toggle) client resource pack, so mod-jar-lang bulk-translation output is picked up
 * by vanilla's own translation resolution. Exposes nothing until something was generated.
 *
 * Registered identically on both loaders via this common RepositorySource (Fabric:
 * FabricPackRepositorySourceInjector, NeoForge: AddPackFindersEvent) - only the REGISTRATION
 * call site differs per loader, not this class. Client-only.
 */
public final class GeneratedLangPackRepositorySource implements RepositorySource {

    private final GeneratedLangPackStore store;

    public GeneratedLangPackRepositorySource(GeneratedLangPackStore store) {
        this.store = store;
    }

    @Override
    public void loadPacks(Consumer<Pack> consumer) {
        Path root = store.combinedPackRoot();
        if (root == null) {
            return;
        }
        try {
            PackLocationInfo locationInfo = new PackLocationInfo(
                    "alltranslator_generated",
                    Component.literal("All Translator: generated translations"),
                    PackSource.BUILT_IN,
                    Optional.empty());
            PackSelectionConfig selectionConfig = new PackSelectionConfig(
                    true, Pack.Position.TOP, true); // required, always top, fixed
            Pack pack = Pack.readMetaAndCreate(
                    locationInfo,
                    new PathPackResources.PathResourcesSupplier(root),
                    PackType.CLIENT_RESOURCES,
                    selectionConfig);
            consumer.accept(pack);
        } catch (RuntimeException e) {
            AllTranslator.LOGGER.warn("Mod jar lang: failed to load the generated pack; "
                    + "generated translations are unavailable for this session", e);
        }
    }
}
