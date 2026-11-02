package com.rusticcraft.publicmines.run;

import com.mojang.logging.LogUtils;
import com.mojang.authlib.GameProfile;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import com.rusticcraft.publicmines.encounter.MineEncounterManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** Server integration checks for M4a mine-entity ownership and safe-zone exclusion. */
@GameTestHolder("publicmines")
@PrefixGameTestTemplate(false)
public final class MineEncounterGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TEMPORARY_WORLD_NAME = "publicmines-m4a-hook-callback-audit";

    private MineEncounterGameTests() { }

    @GameTest(template = "empty", batch = "m4a_encounters", timeoutTicks = 1200)
    public static void mineEntitiesAreTrackedAndSafeZoneHostilesAreRejected(GameTestHelper helper) {
        ServerLevel testLevel = helper.getLevel();
        MineManager manager = MineManager.get(testLevel.getServer());
        ServerLevel mine = manager.mineLevel();
        if (mine == null) {
            LOGGER.warn("[publicmines] M4a entity tracking GameTest skipped: public_mine_v2 is not loaded.");
            helper.succeed();
            return;
        }
        if (!TEMPORARY_WORLD_NAME.equals(testLevel.getServer().getWorldData().getLevelName())) {
            LOGGER.warn("[publicmines] M4a entity tracking GameTest skipped outside disposable world '{}'.",
                    TEMPORARY_WORLD_NAME);
            helper.succeed();
            return;
        }

        MineRunData data = manager.data();
        var original = data.snapshot(testLevel.registryAccess());
        int radius = PublicMineConfig.mineRegionRadiusChunks();
        OwnedVolume owned = new OwnedVolume("m4a_gametest_region", MineManager.MINE_DIMENSION,
                new BlockPos(-radius * 16, mine.getMinBuildHeight(), -radius * 16),
                new BlockPos(radius * 16 - 1, mine.getMaxBuildHeight() - 1, radius * 16 - 1));
        BlockPos entityPos = new BlockPos(128, 90, 128);
        BlockPos safeCenter = entityPos.north(24);
        OwnedVolume safeVolume = new OwnedVolume("m4a_gametest_safe", MineManager.MINE_DIMENSION,
                safeCenter.offset(-3, -2, -3), safeCenter.offset(3, 2, 3));
        ResourceLocation safeId = ResourceLocation.fromNamespaceAndPath("publicmines",
                "m4a_gametest_" + UUID.randomUUID().toString().replace("-", ""));
        Zombie tracked = null;
        Zombie natural = null;
        try {
            data.beginNewRun("m4a-gametest-" + UUID.randomUUID(), 1, 0x4d3441L,
                    LocalDate.now(PublicMineConfig.resetZoneId()).toString());
            data.setBounds(-radius, -radius, radius - 1, radius - 1);
            data.addOwnedVolume(owned);
            data.markStatus(RunStatus.READY);
            helper.assertTrue(data.registerSafeZone(safeId, safeVolume),
                    "could not register the temporary safe zone");
            mine.getChunkAt(entityPos);

            tracked = EntityType.ZOMBIE.create(mine);
            helper.assertTrue(tracked != null, "could not create zombie fixture");
            if (tracked != null) {
                tracked.getPersistentData().putString("publicminesSourceId", "encounter:test:node");
                tracked.getPersistentData().putString("publicminesRegion", "upper");
                tracked.moveTo(entityPos.getX() + 0.5, entityPos.getY(), entityPos.getZ() + 0.5,
                        0.0F, 0.0F);
                UUID id = tracked.getUUID();
                helper.assertTrue(mine.addFreshEntity(tracked), "mine hostile did not join the level");
                helper.assertTrue(data.isTrackedMineEntity(id),
                        "mine hostile did not receive persistent run-origin tracking");
                var origin = data.mineEntities().stream().filter(candidate -> candidate.uuid().equals(id))
                        .findFirst().orElse(null);
                helper.assertTrue(origin != null && origin.entityType().equals(ResourceLocation.parse("minecraft:zombie"))
                                && origin.sourceId().equals("encounter:test:node"),
                        "tracked encounter origin did not retain the entity type and source");

                tracked.moveTo(entityPos.getX() + 10.5, entityPos.getY(), entityPos.getZ() + 0.5,
                        0.0F, 0.0F);
                CompoundTag savedMob = new CompoundTag();
                helper.assertTrue(tracked.save(savedMob), "tracked mob could not be serialized");
                tracked.discard();
                var loaded = EntityType.loadEntityRecursive(savedMob, mine, entity -> entity);
                helper.assertTrue(loaded instanceof Zombie,
                        "tracked mob did not reload from its serialized entity data");
                if (loaded instanceof Zombie reloaded) {
                    tracked = reloaded;
                    helper.assertTrue(mine.addFreshEntity(reloaded),
                            "tracked mob did not rejoin after its movement and serialization");
                    helper.assertTrue(data.isTrackedMineEntity(id),
                            "reloaded mob lost its run-origin record");
                    helper.assertTrue(data.mineEntity(id).pos().equals(entityPos),
                            "reloaded mob overwrote its original spawn position with its moved position");
                }

                MineEncounterManager.cleanupRun(mine, data, data.runId());
                helper.assertTrue(mine.getEntity(id) == null,
                        "run cleanup did not discard its tracked hostile");
                helper.assertFalse(data.isTrackedMineEntity(id),
                        "run cleanup retained the hostile origin record");
            }

            natural = EntityType.ZOMBIE.create(mine);
            helper.assertTrue(natural != null, "could not create ordinary hostile fixture");
            if (natural != null) {
                BlockPos naturalPos = entityPos.east(24);
                natural.moveTo(naturalPos.getX() + 0.5, naturalPos.getY(), naturalPos.getZ() + 0.5,
                        0.0F, 0.0F);
                UUID naturalId = natural.getUUID();
                helper.assertTrue(mine.addFreshEntity(natural), "ordinary mine hostile did not join the level");
                helper.assertFalse(data.isTrackedMineEntity(naturalId),
                        "ordinary natural hostile consumed the bounded encounter registry");
                helper.assertFalse(natural.isPersistenceRequired(),
                        "ordinary natural hostile was incorrectly pinned against vanilla despawning");
            }

            Zombie safeHostile = EntityType.ZOMBIE.create(mine);
            helper.assertTrue(safeHostile != null, "could not create safe-zone zombie fixture");
            if (safeHostile != null) {
                safeHostile.moveTo(safeCenter.getX() + 0.5, safeCenter.getY(), safeCenter.getZ() + 0.5,
                        0.0F, 0.0F);
                EntityJoinLevelEvent join = new EntityJoinLevelEvent(safeHostile, mine);
                NeoForge.EVENT_BUS.post(join);
                helper.assertTrue(join.isCanceled(), "hostile entity was allowed to join inside a safe zone");
                safeHostile.discard();
            }

            helper.assertTrue(data.claimEncounterSite("node:test-site"),
                    "new encounter site could not be claimed");
            helper.assertFalse(data.claimEncounterSite("node:test-site"),
                    "a previously claimed encounter site was claimed twice");
        } finally {
            if (tracked != null) tracked.discard();
            if (natural != null) natural.discard();
            data.restoreSnapshot(original, testLevel.registryAccess());
        }
        helper.assertTrue(data.snapshot(testLevel.registryAccess()).equals(original),
                "encounter event test failed to restore the original shared run state");
        LOGGER.info("[publicmines] M4a entity tracking/safe-zone GameTest passed.");
        helper.succeed();
    }

    /** Exact-pack GameTest: infected ores run their native player-break hook inside the mine-owned area. */
    @GameTest(template = "empty", batch = "m4a_native_bic", timeoutTicks = 1200)
    public static void infectedOreTermitesAreTrackedAndResetCleaned(GameTestHelper helper) {
        ServerLevel testLevel = helper.getLevel();
        MineManager manager = MineManager.get(testLevel.getServer());
        ServerLevel mine = manager.mineLevel();
        if (mine == null || BuiltInRegistries.BLOCK.getOptional(
                ResourceLocation.parse("born_in_chaos_v1:infected_diamond_ore")).isEmpty()) {
            LOGGER.warn("[publicmines] M4a native BiC GameTest skipped: Born in Chaos is not installed.");
            helper.succeed();
            return;
        }
        if (!TEMPORARY_WORLD_NAME.equals(testLevel.getServer().getWorldData().getLevelName())) {
            LOGGER.warn("[publicmines] M4a native BiC GameTest skipped outside disposable world '{}'.",
                    TEMPORARY_WORLD_NAME);
            helper.succeed();
            return;
        }

        MineRunData data = manager.data();
        var original = data.snapshot(testLevel.registryAccess());
        Map<BlockPos, BlockState> originalBlocks = new LinkedHashMap<>();
        Set<UUID> spawnedTermiteIds = new LinkedHashSet<>();
        int radius = PublicMineConfig.mineRegionRadiusChunks();
        OwnedVolume owned = new OwnedVolume("m4a_bic_gametest_region", MineManager.MINE_DIMENSION,
                new BlockPos(-radius * 16, mine.getMinBuildHeight(), -radius * 16),
                new BlockPos(radius * 16 - 1, mine.getMaxBuildHeight() - 1, radius * 16 - 1));
        BlockPos base = new BlockPos(320, 90, 320);
        mine.getChunkAt(base);
        data.beginNewRun("m4a-bic-gametest-" + UUID.randomUUID(), 1, 0x4d3441L,
                LocalDate.now(PublicMineConfig.resetZoneId()).toString());
        data.setBounds(-radius, -radius, radius - 1, radius - 1);
        data.addOwnedVolume(owned);
        data.markStatus(RunStatus.READY);
        var audit = createAuditPlayer(mine);
        ServerPlayer player = audit.player();
        try {
            String[] ores = {"born_in_chaos_v1:infected_diamond_ore",
                    "born_in_chaos_v1:infected_deepslate_diamond_ore"};
            for (int index = 0; index < ores.length; index++) {
                ResourceLocation oreId = ResourceLocation.parse(ores[index]);
                BlockState ore = BuiltInRegistries.BLOCK.getOptional(oreId)
                        .orElseThrow(() -> new AssertionError("missing runtime infected ore " + oreId))
                        .defaultBlockState();
                BlockPos pos = base.offset(index * 8, 0, 0);
                AABB nearby = new AABB(pos).inflate(5);
                rememberBlock(mine, originalBlocks, pos.below());
                rememberBlock(mine, originalBlocks, pos);
                mine.setBlock(pos.below(), index == 0
                        ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()
                        : net.minecraft.world.level.block.Blocks.DEEPSLATE.defaultBlockState(), 3);
                mine.setBlock(pos, ore, 3);
                player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                Set<UUID> entitiesBeforeBreak = mine.getEntitiesOfClass(Entity.class, nearby).stream()
                        .map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
                // Exercise BiC's registered player-break callback; ServerLevel.destroyBlock
                // removes the block directly and skips that mod callback.
                helper.assertTrue(ore.onDestroyedByPlayer(mine, pos, player, true, ore.getFluidState()),
                        "fake survival player could not break native infected ore " + oreId);
                var termites = mine.getEntitiesOfClass(Mob.class, nearby).stream()
                        .filter(mob -> !entitiesBeforeBreak.contains(mob.getUUID()))
                        .filter(mob -> BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).equals(
                                ResourceLocation.parse("born_in_chaos_v1:diamond_termite"))).toList();
                helper.assertTrue(!termites.isEmpty(), "infected ore did not summon Diamond Termites: " + oreId);
                termites.stream().map(Entity::getUUID).forEach(spawnedTermiteIds::add);
                for (Mob termite : termites) {
                    helper.assertTrue(data.isTrackedMineEntity(termite.getUUID()),
                            "native Diamond Termite was not registered to the active mine run");
                    var origin = data.mineEntity(termite.getUUID());
                    helper.assertTrue(origin != null && origin.sourceId().equals("native_mine_spawn"),
                            "native Diamond Termite lost its mine-origin source record");
                }
                var spawnedIds = termites.stream().map(Entity::getUUID).toList();
                MineEncounterManager.cleanupRun(mine, data, data.runId());
                helper.assertTrue(spawnedIds.stream().allMatch(id -> mine.getEntity(id) == null
                                && !data.isTrackedMineEntity(id)),
                        "reset did not remove every native infected-ore termite");
                if (index + 1 < ores.length) {
                    data.beginNewRun("m4a-bic-gametest-next-" + UUID.randomUUID(), 1, 0x4d3442L,
                            LocalDate.now(PublicMineConfig.resetZoneId()).toString());
                    data.setBounds(-radius, -radius, radius - 1, radius - 1);
                    data.addOwnedVolume(owned);
                    data.markStatus(RunStatus.READY);
                }
            }
            var termiteType = BuiltInRegistries.ENTITY_TYPE.getOptional(
                    ResourceLocation.parse("born_in_chaos_v1:diamond_termite")).orElseThrow();
            Entity cleanupProbe = termiteType.create(mine);
            helper.assertTrue(cleanupProbe instanceof Mob && cleanupProbe instanceof Enemy,
                    "runtime Diamond Termite registry entry did not create a hostile Mob");
            if (cleanupProbe instanceof Mob mob && cleanupProbe instanceof Enemy) {
                mob.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0.0F, 0.0F);
                UUID probeId = mob.getUUID();
                helper.assertTrue(mine.addFreshEntity(mob), "native termite cleanup fixture did not join the mine");
                spawnedTermiteIds.add(probeId);
                helper.assertTrue(data.isTrackedMineEntity(probeId),
                        "native termite cleanup fixture did not receive run tracking");
                MineEncounterManager.cleanupRun(mine, data, data.runId());
                helper.assertTrue(mine.getEntity(probeId) == null && !data.isTrackedMineEntity(probeId),
                        "run cleanup did not remove a live native Diamond Termite");
            }
            helper.assertTrue(data.mineEntities().isEmpty(), "run cleanup retained native termite origin records");
            helper.assertTrue(mine.getEntitiesOfClass(Mob.class, new AABB(base).inflate(16)).stream()
                            .noneMatch(mob -> BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).equals(
                                    ResourceLocation.parse("born_in_chaos_v1:diamond_termite"))),
                    "run reset left a tracked native Diamond Termite alive");
        } finally {
            MineEncounterManager.cleanupRun(mine, data, data.runId());
            for (UUID id : spawnedTermiteIds) {
                Entity entity = mine.getEntity(id);
                if (entity instanceof Mob && BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).equals(
                        ResourceLocation.parse("born_in_chaos_v1:diamond_termite"))) {
                    entity.discard();
                }
                data.unregisterMineEntity(id);
            }
            restoreBlocks(mine, originalBlocks);
            audit.connection().disconnect(Component.literal("M4a native BiC GameTest complete"));
            data.restoreSnapshot(original, testLevel.registryAccess());
        }
        helper.assertTrue(data.snapshot(testLevel.registryAccess()).equals(original),
                "native BiC GameTest failed to restore shared run state");
        LOGGER.info("[publicmines] M4a native infected-ore tracking/reset GameTest passed.");
        helper.succeed();
    }

    private static AuditPlayer createAuditPlayer(ServerLevel level) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), "mine-encounter-audit"), false);
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

    private static void rememberBlock(ServerLevel level, Map<BlockPos, BlockState> original, BlockPos pos) {
        original.computeIfAbsent(pos.immutable(), level::getBlockState);
    }

    private static void restoreBlocks(ServerLevel level, Map<BlockPos, BlockState> original) {
        original.forEach((pos, state) -> level.setBlock(pos, state, 3));
    }

    private record AuditPlayer(ServerPlayer player, Connection connection) { }
}
