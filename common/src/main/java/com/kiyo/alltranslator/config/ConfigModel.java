package com.kiyo.alltranslator.config;

import com.kiyo.alltranslator.service.TranslationApiConfig;

import java.util.ArrayList;
import java.util.List;

/** Serialized as config/alltranslator/config.json. Contains no API keys - see CredentialStore. */
public final class ConfigModel {
    public boolean translationEnabled = true;
    public String forcedTargetLanguage = null; // null = use client/player language

    public int memoryCacheCapacity = 2000;
    public int dynamicTextCacheTtlDays = 30; // Phase 3+, dynamic/keyless content
    /**
     * Phase 14: max simultaneous in-flight HTTP translation requests across all
     * configured APIs combined (not per-API - failover is priority-ordered
     * sequential, not parallel-per-API). Was hardcoded in TranslationService as
     * MAX_CONCURRENT_HTTP_REQUESTS (Phase 13 fix); now user-configurable so
     * local-LLM users with more/less VRAM headroom (or cloud-only users with no
     * local server to protect) can tune it without a code change. Default 3
     * matches the original Phase 13 hardcoded value.
     */
    public int maxConcurrentHttpRequests = 3;
    /**
     * Phase 14 (ARCHITECTURE.md §26.4): how candidate APIs are ordered for each request.
     * Stored by constant name - do not rename. A missing or unknown value (Gson gives
     * null) is treated as PRIORITY_FAILOVER by TranslationService.
     */
    public com.kiyo.alltranslator.api.ApiSelectionMode apiSelectionMode =
            com.kiyo.alltranslator.api.ApiSelectionMode.PRIORITY_FAILOVER;
    /**
     * Phase 14: shows a live sidebar scoreboard (in-flight request count, status,
     * queue depth per enabled API) - server-wide, not per-player (a Minecraft
     * sidebar objective is a single shared Scoreboard, not per-player state).
     * Toggled via /alltranslator scoreboard on|off (OP) on a dedicated/remote
     * server, or this same field via the config screen's toggle button on
     * singleplayer (the integrated server reads the same ConfigModel instance).
     * Off by default - CLAUDE.md §24 (don't turn on new UI unasked).
     */
    public boolean scoreboardEnabled = false;
    /** Phase 14: how often (in seconds) the scoreboard's live counts refresh. */
    public int scoreboardUpdateIntervalSeconds = 5;

    /**
     * Every configured API, including the keyless GOOGLE_WEB_FREE provider
     * (ARCHITECTURE.md §22). These are ordinary entries like any other provider:
     * user-chosen priority, editable via ApiEditScreen ("Manage Translation APIs").
     * AllTranslatorConfigScreen's "Google Translate (Free)" toggle is a convenience
     * that creates-or-flips-enabled on the one GOOGLE_WEB_FREE entry here rather
     * than a separate data path.
     */
    public List<TranslationApiConfig> apis = new ArrayList<>();

    /**
     * Phase 6, ARCHITECTURE.md §9: opt-in "server-side translation mode" for chat. Off by
     * default - the default chat translation path remains Phase 5's client-side
     * ChatTranslationCoordinator, which needs no server-side config at all. When an admin turns
     * this on, the server additionally translates chat per-recipient using the SERVER's own
     * TranslationService/credentials (never a client's - see CredentialStore and
     * ARCHITECTURE.md §12), for players who haven't individually opted out via
     * PlayerTranslationSettingsManager.
     */
    public boolean serverSideChatTranslationEnabled = false;

    /** Phase 13: append " (original text)" after translated chat lines. Off by default. */
    public boolean showOriginalTextInChat = false;

    /** Phase 13: recipe-unlocked-style toast (top-right) on translation API failure. On by default. */
    public boolean apiErrorToastEnabled = true;
    /** Phase 13: sound for the above toast. Off by default (visual-only unless explicitly enabled). */
    public boolean apiErrorToastSoundEnabled = false;

    /**
     * Phase 13: translate other mods' Screen widgets (buttons, toggles, labels),
     * not just items/tooltips/entities/chat. Off by default after real-world
     * testing found it breaking the vanilla world-creation screen (dynamic-state
     * buttons like the gamemode cycle button being corrupted by an unconditional
     * setMessage() every tick) - see DEVELOPMENT_STATUS.md Phase 13 for the
     * investigation. Left in the config as an opt-in toggle rather than removed
     * outright while the root cause is narrowed down.
     */
    public boolean translateOtherModScreens = false;

    /**
     * Phase 14: per-category translation ON/OFF (PHASE_INSTRUCTIONS.md Phase 14 item 1).
     * All default false (opt-in, to avoid unintended API use; values already stored in config.json are kept, missing ones default to off; enable them on the new
     * screen). Checked at each hook site IN ADDITION TO (not instead of) the existing
     * global translationEnabled switch, which TranslatableTextInterceptor#intercept()
     * itself still enforces as the master switch. translateOtherModScreens above is the
     * pre-existing Phase 13 switch for other mods' Screen widgets; its UI now lives
     * alongside these on TranslationCategoriesScreen instead of the main config screen,
     * but its field name/JSON key is unchanged for config.json backward compatibility.
     */
    public boolean translateItemNames = false;
    public boolean translateItemTooltips = false;
    public boolean translateEntityNames = false;
    public boolean translateChat = false;

    /**
     * Phase 14 (item 7, tellraw translation, Option A - see
     * TellrawTranslationCoordinator's class Javadoc for the scope decision).
     * Default ON. Independent of serverSideChatTranslationEnabled - this only
     * gates /tellraw output specifically, never regular chat or command
     * feedback/broadcasts (deliberately NOT a general
     * ServerPlayer#sendSystemMessage hook - see that class's Javadoc for why).
     */
    public boolean translateSystemMessages = false;

    /**
     * Real-world follow-up fix (Toast/Advancement translation task session, user
     * request): unified toggle for /tell, /msg, /w (all the same command), /teammsg
     * (/tm), and /title (title/subtitle/actionbar) - grouped together per user decision
     * since they are all "commands that let a player/admin freely type arbitrary text
     * with no vanilla translation of their own", as opposed to /tellraw (its own
     * existing translateSystemMessages toggle, kept separate/unrenamed for config.json
     * backward compatibility) and ordinary chat (translateChat). Default ON.
     */
    public boolean translatePrivateMessagesAndTitles = false;

    /**
     * Phase 14 (Toast/Advancement translation task): translates the title line of the
     * vanilla "advancement made"/"challenge complete" toast notification
     * (AdvancementToastMixin, @Redirect on AdvancementToast#extractRenderState's single
     * DisplayInfo#getTitle() call - see that Mixin's Javadoc for why only the toast, not
     * the advancement tree/progress screen, is in scope). Default ON, independent of the
     * other category toggles above (this is its own distinct, narrowly-scoped hook, not
     * part of translateEntityNames/translateChat/etc).
     */
    public boolean translateAdvancementToasts = false;

    /**
     * Phase 14 (Toast/Advancement translation task, follow-up): the "New Recipes
     * Unlocked!" toast title/description (RecipeToastMixin, @Redirect on RecipeToast's
     * two static Component fields TITLE_TEXT/DESCRIPTION_TEXT - both vanilla-keyed
     * translatable strings, so existing-translation lookup (ARCHITECTURE.md §3) always
     * wins for vanilla and any mod that already ships that language). Separate flag
     * from translateAdvancementToasts since it is a structurally distinct Mixin/toast
     * class, even though both are "toast notifications" in spirit. Default ON.
     */
    public boolean translateRecipeToasts = false;

    /**
     * Phase 14 (Boss bar name translation, PHASE_INSTRUCTIONS.md Phase 14 item 6):
     * BossHealthOverlayMixin's @Redirect on LerpingBossEvent#getName() (client-side
     * draw-time substitution only - see that Mixin's Javadoc for why this never
     * touches ServerBossEvent/broadcast state). Default ON. Client-only category, so
     * unlike translateSystemMessages/translatePrivateMessagesAndTitles this has no
     * server-authoritative counterpart and is therefore NOT exposed on
     * RemoteServerCategoriesScreen (which only surfaces settings a server admin can
     * meaningfully control).
     */
    public boolean translateBossBarNames = false;
    /** Active-effect list in the inventory screen (EffectsInInventory), key-based dynamic text. */
    public boolean translateEffectNames = false;
    /** Mod jar lang bulk translation: auto-show the confirmation screen on the title screen. */
    public boolean modJarLangPromptEnabled = true;
    /** Mod IDs the user unchecked in the confirmation screen; excluded from the auto prompt. */
    public java.util.List<String> modJarLangIgnoredMods = new java.util.ArrayList<>();

    /** Phase 13: append " (original name)" after translated item names/tooltip first lines. Off by default. */
    public boolean showOriginalNameOnItems = false;

    /**
     * Phase 14 (SERVER_PROXY, ARCHITECTURE.md §12): server-admin opt-in switch.
     * When true, this server will translate incoming ServerProxyPayloads.Request
     * packets using its OWN TranslationService/credentials and return the result
     * to the requesting client. Off by default. Currently toggled only via
     * /alltranslator serverproxy on|off (OP) - no config-screen button yet
     * (AllTranslatorConfigScreen.java was not inspected this session; adding a
     * UI toggle is left for a follow-up pass, same reasoning as scoreboardEnabled
     * originally being command-only before its screen button was added).
     */
    public boolean serverProxyTranslationEnabled = false;

    /**
     * Phase 14 (SERVER_PROXY): client-side timeout for a single proxy round-trip
     * (C2S request -> server translates -> S2C response). Deliberately finite -
     * see ServerProxyProvider's Javadoc for why an unbounded wait here would risk
     * the same class of "future never completes" problem TranslatableTextInterceptor's
     * STUCK_TRACKING diagnostic was investigating (unresolved, tracked separately;
     * see DEVELOPMENT_STATUS.md). No config-screen field yet - edit config.json
     * directly to change it, same as the per-API "timeoutSeconds" extraParam.
     */
    public int serverProxyTimeoutSeconds = 150;
}
