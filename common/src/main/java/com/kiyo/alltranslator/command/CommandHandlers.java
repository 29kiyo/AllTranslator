package com.kiyo.alltranslator.command;

import com.kiyo.alltranslator.AllTranslatorCore;
import com.kiyo.alltranslator.config.ConfigManager;
import com.kiyo.alltranslator.config.ConfigModel;
import com.kiyo.alltranslator.lang.LanguageResolver;
import com.kiyo.alltranslator.server.PerPlayerLanguageResolver;
import com.kiyo.alltranslator.server.PlayerTranslationSettings;
import com.kiyo.alltranslator.server.PlayerTranslationSettingsManager;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.players.NameAndId;

import java.util.List;
import java.util.function.Predicate;

/**
 * Phase 9: /alltranslator (alias /at) enable|disable|status|language, per ARCHITECTURE.md §13.
 *
 * SCOPE (important): these commands are a proper front-end for
 * PlayerTranslationSettingsManager / PerPlayerLanguageResolver (Phase 6) - i.e. the OPTIONAL
 * server-side chat translation mode (ConfigModel#serverSideChatTranslationEnabled). They do
 * NOT affect Phase 3/4/5's always-client-side translation (items/blocks/entities/UI, or the
 * default client-side chat translation path), which each client controls locally via
 * LanguageResolver (client's own Minecraft options.languageCode) and is not
 * server-controllable.
 *
 * Registered once from AllTranslatorCore#init() via Architectury's common
 * CommandRegistrationEvent (confirmed via javap + sources jar), which fires on both dedicated
 * servers and the integrated (singleplayer) server.
 *
 * PERMISSION MODEL (revised after in-game testing): the "player" argument branches (enable/
 * disable/status/language <player>) are registered with Commands.LEVEL_ALL so that vanilla
 * target selectors - especially "@s" - are always usable, then requireSelfOrOp() below checks
 * at EXECUTION time whether the resolved target is the invoker themself (always allowed,
 * covers "@s" and any selector that happens to resolve to the invoker) or requires
 * Commands.LEVEL_GAMEMASTERS otherwise (targeting another player by name or a selector like
 * "@a"/"@p"/"@r" that resolves to someone else). This was deliberately NOT done with
 * .requires(...) alone: gating the whole argument node with LEVEL_GAMEMASTERS via .requires(...)
 * would make brigadier hide the entire "<player>" branch (including "@s") from non-op players,
 * which is what caused "@s" to silently fail as "unknown command" during testing. Checking at
 * execution time also lets us send a clear failure message instead of a bare unknown-command
 * error.
 *
 * NOTE on literal(...)/argument(...)/permission(...) helpers below: brigadier's
 * LiteralArgumentBuilder.literal(String) / RequiredArgumentBuilder.argument(String,ArgumentType)
 * and vanilla's Commands.hasPermission(PermissionCheck) are generic in the source type S; used
 * bare at the head of a fluent chain, javac cannot always back-infer S = CommandSourceStack and
 * silently falls back to S = Object, breaking every later .getSource()-typed call in the same
 * chain. Pinning S via these small locally-typed wrappers avoids that once, here, rather than
 * needing an explicit <CommandSourceStack> type witness at every call site.
 */
public final class CommandHandlers {

    private CommandHandlers() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build("alltranslator"));
        dispatcher.register(build("at"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String rootLiteral) {
        return literal(rootLiteral)
                .requires(permission(Commands.LEVEL_ALL))
                .then(literal("enable")
                        .executes(ctx -> setEnabled(ctx.getSource(), ctx.getSource().getPlayerOrException(), true))
                        .then(argument("player", EntityArgument.player())
                                .suggests(PLAYER_SUGGESTIONS)
                                .executes(ctx -> setEnabled(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), true))))
                .then(literal("disable")
                        .executes(ctx -> setEnabled(ctx.getSource(), ctx.getSource().getPlayerOrException(), false))
                        .then(argument("player", EntityArgument.player())
                                .suggests(PLAYER_SUGGESTIONS)
                                .executes(ctx -> setEnabled(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), false))))
                .then(literal("status")
                        .executes(ctx -> status(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                        .then(argument("player", EntityArgument.player())
                                .suggests(PLAYER_SUGGESTIONS)
                                .executes(ctx -> status(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(literal("language")
                        .then(argument("code", StringArgumentType.word())
                                .executes(ctx -> setLanguage(ctx.getSource(), ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "code")))
                                .then(argument("player", EntityArgument.player())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(ctx -> setLanguage(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "code"))))))
                .then(literal("refresh")
                        .requires(CommandHandlers::canRefresh)
                        .executes(ctx -> refresh(ctx.getSource())))
                .then(literal("config")
                        .requires(permission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> openRemoteConfig(ctx.getSource())));
    }

    /**
     * Phase 14: non-OP sources only ever see their own name as a "<player>"
     * candidate. EntityArgument.player()'s default suggestor lists every online
     * player regardless of whether the invoker could actually target them -
     * requireSelfOrOp() already blocks EXECUTION against another player for a
     * non-OP invoker, but without this the tab-completion list itself still
     * leaked every online player's name to every player.
     */
    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS = (ctx, builder) -> {
        CommandSourceStack source = ctx.getSource();
        if (Commands.LEVEL_GAMEMASTERS.check(source.permissions())) {
            return SharedSuggestionProvider.suggest(source.getOnlinePlayerNames(), builder);
        }
        ServerPlayer invoker = source.getPlayer();
        List<String> selfOnly = invoker != null ? List.of(invoker.getGameProfile().name()) : List.of();
        return SharedSuggestionProvider.suggest(selfOnly, builder);
    };

    /**
     * Phase 14: permission gate for /alltranslator refresh. Unlike enable/
     * disable/status/language, refresh has no "target player" concept - it acts
     * on server-wide translation API state - so gating the whole node with
     * .requires(...) here is correct (no "@s"-style self-targeting branch to
     * accidentally hide, unlike those subcommands - see their Javadoc above).
     *
     * Allowed for: OPs (LEVEL_GAMEMASTERS), OR - verified via javap against MC
     * 26.2's actual MinecraftServer/IntegratedServer#isSingleplayerOwner(NameAndId)
     * - the singleplayer world's own owner, REGARDLESS of that world's "Allow
     * Cheats" setting. Deliberate: refresh only resets All Translator's own
     * internal API health/queue bookkeeping and never touches the actual game
     * world, so it doesn't need the same bar as a true cheat command - a
     * singleplayer owner stuck behind an overloaded local LLM server shouldn't
     * have to enable cheats just to unstick their own translation mod.
     */
    private static boolean canRefresh(CommandSourceStack source) {
        if (Commands.LEVEL_GAMEMASTERS.check(source.permissions())) return true;
        MinecraftServer server = source.getServer();
        ServerPlayer invoker = source.getPlayer();
        if (server != null && invoker != null && server.isSingleplayer()) {
            return server.isSingleplayerOwner(new NameAndId(invoker.getGameProfile()));
        }
        return false;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSourceStack, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private static Predicate<CommandSourceStack> permission(PermissionCheck check) {
        return Commands.hasPermission(check);
    }

    /** True if invoking the command against `target` needs no elevated permission. */
    private static boolean isSelf(CommandSourceStack source, ServerPlayer target) {
        ServerPlayer invoker = source.getPlayer();
        return invoker != null && invoker.getUUID().equals(target.getUUID());
    }

    /** Runtime permission gate for the "<player>" branches; see class Javadoc. */
    private static boolean requireSelfOrOp(CommandSourceStack source, ServerPlayer target) {
        if (isSelf(source, target)) return true;
        if (Commands.LEVEL_GAMEMASTERS.check(source.permissions())) return true;
        source.sendFailure(Component.literal(
                "[All Translator] You do not have permission to change another player's translation settings."));
        return false;
    }

    private static int setEnabled(CommandSourceStack source, ServerPlayer target, boolean enabled) {
        if (!requireSelfOrOp(source, target)) return 0;
        AllTranslatorCore.playerTranslationSettingsManager().setEnabled(target.getUUID(), enabled);
        String who = isSelf(source, target) ? "Your" : target.getGameProfile().name() + "'s";
        String state = enabled ? "ENABLED" : "DISABLED";
        source.sendSuccess(() -> Component.literal(
                "[All Translator] " + who + " server-side chat translation preference set to " + state + "."), true);
        return 1;
    }

    private static int setLanguage(CommandSourceStack source, ServerPlayer target, String code) {
        if (!requireSelfOrOp(source, target)) return 0;
        String normalized = LanguageResolver.normalize(code);
        AllTranslatorCore.playerTranslationSettingsManager().setLanguageOverride(target.getUUID(), normalized);
        String who = isSelf(source, target) ? "Your" : target.getGameProfile().name() + "'s";
        source.sendSuccess(() -> Component.literal(
                "[All Translator] " + who + " language override set to " + normalized + "."), true);
        return 1;
    }

    private static int status(CommandSourceStack source, ServerPlayer target) {
        if (!requireSelfOrOp(source, target)) return 0;
        ConfigManager configManager = AllTranslatorCore.configManager();
        PerPlayerLanguageResolver resolver = AllTranslatorCore.perPlayerLanguageResolver();
        PlayerTranslationSettingsManager settingsManager = AllTranslatorCore.playerTranslationSettingsManager();
        ConfigModel model = configManager.model();
        PlayerTranslationSettings raw = settingsManager.get(target.getUUID());
        String who = isSelf(source, target) ? "you" : target.getGameProfile().name();

        source.sendSuccess(() -> Component.literal("[All Translator] Status for " + who + ":"), false);
        source.sendSuccess(() -> Component.literal("  Mod-wide translation: "
                + (model.translationEnabled ? "ON" : "OFF")), false);
        source.sendSuccess(() -> Component.literal("  Server-side chat translation mode (server-wide switch): "
                + (model.serverSideChatTranslationEnabled ? "ON" : "OFF")), false);
        source.sendSuccess(() -> Component.literal("  Per-player opt-in/out override: "
                + (raw.enabled() == null ? "(inherit server-wide switch)" : (raw.enabled() ? "ENABLED" : "DISABLED"))), false);
        source.sendSuccess(() -> Component.literal("  Effective for this player right now: "
                + (resolver.isEnabledFor(target) ? "YES" : "NO")), false);
        source.sendSuccess(() -> Component.literal("  Language override: "
                + (raw.languageOverride() == null ? "(none, uses client-reported language)" : raw.languageOverride())), false);
        source.sendSuccess(() -> Component.literal("  Resolved target language: " + resolver.resolve(target)), false);
        return 1;
    }

    /**
     * Phase 14: backing executor for /alltranslator scoreboard on|off. Server-
     * wide, not per-player (see ScoreboardManager's class Javadoc for why) -
     * this is the OP/multiplayer front-end; singleplayer also has a config-
     * screen toggle button that flips the same ConfigModel field.
     */
    private static int setScoreboard(CommandSourceStack source, boolean enabled) {
        ConfigManager configManager = AllTranslatorCore.configManager();
        configManager.model().scoreboardEnabled = enabled;
        configManager.save();
        com.kiyo.alltranslator.server.ScoreboardManager.refreshEnabledState();
        source.sendSuccess(() -> Component.literal("[All Translator] Scoreboard: "
                + (enabled ? "ON" : "OFF") + "."), true);
        return 1;
    }

    /**
     * Phase 14 (SERVER_PROXY): backing executor for /alltranslator serverproxy
     * on|off. Server-wide admin opt-in - see ConfigModel#serverProxyTranslationEnabled
     * and AllTranslatorNetworking's request handler for what this actually gates.
     */
    private static int setServerProxy(CommandSourceStack source, boolean enabled) {
        ConfigManager configManager = AllTranslatorCore.configManager();
        configManager.model().serverProxyTranslationEnabled = enabled;
        configManager.save();
        source.sendSuccess(() -> Component.literal("[All Translator] Server-proxy translation: "
                + (enabled ? "ON" : "OFF") + "."), true);
        return 1;
    }

    /**
     * Phase 14: backing executor for /alltranslator config. Pushes a
     * ServerConfigPayloads.OpenScreen snapshot of this server's own ConfigModel to the
     * invoker only (requires the All Translator client mod; a vanilla/other-mod client just
     * silently ignores the unrecognized payload). See ServerConfigNetworking's Javadoc for
     * the independent permission re-check performed again when the resulting Save payload
     * comes back.
     */
    private static int openRemoteConfig(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[All Translator] This command must be run by a player (not the console)."));
            return 0;
        }
        com.kiyo.alltranslator.network.ServerConfigNetworking.sendConfigScreen(player);
        source.sendSuccess(() -> Component.literal("[All Translator] Sent the server config screen (requires the All Translator client mod)."), true);
        return 1;
    }

    /**
     * Phase 14: backing executor for /alltranslator refresh. See
     * TranslationService#refresh() for what this actually does (cancels
     * in-flight HTTP calls, clears duplicate-request bookkeeping, resets
     * cooldown/rate-limit state on non-permanently-disabled APIs).
     */
    private static int refresh(CommandSourceStack source) {
        var result = AllTranslatorCore.translationService().refresh();
        source.sendSuccess(() -> Component.literal("[All Translator] Refresh: cancelled "
                + result.cancelledCalls() + " call(s), reset " + result.resetApis() + " API(s)."), true);
        return 1;
    }
}
