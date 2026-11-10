package com.dwurdy.dawnmine.encounter;

import com.mojang.logging.LogUtils;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.MineRunData;
import com.dwurdy.dawnmine.run.MineRunData.MineEntityOrigin;
import com.dwurdy.dawnmine.topology.NodeFeature;
import com.dwurdy.dawnmine.topology.NodeKind;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopologyProfiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;

/** Run-owned encounter spawning, entity provenance, population caps, and cleanup. */
public final class MineEncounterManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String RUN_TAG = "dawnmineRunId";
    private static final String SOURCE_TAG = "dawnmineSourceId";
    private static final String REGION_TAG = "dawnmineRegion";
    private static final String ORIGIN_X_TAG = "dawnmineOriginX";
    private static final String ORIGIN_Y_TAG = "dawnmineOriginY";
    private static final String ORIGIN_Z_TAG = "dawnmineOriginZ";
    private static final ResourceLocation NATIVE_TERMITE_ID = ResourceLocation.parse(
            "born_in_chaos_v1:diamond_termite");
    private static final int MAX_SPAWN_DISTANCE_FROM_PLAYER_SQUARED = 14 * 14;
    private static boolean registered;

    private MineEncounterManager() { }

    public static synchronized void register() {
        if (!registered) {
            NeoForge.EVENT_BUS.register(new MineEncounterManager());
            registered = true;
        }
    }

    /** Called exactly after a topology room is completely generated. */
    public static void onNodeGenerated(MinecraftServer server, TopoNode node) {
        if (node == null || node.has(NodeFeature.CHECKPOINT)
                || !(node.kind() == NodeKind.CAVERN || node.kind() == NodeKind.CAVE
                || node.has(NodeFeature.DEAD_END))) return;
        ServerLevel mine = server.getLevel(MineManager.MINE_DIMENSION);
        if (mine == null) return;
        MineManager manager = MineManager.get(server);
        MineRunData data = manager.data();
        MineEncounterCatalog.Profile profile = MineEncounterCatalog.forProfile(
                TopologyProfiles.activeProfileId());
        if (profile == null) {
            LOGGER.error("[dawnmine] No encounter profile matches topology profile '{}'; skipping node {}.",
                    TopologyProfiles.activeProfileId(), node.id());
            return;
        }
        MineEncounterCatalog.Region region = profile.region(node.region());
        if (region == null) {
            LOGGER.error("[dawnmine] Encounter profile '{}' has no region '{}'; skipping node {}.",
                    profile.id(), node.region(), node.id());
            return;
        }
        if (!manager.isOwned(mine, new BlockPos(node.pos().x(), node.pos().y(), node.pos().z()))) {
            return;
        }
        String siteId = "node:" + node.id();
        MineEncounterCatalog.Composition composition = selectComposition(
                data.seed(), siteId, region.compositions());
        if (composition == null) return;
        List<SpawnRequest> requests = resolveRequests(mine, composition);
        if (requests == null) return;
        int currentRegionPopulation = (int) data.mineEntities().stream()
                .filter(origin -> origin.runId().equals(data.runId()) && origin.region().equals(node.region()))
                .count();
        int currentPopulation = (int) data.mineEntities().stream()
                .filter(origin -> origin.runId().equals(data.runId())).count();
        // Budget by what this server can actually spawn (base + whatever bonus
        // entities resolve), not the composition's worst-case maxPopulation -
        // otherwise a server without the bonus pack reserves headroom for mobs
        // that will never spawn and admits fewer encounters than it could.
        if (currentRegionPopulation + requests.size() > region.populationCap()
                || currentPopulation + requests.size() > profile.totalPopulationCap()) {
            return;
        }
        List<BlockPos> positions = findSpawnPositions(mine, manager, data, node, requests.size());
        if (positions.size() != requests.size()) {
            LOGGER.warn("[dawnmine] No safe surface for encounter '{}' at {} in {}; site skipped.",
                    composition.id(), node.id(), node.region());
            return;
        }
        if (!data.claimEncounterSite(siteId)) return;
        notifyNearbyPlayers(mine, node);

        int spawned = 0;
        for (int i = 0; i < requests.size(); i++) {
            SpawnRequest request = requests.get(i);
            BlockPos pos = positions.get(i);
            Entity entity = request.type().create(mine);
            if (!(entity instanceof Mob mob) || !(mob instanceof Enemy)) {
                LOGGER.error("[dawnmine] Encounter entity {} could not create a hostile mob; site {} claimed and skipped.",
                        request.id(), siteId);
                continue;
            }
            mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                    stableYaw(data.seed(), siteId, i), 0.0F);
            mob.finalizeSpawn(mine, mine.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
            mob.setPersistenceRequired();
            mob.getPersistentData().putString(RUN_TAG, data.runId());
            mob.getPersistentData().putString(SOURCE_TAG, "encounter:" + composition.id() + ":" + node.id());
            mob.getPersistentData().putString(REGION_TAG, node.region());
            MobLevelScaling.apply(mob, composition.level());
            if (mine.noCollision(mob) && mine.addFreshEntity(mob)) {
                ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
                data.registerMineEntity(mob.getUUID(), entityId, mine.dimension(), pos,
                        mob.getPersistentData().getString(SOURCE_TAG), node.region());
                spawned++;
            } else {
                mob.discard();
            }
        }
        LOGGER.info("[dawnmine] Spawned {}/{} mobs for '{}' in {} ({}; tactic: {}; counterplay: {}).",
                spawned, requests.size(), composition.id(), node.region(), siteId,
                composition.threat(), composition.counterplay());
    }

    /** Validate the encounter data against the actual server registry at startup. */
    public static void validateEntityRegistry() {
        MineEncounterCatalog.Profile profile = MineEncounterCatalog.forProfile(
                TopologyProfiles.activeProfileId());
        if (profile == null) {
            LOGGER.error("[dawnmine] Encounter profile '{}' is missing; generated rooms will not spawn encounters.",
                    TopologyProfiles.activeProfileId());
            return;
        }
        List<ResourceLocation> missingBase = profile.regions().values().stream()
                .flatMap(region -> region.compositions().stream())
                .flatMap(composition -> composition.entities().stream())
                .map(MineEncounterCatalog.EntityCount::entityId)
                .distinct()
                .filter(id -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).isEmpty())
                .toList();
        List<ResourceLocation> missingBonus = profile.regions().values().stream()
                .flatMap(region -> region.compositions().stream())
                .flatMap(composition -> composition.bonusEntities().stream())
                .map(MineEncounterCatalog.EntityCount::entityId)
                .distinct()
                .filter(id -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).isEmpty())
                .toList();
        if (missingBase.isEmpty()) {
            LOGGER.info("[dawnmine] Encounter profile '{}' resolved all base entities against the runtime registry.",
                    profile.id());
        } else {
            LOGGER.error("[dawnmine] Encounter profile '{}' references unavailable BASE entities {}; those "
                    + "compositions will fail closed.", profile.id(), missingBase);
        }
        if (missingBonus.isEmpty()) {
            LOGGER.info("[dawnmine] Encounter profile '{}' resolved all bonus entities; optional packs are present.",
                    profile.id());
        } else {
            LOGGER.info("[dawnmine] Encounter profile '{}' is missing optional bonus entities {} (pack not "
                    + "installed); compositions will spawn without them.", profile.id(), missingBonus);
        }
    }

    /** Remove every currently loaded entity from an ending run before its state is replaced. */
    public static void cleanupRun(ServerLevel mine, MineRunData data, String runId) {
        if (mine == null || data == null || runId == null || runId.isBlank()) return;
        for (MineEntityOrigin origin : data.mineEntities()) {
            if (!origin.runId().equals(runId)) continue;
            Entity entity = mine.getEntity(origin.uuid());
            if (entity != null) entity.discard();
            data.unregisterMineEntity(origin.uuid());
        }
    }

    /** Capture native infected-ore summons and scheduled encounter mobs for bounded cleanup/rewards. */
    @SubscribeEvent
    public void onEntityJoinMine(EntityJoinLevelEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)
                || !level.dimension().equals(MineManager.MINE_DIMENSION)
                || !(event.getEntity() instanceof Mob enemy) || !(enemy instanceof Enemy)) return;
        ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(enemy.getType());
        String sourceId = enemy.getPersistentData().getString(SOURCE_TAG);
        boolean scheduledEncounter = sourceId.startsWith("encounter:");
        boolean nativeInfectedOreSummon = NATIVE_TERMITE_ID.equals(entityId);
        // Ordinary natural hostiles use vanilla despawn rules and never consume
        // the bounded mine-owned encounter registry. Only M4a encounters and
        // the known infected-ore summon participate in run cleanup/rewards.
        if (!scheduledEncounter && !nativeInfectedOreSummon) return;
        MineManager manager = MineManager.get(level.getServer());
        MineRunData data = manager.data();
        BlockPos pos = enemy.blockPosition();
        if (!manager.isOwned(level, pos)) return;
        String savedRun = enemy.getPersistentData().getString(RUN_TAG);
        if (!savedRun.isEmpty() && !savedRun.equals(data.runId())) {
            enemy.discard();
            return;
        }
        String region = enemy.getPersistentData().getString(REGION_TAG);
        if (region.isBlank()) region = regionAtY(pos.getY());
        if (sourceId.isBlank()) sourceId = "native_mine_spawn";
        MineEntityOrigin existing = data.mineEntity(enemy.getUUID());
        if (existing != null) {
            if (existing.runId().equals(data.runId()) && existing.entityType().equals(entityId)
                    && existing.dimension().equals(level.dimension())
                    && existing.sourceId().equals(sourceId) && existing.region().equals(region)) {
                if (scheduledEncounter) enemy.setPersistenceRequired();
                return;
            }
            LOGGER.error("[dawnmine] Tracked mine hostile {} returned with inconsistent run metadata; discarding it.",
                    enemy.getUUID());
            enemy.discard();
            return;
        }
        MineEncounterCatalog.Profile profile = MineEncounterCatalog.forProfile(
                TopologyProfiles.activeProfileId());
        MineEncounterCatalog.Region regionProfile = profile == null ? null : profile.region(region);
        String trackedRegion = region;
        long regionPopulation = data.mineEntities().stream()
                .filter(origin -> origin.runId().equals(data.runId()) && origin.region().equals(trackedRegion))
                .count();
        long minePopulation = data.mineEntities().stream()
                .filter(origin -> origin.runId().equals(data.runId())).count();
        if (regionProfile == null || regionPopulation >= regionProfile.populationCap()
                || minePopulation >= profile.totalPopulationCap()) {
            event.setCanceled(true);
            LOGGER.warn("[dawnmine] Rejected mine hostile {} in {} because the bounded encounter population is full.",
                    entityId, region);
            return;
        }
        BlockPos origin = pos;
        if (enemy.getPersistentData().contains(ORIGIN_X_TAG)
                && enemy.getPersistentData().contains(ORIGIN_Y_TAG)
                && enemy.getPersistentData().contains(ORIGIN_Z_TAG)) {
            origin = new BlockPos(enemy.getPersistentData().getInt(ORIGIN_X_TAG),
                    enemy.getPersistentData().getInt(ORIGIN_Y_TAG),
                    enemy.getPersistentData().getInt(ORIGIN_Z_TAG));
        } else {
            enemy.getPersistentData().putInt(ORIGIN_X_TAG, pos.getX());
            enemy.getPersistentData().putInt(ORIGIN_Y_TAG, pos.getY());
            enemy.getPersistentData().putInt(ORIGIN_Z_TAG, pos.getZ());
        }
        enemy.getPersistentData().putString(RUN_TAG, data.runId());
        enemy.getPersistentData().putString(SOURCE_TAG, sourceId);
        enemy.getPersistentData().putString(REGION_TAG, region);
        // Only scheduled encounters are kept persistent. Native infected-ore
        // termites keep their ordinary despawn behavior; their origin record
        // survives chunk unloads and is removed if they despawn.
        if (scheduledEncounter) enemy.setPersistenceRequired();
        if (entityId == null || !data.registerMineEntity(enemy.getUUID(), entityId,
                level.dimension(), origin, sourceId, region)) {
            LOGGER.error("[dawnmine] Could not track mine hostile {} at {} for run {}; discarding it.",
                    enemy.getType(), pos, data.runId());
            enemy.discard();
        }
    }

    @SubscribeEvent
    public void onTrackedMobDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        if (entity.level() instanceof ServerLevel level
                && level.dimension().equals(MineManager.MINE_DIMENSION)) {
            MineManager manager = MineManager.get(level.getServer());
            MineRunData data = manager.data();
            MineEntityOrigin origin = data.mineEntity(entity.getUUID());
            if (origin != null) {
                awardMineReward(level, entity, event.getSource().getEntity(), manager, data, origin);
                data.unregisterMineEntity(entity.getUUID());
            }
        }
    }

    private static void awardMineReward(ServerLevel level, Entity entity, Entity attacker,
                                        MineManager manager, MineRunData data, MineEntityOrigin origin) {
        if (!(entity instanceof Mob mob) || !(mob instanceof Enemy)
                || !(attacker instanceof ServerPlayer player)) return;
        MineRewardCatalog.Reward reward = MineRewardCatalog.forTier(origin.region());
        BlockPos deathPos = entity.blockPosition();
        boolean owned = manager.isOwned(level, origin.pos()) && manager.isOwned(level, deathPos)
                && !data.isInSafeZone(level, deathPos);
        boolean configured = origin.sourceId().startsWith("encounter:")
                && isConfiguredEncounter(origin);
        if (!MineRewardRules.eligible(origin, data.runId(), level.dimension(), deathPos,
                owned, true, configured, reward)) return;
        // Claim before rolling/delivery: duplicate death callbacks cannot reroll
        // a failed chance or grant a second item. Claims persist for this run.
        if (!data.claimMineReward(entity.getUUID())) return;
        if (!MineRewardRules.rollSucceeded(reward.chanceBasisPoints(), level.getRandom().nextInt(10_000))) {
            LOGGER.debug("[dawnmine] Encounter {} in {} yielded no combat bonus (rate {}%).",
                    origin.sourceId(), origin.region(), reward.chancePercent());
            return;
        }
        int count = reward.minCount() + level.getRandom().nextInt(reward.maxCount() - reward.minCount() + 1);
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(reward.itemId()), count);
        player.getInventory().add(stack);
        if (!stack.isEmpty()) player.drop(stack.copy(), false);
        player.sendSystemMessage(Component.literal("Mine salvage: " + count + " "
                + reward.itemId() + " (" + origin.region() + " encounter)"));
        LOGGER.info("[dawnmine] Combat reward player={} entity={} source={} tier={} item={} count={} chance={}% purpose={}",
                player.getGameProfile().getName(), entity.getUUID(), origin.sourceId(), origin.region(),
                reward.itemId(), count, reward.chancePercent(), reward.purpose());
    }

    private static boolean isConfiguredEncounter(MineEntityOrigin origin) {
        MineEncounterCatalog.Profile profile = MineEncounterCatalog.forProfile(
                TopologyProfiles.activeProfileId());
        MineEncounterCatalog.Region region = profile == null ? null : profile.region(origin.region());
        if (region == null) return false;
        return region.compositions().stream()
                .filter(composition -> origin.sourceId().startsWith("encounter:" + composition.id() + ":"))
                .flatMap(composition -> composition.entities().stream())
                .anyMatch(entityCount -> entityCount.entityId().equals(origin.entityType()));
    }

    @SubscribeEvent
    public void onTrackedMobLeaveLevel(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(event.getLevel() instanceof ServerLevel level)
                || !level.dimension().equals(MineManager.MINE_DIMENSION)) return;
        MineRunData data = MineManager.get(level.getServer()).data();
        if (!data.isTrackedMineEntity(entity.getUUID())) return;
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            return;
        }
        data.unregisterMineEntity(entity.getUUID());
    }

    public static boolean canResolveRequests(ServerLevel level, MineEncounterCatalog.Composition composition) {
        return resolveRequests(level, composition) != null;
    }

    private static List<SpawnRequest> resolveRequests(ServerLevel level,
            MineEncounterCatalog.Composition composition) {
        List<SpawnRequest> result = new ArrayList<>();
        for (MineEncounterCatalog.EntityCount entry : composition.entities()) {
            List<SpawnRequest> resolved = resolveHostileEntries(level, entry, entry.count());
            if (resolved == null) {
                LOGGER.error("[dawnmine] Encounter '{}' requires base entity {}, which this server cannot "
                        + "spawn as a hostile mob; site skipped.", composition.id(), entry.entityId());
                return null;
            }
            result.addAll(resolved);
        }
        // Bonus entities (e.g. Born in Chaos) are additive extras: skip any that
        // this server can't resolve instead of failing the whole composition.
        for (MineEncounterCatalog.EntityCount entry : composition.bonusEntities()) {
            List<SpawnRequest> resolved = resolveHostileEntries(level, entry, entry.count());
            if (resolved == null) continue;
            result.addAll(resolved);
        }
        return result;
    }

    private static List<SpawnRequest> resolveHostileEntries(ServerLevel level,
            MineEncounterCatalog.EntityCount entry, int count) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(entry.entityId()).orElse(null);
        if (type == null) return null;
        List<SpawnRequest> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Entity probe = type.create(level);
            if (!(probe instanceof Mob mob) || !(mob instanceof Enemy)) {
                if (probe != null) probe.discard();
                return null;
            }
            probe.discard();
            result.add(new SpawnRequest(type, entry.entityId()));
        }
        return result;
    }

    private static List<BlockPos> findSpawnPositions(ServerLevel mine, MineManager manager, MineRunData data,
                                                      TopoNode node, int count) {
        int radius = node.kind() == NodeKind.CAVERN ? 6 : 3;
        BlockPos center = new BlockPos(node.pos().x(), node.pos().y(), node.pos().z());
        List<BlockPos> candidates = new ArrayList<>();
        int[][] offsets = {
                {-radius, 0}, {radius, 0}, {0, -radius}, {0, radius},
                {-radius, -radius}, {-radius, radius}, {radius, -radius}, {radius, radius},
                {-radius + 1, 0}, {radius - 1, 0}, {0, -radius + 1}, {0, radius - 1},
                {-2, -2}, {-2, 2}, {2, -2}, {2, 2}, {-2, 0}, {2, 0}, {0, -2}, {0, 2}
        };
        for (int[] offset : offsets) {
            BlockPos pos = center.offset(offset[0], 0, offset[1]);
            if (!mine.hasChunkAt(pos) || !manager.isOwned(mine, pos)
                    || data.isInSafeZone(mine, pos) || data.isInSafeZone(mine, pos.above())) continue;
            BlockState floor = mine.getBlockState(pos.below());
            if (!floor.isFaceSturdy(mine, pos.below(), net.minecraft.core.Direction.UP)
                    || !mine.getBlockState(pos).isAir() || !mine.getBlockState(pos.above()).isAir()) continue;
            if (candidates.stream().anyMatch(other -> other.distSqr(pos) < 4.0)) continue;
            candidates.add(pos);
            if (candidates.size() >= count) break;
        }
        return candidates;
    }

    private static void notifyNearbyPlayers(ServerLevel mine, TopoNode node) {
        double x = node.pos().x() + 0.5;
        double y = node.pos().y();
        double z = node.pos().z() + 0.5;
        for (Player player : mine.players()) {
            if (player.distanceToSqr(x, y, z) <= MAX_SPAWN_DISTANCE_FROM_PLAYER_SQUARED) {
                player.sendSystemMessage(Component.literal("You hear movement deeper in the mineshaft."));
            }
        }
    }

    private static MineEncounterCatalog.Composition selectComposition(long seed, String siteId,
            List<MineEncounterCatalog.Composition> compositions) {
        int totalWeight = compositions.stream().mapToInt(MineEncounterCatalog.Composition::weight).sum();
        if (totalWeight <= 0) return null;
        Random random = new Random(seed ^ stableHash(siteId));
        int choice = random.nextInt(totalWeight);
        for (MineEncounterCatalog.Composition composition : compositions) {
            choice -= composition.weight();
            if (choice < 0) return composition;
        }
        return compositions.get(compositions.size() - 1);
    }

    private static float stableYaw(long seed, String siteId, int index) {
        return new Random(seed ^ stableHash(siteId) ^ index).nextFloat() * 360.0F;
    }

    private static long stableHash(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static String regionAtY(int y) {
        String nearest = "chaos";
        int nearestDistance = Integer.MAX_VALUE;
        for (var region : TopologyProfiles.active().regions()) {
            int min = Math.min(region.topY(), region.bottomY());
            int max = Math.max(region.topY(), region.bottomY());
            if (y >= min && y <= max) return region.id();
            int distance = y < min ? min - y : y - max;
            if (distance < nearestDistance) {
                nearest = region.id();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private record SpawnRequest(EntityType<?> type, ResourceLocation id) { }
}
