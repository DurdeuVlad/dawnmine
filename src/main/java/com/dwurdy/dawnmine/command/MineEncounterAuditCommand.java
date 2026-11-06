package com.dwurdy.dawnmine.command;

import static net.minecraft.commands.Commands.literal;

import com.mojang.logging.LogUtils;
import com.dwurdy.dawnmine.run.MineEncounterRuntimeAudit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/** Exact-pack native encounter check, absent unless explicitly enabled on a test server. */
public final class MineEncounterAuditCommand {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String ENABLE_PROPERTY = "dawnmine.enableMineEncounterAudit";

    private MineEncounterAuditCommand() { }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        dispatcher.register(literal("dawnmine_audit_encounters")
                .requires(source -> source.hasPermission(Commands.LEVEL_OWNERS))
                .executes(context -> run(context.getSource())));
    }

    private static int run(CommandSourceStack source) {
        ServerLevel mine = source.getServer().getLevel(
                com.dwurdy.dawnmine.run.MineManager.MINE_DIMENSION);
        MineEncounterRuntimeAudit.Result result = MineEncounterRuntimeAudit.run(mine);
        LOGGER.info("[dawnmine] Exact-pack encounter audit: passed={}, cases={}, evidence={}, failures={}",
                result.passed(), result.cases(), result.evidence(), result.failures());
        String message = "Mine encounter audit " + (result.passed() ? "passed" : "failed")
                + ": " + result.cases() + " runtime cases; see server log for details.";
        if (result.passed()) {
            source.sendSuccess(() -> Component.literal(message), false);
            return 1;
        }
        source.sendFailure(Component.literal(message + " " + result.failures()));
        return 0;
    }
}
