package com.dwurdy.dawnmine.teleporter;

import com.dwurdy.dawnmine.dispatch.DispatchManager;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.RunStatus;
import com.dwurdy.dawnmine.run.TeleporterData;
import com.dwurdy.dawnmine.topology.HubLayout;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

/**
 * Server-side teleporter/hub console interaction. Ordinary vanilla markers
 * only: a staff-bound surface lodestone teleports players into the prepared
 * mine (recording their return position), and the generated hub lodestone
 * returns them to their recorded return point. The hub's Dispatch barrel
 * opens the same per-UUID 54-slot Dispatch used at checkpoints.
 *
 * <p>Safety rules: a block is identified as a console BEFORE any cooldown
 * applies so ordinary block uses are never eaten; off-hand clicks on the
 * hub barrel only cancel the vanilla barrel UI (no custom action); every
 * check uses the player's real level, the real block at the position, and a
 * reach of at most six blocks; a cooldown is recorded only after a
 * successful action and cleared on logout/server stop.
 */
public final class TeleporterManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long COOLDOWN_TICKS = 40;
    private static final double MAX_REACH_SQ = 36.0;
    private static final Map<UUID, Long> lastUse = new HashMap<>();

    private TeleporterManager() { }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, TeleporterManager::onRightClickBlock);
    }

    /** Clears per-session state; called on player logout and server stop. */
    public static synchronized void clearSessionState() {
        lastUse.clear();
    }

    public static synchronized void clearSessionState(UUID playerId) {
        lastUse.remove(playerId);
    }

    public enum Result {
        NOT_A_CONSOLE, ENTERED, LEFT, DISPATCH_OPENED, ON_COOLDOWN,
        PREPARING, DENIED, FAILED, OFFHAND_CANCELLED
    }

    private enum Console { SURFACE_TELEPORTER, HUB_RETURN, HUB_DISPATCH }

    /** Which generated/bound console this block is, in the player's real
     * level, or null. Never trusts a caller-supplied state. */
    private static Console consoleAt(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (player.blockPosition().distSqr(pos) > MAX_REACH_SQ) {
            return null;
        }
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockState actual = level.getBlockState(pos);
        if (level.dimension().equals(MineManager.MINE_DIMENSION) && actual.is(Blocks.LODESTONE)
                && pos.getX() == HubLayout.TELEPORTER_CONSOLE.x()
                && pos.getY() == HubLayout.TELEPORTER_CONSOLE.y()
                && pos.getZ() == HubLayout.TELEPORTER_CONSOLE.z()) {
            return Console.HUB_RETURN;
        }
        if (level.dimension().equals(MineManager.MINE_DIMENSION) && actual.is(Blocks.BARREL)
                && pos.getX() == HubLayout.DISPATCH_CONSOLE.x()
                && pos.getY() == HubLayout.DISPATCH_CONSOLE.y()
                && pos.getZ() == HubLayout.DISPATCH_CONSOLE.z()) {
            return Console.HUB_DISPATCH;
        }
        if (level.dimension().equals(Level.OVERWORLD) && actual.is(Blocks.LODESTONE)) {
            TeleporterData.Binding binding = TeleporterData.get(player.getServer()).binding();
            if (binding != null && pos.equals(binding.lodestone())) {
                return Console.SURFACE_TELEPORTER;
            }
        }
        return null;
    }

    /**
     * Shared physical-interaction logic; the Forge event listener and
     * GameTests exercise this exact path. {@code hand} must reflect the
     * actual interaction hand.
     *
     * Full check path: identifies the console at {@code pos} in the
     * player's real level, then applies hand rules, gating, and cooldown. */
    public static synchronized Result tryUseConsole(ServerPlayer player, BlockPos pos,
                                                    InteractionHand hand) {
        ServerLevel level = player.serverLevel();
        Console console = consoleAt(player, level, pos);
        if (console == null) {
            return Result.NOT_A_CONSOLE;
        }
        // Off-hand clicks on the hub barrel cancel the vanilla UI but never
        // trigger the custom Dispatch action.
        if (hand != InteractionHand.MAIN_HAND) {
            return console == Console.HUB_DISPATCH
                    ? Result.OFFHAND_CANCELLED : Result.NOT_A_CONSOLE;
        }
        MineManager manager = MineManager.get(player.getServer());
        switch (console) {
            case HUB_RETURN -> {
                // Emergency escape: leaving is always allowed, even while the
                // run is closed or preparing.
                if (!consumeCooldown(player)) {
                    return Result.ON_COOLDOWN;
                }
                MineManager.LeaveResult result = manager.leave(player);
                if (result == MineManager.LeaveResult.LEFT) {
                    player.sendSystemMessage(Component.literal(
                            "Teleporter engaged - returning to the surface."));
                    return Result.LEFT;
                }
                refundCooldown(player);
                return Result.FAILED;
            }
            case HUB_DISPATCH -> {
                if (!manager.preparationComplete()
                        || manager.data().status() != RunStatus.READY) {
                    return Result.PREPARING;
                }
                if (!consumeCooldown(player)) {
                    return Result.ON_COOLDOWN;
                }
                boolean opened = DispatchManager.get(player.getServer()).open(player);
                if (!opened) {
                    refundCooldown(player);
                    return Result.FAILED;
                }
                return Result.DISPATCH_OPENED;
            }
            case SURFACE_TELEPORTER -> {
                TeleporterData.Binding binding =
                        TeleporterData.get(player.getServer()).binding();
                if (binding == null) {
                    return Result.NOT_A_CONSOLE;
                }
                if (!manager.preparationComplete()) {
                    player.sendSystemMessage(Component.literal(
                            "The mine is still being prepared; the teleporter opens when it finishes."));
                    return Result.PREPARING;
                }
                if (!safeLanding(level, binding.safeLanding())) {
                    player.sendSystemMessage(Component.literal(
                            "The teleporter landing is no longer safe; ask staff to rebind it."));
                    return Result.DENIED;
                }
                if (!consumeCooldown(player)) {
                    return Result.ON_COOLDOWN;
                }
                MineManager.EnterOutcome outcome = manager.enter(player);
                player.sendSystemMessage(Component.literal(outcome.detail()));
                if (outcome.result() == MineManager.EnterResult.ENTERED) {
                    return Result.ENTERED;
                }
                refundCooldown(player);
                return Result.DENIED;
            }
            default -> {
                return Result.NOT_A_CONSOLE;
            }
        }
    }

    private static boolean consumeCooldown(ServerPlayer player) {
        long tick = player.getServer().getTickCount();
        Long last = lastUse.get(player.getUUID());
        if (last != null && tick - last < COOLDOWN_TICKS) {
            return false;
        }
        lastUse.put(player.getUUID(), tick);
        return true;
    }

    private static void refundCooldown(ServerPlayer player) {
        lastUse.remove(player.getUUID());
    }

    /** A usable landing: loaded chunk, sturdy floor, two air blocks, and no
     * hazardous block (lava, fire, magma, cactus, powder snow, campfire,
     * sweet berry, pointed dripstone) at the landing or its floor. */
    public static boolean safeLanding(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.below())) {
            return false;
        }
        BlockState below = level.getBlockState(feet.below());
        if (!below.isFaceSturdy(level, feet.below(), Direction.UP)) {
            return false;
        }
        return level.getBlockState(feet).isAir()
                && level.getBlockState(feet.above()).isAir()
                && !isHazardous(level, feet.below())
                && !isHazardous(level, feet)
                && !isHazardous(level, feet.above());
    }

    private static boolean isHazardous(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POINTED_DRIPSTONE)
                || !level.getFluidState(pos).isEmpty()
                        && level.getFluidState(pos).is(FluidTags.LAVA);
    }

    /** Picks the first safe horizontal neighbour (then the block above the
     * lodestone) as the landing, or null when nothing safe is adjacent. */
    public static BlockPos deriveLanding(ServerLevel level, BlockPos lodestone) {
        for (BlockPos feet : new BlockPos[] {
                lodestone.north(), lodestone.south(), lodestone.east(),
                lodestone.west(), lodestone.above()}) {
            if (safeLanding(level, feet)) {
                return feet.immutable();
            }
        }
        return null;
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()
                || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        InteractionHand hand = event.getHand();
        BlockPos pos = event.getPos();
        // Identify the console first: unrelated block uses must never be
        // cancelled, cooldown included.
        Console console = consoleAt(player, level, pos);
        if (console == null) {
            return;
        }
        if (hand != InteractionHand.MAIN_HAND) {
            // Cancel only the vanilla interaction for the hub barrel; other
            // consoles ignore off-hand clicks entirely.
            if (console == Console.HUB_DISPATCH) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }
        Result result = tryUseConsole(player, pos, hand);
        if (result != Result.NOT_A_CONSOLE) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (result == Result.FAILED) {
                LOGGER.warn("[dawnmine] console interaction at {} in {} failed", pos,
                        level.dimension().location());
            }
        }
    }
}
