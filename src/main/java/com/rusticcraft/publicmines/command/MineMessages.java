package com.rusticcraft.publicmines.command;

import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.run.MineManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Audience-correct messaging for Public Mines.
 *
 * <p>Player-facing notices go only to the invoking player or to players
 * inside the mine. Staff diagnostics go to the invoking operator, other
 * online operators, and the server log. Nothing here broadcasts globally.
 */
public final class MineMessages {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MineMessages() {}

    /** Send a message to just the command invoker (player or console). */
    public static void tell(CommandSourceStack source, String message) {
        source.sendSystemMessage(Component.literal(message));
    }

    public static void tellLines(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            tell(source, line);
        }
    }

    /** Send a friendly notice to every player currently inside the mine. */
    public static List<ServerPlayer> mineOccupants(MinecraftServer server) {
        List<ServerPlayer> inside = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension().equals(MineManager.MINE_DIMENSION)) {
                inside.add(player);
            }
        }
        return inside;
    }

    public static void notifyMineOccupants(MinecraftServer server, String message) {
        Component component = Component.literal(message);
        for (ServerPlayer player : mineOccupants(server)) {
            player.sendSystemMessage(component);
        }
    }

    /**
     * Staff-only notice: all online players with the admin permission level
     * plus the server log. Console invokers already see their own output.
     */
    public static void notifyAdmins(MinecraftServer server, int adminLevel, String message) {
        Component component = Component.literal(message);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(adminLevel)) {
                player.sendSystemMessage(component);
            }
        }
        LOGGER.info("[publicmines] {}", message);
    }
}
