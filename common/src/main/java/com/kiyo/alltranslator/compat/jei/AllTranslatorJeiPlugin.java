package com.kiyo.alltranslator.compat.jei;

import com.kiyo.alltranslator.AllTranslator;
import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.lang.LanguageResolver;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IIngredientAliasRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 14 (ARCHITECTURE.md section 26.5): lets JEI's search find an item by its original
 * (en_us) name and by its translated name, whichever language the game shows.
 *
 * Uses JEI's own public alias API (IIngredientAliasRegistration). Optional integration:
 * JEI loads this class itself (Fabric: fabric.mod.json entrypoint "jei_mod_plugin";
 * NeoForge: the JeiPlugin annotation). Without JEI nothing loads this class, so no other
 * class may reference it.
 *
 * Never calls a translation API: the translated name comes only from the existing language
 * files and from the cache (TranslationService#peekCache). Aliases are registered each time
 * JEI (re)starts, so a translation that finishes later shows up after the next JEI restart
 * (for example after a resource reload).
 */
@JeiPlugin
public final class AllTranslatorJeiPlugin implements IModPlugin {

    private static final Identifier PLUGIN_UID = Identifier.fromNamespaceAndPath("alltranslator", "jei_plugin");

    @Override
    public Identifier getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerIngredientAliases(IIngredientAliasRegistration registration) {
        try {
            registerAliases(registration);
        } catch (RuntimeException e) {
            // Never let this optional integration break JEI's own start-up.
            AllTranslator.LOGGER.warn("JEI integration: registering item name aliases failed", e);
        }
    }

    private static void registerAliases(IIngredientAliasRegistration registration) {
        if (AllTranslatorCore.configManager() == null
                || AllTranslatorCore.languageResolver() == null
                || AllTranslatorCore.languageDataSource() == null
                || AllTranslatorCore.existingTranslationChecker() == null
                || AllTranslatorCore.translationService() == null) {
            return;
        }
        ConfigModel model = AllTranslatorCore.configManager().model();
        if (!model.translationEnabled || !model.translateItemNames) {
            return;
        }
        String target = normalize(AllTranslatorCore.languageResolver().resolveTargetLanguage());
        String live = normalize(AllTranslatorCore.languageResolver().resolveLiveClientLanguage());
        if (LanguageResolver.DEFAULT_LANGUAGE.equals(target) && LanguageResolver.DEFAULT_LANGUAGE.equals(live)) {
            return; // English everywhere: nothing to reverse
        }
        List<Item> items = BuiltInRegistries.ITEM.stream().toList();
        int aliased = 0;
        for (Item item : items) {
            List<String> aliases = aliasesFor(item, target);
            if (!aliases.isEmpty()) {
                registration.addAliases(item, aliases);
                aliased++;
            }
        }
        AllTranslator.LOGGER.info("JEI integration: registered name aliases for " + aliased
                + " of " + items.size() + " items (target=" + target + ")");
    }

    private static List<String> aliasesFor(Item item, String target) {
        String key = item.getDescriptionId();
        String original = AllTranslatorCore.languageDataSource().lookupDefaultLanguageValue(key);
        boolean hasOriginal = original != null && !original.isBlank();
        Set<String> aliases = new LinkedHashSet<>();
        if (hasOriginal) {
            aliases.add(original);
        }
        String existing = AllTranslatorCore.existingTranslationChecker().check(key, target);
        if (existing != null && !existing.isBlank()) {
            aliases.add(existing);
        } else if (hasOriginal) {
            // Same request shape LocalizedTextResolver uses for a keyed name; cache lookup only.
            TranslationResult cached = AllTranslatorCore.translationService()
                    .peekCache(new TranslationRequest(original, null, target), true);
            if (cached != null && !cached.noTranslationNeeded()
                    && cached.translatedText() != null && !cached.translatedText().isBlank()) {
                aliases.add(cached.translatedText());
            }
        }
        return List.copyOf(aliases);
    }

    private static String normalize(String code) {
        return code == null || code.isBlank() ? LanguageResolver.DEFAULT_LANGUAGE : LanguageResolver.normalize(code);
    }
}
