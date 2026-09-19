package com.kiyo.alltranslator.fabric.client;

import com.kiyo.alltranslator.AllTranslator;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Adds a RepositorySource to a live PackRepository on Fabric.
 *
 * PackRepository#addPackFinder does NOT exist in vanilla Minecraft - confirmed via
 * javap that it is entirely absent from the plain "official" (un-patched)
 * Mojang-mapped jar, and only present in NeoForge's own patched Minecraft source
 * (a NeoForge-exclusive addition, not an access-transformed vanilla method - unlike
 * e.g. ServerPlayer#getLanguage(), see ARCHITECTURE.md §20.1). Fabric therefore has
 * no equivalent public method to Access Widener into visibility, since there is
 * nothing there to widen.
 *
 * Fabric API's OWN fabric-resource-loader-v1 module instead solves this by injecting
 * a public "sources" Set&lt;RepositorySource&gt; field directly onto the runtime
 * PackRepository instance via its own PackRepositoryMixin (confirmed via javap
 * against fabric-resource-loader-v1-2.0.13's PackRepositoryMixin.class - not this
 * project's guess). That field is not part of PackRepository's normal Mojang-mapped
 * source, so it cannot be referenced directly at compile time here (the compiler has
 * no declaration for it) - this class reflects onto it instead, at runtime, after
 * Fabric API's mixin has already applied.
 *
 * Reflection-based rather than a compile-time cast, following this project's
 * established WidgetFrameworkCompat pattern for API surfaces outside Mojang's own
 * mapped classes: fails closed (logs once, no-ops) rather than crashing the client
 * if fabric-resource-loader-v1 ever changes this internal field's name/type.
 */
final class FabricPackRepositorySourceInjector {

    private FabricPackRepositorySourceInjector() {}

    @SuppressWarnings("unchecked")
    static void inject(PackRepository repository, RepositorySource source) {
        try {
            Field sourcesField = PackRepository.class.getField("sources");
            Object rawSources = sourcesField.get(repository);
            ((Set<RepositorySource>) rawSources).add(source);
            repository.reload();
            AllTranslator.LOGGER.info("Mod jar lang: registered generated lang pack source with PackRepository.");
        } catch (ReflectiveOperationException | ClassCastException e) {
            AllTranslator.LOGGER.warn(
                    "Mod jar lang: failed to register generated lang pack source with PackRepository "
                            + "(Fabric API's PackRepositoryMixin \"sources\" field may have changed) - "
                            + "mod jar lang bulk translation will be unavailable this session.", e);
        }
    }
}
