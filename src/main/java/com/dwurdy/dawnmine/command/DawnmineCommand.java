package com.dwurdy.dawnmine.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.dwurdy.dawnmine.config.DawnmineConfig;
import com.dwurdy.dawnmine.dispatch.DispatchManager;
import com.dwurdy.dawnmine.generation.MineShellGeometry;
import com.dwurdy.dawnmine.generation.OreCatalog;
import com.dwurdy.dawnmine.progression.ProgressionGraph;
import com.dwurdy.dawnmine.encounter.MineRewardCatalog;
import com.dwurdy.dawnmine.run.MineDailyReset;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.MineRunData;
import com.dwurdy.dawnmine.run.OwnedVolume;
import com.dwurdy.dawnmine.run.RunStatus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code /dawnmine} command tree.
 *
 * <p>Player commands (help, status player view, enter, leave, exit) are open
 * to everyone. Setup, config writes, and recovery require permission level 2
 * (the vanilla game-master/operator threshold), checked server-side on every
 * invocation; the console passes automatically. Admin diagnostics are never
 * shown to players without that level.
 */
public final class DawnmineCommand {
    /** Vanilla operator/game-master threshold required for all mutations and staff diagnostics. */
    public static final int ADMIN_LEVEL = Commands.LEVEL_GAMEMASTERS;

    private DawnmineCommand() {}

    private static final SuggestionProvider<CommandSourceStack> CONFIG_KEYS =
            (context, builder) -> SharedSuggestionProvider.suggest(ConfigKeys.names(), builder);
    private static final SuggestionProvider<CommandSourceStack> CHECKPOINT_IDS =
            (context, builder) -> SharedSuggestionProvider.suggest(MineManager
                    .get(context.getSource().getServer()).data().checkpointStations().stream()
                    .map(MineRunData.CheckpointStation::id).toList(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("dawnmine")
                .executes(ctx -> help(ctx.getSource()));

        root.then(literal("help").executes(ctx -> help(ctx.getSource())));
        root.then(literal("status").executes(ctx -> status(ctx.getSource())));
        root.then(literal("rewards").requires(src -> src.hasPermission(ADMIN_LEVEL))
                .executes(ctx -> rewardStatus(ctx.getSource())));
        root.then(literal("enter").executes(ctx -> enter(ctx)));
        root.then(literal("leave").executes(ctx -> leave(ctx)));
        root.then(literal("exit").executes(ctx -> leave(ctx)));
        root.then(literal("checkpoints").executes(ctx -> checkpoints(ctx.getSource())));
        root.then(literal("progression").executes(ctx -> progression(ctx.getSource())));
        root.then(literal("checkpoint")
                .then(argument("id", StringArgumentType.word()).suggests(CHECKPOINT_IDS)
                        .executes(ctx -> checkpoint(ctx.getSource(),
                                StringArgumentType.getString(ctx, "id")))));
        root.then(literal("dispatch").executes(DawnmineCommand::dispatch));
        root.then(literal("teleporter")
                .then(literal("bind").requires(src -> src.hasPermission(ADMIN_LEVEL))
                        .then(net.minecraft.commands.Commands.argument("pos",
                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(ctx -> teleporterBind(ctx,
                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument
                                                .getLoadedBlockPos(ctx, "pos")))))
                .then(literal("status").executes(ctx -> teleporterStatus(ctx.getSource())))
                .then(literal("unbind").requires(src -> src.hasPermission(ADMIN_LEVEL))
                        .executes(ctx -> teleporterUnbind(ctx.getSource()))));

        root.then(literal("setup").requires(src -> src.hasPermission(ADMIN_LEVEL))
                .executes(ctx -> setupUsage(ctx.getSource()))
                .then(literal("guide").executes(ctx -> setupGuide(ctx.getSource())))
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

        root.then(literal("encounters").requires(src -> src.hasPermission(ADMIN_LEVEL))
                .then(literal("status").executes(ctx -> {
                    MineMessages.tellLines(ctx.getSource(),
                            com.dwurdy.dawnmine.encounter.MineEncounterManager.statusLines(ctx.getSource().getServer()));
                    return 1;
                }))
                .then(literal("wake")
                        .then(Commands.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                        .then(Commands.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                                .executes(ctx -> {
                                                    int n = com.dwurdy.dawnmine.encounter.MineEncounterManager.adminWake(
                                                            ctx.getSource().getServer(),
                                                            new net.minecraft.core.BlockPos(
                                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "x"),
                                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "y"),
                                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "z")));
                                                    ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                                                            "Woke " + n + " encounter site(s)."), false);
                                                    return n;
                                                }))))));

        root.then(literal("census").requires(src -> src.hasPermission(ADMIN_LEVEL))
                .executes(ctx -> census(ctx.getSource())));

        root.then(literal("reset").requires(src -> src.hasPermission(ADMIN_LEVEL))
                .executes(ctx -> resetStatus(ctx.getSource()))
                .then(literal("status").executes(ctx -> resetStatus(ctx.getSource())))
                .then(literal("now").executes(ctx -> resetNow(ctx.getSource())))
                .then(literal("retry").executes(ctx -> resetRetry(ctx.getSource()))));

        dispatcher.register(root);
    }

    // ------------------------------------------------------------------
    // help / status
    // ------------------------------------------------------------------

    private static int help(CommandSourceStack src) {
        List<String> lines = new ArrayList<>();
        boolean admin = src.hasPermission(ADMIN_LEVEL);
        lines.add("=== Dawnmine ===");
        lines.add("A shared daily mine for everyone on the server.");
        lines.add("");
        lines.add("Player commands (everyone):");
        lines.add("  /dawnmine help    - this guide");
        lines.add("  /dawnmine status  - is the mine open?");
        lines.add("  /dawnmine enter   - travel into the shared mine");
        lines.add("  Right-click the surface teleporter to arrive inside the underground hub.");
        lines.add("  /dawnmine leave   - emergency return to the surface (also: exit)");
        lines.add("  /dawnmine teleporter status - check whether the surface console is linked");
        lines.add("  /dawnmine checkpoints - list shared generated checkpoints");
        lines.add("  /dawnmine checkpoint <id> - travel to a generated checkpoint");
        lines.add("  /dawnmine dispatch - open your private 54-slot Foreman's Dispatch at a checkpoint or surface depot");
        lines.add("  /dawnmine progression - what your mine finds can become");
        if (admin) {
            lines.add("");
            lines.add("Staff commands (operator level " + ADMIN_LEVEL + "+):");
            lines.add("  /dawnmine status            - full run diagnostics");
            lines.add("  /dawnmine rewards           - show depth combat bonus rates and purposes");
            lines.add("  /dawnmine progression       - progression graph diagnostics");
            lines.add("  /dawnmine setup guide       - walk through first-time setup step by step");
            lines.add("  /dawnmine setup validate    - check what is missing");
            lines.add("  /dawnmine setup initialize  - create or repair the mine (safe to re-run)");
            lines.add("  /dawnmine teleporter bind <x> <y> <z> - link a nearby overworld lodestone");
            lines.add("  /dawnmine teleporter unbind - remove the link without changing world blocks");
            lines.add("  /dawnmine config get [key]  - show settings");
            lines.add("  /dawnmine config set <key> <value> - change a setting");
            lines.add("  /dawnmine recover check     - inspect saved state");
            lines.add("  /dawnmine recover repair    - fix a failed/partial setup");
            lines.add("  /dawnmine reset status      - daily reset schedule and progress");
            lines.add("  /dawnmine reset now         - run the reset cycle immediately");
            lines.add("  /dawnmine reset retry       - re-arm a halted reset");
            lines.add("");
            lines.add("First-time setup: run '/dawnmine setup guide' and follow the steps,");
            lines.add("or run '/dawnmine setup validate', fix any blockers,");
            lines.add("then '/dawnmine setup initialize' and '/dawnmine status' to confirm.");
            lines.add("If anything goes wrong, '/dawnmine recover repair' is the safe fix.");
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
        lines.add("=== Dawnmine status (staff view) ===");
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
        var prep = manager.preparationProgress();
        lines.add("Preparation: " + (prep.complete() ? "complete - all " + prep.expected()
                + " expected segments generated; entry open"
                : prep.expected() == 0 ? "not started or unavailable (entry closed)"
                        : "in progress " + prep.completed() + "/" + prep.expected()
                                + " segments (" + prep.percent() + "%); entry closed until complete"));
        lines.add("Shared checkpoints: " + data.checkpointStations().size());
        lines.add("Boundary shell: " + DawnmineConfig.mineRegionRadiusChunks()
                + " chunks, generated before each chunk becomes available");
        OreCatalog.Completeness oreCatalog = manager.mineLevel() == null ? null
                : OreCatalog.active().completeness(manager.mineLevel());
        lines.add("Ore catalog: " + (oreCatalog == null ? "not validated (mine dimension unavailable)"
                : OreCatalog.describe(oreCatalog)));
        ProgressionGraph.ProgressionSnapshot graph = ProgressionGraph.active();
        lines.add("Progression graph: " + graph.adminSummary()
                + (graph.completeness().complete() ? " (integrity ok)" : " (integrity issues — see '/dawnmine progression')"));
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
        // Entry is open only when the run admits it AND no reset transaction
        // still owns the mine - enter() enforces the same two conditions.
        lines.add("Entry: " + (data.status().entryAllowed() && !data.resetInProgress()
                && manager.preparationComplete() ? "open" : "closed"));
        MineDailyReset reset = MineDailyReset.get(src.getServer());
        lines.add("Daily reset: " + manager.effectiveResetDescription()
                + "  |  next: " + reset.nextResetDescription());
        lines.add("Reset progress: " + (data.resetInProgress()
                ? data.resetPhase() + " '" + data.resetDue() + "' at column " + data.resetCursor()
                        + " (" + data.resetAttempts() + " attempts)"
                : "none running; last completed "
                        + (data.lastResetDue().isEmpty() ? "(none yet)" : data.lastResetDue())));
        lines.add("Netherrack: " + (DawnmineConfig.netherrackEnabled()
                ? "enabled, 1 attempt per " + DawnmineConfig.netherrackColumnInterval() + " eligible columns (deepest region only)"
                : "off (default)"));
        lines.add("Next steps: '/dawnmine setup validate' for checks, '/dawnmine help' for commands.");
        MineMessages.tellLines(src, lines);
        return 1;
    }

    private static int rewardStatus(CommandSourceStack src) {
        MineMessages.tell(src, "Configured mine combat bonuses: " + MineRewardCatalog.adminSummary());
        MineMessages.tell(src, "These per-encounter rates are configurable by data pack; final balance is subject to M8 playtests.");
        return 1;
    }

    private static int progression(CommandSourceStack src) {
        ProgressionGraph.ProgressionSnapshot graph = ProgressionGraph.active();
        if (graph.materials().isEmpty()) {
            MineMessages.tell(src, "The mine progression graph failed to load; staff can check the server log.");
            return 0;
        }
        List<String> lines = new ArrayList<>();
        lines.add("=== What your mine finds can become ===");
        lines.add("Bank finds on the surface with '/dawnmine leave' - carried gear and stored");
        lines.add("materials are yours to keep across daily runs. Tomorrow's route goes deeper.");
        for (String tier : new String[] {"upper", "middle", "chaos"}) {
            List<ProgressionGraph.Upgrade> hints = graph.reachableUpgrades(tier);
            if (hints.isEmpty()) continue;
            String title = switch (tier) {
                case "upper" -> "Upper Galleries";
                case "middle" -> "Middle Descent";
                default -> "Chaos Depths";
            };
            lines.add("");
            lines.add(title + ":");
            for (ProgressionGraph.Upgrade upgrade : hints) {
                lines.add("  - " + upgrade.output() + " — " + firstSentence(upgrade.solves()));
            }
        }
        if (!src.hasPermission(ADMIN_LEVEL)) {
            lines.add("");
            lines.add("Ask a server operator for the full progression report.");
        } else {
            lines.add("");
            lines.add("Graph: " + graph.adminSummary());
            ProgressionGraph.Completeness completeness = graph.completeness();
            if (completeness.complete()) {
                lines.add("Integrity: all sources, drops, rewards, and items resolved.");
            } else {
                lines.add("Integrity issues:");
                if (!completeness.unavailableItems().isEmpty()) {
                    lines.add("  unavailable items: " + completeness.unavailableItems());
                }
                if (!completeness.unknownMiningBlocks().isEmpty()) {
                    lines.add("  unknown mining blocks: " + completeness.unknownMiningBlocks());
                }
                if (!completeness.miningDropMismatches().isEmpty()) {
                    lines.add("  mining drop mismatches: " + completeness.miningDropMismatches());
                }
                if (!completeness.unknownRewardTiers().isEmpty()) {
                    lines.add("  unknown reward tiers: " + completeness.unknownRewardTiers());
                }
                if (!completeness.rewardItemMismatches().isEmpty()) {
                    lines.add("  reward item mismatches: " + completeness.rewardItemMismatches());
                }
                if (!completeness.unavailableSourceEntities().isEmpty()) {
                    lines.add("  unavailable source entities: " + completeness.unavailableSourceEntities());
                }
                if (!completeness.errors().isEmpty()) {
                    lines.add("  load errors: " + completeness.errors());
                }
            }
            if (!graph.blocked().isEmpty()) {
                lines.add("Blocked (verified pack gaps, not fabricated):");
                for (ProgressionGraph.BlockedNode node : graph.blocked()) {
                    lines.add("  - " + node.item() + ": " + node.reason());
                }
            }
        }
        MineMessages.tellLines(src, lines);
        return 1;
    }

    private static String firstSentence(String text) {
        int end = text.indexOf(". ");
        if (end < 0) end = text.indexOf(';');
        if (end < 0) end = Math.min(text.length(), 140);
        String sentence = text.substring(0, Math.min(Math.min(end + 1, 160), text.length())).trim();
        return sentence.endsWith(".") ? sentence : sentence + "…";
    }

    private static int checkpoints(CommandSourceStack src) {
        MineRunData data = MineManager.get(src.getServer()).data();
        if (!data.status().entryAllowed()) {
            MineMessages.tell(src, "Dawnmine checkpoints are unavailable while the shared run is "
                    + data.status().name().toLowerCase() + ".");
            return 0;
        }
        List<MineRunData.CheckpointStation> stations = data.checkpointStations();
        if (stations.isEmpty()) {
            MineMessages.tell(src, "No checkpoints have been generated yet. Explore the current route; "
                    + "stations unlock for everyone when their rooms are built.");
            return 1;
        }
        MineMessages.tell(src, "Shared checkpoints (available to everyone this run):");
        for (MineRunData.CheckpointStation station : stations) {
            MineMessages.tell(src, "  " + station.id() + " — " + station.label() + ", "
                    + station.region() + ", about " + station.depth() + " blocks below the entrance; "
                    + "travel: /dawnmine checkpoint " + station.id());
        }
        MineMessages.tell(src, "Bank/extract with '/dawnmine leave', or pick a deeper station to push onward.");
        return 1;
    }

    private static int checkpoint(CommandSourceStack src, String id) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            MineMessages.tell(src, "Only in-game players can travel to a checkpoint.");
            return 0;
        }
        MineManager.CheckpointOutcome outcome = MineManager.get(src.getServer()).travelToCheckpoint(player, id);
        if (outcome.result() == MineManager.CheckpointResult.TRAVELED) {
            MineMessages.tell(src, outcome.detail());
            return 1;
        }
        MineMessages.tell(src, outcome.detail());
        return 0;
    }

    private static void statusForPlayer(CommandSourceStack src, MineManager manager) {
        RunStatus status = manager.data().status();
        if (status.entryAllowed() && !manager.data().resetInProgress()) {
            if (!manager.preparationComplete()) {
                var prep = manager.preparationProgress();
                MineMessages.tell(src, "The Dawnmine is still being prepared for today's run"
                        + (prep.expected() > 0 ? " - " + prep.percent() + "% done." : ".")
                        + " It opens automatically when preparation finishes.");
                return;
            }
            MineMessages.tell(src, "The Dawnmine is open! Right-click the surface teleporter "
                    + "to arrive in the underground hub. '/dawnmine enter' is also available.");
        } else if (status == RunStatus.RESETTING) {
            MineMessages.tell(src, "The Dawnmine is resetting for a new day - "
                    + "it reopens in a few minutes with a fresh run.");
        } else {
            MineMessages.tell(src, "The Dawnmine isn't open right now. "
                    + "A server operator can open it with '/dawnmine setup initialize'.");
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
                MineMessages.tell(src, "Welcome to the Dawnmine! This run is shared with everyone.");
                MineMessages.tell(src, "Use '/dawnmine leave' to return to the surface.");
            }
            case ALREADY_INSIDE -> MineMessages.tell(src, "You're already inside the Dawnmine. "
                    + "Use '/dawnmine leave' to head back.");
            case NOT_READY -> MineMessages.tell(src, "The Dawnmine isn't open right now. "
                    + outcome.detail());
            case ARRIVAL_UNSAFE -> {
                MineMessages.tell(src, "The mine entrance isn't safe at the moment - please try again soon.");
                MineMessages.notifyAdmins(src.getServer(), ADMIN_LEVEL,
                        "Mine arrival pad failed its safety check for " + player.getGameProfile().getName()
                                + "; run '/dawnmine recover repair'");
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
                MineMessages.tell(src, "You're not inside the Dawnmine.");
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

    private static int teleporterBind(CommandContext<CommandSourceStack> ctx,
                                      net.minecraft.core.BlockPos lodestone) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null || src.getLevel().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            MineMessages.tell(src, "teleporter bind must be run by a player in the overworld");
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = src.getLevel();
        if (player.blockPosition().distSqr(lodestone) > 36) {
            MineMessages.tell(src, "stand within 6 blocks of the lodestone you want to bind");
            return 0;
        }
        if (!level.isLoaded(lodestone)) {
            MineMessages.tell(src, "that position is not in a loaded chunk - move closer and retry");
            return 0;
        }
        if (!level.getBlockState(lodestone).is(net.minecraft.world.level.block.Blocks.LODESTONE)) {
            MineMessages.tell(src, "there is no lodestone at " + lodestone.getX() + " "
                    + lodestone.getY() + " " + lodestone.getZ() + " - staff place it themselves");
            return 0;
        }
        var data = com.dwurdy.dawnmine.run.TeleporterData.get(src.getServer());
        var existing = data.binding();
        if (existing != null) {
            if (existing.lodestone().equals(lodestone)) {
                MineMessages.tell(src, "that lodestone is already bound");
                return 0;
            }
            MineMessages.tell(src, "a teleporter is already bound at "
                    + existing.lodestone().toShortString() + " - unbind it first");
            return 0;
        }
        net.minecraft.core.BlockPos landing =
                com.dwurdy.dawnmine.teleporter.TeleporterManager.deriveLanding(level, lodestone);
        if (landing == null) {
            MineMessages.tell(src, "no safe landing next to that lodestone - clear a solid floor "
                    + "with two open blocks beside or above it");
            return 0;
        }
        if (!data.bind(lodestone, landing)) {
            MineMessages.tell(src, "could not bind that lodestone");
            return 0;
        }
        if (!MineManager.get(src.getServer()).bindSurfaceExit(landing)) {
            // Atomic policy: never leave a binding whose exit zone failed.
            data.unbind();
            MineMessages.tell(src, "could not update the surface exit safe zone - "
                    + "binding rolled back, previous surface exit retained");
            return 0;
        }
        MineMessages.tell(src, "teleporter bound at " + lodestone.toShortString()
                + " (landing " + landing.toShortString() + "); right-click it to enter the mine");
        return 1;
    }

    private static int teleporterStatus(CommandSourceStack src) {
        var binding = com.dwurdy.dawnmine.run.TeleporterData.get(src.getServer()).binding();
        if (binding == null) {
            MineMessages.tell(src, "teleporter: not bound (staff place a lodestone in the "
                    + "overworld, then run '/dawnmine teleporter bind <x> <y> <z>')");
        } else if (src.hasPermission(ADMIN_LEVEL)) {
            MineMessages.tell(src, "teleporter: bound lodestone at "
                    + binding.lodestone().toShortString() + ", landing at "
                    + binding.safeLanding().toShortString());
        } else {
            MineMessages.tell(src, "teleporter: linked - right-click the "
                    + "surface lodestone to enter the mine once it is prepared");
        }
        return 1;
    }

    private static int teleporterUnbind(CommandSourceStack src) {
        var data = com.dwurdy.dawnmine.run.TeleporterData.get(src.getServer());
        if (data.binding() == null) {
            MineMessages.tell(src, "teleporter: nothing is bound");
            return 0;
        }
        boolean zone = MineManager.get(src.getServer()).bindSurfaceExit(
                src.getServer().overworld().getSharedSpawnPos());
        if (zone) {
            data.unbind();
            MineMessages.tell(src, "teleporter binding cleared; surface exit safe zone "
                    + "restored to the shared spawn (world blocks were not touched)");
        } else {
            MineMessages.tell(src, "could not restore the surface exit safe zone; "
                    + "the existing teleporter binding is unchanged");
            return 0;
        }
        return 1;
    }

    private static int dispatch(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            MineMessages.tell(src, "Only in-game players can open Foreman's Dispatch.");
            return 0;
        }
        return DispatchManager.get(src.getServer()).open(player) ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // setup
    // ------------------------------------------------------------------

    private static int setupUsage(CommandSourceStack src) {
        MineMessages.tellLines(src, List.of(
                "Usage: '/dawnmine setup guide' for a step-by-step walkthrough,",
                "'/dawnmine setup validate' to check readiness,",
                "or '/dawnmine setup initialize' to create/repair the shared mine.",
                "All are safe to re-run and only touch mine-owned state."));
        return 0;
    }

    /** State-aware walkthrough for first-time operators/testers: marks each
     * setup step done/next/todo from the live run state and names the exact
     * command to run, stopping early when a step is still outstanding. */
    private static int setupGuide(CommandSourceStack src) {
        MineManager manager = MineManager.get(src.getServer());
        MineRunData data = manager.data();
        boolean dimensionLoaded = manager.mineLevel() != null;
        long failures = manager.validate().stream()
                .filter(c -> c.severity() == MineManager.Severity.FAIL).count();
        boolean ready = data.status() == RunStatus.READY && !data.resetInProgress()
                && manager.preparationComplete();
        List<String> lines = new ArrayList<>();
        lines.add("=== Dawnmine setup guide ===");
        lines.add("Work down the list; [done] steps are already finished on this server.");
        lines.add("");
        lines.add((dimensionLoaded ? " [done] " : " [next] ")
                + "1. Mod + mine dimension loaded"
                + (dimensionLoaded ? "" : " - mine is NOT registered; check the jar in mods/ and restart"));
        if (!dimensionLoaded) {
            lines.add("         Stop here: the mine cannot initialize without its dimension.");
            MineMessages.tellLines(src, lines);
            return 0;
        }
        lines.add((failures == 0 ? " [done] " : " [next] ")
                + "2. '/dawnmine setup validate' passes"
                + (failures == 0 ? "" : " - " + failures + " [!!] item(s) need the fix each line names"));
        if (failures > 0) {
            lines.add("         Fix them, then re-run '/dawnmine setup guide'. Stuck halfway? '/dawnmine recover repair'.");
            MineMessages.tellLines(src, lines);
            return 0;
        }
        lines.add((ready ? " [done] " : " [next] ")
                + "3. '/dawnmine setup initialize' run READY"
                + (ready ? " (run " + data.runId() + ")" : " - creates the shared mine; safe to re-run"));
        if (!ready) {
            lines.add("         The whole mine is generated before entry - watch '/dawnmine status' until");
            lines.add("         Preparation shows complete; players are admitted only afterwards.");
            if (data.status() == RunStatus.FAILED) {
                lines.add("         Run is FAILED: '/dawnmine recover check' then '/dawnmine recover repair'.");
            }
            MineMessages.tellLines(src, lines);
            return 0;
        }
        boolean bound = com.dwurdy.dawnmine.run.TeleporterData.get(src.getServer()).binding() != null;
        lines.add((bound ? " [done] " : " [next] ") + "4. Surface teleporter linked");
        if (!bound) {
            lines.add("         Place a lodestone in the overworld with a safe floor and two blocks");
            lines.add("         of air beside it. Stand within six blocks and run in-game:");
            lines.add("         '/dawnmine teleporter bind <x> <y> <z>'. The mod builds no overworld blocks.");
            MineMessages.tellLines(src, lines);
            return 0;
        }
        lines.add(" [todo] 5. Right-click the surface lodestone: arrive INSIDE the underground hub.");
        lines.add("         Try the private Dispatch barrel, follow the east cave gateway,");
        lines.add("         and right-click the hub lodestone to return. '/dawnmine leave' is emergency escape.");
        lines.add(" [todo] 6. Check the daily reset is armed: '/dawnmine reset status'.");
        lines.add("");
        lines.add("Setup complete - tell testers to run '/dawnmine help'.");
        lines.add("If anything breaks later: '/dawnmine recover check' then '/dawnmine recover repair'.");
        MineMessages.tellLines(src, lines);
        return 1;
    }

    private static int validate(CommandSourceStack src) {
        MineManager manager = MineManager.get(src.getServer());
        List<MineManager.Check> checks = manager.validate();
        int failures = 0;
        List<String> lines = new ArrayList<>();
        lines.add("=== Dawnmine validation ===");
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
        if (manager.data().status() == RunStatus.READY && failures == 0
                && manager.preparationComplete() && !manager.data().resetInProgress()) {
            lines.add("The mine is prepared. Link the surface teleporter with "
                    + "'/dawnmine setup guide', then right-click it to enter the hub.");
        } else if (manager.data().status() == RunStatus.READY && failures == 0) {
            lines.add("Initialization is valid, but entry remains closed until preparation/reset finishes.");
        } else if (failures == 0) {
            lines.add("Validation passed. Next step: '/dawnmine setup initialize'.");
        } else {
            lines.add(failures + " item(s) need attention - see the [!!] lines for the fix for each.");
            lines.add("If a previous setup stopped halfway, '/dawnmine recover repair' is the safe retry.");
        }
        MineMessages.tellLines(src, lines);
        return failures == 0 ? 1 : 0;
    }

    private static int initialize(CommandSourceStack src) {
        MineManager manager = MineManager.get(src.getServer());
        List<MineManager.Step> steps = manager.initialize();
        reportSteps(src, "=== Dawnmine setup ===", steps);
        boolean ok = steps.stream().allMatch(MineManager.Step::ok);
        if (ok) {
            MineMessages.tell(src, "Setup finished. Verify with '/dawnmine setup validate' or '/dawnmine status'.");
            if (!manager.occupants().isEmpty()) {
                MineMessages.notifyMineOccupants(src.getServer(),
                        "The Dawnmine was refreshed by staff. Use '/dawnmine leave' if you need to exit.");
            }
            return 1;
        }
        MineMessages.tell(src, "Setup could not finish - see the failed step above. "
                + "'/dawnmine recover repair' retries safely once the blocker is fixed.");
        return 0;
    }

    // ------------------------------------------------------------------
    // config
    // ------------------------------------------------------------------

    private static int configGetAll(CommandSourceStack src) {
        List<String> lines = new ArrayList<>();
        lines.add("=== Dawnmine config (server) ===");
        for (ConfigKeys.Key key : ConfigKeys.ALL) {
            lines.add("  " + key.name + " = " + key.get()
                    + "   (" + key.doc + "; applies " + key.timing.phrase + ")");
        }
        lines.add("Change one with '/dawnmine config set <key> <value>'. Example: /dawnmine config set resetHour 6");
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
                MineMessages.tell(src, "Valid: " + key.validRange + ". Example: /dawnmine config set "
                        + key.name + " " + key.example);
                return 0;
            }).orElseGet(() -> {
                MineMessages.tell(src, key.name + " changed: " + before + " -> " + key.get()
                        + " (applies " + key.timing.phrase + ", saved to the server config)");
                MineMessages.notifyAdmins(src.getServer(), ADMIN_LEVEL,
                        adminName(src) + " set dawnmine config " + key.name + "=" + key.get());
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
        lines.add("=== Dawnmine recovery check ===");
        lines.add("Status: " + data.status() + "  run: " + (data.runId().isEmpty() ? "(none)" : data.runId()));
        if (data.status() == RunStatus.FAILED) {
            lines.add("Last error: " + data.lastError());
        }
        lines.add("Owned volumes: " + data.ownedVolumes().size()
                + "  frontier regions: " + data.frontierRegions());
        switch (data.status()) {
            case READY -> lines.add("The run looks healthy. If players report a broken entrance, "
                    + "'/dawnmine recover repair' re-checks the arrival pad.");
            case UNINITIALIZED -> lines.add("No run exists. '/dawnmine setup initialize' creates one.");
            case FAILED -> lines.add("Setup failed earlier. '/dawnmine recover repair' retries it safely "
                    + "(only mine-owned state is touched).");
            case RESETTING -> lines.add("A daily reset is in progress; it finishes on its own. "
                    + "'/dawnmine reset status' shows progress and '/dawnmine reset retry' re-arms a halt.");
            case INITIALIZING -> lines.add("A setup was left unfinished - usually after a restart. "
                    + "'/dawnmine recover repair' completes it.");
        }
        lines.add("Recovery never clears blocks outside recorded mine-owned volumes and");
        lines.add("never touches the factions mine or other dimensions.");
        MineMessages.tellLines(src, lines);
        return 1;
    }

    private static int recoverRepair(CommandSourceStack src) {
        MineManager manager = MineManager.get(src.getServer());
        List<MineManager.Step> steps = manager.recoverRepair();
        reportSteps(src, "=== Dawnmine recovery ===", steps);
        boolean ok = steps.stream().allMatch(MineManager.Step::ok);
        MineMessages.tell(src, ok
                ? "Recovery finished - confirm with '/dawnmine setup validate' and '/dawnmine status'."
                : "Recovery could not finish - fix the failed step's blocker, then retry.");
        return ok ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // daily reset (M7)
    // ------------------------------------------------------------------

    private static int census(CommandSourceStack src) {
        String refusal = com.dwurdy.dawnmine.census.CensusService.start(src);
        if (refusal != null) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(refusal));
            return 0;
        }
        return 1;
    }

    private static int resetStatus(CommandSourceStack src) {
        MineMessages.tellLines(src, MineDailyReset.get(src.getServer()).statusLines());
        return 1;
    }

    private static int resetNow(CommandSourceStack src) {
        return switch (MineDailyReset.get(src.getServer()).requestManualReset()) {
            case STARTED -> {
                MineMessages.tell(src, "Daily reset started: entry is locked, occupants are evacuated, "
                        + "and mine-owned state is being restored. Track with '/dawnmine reset status'.");
                yield 1;
            }
            case ALREADY_RUNNING -> {
                MineMessages.tell(src, "A daily reset is already running - see '/dawnmine reset status'.");
                yield 0;
            }
            case REJECTED -> {
                MineMessages.tell(src, "No run exists to reset yet; '/dawnmine setup initialize' creates one.");
                yield 0;
            }
        };
    }

    private static int resetRetry(CommandSourceStack src) {
        if (MineDailyReset.get(src.getServer()).retryReset()) {
            MineMessages.tell(src, "Daily reset retries re-armed; the scheduler continues automatically. "
                    + "Watch '/dawnmine reset status'.");
            return 1;
        }
        MineMessages.tell(src, "No daily reset is in progress right now.");
        return 0;
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
