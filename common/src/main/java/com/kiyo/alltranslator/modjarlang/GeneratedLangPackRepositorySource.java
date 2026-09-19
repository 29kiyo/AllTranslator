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
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Exposes every per-mod directory under config/alltranslator/generated_lang_packs/
 * (GeneratedLangPackStore) to Minecraft as an always-on (required=true, per
 * PackSelectionConfig - no user toggle needed/possible in the resource pack
 * screen) client resource pack, so mod-jar-lang bulk-translation output is picked
 * up by vanilla's own translation resolution with zero per-mod Mixin/hook work.
 *
 * Registered identically on both loaders via this single common RepositorySource
 * interface (confirmed via javap: PackRepository#addPackFinder(RepositorySource)
 * on Fabric, AddPackFindersEvent#addRepositorySource(RepositorySource) on
 * NeoForge) - only the REGISTRATION call site differs per loader, not this class.
 *
 * Client-only: PackType.CLIENT_RESOURCES only, and this must only ever be
 * constructed/registered from a confirmed physical-client entrypoint (same rule
 * as AllTranslatorClientCore).
 */
public final class GeneratedLangPackRepositorySource implements RepositorySource {

    private final GeneratedLangPackStore store;

    public GeneratedLangPackRepositorySource(GeneratedLangPackStore store) {
        this.store = store;
    }

    @Override
    public void loadPacks(Consumer<Pack> consumer) {
        List<Path> packDirs = store.listGeneratedPackDirs();
        for (Path dir : packDirs) {
            String modId = dir.getFileName().toString();
            String packId = "alltranslator_generated_" + modId;
            try {
                PackLocationInfo locationInfo = new PackLocationInfo(
                        packId,
                        Component.literal("All Translator: " + modId),
                        PackSource.BUILT_IN,
                        Optional.empty());
                PackSelectionConfig selectionConfig = new PackSelectionConfig(
                        true, Pack.Position.TOP, true); // required, always top, fixed - see class Javadoc
                Pack pack = Pack.readMetaAndCreate(
                        locationInfo,
                        new PathPackResources.PathResourcesSupplier(dir),
                        PackType.CLIENT_RESOURCES,
                        selectionConfig);
                consumer.accept(pack);
            } catch (RuntimeException e) {
                AllTranslator.LOGGER.warn("Mod jar lang: failed to load generated pack for " + modId
                        + "; skipping this mod's generated translations for this session", e);
            }
        }
    }
}
