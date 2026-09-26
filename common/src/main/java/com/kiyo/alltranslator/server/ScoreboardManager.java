package com.kiyo.alltranslator.server;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.api.ApiFailureType;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.service.ApiState;
import com.kiyo.alltranslator.service.ApiStatus;
import com.kiyo.alltranslator.service.TranslationApiConfig;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 14: server-wide sidebar scoreboard showing, per ENABLED translation API
 * (ApiManager#getEnabledConfigs() - deliberately not filtered by current
 * usability, since showing a cooldown/error state IS the point), its live
 * status and in-flight request count, plus a total queue-depth row.
 *
 * Design notes (see conversation history for the alternatives tried/rejected):
 *  - A vanilla sidebar Objective is single, server-wide state (one shared
 *    Scoreboard instance, ARCHITECTURE.md-style "no per-player Minecraft
 *    concept for this") - so ConfigModel#scoreboardEnabled is a server-wide
 *    switch, not a per-player preference. On singleplayer this is naturally
 *    "personal" anyway since only one player exists; the config screen's
 *    toggle button and /alltranslator scoreboard on|off (OP, dedicated/remote
 *    servers) both just flip the same ConfigModel field and call
 *    refreshEnabledState().
 *  - Each score entry's SEARCH key (ScoreHolder#forNameOnly) is the API
 *    config's stable UUID string, deliberately never the (mutable, can be
 *    edited by the user, may contain spaces) display name. The actual
 *    rendered line is set separately via ScoreAccess#display(Component) each
 *    tick, so changing an API's live status (e.g. AVAILABLE -> RATE_LIMITED)
 *    never requires deleting/recreating the scoreboard entry - only its
 *    displayed Component and numeric score change in place.
 *  - Stale entries (an API disabled/removed since the last tick) ARE removed
 *    via Scoreboard#resetSinglePlayerScore, tracked via lastEntryKeys below.
 */
public final class ScoreboardManager {

    private static final String OBJECTIVE_NAME = "alltranslator";
    private static final String QUEUE_ENTRY_KEY = "alltranslator_queue";

    private static volatile MinecraftServer currentServer;
    private static volatile int tickCounter;
    private static Set<String> lastEntryKeys = new HashSet<>();

    private ScoreboardManager() {}

    public static void install() {
        LifecycleEvent.SERVER_STARTED.register(server -> {
            currentServer = server;
            tickCounter = 0;
            applyEnabledState(server);
        });
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            currentServer = null;
        });
        TickEvent.SERVER_POST.register(ScoreboardManager::onServerTick);
    }

    /**
     * Call after ConfigModel#scoreboardEnabled changes (config screen save, or
     * the /alltranslator scoreboard command) so the change takes effect
     * immediately rather than waiting for the next periodic tick. Safe no-op
     * if no server is currently running on this physical side (e.g. called
     * from a client that's connected to a REMOTE server, not hosting one).
     */
    public static void refreshEnabledState() {
        MinecraftServer server = currentServer;
        if (server != null) {
            applyEnabledState(server);
        }
    }

    private static void onServerTick(MinecraftServer server) {
        if (server != currentServer) return;
        ConfigModel model = AllTranslatorCore.configManager().model();
        if (!model.scoreboardEnabled) return;
        int intervalTicks = Math.max(1, model.scoreboardUpdateIntervalSeconds) * 20;
        tickCounter++;
        if (tickCounter < intervalTicks) return;
        tickCounter = 0;
        updateScoreboard(server);
    }

    private static void applyEnabledState(MinecraftServer server) {
        ConfigModel model = AllTranslatorCore.configManager().model();
        ServerScoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);

        if (model.scoreboardEnabled) {
            if (objective == null) {
                objective = scoreboard.addObjective(OBJECTIVE_NAME, ObjectiveCriteria.DUMMY,
                        Component.literal("All Translator"), ObjectiveCriteria.RenderType.INTEGER, false, null);
            }
            scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
            tickCounter = 0;
            updateScoreboard(server);
        } else if (objective != null) {
            if (scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) == objective) {
                scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, null);
            }
            for (String key : lastEntryKeys) {
                scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(key), objective);
            }
            lastEntryKeys = new HashSet<>();
            scoreboard.removeObjective(objective);
        }
    }

    private static void updateScoreboard(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
        if (objective == null) return;

        Set<String> currentKeys = new HashSet<>();
        List<TranslationApiConfig> enabledApis = AllTranslatorCore.apiManager().getEnabledConfigs();

        for (TranslationApiConfig api : enabledApis) {
            String entryKey = api.id().toString();
            currentKeys.add(entryKey);
            ScoreAccess access = scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(entryKey), objective);
            int inFlight = AllTranslatorCore.translationService().inFlightCountFor(api.id());
            access.display(formatApiLine(api, inFlight));
            access.set(inFlight);
        }

        currentKeys.add(QUEUE_ENTRY_KEY);
        ScoreAccess queueAccess = scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(QUEUE_ENTRY_KEY), objective);
        int queueSize = AllTranslatorCore.translationService().pendingQueueSize();
        queueAccess.display(Component.literal("\u00A77Queue"));
        queueAccess.set(queueSize);

        for (String staleKey : lastEntryKeys) {
            if (!currentKeys.contains(staleKey)) {
                scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly(staleKey), objective);
            }
        }
        lastEntryKeys = currentKeys;
    }

    private static Component formatApiLine(TranslationApiConfig api, int inFlight) {
        String name = api.displayName() == null ? "?" : api.displayName();
        if (name.length() > 20) {
            name = name.substring(0, 19) + "\u2026";
        }
        ApiState state = AllTranslatorCore.apiManager().getState(api.id());
        String colorCode;
        String abbrev;
        // Phase 14 fix (real-world bug): ApiState#status() only flips back to AVAILABLE
        // when a translation actually SUCCEEDS again - it does not update merely because
        // the cooldown window has elapsed, so the scoreboard kept showing a stale
        // COOLDOWN/ERROR label for however long nobody happened to trigger a new
        // translation attempt (observed: ~5 minutes of "stuck" ERROR after the actual
        // cooldown had long since passed). isUsableNow() is the correct live check here
        // (same logic ApiManager's own candidate-selection already trusts) - if the
        // cooldown has passed, the API IS available again for display purposes even
        // before the next real attempt confirms it with a fresh success.
        ApiStatus status = state == null ? ApiStatus.AVAILABLE
                : (state.isUsableNow() ? ApiStatus.AVAILABLE : state.status());
        switch (status) {
            case AVAILABLE -> { colorCode = "\u00A7a"; abbrev = "OK"; }
            case RATE_LIMITED -> { colorCode = "\u00A7e"; abbrev = "COOLDOWN"; }
            case QUOTA_EXCEEDED -> { colorCode = "\u00A7e"; abbrev = "QUOTA"; }
            case TEMP_UNAVAILABLE -> { colorCode = "\u00A7c"; abbrev = "ERROR"; }
            case AUTH_FAILED, CONFIG_ERROR -> { colorCode = "\u00A7c"; abbrev = "ERROR"; }
            case DISABLED_PERMANENT -> { colorCode = "\u00A78"; abbrev = "OFF"; }
            default -> { colorCode = "\u00A77"; abbrev = "?"; }
        }
        return Component.literal("\u00A7b" + name + " " + colorCode + "[" + abbrev + "]");
    }
}
