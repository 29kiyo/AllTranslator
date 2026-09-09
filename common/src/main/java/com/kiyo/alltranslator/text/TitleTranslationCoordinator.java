package com.kiyo.alltranslator.text;

import com.kiyo.alltranslator.api.TranslationRequest;
import com.kiyo.alltranslator.api.TranslationResult;
import com.kiyo.alltranslator.server.PerPlayerLanguageResolver;
import com.kiyo.alltranslator.service.TranslationService;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Real-world follow-up fix (Toast/Advancement translation task session, user request):
 * translates /title command output (title/subtitle/actionbar - see TitleCommandMixin's
 * Javadoc for exactly which subcommands and why /title ... times is untouched).
 *
 * STRUCTURALLY DIFFERENT from every other server-side text coordinator in this project
 * (Tellraw/ServerChat/AdvancementAnnounce): those all run from an async-safe context and
 * can defer final delivery until a translation completes. A /title command's packet send
 * happens synchronously inside Brigadier command execution on the server thread - there is
 * no "send now, patch later" concept for a transient title/actionbar overlay the way there
 * is for a persistent chat line. CLAUDE.md §7 (never block the main/server thread) forbids
 * synchronously awaiting an async CompletableFuture here.
 *
 * Resulting design: resolveForSend() below performs a synchronous, non-blocking CACHE-ONLY
 * lookup (TranslationService#peekCache - see its Javadoc) using the SAME target-language
 * resolution as tellraw/other per-player system messages. If a cached translation already
 * exists, that cached text is used immediately - indistinguishable from a "real"
 * translation to the player. If nothing is cached yet, the ORIGINAL Component is used for
 * this one send (so nothing is ever incorrectly blocked or delayed), and a normal async
 * translate() call is kicked off in the background purely to warm the cache for a LATER
 * identical /title invocation - mirroring how item/tooltip translation already behaves
 * (first occurrence shows the original; a later occurrence of the same text benefits from
 * the now-cached result).
 *
 * Real-world follow-up fix (user request): gated on the NEW, UNIFIED
 * ConfigModel#translatePrivateMessagesAndTitles flag (grouped with /tell, /msg, /w,
 * /teammsg per user decision - see that field's Javadoc), checked directly here
 * rather than via PerPlayerLanguageResolver#isEnabledForSystemMessages() (which
 * remains specific to tellraw/advancement announcements' own
 * translateSystemMessages flag). Language RESOLUTION still reuses
 * PerPlayerLanguageResolver#resolve() (identical per-player language priority chain -
 * only the enable-flag check differs).
 *
 * Persistent cache: persistable=true (same reasoning as tellraw - title text is typically
 * fixed/repeated announcement content, e.g. a boss-fight phase banner, so caching across
 * restarts/players has real value).
 */
public final class TitleTranslationCoordinator {

    private final TranslationService translationService;
    private final PerPlayerLanguageResolver languageResolver;
    private final com.kiyo.alltranslator.config.ConfigManager configManager;

    public TitleTranslationCoordinator(TranslationService translationService,
                                        PerPlayerLanguageResolver languageResolver,
                                        com.kiyo.alltranslator.config.ConfigManager configManager) {
        this.translationService = translationService;
        this.languageResolver = languageResolver;
        this.configManager = configManager;
    }

    /**
     * @param recipient the player this title/subtitle/actionbar Component is being sent to.
     * @param original  the fully-resolved Component (post ComponentUtils.resolve(...), as
     *                  TitleCommandMixin captures it) for THIS recipient.
     * @return the Component to actually send: either a cache-hit translation, or the
     *         original unchanged (with a background translate() kicked off to warm the
     *         cache for next time) - never null, never blocks.
     */
    public Component resolveForSend(ServerPlayer recipient, Component original) {
        if (!configManager.model().translatePrivateMessagesAndTitles) {
            return original;
        }

        String plain = original.getString();
        if (plain.isBlank()) {
            return original;
        }

        String targetLang = languageResolver.resolve(recipient);
        PlaceholderProtector.Protected protectedText = PlaceholderProtector.protect(plain);
        TranslationRequest request = new TranslationRequest(protectedText.text(), null, targetLang);

        TranslationResult cached = translationService.peekCache(request, true);
        if (cached != null && !cached.noTranslationNeeded()) {
            String restored = PlaceholderProtector.restore(cached.translatedText(), protectedText.tokens());
            if (restored != null && !restored.equals(plain)) {
                return Component.literal(restored).setStyle(original.getStyle());
            }
            return original;
        }

        // Not cached yet: use the original for THIS send, and warm the cache in the
        // background for a later identical /title invocation. Fire-and-forget by design -
        // see class Javadoc for why this coordinator cannot wait for the result here.
        translationService.translate(request, true);
        return original;
    }
}
