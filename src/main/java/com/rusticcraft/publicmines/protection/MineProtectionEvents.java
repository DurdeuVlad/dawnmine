package com.rusticcraft.publicmines.protection;

import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.generation.OreCatalog;
import com.rusticcraft.publicmines.run.MineManager;
import com.rusticcraft.publicmines.run.MineRunData;
import com.rusticcraft.publicmines.run.OwnedVolume;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.level.BlockEvent.FluidPlaceBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent.BlockToolModificationEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.slf4j.Logger;

/** Server-side interaction, terrain, and combat protections for the active public mine. */
public final class MineProtectionEvents {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long DENIAL_FEEDBACK_TICKS = 40;
    private static final long DENIAL_ENTRY_TTL_TICKS = 1200;
    private static final String SAFE_ZONE_LAUNCH_TAG = "publicminesSafeZoneLaunch";
    private static final Map<FeedbackKey, Long> LAST_FEEDBACK = new ConcurrentHashMap<>();
    private static long lastFeedbackCleanupTick;

    private enum Action {
        BLOCK_BREAK,
        BLOCK_PLACE,
        TERRAIN_MODIFICATION,
        PLAYER_COMBAT
    }

    private record FeedbackKey(UUID player, Action action) { }

    private MineProtectionEvents() { }

    /** Register once during mod construction on the logical server event bus. */
    public static void register() {
        NeoForge.EVENT_BUS.register(new MineProtectionEvents());
    }

    @SubscribeEvent
    public void onPlayerBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player player = event.getPlayer();
        if (!isOwnedMinePosition(level, event.getPos())) return;
        if (player.isCreative() && player.hasPermissions(2)) return;

        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(event.getState().getBlock());
        MineRunData data = MineManager.get(level.getServer()).data();
        boolean allowed = OreCatalog.active().entry(blockId).isPresent()
                && data.isGeneratedMineOre(level, event.getPos(), blockId);
        if (!allowed) {
            event.setCanceled(true);
            sendDenial(player, Action.BLOCK_BREAK,
                    "Only generated mine ores can be mined here.");
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !isOwnedMinePosition(level, event.getPos())) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) {
            event.setCanceled(true);
            return;
        }
        if (player.isCreative() && player.hasPermissions(2)) return;

        BlockState placed = event.getPlacedBlock();
        boolean torch = placed.is(Blocks.TORCH) || placed.is(Blocks.SOUL_TORCH);
        BlockPos supportPos = event.getPos().below();
        boolean floorSupport = level.getBlockState(supportPos)
                .isFaceSturdy(level, supportPos, Direction.UP);
        if (!torch || !floorSupport || !placed.canSurvive(level, event.getPos())) {
            event.setCanceled(true);
            sendDenial(player, Action.BLOCK_PLACE,
                    "Only ordinary or soul torches may be placed on the floor in the mine.");
        }
    }

    @SubscribeEvent
    public void onToolModification(BlockToolModificationEvent event) {
        if (event.isSimulated() || !(event.getLevel() instanceof ServerLevel level)
                || !isOwnedMinePosition(level, event.getPos())) return;
        event.setCanceled(true);
        if (event.getPlayer() != null) {
            sendDenial(event.getPlayer(), Action.TERRAIN_MODIFICATION,
                    "Terrain cannot be modified with tools in the mine.");
        }
    }

    @SubscribeEvent
    public void onHazardItemUse(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (!(player.level() instanceof ServerLevel level)
                || !isOwnedMinePosition(level, event.getPos())) return;
        Item item = event.getItemStack().getItem();
        boolean hazardousUse = item instanceof BucketItem || item instanceof SpawnEggItem
                || item instanceof MinecartItem || item == Items.FLINT_AND_STEEL
                || item == Items.FIRE_CHARGE || item == Items.END_CRYSTAL;
        if (hazardousUse) {
            event.setUseItem(TriState.FALSE);
            sendDenial(player, Action.TERRAIN_MODIFICATION,
                    "That item cannot alter or create hazards in the mine.");
        }
    }

    @SubscribeEvent
    public void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level
                && isOwnedMinePosition(level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onMobGriefing(EntityMobGriefingEvent event) {
        Entity entity = event.getEntity();
        if (entity.level() instanceof ServerLevel level && isOwnedMinePosition(level, entity.blockPosition())) {
            event.setCanGrief(false);
        }
    }

    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        event.getAffectedBlocks().removeIf(pos -> isOwnedMinePosition(level, pos));
    }

    @SubscribeEvent
    public void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        List<BlockPos> affected = new ArrayList<>(resolver.getToPush());
        affected.addAll(resolver.getToDestroy());
        if (isOwnedMinePosition(level, event.getPos())
                || isOwnedMinePosition(level, event.getFaceOffsetPos())
                || affected.stream().anyMatch(pos -> isOwnedMinePosition(level, pos))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onFluidPlace(FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && (isOwnedMinePosition(level, event.getPos())
                || isOwnedMinePosition(level, event.getLiquidPos()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerAttack(AttackEntityEvent event) {
        Entity target = event.getTarget();
        Player attacker = event.getEntity();
        if (!(target.level() instanceof ServerLevel level)) return;
        MineRunData data = dataIfRelevant(level, attacker.blockPosition(), target.blockPosition());
        if (data == null) return;
        CombatPolicy.Context context = context(data, level, List.of(attacker), target, false);
        if (CombatPolicy.shouldBlock(kind(attacker), kind(target), context)) {
            event.setCanceled(true);
            sendDenial(attacker, Action.PLAYER_COMBAT,
                    context.attackerInMine() || context.targetInMine()
                            ? "Player combat is disabled in the public mine."
                            : "Combat is disabled in the safe zone and across its boundary.");
        }
    }

    @SubscribeEvent
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        Entity target = event.getEntity();
        if (!(target.level() instanceof ServerLevel level)) return;
        List<Entity> sources = sourceEntities(event.getSource());
        MineRunData data = dataForEntities(level, sources, target);
        if (data == null) return;
        Entity attacker = classifySource(sources);
        boolean launchedInSafeZone = hasSafeZoneProjectileOrigin(sources);
        CombatPolicy.Context context = context(data, level, sources, target, launchedInSafeZone);
        if (isUnknownSafeZoneAttack(attacker, target, launchedInSafeZone)
                || CombatPolicy.shouldBlock(kind(attacker), kind(target), context)) {
            event.setCanceled(true);
            if (attacker instanceof Player player) {
                sendDenial(player, Action.PLAYER_COMBAT,
                        context.attackerInMine() || context.targetInMine()
                                ? "Player combat is disabled in the public mine."
                                : "Combat is disabled in the safe zone and across its boundary.");
            }
        }
    }

    @SubscribeEvent
    public void onHarmfulEffect(MobEffectEvent.Applicable event) {
        if (event.getEffectInstance() == null
                || event.getEffectInstance().getEffect().value().getCategory() != MobEffectCategory.HARMFUL) return;
        Entity target = event.getEntity();
        if (!(target.level() instanceof ServerLevel level)) return;
        Entity source = event.getEffectSource();
        if (source == null) {
            MineRunData data = dataForTarget(level, target);
            if (data != null && CombatPolicy.shouldBlockUnattributedHarmfulEffect(kind(target),
                    data.isInSafeZone(level, target.blockPosition()))) {
                event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
            }
            return;
        }
        List<Entity> sources = ownerChain(source);
        MineRunData data = dataForEntities(level, sources, target);
        if (data == null) return;
        Entity attacker = classifySource(sources);
        boolean launchedInSafeZone = hasSafeZoneProjectileOrigin(sources);
        CombatPolicy.Context context = context(data, level, sources, target, launchedInSafeZone);
        if (isUnknownSafeZoneAttack(attacker, target, launchedInSafeZone)
                || CombatPolicy.shouldBlock(kind(attacker), kind(target), context)) {
            event.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
            if (attacker instanceof Player player) {
                sendDenial(player, Action.PLAYER_COMBAT,
                        context.attackerInMine() || context.targetInMine()
                                ? "Player combat is disabled in the public mine."
                                : "Combat is disabled in the safe zone and across its boundary.");
            }
        }
    }

    @SubscribeEvent
    public void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!(projectile.level() instanceof ServerLevel level)) return;
        boolean launchedInSafeZone = projectile.getPersistentData().getBoolean(SAFE_ZONE_LAUNCH_TAG);
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit)) return;
        Entity target = hit.getEntity();
        Entity owner = projectile.getOwner();
        List<Entity> sources = ownerChain(projectile);
        MineRunData data = dataForEntities(level, sources, target);
        if (data == null) return;
        Entity attacker = classifySource(sources);
        CombatPolicy.Context context = context(data, level, sources, target, launchedInSafeZone);
        boolean unknownSafeZoneProjectile = launchedInSafeZone && kind(attacker) == CombatPolicy.CombatantKind.OTHER
                && (kind(target) == CombatPolicy.CombatantKind.PLAYER
                || kind(target) == CombatPolicy.CombatantKind.HOSTILE);
        if (unknownSafeZoneProjectile || CombatPolicy.shouldBlock(kind(attacker), kind(target), context)) {
            event.setCanceled(true);
            if (attacker instanceof Player player) {
                sendDenial(player, Action.PLAYER_COMBAT,
                        context.attackerInMine() || context.targetInMine()
                                ? "Player combat is disabled in the public mine."
                                : "Combat is disabled in the safe zone and across its boundary.");
            }
        }
    }

    @SubscribeEvent
    public void onProjectileJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Projectile projectile)
                || !(event.getLevel() instanceof ServerLevel level)
                || !isMineWorld(level)) return;
        if (event.loadedFromDisk() && projectile.getPersistentData().getBoolean(SAFE_ZONE_LAUNCH_TAG)) return;
        List<Entity> sources = ownerChain(projectile);
        MineRunData data = dataForEntities(level, sources, projectile);
        if (data == null) return;
        boolean launchedInSafeZone = sources.stream().anyMatch(source -> source.level() == level
                && data.isInSafeZone(level, source.blockPosition()));
        if (launchedInSafeZone) {
            projectile.getPersistentData().putBoolean(SAFE_ZONE_LAUNCH_TAG, true);
        }
    }

    /** Hostile entities loaded or spawned inside an active safe zone never join the world. */
    @SubscribeEvent
    public void onHostileJoinSafeZone(EntityJoinLevelEvent event) {
        Entity hostile = event.getEntity();
        if (!(hostile instanceof Enemy)
                || !(event.getLevel() instanceof ServerLevel level)
                || !isMineWorld(level)) return;
        MineRunData data = MineManager.get(level.getServer()).data();
        if (intersectsSafeZone(data, level, hostile)) {
            event.setCanceled(true);
        }
    }

    /** Also removes hostiles that walk or teleport into a zone after joining. */
    @SubscribeEvent
    public void onHostileTickInSafeZone(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Enemy)
                || !(entity.level() instanceof ServerLevel level)
                || !isMineWorld(level)) return;
        MineRunData data = MineManager.get(level.getServer()).data();
        if (intersectsSafeZone(data, level, entity)) {
            entity.discard();
        }
    }

    private static boolean intersectsSafeZone(MineRunData data, ServerLevel level, Entity entity) {
        AABB bounds = entity.getBoundingBox();
        String activeRunId = data.runId();
        return data.safeZones().stream()
                .filter(zone -> zone.runId().equals(activeRunId)
                        && zone.volume().dimension().equals(level.dimension()))
                .map(MineRunData.SafeZone::volume)
                .anyMatch(volume -> bounds.maxX > volume.min().getX()
                        && bounds.minX < volume.max().getX() + 1.0
                        && bounds.maxY > volume.min().getY()
                        && bounds.minY < volume.max().getY() + 1.0
                        && bounds.maxZ > volume.min().getZ()
                        && bounds.minZ < volume.max().getZ() + 1.0);
    }

    private static boolean hasSafeZoneProjectileOrigin(List<Entity> sources) {
        return sources.stream().filter(Projectile.class::isInstance).map(Projectile.class::cast)
                .anyMatch(projectile -> projectile.getPersistentData().getBoolean(SAFE_ZONE_LAUNCH_TAG));
    }

    private static boolean isUnknownSafeZoneAttack(Entity attacker, Entity target, boolean launchedInSafeZone) {
        return launchedInSafeZone && kind(attacker) == CombatPolicy.CombatantKind.OTHER
                && (kind(target) == CombatPolicy.CombatantKind.PLAYER
                || kind(target) == CombatPolicy.CombatantKind.HOSTILE);
    }

    private static CombatPolicy.Context context(MineRunData data, ServerLevel level,
                                                List<Entity> attackerEntities, Entity target,
                                                boolean attackerWasInSafeZone) {
        boolean attackerInMine = attackerEntities.stream().filter(entity -> entity.level() == level)
                .anyMatch(entity -> isMinePvPEndpoint(data, level, entity.blockPosition()));
        boolean targetInMine = isMinePvPEndpoint(data, level, target.blockPosition());
        boolean attackerInSafeZone = attackerWasInSafeZone || attackerEntities.stream()
                .filter(entity -> entity.level() == level)
                .anyMatch(entity -> data.isInSafeZone(level, entity.blockPosition()));
        boolean targetInSafeZone = data.isInSafeZone(level, target.blockPosition());
        return new CombatPolicy.Context(attackerInMine, targetInMine, attackerInSafeZone, targetInSafeZone);
    }

    private static CombatPolicy.CombatantKind kind(Entity entity) {
        if (entity instanceof Player) return CombatPolicy.CombatantKind.PLAYER;
        if (entity instanceof Enemy) return CombatPolicy.CombatantKind.HOSTILE;
        return CombatPolicy.CombatantKind.OTHER;
    }

    private static Entity classifySource(List<Entity> sources) {
        for (Entity entity : sources) {
            if (entity instanceof Player) return entity;
        }
        for (Entity entity : sources) {
            if (entity instanceof Enemy) return entity;
        }
        return sources.isEmpty() ? null : sources.get(0);
    }

    private static List<Entity> sourceEntities(DamageSource source) {
        List<Entity> sources = new ArrayList<>();
        if (source.getEntity() != null) sources.addAll(ownerChain(source.getEntity()));
        if (source.getDirectEntity() != null) sources.addAll(ownerChain(source.getDirectEntity()));
        return sources.stream().distinct().toList();
    }

    private static List<Entity> ownerChain(Entity source) {
        List<Entity> chain = new ArrayList<>();
        Entity current = source;
        for (int i = 0; current != null && i < 5 && !chain.contains(current); i++) {
            chain.add(current);
            Entity owner = null;
            if (current instanceof Projectile projectile) {
                owner = projectile.getOwner();
            } else if (current instanceof OwnableEntity ownable) {
                owner = ownable.getOwner();
            } else if (current instanceof AreaEffectCloud cloud) {
                owner = cloud.getOwner();
            }
            current = owner;
        }
        return chain;
    }

    private static MineRunData dataForEntities(ServerLevel level, List<Entity> attackers, Entity target) {
        if (!isMineWorld(level) || target.level() != level) return null;
        MineRunData data = MineManager.get(level.getServer()).data();
        boolean inside = isMinePvPEndpoint(data, level, target.blockPosition())
                || attackers.stream().filter(entity -> entity.level() == level)
                .anyMatch(entity -> isMinePvPEndpoint(data, level, entity.blockPosition()))
                || isSafeZoneEndpoint(data, level, target.blockPosition())
                || attackers.stream().filter(entity -> entity.level() == level)
                .anyMatch(entity -> isSafeZoneEndpoint(data, level, entity.blockPosition()));
        return inside ? data : null;
    }

    private static MineRunData dataForTarget(ServerLevel level, Entity target) {
        if (!isMineWorld(level)) return null;
        MineRunData data = MineManager.get(level.getServer()).data();
        return isSafeZoneEndpoint(data, level, target.blockPosition()) ? data : null;
    }

    private static MineRunData dataIfRelevant(ServerLevel level, BlockPos attacker, BlockPos target) {
        if (!isMineWorld(level)) return null;
        MineRunData data = MineManager.get(level.getServer()).data();
        return isMinePvPEndpoint(data, level, attacker) || isMinePvPEndpoint(data, level, target)
                || isSafeZoneEndpoint(data, level, attacker) || isSafeZoneEndpoint(data, level, target)
                ? data : null;
    }

    private static boolean isMineWorld(Level level) {
        return level.dimension().equals(MineManager.MINE_DIMENSION);
    }

    private static boolean isOwnedMinePosition(ServerLevel level, BlockPos pos) {
        if (!isMineWorld(level)) return false;
        MineRunData data = MineManager.get(level.getServer()).data();
        return data.ownedVolumes().stream().anyMatch(volume -> volume.contains(level, pos));
    }

    private static boolean isMinePvPEndpoint(MineRunData data, ServerLevel level, BlockPos pos) {
        return isMineWorld(level) && data.ownedVolumes().stream().anyMatch(volume -> volume.contains(level, pos));
    }

    private static boolean isSafeZoneEndpoint(MineRunData data, ServerLevel level, BlockPos pos) {
        return isMineWorld(level) && data.isInSafeZone(level, pos);
    }

    private static void sendDenial(Player player, Action action, String message) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        long tick = serverPlayer.serverLevel().getGameTime();
        FeedbackKey key = new FeedbackKey(player.getUUID(), action);
        Long previous = LAST_FEEDBACK.put(key, tick);
        if (previous != null && tick - previous < DENIAL_FEEDBACK_TICKS) {
            LAST_FEEDBACK.put(key, previous);
            return;
        }
        if (tick - lastFeedbackCleanupTick > DENIAL_ENTRY_TTL_TICKS) {
            LAST_FEEDBACK.entrySet().removeIf(entry -> tick - entry.getValue() > DENIAL_ENTRY_TTL_TICKS);
            lastFeedbackCleanupTick = tick;
        }
        serverPlayer.sendSystemMessage(Component.literal(message));
    }
}
