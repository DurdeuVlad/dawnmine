package com.rusticcraft.publicmines.run;

import com.mojang.authlib.GameProfile;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import com.rusticcraft.publicmines.encounter.MineEncounterCatalog;
import com.rusticcraft.publicmines.encounter.MineEncounterManager;
import com.rusticcraft.publicmines.topology.NodeKind;
import com.rusticcraft.publicmines.topology.TopoNode;
import com.rusticcraft.publicmines.topology.TopoPos;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Isolated, opt-in exact-pack audit for native infected-ore encounters inside the mine. */
public final class MineEncounterRuntimeAudit {
    private static final String TEMPORARY_WORLD_NAME = "publicmines-m4a-hook-callback-audit";
    private static final ResourceLocation DIAMOND_TERMITE = ResourceLocation.parse(
            "born_in_chaos_v1:diamond_termite");
    private static final ResourceLocation TERMITE_SHARD = ResourceLocation.parse(
            "born_in_chaos_v1:diamond_termite_shard");
    private static final List<ResourceLocation> INFECTED_ORES = List.of(
            ResourceLocation.parse("born_in_chaos_v1:infected_diamond_ore"),
            ResourceLocation.parse("born_in_chaos_v1:infected_deepslate_diamond_ore"));

    private MineEncounterRuntimeAudit() { }

    public record Result(int cases, List<String> evidence, List<String> failures) {
        public Result {
            evidence = List.copyOf(evidence);
            failures = List.copyOf(failures);
        }

        public boolean passed() { return cases == INFECTED_ORES.size() + 6 && failures.isEmpty(); }
    }

    public static Result run(ServerLevel mine) {
        List<String> evidence = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        if (mine == null || !mine.dimension().equals(MineManager.MINE_DIMENSION)) {
            return new Result(0, evidence, List.of("public mine dimension is unavailable"));
        }
        if (!TEMPORARY_WORLD_NAME.equals(mine.getServer().getWorldData().getLevelName())) {
            return new Result(0, evidence, List.of("audit is restricted to disposable world '"
                    + TEMPORARY_WORLD_NAME + "'; current world is '"
                    + mine.getServer().getWorldData().getLevelName() + "'"));
        }
        for (ResourceLocation id : INFECTED_ORES) {
            if (BuiltInRegistries.BLOCK.getOptional(id).isEmpty()) {
                failures.add("required infected ore is unavailable: " + id);
            }
        }
        if (BuiltInRegistries.ENTITY_TYPE.getOptional(DIAMOND_TERMITE).isEmpty()
                || BuiltInRegistries.ITEM.getOptional(TERMITE_SHARD).isEmpty()) {
            failures.add("Diamond Termite or shard registry entry is unavailable");
        }
        if (!failures.isEmpty()) return new Result(0, evidence, failures);

        var missingEntityComposition = new MineEncounterCatalog.Composition("m4a_missing_entity_audit", 1,
                "unknown entity fixture", "skip the composition", List.of(
                        new MineEncounterCatalog.EntityCount(ResourceLocation.parse(
                                "publicmines:m4a_audit_entity_that_does_not_exist"), 1)));
        if (!MineEncounterManager.canResolveRequests(mine, missingEntityComposition)) {
            evidence.add("missing runtime entity: composition rejected without substitute");
        } else {
            failures.add("missing runtime entity was not rejected fail-closed");
        }

        MineManager manager = MineManager.get(mine.getServer());
        MineRunData data = manager.data();
        var original = data.snapshot(mine.registryAccess());
        int radius = PublicMineConfig.mineRegionRadiusChunks();
        BlockPos base = new BlockPos(320, 90, 320);
        OwnedVolume owned = new OwnedVolume("m4a_runtime_audit", MineManager.MINE_DIMENSION,
                new BlockPos(-radius * 16, mine.getMinBuildHeight(), -radius * 16),
                new BlockPos(radius * 16 - 1, mine.getMaxBuildHeight() - 1, radius * 16 - 1));
        ServerPlayer player = null;
        Connection connection = null;
        int completed = 0;
        completed += failures.isEmpty() && evidence.stream()
                .anyMatch(line -> line.startsWith("missing runtime entity:")) ? 1 : 0;
        Map<BlockPos, BlockState> originalBlocks = new LinkedHashMap<>();
        try {
            var cookie = CommonListenerCookie.createInitial(
                    new GameProfile(UUID.randomUUID(), "mine-encounter-audit"), false);
            player = new ServerPlayer(mine.getServer(), mine, cookie.gameProfile(), cookie.clientInformation()) {
                @Override public boolean isSpectator() { return false; }
                @Override public boolean isCreative() { return false; }
            };
            connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            new ServerGamePacketListenerImpl(mine.getServer(), connection, player, cookie);
            player.setGameMode(GameType.SURVIVAL);
            mine.getChunkAt(base);
            data.beginNewRun("m4a-runtime-audit-" + UUID.randomUUID(), 1, 0x4d3441L,
                    LocalDate.now(PublicMineConfig.resetZoneId()).toString());
            data.setBounds(-radius, -radius, radius - 1, radius - 1);
            data.addOwnedVolume(owned);
            data.markStatus(RunStatus.READY);

            for (int index = 0; index < INFECTED_ORES.size(); index++) {
                BlockPos pos = base.offset(index * 12, 0, 0);
                AABB nearby = new AABB(pos).inflate(9);
                rememberBlock(mine, originalBlocks, pos.below());
                rememberBlock(mine, originalBlocks, pos);
                mine.setBlock(pos.below(), index == 0
                        ? Blocks.STONE.defaultBlockState() : Blocks.DEEPSLATE.defaultBlockState(), 3);
                BlockState ore = BuiltInRegistries.BLOCK.get(INFECTED_ORES.get(index)).defaultBlockState();
                boolean placed = mine.setBlock(pos, ore, 3);
                player.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                Set<UUID> entitiesBeforeBreak = mine.getEntitiesOfClass(Entity.class, nearby).stream()
                        .map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
                boolean correctTool = player.hasCorrectToolForDrops(ore);
                // Call the registered BlockState player-destruction callback. The
                // world-level destroyBlock helper bypasses mod block callbacks,
                // which would make Born in Chaos's native termite procedure invisible.
                boolean breakResult = ore.onDestroyedByPlayer(mine, pos, player, true, ore.getFluidState());
                boolean removed = mine.getBlockState(pos).isAir();
                if (!breakResult || !removed) {
                    failures.add(INFECTED_ORES.get(index) + " could not be player-broken"
                            + " (placed=" + placed + ", correctTool=" + correctTool
                            + ", breakResult=" + breakResult + ", removed=" + removed + ")");
                    mine.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    continue;
                }
                var termites = termitesNear(mine, nearby).stream()
                        .filter(termite -> !entitiesBeforeBreak.contains(termite.getUUID())).toList();
                if (termites.isEmpty()) {
                    failures.add(INFECTED_ORES.get(index) + " did not summon a Diamond Termite");
                    continue;
                }
                boolean tracked = termites.stream().allMatch(termite -> {
                    MineRunData.MineEntityOrigin origin = data.mineEntity(termite.getUUID());
                    return origin != null && origin.runId().equals(data.runId())
                            && origin.entityType().equals(DIAMOND_TERMITE)
                            && origin.sourceId().equals("native_mine_spawn")
                            && origin.pos().equals(termite.blockPosition());
                });
                if (!tracked) {
                    failures.add(INFECTED_ORES.get(index) + " produced a termite without exact mine-origin tracking");
                }
                List<UUID> spawnedIds = termites.stream().map(Entity::getUUID).toList();
                MineEncounterManager.cleanupRun(mine, data, data.runId());
                boolean cleaned = spawnedIds.stream().allMatch(id -> mine.getEntity(id) == null
                        && !data.isTrackedMineEntity(id));
                if (!cleaned) failures.add(INFECTED_ORES.get(index)
                        + " reset cleanup left a termite or origin record");
                evidence.add(INFECTED_ORES.get(index) + ": tracked=" + tracked + ", resetCleaned=" + cleaned);
                completed++;

                if (index + 1 < INFECTED_ORES.size()) {
                    data.beginNewRun("m4a-runtime-audit-next-" + UUID.randomUUID(), 1, 0x4d3442L,
                            LocalDate.now(PublicMineConfig.resetZoneId()).toString());
                    data.setBounds(-radius, -radius, radius - 1, radius - 1);
                    data.addOwnedVolume(owned);
                    data.markStatus(RunStatus.READY);
                }
            }
            completed += auditScheduledEncounters(mine, manager, data, originalBlocks, evidence, failures);
        } catch (RuntimeException exception) {
            failures.add(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } finally {
            MineEncounterManager.cleanupRun(mine, data, data.runId());
            restoreBlocks(mine, originalBlocks);
            if (connection != null) connection.disconnect(Component.literal("M4a exact-pack audit complete"));
            data.restoreSnapshot(original, mine.registryAccess());
        }
        return new Result(completed, evidence, failures);
    }

    private static List<Mob> termitesNear(ServerLevel mine, AABB bounds) {
        return mine.getEntitiesOfClass(Mob.class, bounds).stream()
                .filter(mob -> BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).equals(DIAMOND_TERMITE))
                .toList();
    }

    private static int auditScheduledEncounters(ServerLevel mine, MineManager manager, MineRunData data,
            Map<BlockPos, BlockState> originalBlocks, List<String> evidence, List<String> failures) {
        int cases = 0;
        BlockPos spawnCenter = new BlockPos(400, 90, 400);
        preparePlatform(mine, originalBlocks, spawnCenter, true);
        TopoNode spawnNode = auditNode("m4a_audit_spawn", "upper", spawnCenter);
        MineEncounterManager.onNodeGenerated(mine.getServer(), spawnNode);
        List<MineRunData.MineEntityOrigin> spawned = encounterOrigins(data, spawnNode.id());
        if (spawned.isEmpty() || spawned.stream().anyMatch(origin -> !origin.region().equals("upper"))) {
            failures.add("valid generated upper-room site did not spawn tracked scheduled encounters");
        } else {
            cases++;
            int initialCount = spawned.size();
            MineEncounterManager.onNodeGenerated(mine.getServer(), spawnNode);
            int duplicateCount = encounterOrigins(data, spawnNode.id()).size();
            if (duplicateCount != initialCount) {
                failures.add("replayed generated room duplicated its scheduled encounter");
            } else {
                cases++;
            }
            evidence.add("scheduled room: spawned=" + initialCount + ", duplicateReplay="
                    + (duplicateCount == initialCount));
        }
        MineEncounterManager.cleanupRun(mine, data, data.runId());

        BlockPos unsupportedCenter = new BlockPos(600, 90, 600);
        preparePlatform(mine, originalBlocks, unsupportedCenter, false);
        TopoNode unsupportedNode = auditNode("m4a_audit_unsupported", "middle", unsupportedCenter);
        MineEncounterManager.onNodeGenerated(mine.getServer(), unsupportedNode);
        if (encounterOrigins(data, unsupportedNode.id()).isEmpty()
                && !data.hasClaimedEncounterSite("node:" + unsupportedNode.id())) {
            cases++;
            evidence.add("unsupported spawn surfaces: skipped without claiming the site");
        } else {
            failures.add("unsupported spawn surface was claimed or produced scheduled encounters");
        }

        BlockPos capCenter = new BlockPos(800, 90, 800);
        preparePlatform(mine, originalBlocks, capCenter, true);
        ResourceLocation fixtureId = ResourceLocation.parse("born_in_chaos_v1:decrepit_skeleton");
        var fixtureType = BuiltInRegistries.ENTITY_TYPE.getOptional(fixtureId).orElse(null);
        if (fixtureType == null) {
            failures.add("could not create runtime entity fixture for upper-region population cap test");
            return cases;
        }
        for (int i = 0; i < 8; i++) {
            BlockPos pos = capCenter.offset(i * 2, 0, 0);
            Entity entity = fixtureType.create(mine);
            if (!(entity instanceof Mob mob) || !(entity instanceof Enemy)) {
                if (entity != null) entity.discard();
                failures.add("verified upper encounter fixture did not create a hostile Mob");
                break;
            }
            mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, i * 20.0F, 0.0F);
            mob.getPersistentData().putString("publicminesRunId", data.runId());
            mob.getPersistentData().putString("publicminesSourceId", "encounter:audit-cap-fixture");
            mob.getPersistentData().putString("publicminesRegion", "upper");
            if (!mine.addFreshEntity(mob) || !data.isTrackedMineEntity(mob.getUUID())) {
                failures.add("upper-region population fixture was not tracked");
                break;
            }
        }
        long cappedPopulation = data.mineEntities().stream()
                .filter(origin -> origin.runId().equals(data.runId()) && origin.region().equals("upper"))
                .count();
        TopoNode capNode = auditNode("m4a_audit_cap", "upper", capCenter.offset(0, 0, 16));
        preparePlatform(mine, originalBlocks, new BlockPos(capNode.pos().x(), capNode.pos().y(), capNode.pos().z()), true);
        MineEncounterManager.onNodeGenerated(mine.getServer(), capNode);
        long afterCappedAttempt = data.mineEntities().stream()
                .filter(origin -> origin.runId().equals(data.runId()) && origin.region().equals("upper"))
                .count();
        if (cappedPopulation == 8 && afterCappedAttempt == cappedPopulation
                && encounterOrigins(data, capNode.id()).isEmpty()) {
            cases++;
            evidence.add("upper-region cap: population=" + cappedPopulation + ", additionalSpawn=0");
        } else {
            failures.add("upper-region cap did not prevent an encounter above the configured limit");
        }
        MineEncounterManager.cleanupRun(mine, data, data.runId());

        boolean totalFixtures = addEncounterFixtures(mine, data, fixtureType, "upper", 8,
                        new BlockPos(800, 90, 800))
                && addEncounterFixtures(mine, data, BuiltInRegistries.ENTITY_TYPE.getOptional(
                        ResourceLocation.parse("born_in_chaos_v1:bone_imp")).orElse(null), "middle", 12,
                        new BlockPos(830, 90, 800))
                && addEncounterFixtures(mine, data, BuiltInRegistries.ENTITY_TYPE.getOptional(
                        ResourceLocation.parse("born_in_chaos_v1:bone_imp")).orElse(null), "chaos", 8,
                        new BlockPos(870, 90, 800));
        long fullPopulation = data.mineEntities().stream()
                .filter(origin -> origin.runId().equals(data.runId())).count();
        BlockPos globalCapCenter = new BlockPos(1000, 90, 800);
        preparePlatform(mine, originalBlocks, globalCapCenter, true);
        TopoNode globalCapNode = auditNode("m4a_audit_global_cap", "chaos", globalCapCenter);
        MineEncounterManager.onNodeGenerated(mine.getServer(), globalCapNode);
        long afterGlobalCapAttempt = data.mineEntities().stream()
                .filter(origin -> origin.runId().equals(data.runId())).count();
        if (totalFixtures && fullPopulation == 28 && afterGlobalCapAttempt == fullPopulation
                && encounterOrigins(data, globalCapNode.id()).isEmpty()) {
            cases++;
            evidence.add("global cap: population=" + fullPopulation + ", additionalSpawn=0");
        } else {
            failures.add("global encounter population cap did not prevent an encounter above the configured limit");
        }
        MineEncounterManager.cleanupRun(mine, data, data.runId());
        return cases;
    }

    private static boolean addEncounterFixtures(ServerLevel mine, MineRunData data,
            net.minecraft.world.entity.EntityType<?> type, String region, int count, BlockPos origin) {
        if (type == null) return false;
        for (int i = 0; i < count; i++) {
            BlockPos pos = origin.offset(i * 2, 0, 0);
            mine.getChunkAt(pos);
            Entity entity = type.create(mine);
            if (!(entity instanceof Mob mob) || !(entity instanceof Enemy)) {
                if (entity != null) entity.discard();
                return false;
            }
            mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, i * 20.0F, 0.0F);
            mob.getPersistentData().putString("publicminesRunId", data.runId());
            mob.getPersistentData().putString("publicminesSourceId", "encounter:audit-cap-fixture");
            mob.getPersistentData().putString("publicminesRegion", region);
            if (!mine.addFreshEntity(mob) || !data.isTrackedMineEntity(mob.getUUID())) return false;
        }
        return true;
    }

    private static TopoNode auditNode(String id, String region, BlockPos center) {
        return new TopoNode(id, com.rusticcraft.publicmines.topology.NodeKind.CAVE, region,
                new TopoPos(center.getX(), center.getY(), center.getZ()), Set.of(), "M4a audit room");
    }

    private static List<MineRunData.MineEntityOrigin> encounterOrigins(MineRunData data, String nodeId) {
        String sourceId = "encounter:";
        return data.mineEntities().stream()
                .filter(origin -> origin.sourceId().startsWith(sourceId)
                        && origin.sourceId().endsWith(":" + nodeId))
                .toList();
    }

    private static void preparePlatform(ServerLevel mine, Map<BlockPos, BlockState> original,
            BlockPos center, boolean supported) {
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                BlockPos floor = center.offset(dx, -1, dz);
                BlockPos feet = center.offset(dx, 0, dz);
                BlockPos head = center.offset(dx, 1, dz);
                rememberBlock(mine, original, floor);
                rememberBlock(mine, original, feet);
                rememberBlock(mine, original, head);
                mine.setBlock(floor, supported ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                mine.setBlock(feet, Blocks.AIR.defaultBlockState(), 3);
                mine.setBlock(head, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static void rememberBlock(ServerLevel mine, Map<BlockPos, BlockState> original, BlockPos pos) {
        original.computeIfAbsent(pos.immutable(), mine::getBlockState);
    }

    private static void restoreBlocks(ServerLevel mine, Map<BlockPos, BlockState> original) {
        original.forEach((pos, state) -> mine.setBlock(pos, state, 3));
    }
}
