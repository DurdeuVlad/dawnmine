package com.dwurdy.dawnmine.command;

import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import com.dwurdy.dawnmine.run.MineProgressionRuntimeAudit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/** Exact-pack progression-economy audit, absent unless explicitly enabled on a test server. */
public final class MineProgressionAuditCommand {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String ENABLE_PROPERTY = "dawnmine.enableMineProgressionAudit";

    private MineProgressionAuditCommand() { }

    public static void register(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) return;
        dispatcher.register(literal("dawnmine_audit_progression")
                .requires(source -> source.hasPermission(Commands.LEVEL_OWNERS))
                .executes(context -> run(context.getSource(), "all"))
                .then(Commands.argument("phase", StringArgumentType.word())
                        .suggests((context, builder) -> builder
                                .suggest("prepare").suggest("verify").suggest("all").buildFuture())
                        .executes(context -> run(context.getSource(),
                                StringArgumentType.getString(context, "phase")))));
    }

    private static int run(CommandSourceStack source, String phase) {
        ServerLevel overworld = source.getServer().overworld();
        MineProgressionRuntimeAudit.Result result =
                MineProgressionRuntimeAudit.run(overworld, phase);
        LOGGER.info("[dawnmine] Progression audit phase={} passed={} cases={} evidence={} failures={}",
                phase, result.passed(), result.cases(), result.evidence(), result.failures());
        String message = "Mine progression audit (" + phase + ") "
                + (result.passed() ? "passed" : "failed") + ": " + result.cases()
                + " runtime cases; see server log for details.";
        if (result.passed()) {
            source.sendSuccess(() -> Component.literal(message), false);
            return 1;
        }
        source.sendFailure(Component.literal(message + " " + result.failures()));
        return 0;
    }
}
