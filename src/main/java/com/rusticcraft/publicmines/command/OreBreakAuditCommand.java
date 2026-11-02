package com.rusticcraft.publicmines.command;

import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.generation.OreBreakAudit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/** Optional exact-pack audit command, absent unless explicitly enabled on a test server. */
public final class OreBreakAuditCommand {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String ENABLE_PROPERTY = "publicmines.enableOreBreakAudit";

    private OreBreakAuditCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            return;
        }
        dispatcher.register(literal("publicmine_audit_ores")
                .requires(source -> source.hasPermission(Commands.LEVEL_OWNERS))
                .executes(context -> run(context.getSource())));
    }

    private static int run(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos testPos = new BlockPos(spawn.getX() + 8,
                Math.min(level.getMaxBuildHeight() - 32, spawn.getY() + 96), spawn.getZ() + 8);
        OreBreakAudit.Result result = OreBreakAudit.run(level, testPos);
        LOGGER.info("[publicmines] Exact-pack ore break audit: {} cases, passed={}, failures={}",
                result.tested(), result.passed(), result.failed());
        OreBreakAudit.HookScanResult hooks = OreBreakAudit.findCodeDefinedBreakHooks();
        LOGGER.info("[publicmines] Code-defined block hook scan: {} registered blocks, {} hook-bearing blocks, {} scan errors.",
                hooks.registeredBlocks(), hooks.hookedBlocks().size(), hooks.scanErrors().size());
        source.sendSuccess(() -> Component.literal("Ore break audit "
                + (result.passed() ? "passed" : "failed") + ": " + result.tested()
                + " block/tool cases; code-hook scan found " + hooks.hookedBlocks().size()
                + " hook-bearing blocks with " + hooks.scanErrors().size() + " scan errors. See server log."),
                !result.passed() || !hooks.scanErrors().isEmpty());
        if (!result.passed()) {
            source.sendFailure(Component.literal(String.join("; ", result.failed())));
            return 0;
        }
        return 1;
    }
}
