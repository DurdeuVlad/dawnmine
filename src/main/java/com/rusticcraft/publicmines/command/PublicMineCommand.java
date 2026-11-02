package com.rusticcraft.publicmines.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import com.rusticcraft.publicmines.generation.MineShellGeometry;
import com.rusticcraft.publicmines.generation.OreCatalog;
import com.rusticcraft.publicmines.encounter.MineRewardCatalog;
import com.rusticcraft.publicmines.run.MineManager;
import com.rusticcraft.publicmines.run.MineRunData;
import com.rusticcraft.publicmines.run.OwnedVolume;
import com.rusticcraft.publicmines.run.RunStatus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /publicmine} command tree.
 *
 * <p>Player commands (help, status player view, enter, leave, exit) are open
 * to everyone. Setup, config writes, and recovery require permission level 2
 * (the vanilla game-master/operator threshold), checked server-side on every
 * invocation; the console passes automatically. Admin diagnostics are never
 * shown to players without that level.
 */
public final class PublicMineCommand {
    /** Vanilla operator/game-master threshold required for all mutations and staff diagnostics. */
    public static final int ADMIN_LEVEL = Commands.LEVEL_GAMEMASTERS;

    private PublicMineCommand() {}

    private static final SuggestionProvider<CommandSourceStack> CONFIG_KEYS =
            (context, builder) -> SharedSuggestionProvider.suggest(ConfigKeys.names(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("publicmine")
                .executes(ctx -> help(ctx.getSource()));

        root.then(literal("help").executes(ctx -> help(ctx.getSource())));
        root.then(literal("status").executes(ctx -> status(ctx.getSource())));
        root.then(literal("rewards").requires(src -> src.hasPermission(ADMIN_LEVEL))
                .executes(ctx -> rewardStatus(ctx.getSource())));
        root.then(literal("enter").executes(ctx -> enter(ctx)));
        root.then(literal("leave").executes(ctx -> leave(ctx)));
        root.then(literal("exit").executes(ctx -> leave(ctx)));

        root.then(literal("setup").requires(src -> src.hasPermission(ADMIN_LEVEL))
                .executes(ctx -> setupUsage(ctx.getSource()))
                .then(literal("validate").executes(ctx -> validate(ctx.getSource())))
                .then(literal("initialize").executes(ctx -> initialize(ctx.getSource()))));

        root.then(literal("config").requires(src -> src.hasPermission(ADMIN_LEVEL))
                .executes(ctx -> configGetAll(ctx.getSource()))
                .then(literal("get")
                        .executes(ctx -> configGetAll(ctx.getSource()))
                        .then(argument("key", StringArgumentType.word())
                                .suggests(CONFIG_KEYS)
                                .executes(ctx -> configGet(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "key")))))
                .then(literal("set")
                        .then(argument("key", StringArgumentType.word())
                                .suggests(CONFIG_KEYS)
                                .then(argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> configSet(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "key"),
                                                StringArgumentType.getString(ctx, "value")))))));

        root.then(literal("recover").requires(src -> src.hasPermission(ADMIN_LEVEL))
                .executes(ctx -> recoverCheck(ctx.getSource()))
                .then(literal("check").executes(ctx -> recoverCheck(ctx.getSource())))
                .then(literal("repair").executes(ctx -> recoverRepair(ctx.getSource()))));

        dispatcher.register(root);
    }

    // ------------------------------------------------------------------
    // help / status
    // ------------------------------------------------------------------

    private static int help(CommandSourceStack src) {
        List<String> lines = new ArrayList<>();
        boolean admin = src.hasPermission(ADMIN_LEVEL);
        lines.add("=== Rustic Craft Public Mines ===");
        lines.add("A shared daily mine for everyone on the server.");
        lines.add("");
        lines.add("Player commands (everyone):");
        lines.add("  /publicmine help    - this guide");
        lines.add("  /publicmine status  - is the mine open?");
        lines.add("  /publicmine enter   - travel into the shared mine");
        lines.add("  /publicmine leave   - return to the surface (also: exit)");
        if (admin) {
            lines.add("");
            lines.add("Staff commands (operator level " + ADMIN_LEVEL + "+):");
            lines.add("  /publicmine status            - full run diagnostics");
            lines.add("  /publicmine rewards           - show depth combat bonus rates and purposes");
            lines.add("  /publicmine setup validate    - check what is missing");
            lines.add("  /publicmine setup initialize  - create or repair the mine (safe to re-run)");
            lines.add("  /publicmine config get [key]  - show settings");
            lines.add("  /publicmine config set <key> <value> - change a setting");
            lines.add("  /publicmine recover check     - inspect saved state");
            lines.add("  /publicmine recover repair    - fix a failed/partial setup");
            lines.add("");
            lines.add("First-time setup: run '/publicmine setup validate', fix any blockers,");
            lines.add("then '/publicmine setup initialize' and '/publicmine status' to confirm.");
            lines.add("If anything goes wrong, '/publicmine recover repair' is the safe fix.");
        } else {
            lines.add("");
            lines.add("If the mine is closed, ask a server operator to run setup.");
        }
        MineMessages.tellLines(src, lines);
        return 1;
    }

    private static int status(CommandSourceStack src) {
        MineManager manager = MineManager.get(src.getServer());
        if (!src.hasPermission(ADMIN_LEVEL)) {
            statusForPlayer(src, manager);
            return 1;
        }
        MineRunData data = manager.data();
        List<String> lines = new ArrayList<>();
        lines.add("=== Public Mines status (staff view) ===");
        lines.add("Run: " + (data.runId().isEmpty() ? "(none yet)" : data.runId())
                + "  status: " + data.status());
        if (data.status() == RunStatus.FAILED) {
            lines.add("Last error: " + data.lastError());
        }
        lines.add("Day: " + (data.dayId().isEmpty() ? "(none)" : data.dayId())
                + "  seed: " + data.seed());
        lines.add("Topology: " + (data.topologyFingerprint().isEmpty()
                ? "(not planned)"
                : data.topologyNodes() + " nodes / " + data.topologyEdges() + " segments, profile "
                        + data.topologyProfile() + ", fingerprint "
                        + data.topologyFingerprintPrefix()));
        lines.add("Geometry: " + data.completedGenerationSegments().size() + " segments complete, "
                + data.pendingGenerationSegments().size() + " queued, active "
                + (data.activeGenerationSegment().isEmpty() ? "(none)"
                        : data.activeGenerationSegment() + " at operation " + data.activeGenerationCursor()));
        lines.add("Boundary shell: " + PublicMineConfig.mineRegionRadiusChunks()
                + " chunks, generated before each chunk becomes available");
        OreCatalog.Completeness oreCatalog = manager.mineLevel() == null ? null
                : OreCatalog.active().completeness(manager.mineLevel());
        lines.add("Ore catalog: " + (oreCatalog == null ? "not validated (mine dimension unavailable)"
                : OreCatalog.describe(oreCatalog)));
        lines.add("Active region: chunks [" + data.boundMinChunkX() + ", " + data.boundMinChunkZ()
                + "] to [" + data.boundMaxChunkX() + ", " + data.boundMaxChunkZ() + "]");
        lines.add("Arrival: " + (data.mineArrival() == null ? "(not set)" : MineManager.describePos(data.mineArrival()))
                + " in " + MineManager.MINE_DIMENSION.location()
                + "  |  Surface exit: " + (data.surfaceExit() == null ? "(not set)" : MineManager.describePos(data.surfaceExit())));
        lines.add("Owned volumes (" + data.ownedVolumes().size() + "):");
        for (OwnedVolume volume : data.ownedVolumes()) {
            lines.add("  - " + volume.describe());
        }
        List<ServerPlayer> inside = manager.occupants();
        lines.add("Players inside: " + inside.size()
                + (inside.isEmpty() ? "" : " (" + names(inside) + ")"));
        lines.add("Entry: " + (data.status().entryAllowed() ? "open" : "closed"));
        lines.add("Reset (effective once M7 ships): " + manager.effectiveResetDescription());
        lines.add("Netherrack: " + (PublicMineConfig.netherrackEnabled()
                ? "enabled, 1 attempt per " + PublicMineConfig.netherrackColumnInterval() + " eligible columns (deepest region only)"
                : "off (default)"));
        lines.add("Next steps: '/publicmine setup validate' for checks, '/publicmine help' for commands.");
        MineMessages.tellLines(src, lines);
        return 1;
    }

    private static int rewardStatus(CommandSourceStack src) {
        MineMessages.tell(src, "Configured mine combat bonuses: " + MineRewardCatalog.adminSummary());
        MineMessages.tell(src, "These per-encounter rates are configurable by data pack; final balance is subject to M8 playtests.");
        return 1;
    }

    private static void statusForPlayer(CommandSourceStack src, MineManager manager) {
        RunStatus status = manager.data().status();
        if (status.entryAllowed()) {
            MineMessages.tell(src, "The Public Mine is open! Travel in with '/publicmine enter'.");
        } else {
            MineMessages.tell(src, "The Public Mine isn't open right now. "
                    + "A server operator can open it with '/publicmine setup initialize'.");
        }
    }

    private static String names(List<ServerPlayer> players) {
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : players) {
            names.add(player.getGameProfile().getName());
        }
        return String.join(", ", names);
    }

    // ------------------------------------------------------------------
    // enter / leave
    // ------------------------------------------------------------------

    private static int enter(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            MineMessages.tell(src, "Only in-game players can enter the mine.");
            return 0;
        }
        MineManager.EnterOutcome outcome = MineManager.get(src.getServer()).enter(player);
        switch (outcome.result()) {
            case ENTERED -> {
                MineMessages.tell(src, "Welcome to the Public Mine! This run is shared with everyone.");
                MineMessages.tell(src, "Use '/publicmine leave' to return to the surface.");
            }
            case ALREADY_INSIDE -> MineMessages.tell(src, "You're already inside the Public Mine. "
                    + "Use '/publicmine leave' to head back.");
            case NOT_READY -> MineMessages.tell(src, "The Public Mine isn't open right now. "
                    + outcome.detail());
            case ARRIVAL_UNSAFE -> {
                MineMessages.tell(src, "The mine entrance isn't safe at the moment - please try again soon.");
                MineMessages.notifyAdmins(src.getServer(), ADMIN_LEVEL,
                        "Mine arrival pad failed its safety check for " + player.getGameProfile().getName()
                                + "; run '/publicmine recover repair'");
            }
            case FAILED -> {
                MineMessages.tell(src, "Something went wrong entering the mine - staff have been notified.");
                MineMessages.notifyAdmins(src.getServer(), ADMIN_LEVEL,
                        "Enter teleport failed for " + player.getGameProfile().getName()
                                + ": " + outcome.detail());
            }
        }
        return outcome.ok() ? 1 : 0;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            MineMessages.tell(src, "Only in-game players can use this command.");
            return 0;
        }
        return switch (MineManager.get(src.getServer()).leave(player)) {
            case LEFT -> {
                MineMessages.tell(src, "Welcome back to the surface! See you in the next run.");
                yield 1;
            }
            case NOT_INSIDE -> {
                MineMessages.tell(src, "You're not inside the Public Mine.");
                yield 0;
            }
            case FAILED -> {
                MineMessages.tell(src, "Something went wrong leaving the mine - staff have been notified.");
                MineMessages.notifyAdmins(src.getServer(), ADMIN_LEVEL,
                        "Leave teleport failed for " + player.getGameProfile().getName()
                                + "; they are still inside the mine (run "
                                + MineManager.get(src.getServer()).data().runId() + ")");
                yield 0;
            }
        };
    }

    // ------------------------------------------------------------------
    // setup
    // ------------------------------------------------------------------

    private static int setupUsage(CommandSourceStack src) {
        MineMessages.tellLines(src, List.of(
                "Usage: '/publicmine setup validate' to check readiness,",
                "or '/publicmine setup initialize' to create/repair the shared mine.",
                "Both are safe to re-run and only touch mine-owned state."));
        return 0;
    }

    private static int validate(CommandSourceStack src) {
        MineManager manager = MineManager.get(src.getServer());
        List<MineManager.Check> checks = manager.validate();
        int failures = 0;
        List<String> lines = new ArrayList<>();
        lines.add("=== Public Mines validation ===");
        for (MineManager.Check check : checks) {
            String marker = switch (check.severity()) {
                case PASS -> " [ok]   ";
                case NOTE -> " [note] ";
                case FAIL -> " [!!]   ";
            };
            lines.add(marker + check.name() + ": " + check.detail());
            if (check.severity() == MineManager.Severity.FAIL) {
                failures++;
            }
        }
        lines.add("");
        if (manager.data().status() == RunStatus.READY && failures == 0) {
            lines.add("The mine is ready. Players can enter with '/publicmine enter'.");
        } else if (failures == 0) {
            lines.add("Validation passed. Next step: '/publicmine setup initialize'.");
        } else {
            lines.add(failures + " item(s) need attention - see the [!!] lines for the fix for each.");
            lines.add("If a previous setup stopped halfway, '/publicmine recover repair' is the safe retry.");
        }
        MineMessages.tellLines(src, lines);
        return failures == 0 ? 1 : 0;
    }

    private static int initialize(CommandSourceStack src) {
        MineManager manager = MineManager.get(src.getServer());
        List<MineManager.Step> steps = manager.initialize();
        reportSteps(src, "=== Public Mines setup ===", steps);
        boolean ok = steps.stream().allMatch(MineManager.Step::ok);
        if (ok) {
            MineMessages.tell(src, "Setup finished. Verify with '/publicmine setup validate' or '/publicmine status'.");
            if (!manager.occupants().isEmpty()) {
                MineMessages.notifyMineOccupants(src.getServer(),
                        "The Public Mine was refreshed by staff. Use '/publicmine leave' if you need to exit.");
            }
            return 1;
        }
        MineMessages.tell(src, "Setup could not finish - see the failed step above. "
                + "'/publicmine recover repair' retries safely once the blocker is fixed.");
        return 0;
    }

    // ------------------------------------------------------------------
    // config
    // ------------------------------------------------------------------

    private static int configGetAll(CommandSourceStack src) {
        List<String> lines = new ArrayList<>();
        lines.add("=== Public Mines config (server) ===");
        for (ConfigKeys.Key key : ConfigKeys.ALL) {
            lines.add("  " + key.name + " = " + key.get()
                    + "   (" + key.doc + "; applies " + key.timing.phrase + ")");
        }
        lines.add("Change one with '/publicmine config set <key> <value>'. Example: /publicmine config set resetHour 6");
        MineMessages.tellLines(src, lines);
        return 1;
    }

    private static int configGet(CommandSourceStack src, String name) {
        return ConfigKeys.find(name).map(key -> {
            MineMessages.tell(src, key.name + " = " + key.get());
            MineMessages.tell(src, "  " + key.doc + ". Valid: " + key.validRange
                    + ". Applies " + key.timing.phrase + ".");
            return 1;
        }).orElseGet(() -> {
            MineMessages.tell(src, "Unknown setting '" + name + "'. Valid keys: " + String.join(", ", ConfigKeys.names()));
            return 0;
        });
    }

    private static int configSet(CommandSourceStack src, String name, String raw) {
        return ConfigKeys.find(name).map(key -> {
            String before = key.get();
            return key.set(raw).map(error -> {
                MineMessages.tell(src, "Could not set " + key.name + ": " + error + ".");
                MineMessages.tell(src, "Valid: " + key.validRange + ". Example: /publicmine config set "
                        + key.name + " " + key.example);
                return 0;
            }).orElseGet(() -> {
                MineMessages.tell(src, key.name + " changed: " + before + " -> " + key.get()
                        + " (applies " + key.timing.phrase + ", saved to the server config)");
                MineMessages.notifyAdmins(src.getServer(), ADMIN_LEVEL,
                        adminName(src) + " set publicmines config " + key.name + "=" + key.get());
                return 1;
            });
        }).orElseGet(() -> {
            MineMessages.tell(src, "Unknown setting '" + name + "'. Valid keys: " + String.join(", ", ConfigKeys.names()));
            return 0;
        });
    }

    private static String adminName(CommandSourceStack src) {
        return src.getTextName();
    }

    // ------------------------------------------------------------------
    // recover
    // ------------------------------------------------------------------

    private static int recoverCheck(CommandSourceStack src) {
        MineManager manager = MineManager.get(src.getServer());
        MineRunData data = manager.data();
        List<String> lines = new ArrayList<>();
        lines.add("=== Public Mines recovery check ===");
        lines.add("Status: " + data.status() + "  run: " + (data.runId().isEmpty() ? "(none)" : data.runId()));
        if (data.status() == RunStatus.FAILED) {
            lines.add("Last error: " + data.lastError());
        }
        lines.add("Owned volumes: " + data.ownedVolumes().size()
                + "  frontier regions: " + data.frontierRegions());
        switch (data.status()) {
            case READY -> lines.add("The run looks healthy. If players report a broken entrance, "
                    + "'/publicmine recover repair' re-checks the arrival pad.");
            case UNINITIALIZED -> lines.add("No run exists. '/publicmine setup initialize' creates one.");
            case FAILED -> lines.add("Setup failed earlier. '/publicmine recover repair' retries it safely "
                    + "(only mine-owned state is touched).");
            case INITIALIZING -> lines.add("A setup was left unfinished - usually after a restart. "
                    + "'/publicmine recover repair' completes it.");
        }
        lines.add("Recovery never clears blocks outside recorded mine-owned volumes and");
        lines.add("never touches the factions mine or other dimensions.");
        MineMessages.tellLines(src, lines);
        return 1;
    }

    private static int recoverRepair(CommandSourceStack src) {
        MineManager manager = MineManager.get(src.getServer());
        List<MineManager.Step> steps = manager.recoverRepair();
        reportSteps(src, "=== Public Mines recovery ===", steps);
        boolean ok = steps.stream().allMatch(MineManager.Step::ok);
        MineMessages.tell(src, ok
                ? "Recovery finished - confirm with '/publicmine setup validate' and '/publicmine status'."
                : "Recovery could not finish - fix the failed step's blocker, then retry.");
        return ok ? 1 : 0;
    }

    private static void reportSteps(CommandSourceStack src, String header, List<MineManager.Step> steps) {
        List<String> lines = new ArrayList<>();
        lines.add(header);
        for (MineManager.Step step : steps) {
            lines.add((step.ok() ? " [ok]   " : " [!!]   ") + step.name() + ": " + step.detail());
        }
        MineMessages.tellLines(src, lines);
    }
}
