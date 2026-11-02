package com.rusticcraft.publicmines.generation;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

/** Exact-pack, server-authoritative check of native ore break/drop behavior. */
public final class OreBreakAudit {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Item> HARVEST_TOOLS = List.of(
            Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE,
            Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE,
            Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL,
            Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL,
            Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
            Items.DIAMOND_AXE, Items.NETHERITE_AXE,
            Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE,
            Items.DIAMOND_HOE, Items.NETHERITE_HOE,
            Items.SHEARS, Items.WOODEN_SWORD, Items.STONE_SWORD,
            Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);
    private static final Set<String> BREAK_HOOK_METHODS = Set.of(
            "onDestroyedByPlayer", "playerDestroy", "playerWillDestroy", "spawnAfterBreak",
            "dropResources", "getDrops", "getDroppedStacks");

    private OreBreakAudit() { }

    public record Result(int tested, List<String> failed) {
        public boolean passed() { return failed.isEmpty(); }
    }

    public record HookScanResult(int registeredBlocks, List<String> hookedBlocks, List<String> scanErrors) {
        public HookScanResult {
            hookedBlocks = List.copyOf(hookedBlocks);
            scanErrors = List.copyOf(scanErrors);
        }
    }

    private record AuditPlayer(ServerPlayer player, Connection connection) { }

    /** Run only in an isolated copy of the target pack; it temporarily places and breaks blocks. */
    public static Result run(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()
                || !level.getEntitiesOfClass(Entity.class, new AABB(pos).inflate(4)).isEmpty()) {
            return new Result(0, List.of("audit position must be empty and clear of entities: " + pos));
        }
        OreCatalog.Completeness completeness = OreCatalog.active().completeness(level);
        if (!completeness.complete()) {
            return new Result(0, List.of("catalog incomplete: " + OreCatalog.describe(completeness)));
        }
        AuditPlayer auditPlayer = createSurvivalPlayer(level);
        ServerPlayer player = auditPlayer.player();
        List<String> failures = new ArrayList<>();
        Set<UUID> existingEntities = level.getEntitiesOfClass(Entity.class, new AABB(pos).inflate(4)).stream()
                .map(Entity::getUUID).collect(java.util.stream.Collectors.toUnmodifiableSet());
        int tested = 0;
        try {
            List<OreCatalog.Entry> entries = OreCatalog.active().entries().values().stream()
                    .sorted(Comparator.comparing(entry -> entry.blockId().toString())).toList();
            for (OreCatalog.Entry entry : entries) {
                BlockState state = OreCatalog.active().blockState(entry);
                if (state.requiresCorrectToolForDrops() != entry.requiresCorrectTool()) {
                    failures.add(entry.blockId() + ": runtime mining requirement differs from catalog metadata");
                    continue;
                }
                if (entry.kind() == OreCatalog.Kind.ENCOUNTER) {
                    Item matchingTool = BuiltInRegistries.ITEM.getOptional(entry.minimumTestedTool())
                            .orElse(Items.AIR);
                    if (matchingTool == Items.AIR) {
                        failures.add(entry.blockId() + ": encounter audit tool is unavailable: "
                                + entry.minimumTestedTool());
                        continue;
                    }
                    for (Item tool : List.of(matchingTool, Items.STICK)) {
                        clearNewEntities(level, pos, existingEntities);
                        level.setBlock(pos, state, 3);
                        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(tool));
                        boolean correctTool = player.hasCorrectToolForDrops(state);
                        if (tool == matchingTool && state.requiresCorrectToolForDrops() && !correctTool) {
                            failures.add(entry.blockId() + ": catalog encounter tool is not harvest-capable: "
                                    + BuiltInRegistries.ITEM.getKey(tool));
                        }
                        if (tool == Items.STICK && state.requiresCorrectToolForDrops() && correctTool) {
                            failures.add(entry.blockId() + ": stick unexpectedly satisfies required encounter tool");
                        }
                        if (!player.gameMode.destroyBlock(pos) || !level.getBlockState(pos).isAir()) {
                            failures.add(entry.blockId() + ": player break did not remove block with "
                                    + BuiltInRegistries.ITEM.getKey(tool));
                            clearNewEntities(level, pos, existingEntities);
                            continue;
                        }
                        String drops = summarizeDrops(level, pos);
                        String spawns = summarizeNewNonItemSpawns(level, pos, existingEntities);
                        String expectedSpawns = List.of(entry.encounterEntity().toString()).toString();
                        LOGGER.info("[publicmines][ore-break-audit] block={} tier={} kind=encounter requires_tool={} "
                                        + "tool={} correct={} drops={} spawns={}",
                                entry.blockId(), entry.tier().id(), state.requiresCorrectToolForDrops(),
                                BuiltInRegistries.ITEM.getKey(tool), correctTool, drops, spawns);
                        if (!spawns.equals(expectedSpawns) || !drops.equals("[]")) {
                            failures.add(entry.blockId() + ": expected only " + entry.encounterEntity()
                                    + " encounter; observed drops=" + drops + ", spawns=" + spawns);
                        }
                        tested++;
                        clearNewEntities(level, pos, existingEntities);
                        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    }
                    continue;
                }
                List<Item> suitableTools = suitableTools(player, state);
                if (state.requiresCorrectToolForDrops() && suitableTools.isEmpty()) {
                    failures.add(entry.blockId() + ": no tested native tool harvests this block");
                    continue;
                }
                Item correctTool = !state.requiresCorrectToolForDrops() || suitableTools.isEmpty()
                        ? Items.AIR : suitableTools.getFirst();
                clearNewEntities(level, pos, existingEntities);
                level.setBlock(pos, state, 3);
                player.setItemInHand(InteractionHand.MAIN_HAND,
                        correctTool == Items.AIR ? ItemStack.EMPTY : new ItemStack(correctTool));
                if (!player.hasCorrectToolForDrops(state)) {
                    failures.add(entry.blockId() + ": selected tool is not suitable: " + correctTool);
                    continue;
                }
                if (!player.gameMode.destroyBlock(pos) || !level.getBlockState(pos).isAir()) {
                    failures.add(entry.blockId() + ": suitable-tool player break did not remove the block");
                    continue;
                }
                String correctDrops = summarizeDrops(level, pos);
                Set<String> correctDropIds = dropIds(level, pos);
                String correctSpawns = summarizeNonItemSpawns(level, pos);
                LOGGER.info("[publicmines][ore-break-audit] block={} tier={} requires_tool={} tool={} correct=true drops={} spawns={}",
                        entry.blockId(), entry.tier().id(), state.requiresCorrectToolForDrops(),
                        BuiltInRegistries.ITEM.getKey(correctTool),
                        correctDrops, correctSpawns);
                if (correctDrops.equals("[]") && correctSpawns.equals("[]")) {
                    failures.add(entry.blockId() + ": suitable-tool break produced no item or entity outcome");
                }
                ResourceLocation testedToolId = BuiltInRegistries.ITEM.getKey(correctTool);
                if (entry.requiresCorrectTool() && !testedToolId.equals(entry.minimumTestedTool())) {
                    failures.add(entry.blockId() + ": minimum tested tool differs from catalog metadata (expected "
                            + entry.minimumTestedTool() + ", got " + testedToolId + ")");
                }
                if (!entry.requiresCorrectTool() && !entry.minimumTestedTool().toString().equals("minecraft:air")) {
                    failures.add(entry.blockId() + ": catalog declares a tool but runtime block does not require one");
                }
                Set<String> expectedDropIds = entry.nativeDropItems().stream()
                        .map(ResourceLocation::toString).collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (correctDropIds.isEmpty() || !expectedDropIds.containsAll(correctDropIds)) {
                    failures.add(entry.blockId() + ": observed native drops disagree with catalog metadata: "
                            + correctDropIds + " not contained in " + expectedDropIds);
                }
                tested++;
                clearNewEntities(level, pos, existingEntities);

                if (state.requiresCorrectToolForDrops()) {
                    level.setBlock(pos, state, 3);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
                    if (player.hasCorrectToolForDrops(state)) {
                        failures.add(entry.blockId() + ": stick unexpectedly satisfies required harvest tool");
                    } else if (!player.gameMode.destroyBlock(pos) || !level.getBlockState(pos).isAir()) {
                        failures.add(entry.blockId() + ": wrong-tool break did not remove the block");
                    } else {
                        String wrongDrops = summarizeDrops(level, pos);
                        String wrongSpawns = summarizeNonItemSpawns(level, pos);
                    LOGGER.info("[publicmines][ore-break-audit] block={} tier={} requires_tool=true tool=minecraft:stick correct=false drops={} spawns={}",
                            entry.blockId(), entry.tier().id(), wrongDrops, wrongSpawns);
                        if (!wrongDrops.equals("[]")) {
                            failures.add(entry.blockId() + ": wrong-tool break yielded item drops " + wrongDrops);
                        }
                        tested++;
                    }
                    clearNewEntities(level, pos, existingEntities);
                }
                level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            }
        } finally {
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            clearNewEntities(level, pos, existingEntities);
            auditPlayer.connection().disconnect(Component.literal("Ore break audit complete"));
        }
        return new Result(tested, List.copyOf(failures));
    }

    /** Scan the complete runtime block registry for class-defined break/drop hooks. */
    public static HookScanResult findCodeDefinedBreakHooks() {
        List<String> hookedBlocks = new ArrayList<>();
        List<String> scanErrors = new ArrayList<>();
        int registeredBlocks = 0;
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            registeredBlocks++;
            List<String> hooks = new ArrayList<>();
            try {
                for (Class<?> type = entry.getValue().getClass(); type != null && type != net.minecraft.world.level.block.Block.class;
                     type = type.getSuperclass()) {
                    if (type.getName().startsWith("net.minecraft.world.level.block.")) continue;
                    for (Method method : type.getDeclaredMethods()) {
                        if (BREAK_HOOK_METHODS.contains(method.getName())) {
                            hooks.add(type.getName() + "#" + method.getName());
                        }
                    }
                }
            } catch (LinkageError error) {
                scanErrors.add(entry.getKey() + ": " + error.getClass().getSimpleName());
            }
            if (!hooks.isEmpty()) {
                hooks.sort(String::compareTo);
                hookedBlocks.add(entry.getKey() + "|" + String.join(";", hooks));
            }
        }
        hookedBlocks.sort(String::compareTo);
        LOGGER.info("[publicmines][ore-hook-audit] registry_blocks={} blocks_with_class_hooks={} scan_errors={}",
                registeredBlocks, hookedBlocks.size(), scanErrors.size());
        scanErrors.forEach(error -> LOGGER.warn("[publicmines][ore-hook-audit] scan_error={}", error));
        for (String block : hookedBlocks) {
            LOGGER.info("[publicmines][ore-hook-audit] {}", block);
        }
        return new HookScanResult(registeredBlocks, hookedBlocks, scanErrors);
    }

    private static AuditPlayer createSurvivalPlayer(ServerLevel level) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "ore-break-audit"), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                cookie.gameProfile(), cookie.clientInformation()) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return false; }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(level.getServer(), connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        return new AuditPlayer(player, connection);
    }

    private static List<Item> suitableTools(Player player, BlockState state) {
        List<Item> suitable = new ArrayList<>();
        for (Item tool : HARVEST_TOOLS) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(tool));
            if (player.hasCorrectToolForDrops(state)) suitable.add(tool);
        }
        return suitable;
    }

    private static String summarizeDrops(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3)).stream()
                .map(item -> item.getItem().getCount() + "x"
                        + BuiltInRegistries.ITEM.getKey(item.getItem().getItem()))
                .sorted().toList().toString();
    }

    private static Set<String> dropIds(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3)).stream()
                .map(item -> BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String summarizeNonItemSpawns(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(Entity.class, new AABB(pos).inflate(4)).stream()
                .filter(entity -> !(entity instanceof Player) && !(entity instanceof ItemEntity))
                .map(entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
                .sorted().toList().toString();
    }

    private static String summarizeNewNonItemSpawns(ServerLevel level, BlockPos pos, Set<UUID> existingEntities) {
        return level.getEntitiesOfClass(Entity.class, new AABB(pos).inflate(4)).stream()
                .filter(entity -> !(entity instanceof Player) && !(entity instanceof ItemEntity)
                        && !existingEntities.contains(entity.getUUID()))
                .map(entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
                .sorted().toList().toString();
    }

    private static void clearNewEntities(ServerLevel level, BlockPos pos, Set<UUID> existingEntities) {
        level.getEntitiesOfClass(Entity.class, new AABB(pos).inflate(4)).stream()
                .filter(entity -> !(entity instanceof Player) && !existingEntities.contains(entity.getUUID()))
                .forEach(Entity::discard);
    }
}
