package com.dwurdy.dawnmine.command;

import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import com.dwurdy.dawnmine.generation.OreBreakAudit;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/** Optional exact-pack audit command, absent unless explicitly enabled on a test server. */
public final class OreBreakAuditCommand {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String ENABLE_PROPERTY = "dawnmine.enableOreBreakAudit";

    private OreBreakAuditCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            return;
        }
        dispatcher.register(literal("dawnmine_audit_ores")
                .requires(source -> source.hasPermission(Commands.LEVEL_OWNERS))
                .executes(context -> run(context.getSource())));
    }

    private static int run(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos testPos = new BlockPos(spawn.getX() + 8,
                Math.min(level.getMaxBuildHeight() - 32, spawn.getY() + 96), spawn.getZ() + 8);
        OreBreakAudit.Result result = OreBreakAudit.run(level, testPos);
        OreBreakAudit.HookScanResult hooks = OreBreakAudit.findCodeDefinedBreakHooks();
        boolean passed = isPassed(result, hooks);
        List<String> failures = new ArrayList<>(result.failed());
        hooks.scanErrors().forEach(error -> failures.add("block hook scan: " + error));
        LOGGER.info("[dawnmine] Exact-pack ore break audit: {} cases, passed={}, failures={}",
                result.tested(), passed, failures);
        LOGGER.info("[dawnmine] Code-defined block hook scan: {} registered blocks, {} hook-bearing blocks, {} scan errors.",
                hooks.registeredBlocks(), hooks.hookedBlocks().size(), hooks.scanErrors().size());
        String summary = "Ore break audit " + (passed ? "passed" : "failed") + ": "
                + result.tested() + " block/tool cases; code-hook scan found "
                + hooks.hookedBlocks().size() + " hook-bearing blocks with "
                + hooks.scanErrors().size() + " scan errors. See server log.";
        if (passed) {
            source.sendSuccess(() -> Component.literal(summary), false);
            return 1;
        }
        if (!failures.isEmpty()) {
            source.sendFailure(Component.literal(String.join("; ", failures)));
        } else {
            source.sendFailure(Component.literal(summary));
        }
        return 0;
    }

    static boolean isPassed(OreBreakAudit.Result result, OreBreakAudit.HookScanResult hooks) {
        return result.passed() && hooks.scanErrors().isEmpty();
    }
}
