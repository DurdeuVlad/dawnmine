package com.rusticcraft.publicmines.run;

import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import com.rusticcraft.publicmines.generation.OreCatalog;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.time.LocalDate;
import org.slf4j.Logger;

/** Dedicated-server event tests for mine break, torch, and safe-zone guards. */
@GameTestHolder("publicmines")
@PrefixGameTestTemplate(false)
public final class MineProtectionGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MineProtectionGameTests() { }

    @SuppressWarnings("deprecation")
    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    @GameTest(template = "empty", batch = "m2c_protection", timeoutTicks = 1200)
    public static void mineBreakTorchAndSafeZoneEventsAreProtected(GameTestHelper helper) {
        ServerLevel testLevel = helper.getLevel();
        MineManager manager = MineManager.get(testLevel.getServer());
        ServerLevel mine = manager.mineLevel();
        helper.assertTrue(mine != null,
                "M2c event GameTest requires the public_mine_v2 dimension from the mod data pack");
        if (mine == null) return;

        MineRunData data = manager.data();
        ResourceLocation coalOreId = ResourceLocation.parse("minecraft:coal_ore");
        var coalOre = OreCatalog.active().entry(coalOreId).orElse(null);
        helper.assertTrue(coalOre != null, "production ore catalog is missing minecraft:coal_ore");
        if (coalOre == null) return;

        var originalRunData = data.snapshot(testLevel.registryAccess());
        int radius = PublicMineConfig.mineRegionRadiusChunks();
        OwnedVolume owned = new OwnedVolume("active_region", MineManager.MINE_DIMENSION,
                new BlockPos(-radius * 16, mine.getMinBuildHeight(), -radius * 16),
                new BlockPos(radius * 16 - 1, mine.getMaxBuildHeight() - 1, radius * 16 - 1));
        int testX = 64;
        int testZ = 64;
        BlockPos orePos = new BlockPos(testX, 96, testZ);
        BlockPos torchPos = orePos.east(4);
        BlockPos torchSupport = torchPos.below();
        BlockStateBackup backup = null;
        ResourceLocation safeZoneId = ResourceLocation.fromNamespaceAndPath("publicmines",
                "gametest_" + UUID.randomUUID().toString().replace("-", ""));
        BlockPos safeCenter = new BlockPos(testX, 96, testZ).north(16);
        OwnedVolume safeZone = new OwnedVolume("gametest_safe_zone", MineManager.MINE_DIMENSION,
                safeCenter.offset(-3, -2, -3), safeCenter.offset(3, 2, 3));
        ServerPlayer player = null;
        ServerPlayer secondPlayer = null;
        Zombie hostile = null;
        Arrow arrow = null;
        try {
            data.beginNewRun("gametest-protection-" + UUID.randomUUID(), 1, 0x4d3243L, LocalDate.now().toString());
            data.setBounds(-radius, -radius, radius - 1, radius - 1);
            data.addOwnedVolume(owned);
            data.markStatus(RunStatus.READY);
            backup = new BlockStateBackup(mine, orePos, torchPos, torchSupport);
            player = fakePlayer(helper);
            secondPlayer = fakePlayer(helper);
            mine.getChunkAt(orePos);
            player.teleportTo(mine, orePos.getX() + 0.5, orePos.getY(), orePos.getZ() + 0.5,
                    0.0F, 0.0F);
            secondPlayer.teleportTo(mine, orePos.getX() + 2.5, orePos.getY(), orePos.getZ() + 0.5,
                    0.0F, 0.0F);
            AttackEntityEvent pvp = new AttackEntityEvent(player, secondPlayer);
            NeoForge.EVENT_BUS.post(pvp);
            helper.assertTrue(pvp.isCanceled(), "player-versus-player attack was allowed inside the mine");

            mine.setBlock(orePos, Blocks.STONE.defaultBlockState(), 3);
            BlockEvent.BreakEvent stoneBreak = new BlockEvent.BreakEvent(
                    mine, orePos, mine.getBlockState(orePos), player);
            NeoForge.EVENT_BUS.post(stoneBreak);
            helper.assertTrue(stoneBreak.isCanceled(), "ordinary mine stone break was not denied");

            mine.setBlock(orePos, OreCatalog.active().blockState(coalOre), 3);
            BlockEvent.BreakEvent untrackedOreBreak = new BlockEvent.BreakEvent(
                    mine, orePos, mine.getBlockState(orePos), player);
            NeoForge.EVENT_BUS.post(untrackedOreBreak);
            helper.assertTrue(untrackedOreBreak.isCanceled(), "untracked catalog ore was breakable");

            helper.assertTrue(data.recordMineOre(mine, orePos, coalOreId),
                    "could not register generated-ore fixture provenance");
            BlockEvent.BreakEvent generatedOreBreak = new BlockEvent.BreakEvent(
                    mine, orePos, mine.getBlockState(orePos), player);
            NeoForge.EVENT_BUS.post(generatedOreBreak);
            helper.assertFalse(generatedOreBreak.isCanceled(),
                    "catalog ore with exact generated-position provenance was denied");
            data.removeMineOre(mine, orePos);

            mine.setBlock(torchSupport, Blocks.STONE.defaultBlockState(), 3);
            mine.setBlock(torchPos, Blocks.TORCH.defaultBlockState(), 3);
            BlockEvent.EntityPlaceEvent floorTorch = new BlockEvent.EntityPlaceEvent(
                    BlockSnapshot.create(mine.dimension(), mine, torchPos),
                    mine.getBlockState(torchSupport), player);
            NeoForge.EVENT_BUS.post(floorTorch);
            helper.assertFalse(floorTorch.isCanceled(), "valid floor torch placement was denied");

            mine.setBlock(torchPos, Blocks.WALL_TORCH.defaultBlockState(), 3);
            BlockEvent.EntityPlaceEvent wallTorch = new BlockEvent.EntityPlaceEvent(
                    BlockSnapshot.create(mine.dimension(), mine, torchPos),
                    mine.getBlockState(torchSupport), player);
            NeoForge.EVENT_BUS.post(wallTorch);
            helper.assertTrue(wallTorch.isCanceled(), "wall torch placement was allowed");

            boolean safeZoneRegistered = data.registerSafeZone(safeZoneId, safeZone);
            helper.assertTrue(safeZoneRegistered,
                    "could not register temporary safe-zone fixture: run=" + data.runId()
                            + " owned=" + data.ownedVolumes() + " zone=" + safeZone);
            hostile = EntityType.ZOMBIE.create(mine);
            helper.assertTrue(hostile != null, "could not create zombie fixture");
            if (hostile != null) {
                hostile.moveTo(safeCenter.getX() + 0.5, safeCenter.getY(), safeCenter.getZ() + 0.5,
                        0.0F, 0.0F);
                mine.addFreshEntity(hostile);
                player.teleportTo(mine, orePos.getX() + 0.5, orePos.getY(), orePos.getZ() + 0.5,
                        0.0F, 0.0F);
                helper.assertFalse(data.isInSafeZone(mine, player.blockPosition()),
                        "test attacker unexpectedly started inside safe zone");
                AttackEntityEvent attackIntoZone = new AttackEntityEvent(player, hostile);
                NeoForge.EVENT_BUS.post(attackIntoZone);
                helper.assertTrue(attackIntoZone.isCanceled(),
                        "player attack from outside into safe zone was not denied");

                player.teleportTo(mine, safeCenter.getX() + 0.5, safeCenter.getY(),
                        safeCenter.getZ() + 0.5, 0.0F, 0.0F);
                hostile.moveTo(safeCenter.getX() + 8.5, safeCenter.getY(), safeCenter.getZ() + 0.5,
                        0.0F, 0.0F);
                AttackEntityEvent attackOutOfZone = new AttackEntityEvent(player, hostile);
                NeoForge.EVENT_BUS.post(attackOutOfZone);
                helper.assertTrue(attackOutOfZone.isCanceled(),
                        "player attack from inside safe zone to outside was not denied");

                MobEffectEvent.Applicable unattributedEffect = new MobEffectEvent.Applicable(
                        player, new MobEffectInstance(MobEffects.POISON, 100), null);
                NeoForge.EVENT_BUS.post(unattributedEffect);
                helper.assertTrue(unattributedEffect.getResult() == MobEffectEvent.Applicable.Result.DO_NOT_APPLY,
                        "source-less harmful effect was allowed on a safe-zone occupant");

                DamageSource hostileDamage = mine.damageSources().mobAttack(hostile);
                LivingIncomingDamageEvent damageInZone = new LivingIncomingDamageEvent(player,
                        new DamageContainer(hostileDamage, 2.0F));
                NeoForge.EVENT_BUS.post(damageInZone);
                helper.assertTrue(damageInZone.isCanceled(), "hostile damage to a safe-zone player was allowed");

                player.teleportTo(mine, orePos.getX() + 0.5, orePos.getY(), orePos.getZ() + 0.5,
                        0.0F, 0.0F);
                LivingIncomingDamageEvent damageOutsideZone = new LivingIncomingDamageEvent(player,
                        new DamageContainer(hostileDamage, 2.0F));
                NeoForge.EVENT_BUS.post(damageOutsideZone);
                helper.assertFalse(damageOutsideZone.isCanceled(),
                        "ordinary hostile PvE outside a safe zone was suppressed");

                arrow = EntityType.ARROW.create(mine);
                helper.assertTrue(arrow != null, "could not create projectile fixture");
                if (arrow != null) {
                    arrow.setOwner(hostile);
                    arrow.moveTo(safeCenter.getX() + 0.5, safeCenter.getY(), safeCenter.getZ() + 0.5,
                            0.0F, 0.0F);
                    mine.addFreshEntity(arrow);
                    hostile.moveTo(safeCenter.getX() + 8.5, safeCenter.getY(), safeCenter.getZ() + 0.5,
                            0.0F, 0.0F);
                    secondPlayer.teleportTo(mine, safeCenter.getX() + 10.5, safeCenter.getY(),
                            safeCenter.getZ() + 0.5, 0.0F, 0.0F);
                    arrow.moveTo(safeCenter.getX() + 10.5, safeCenter.getY(), safeCenter.getZ() + 0.5,
                            0.0F, 0.0F);
                    CompoundTag savedArrow = new CompoundTag();
                    helper.assertTrue(arrow.save(savedArrow), "projectile fixture could not be serialized");
                    arrow.discard();
                    hostile.discard();
                    hostile = null;
                    Entity reloaded = EntityType.loadEntityRecursive(savedArrow, mine, entity -> entity);
                    helper.assertTrue(reloaded instanceof Arrow, "serialized projectile fixture could not be reloaded");
                    if (reloaded instanceof Arrow reloadedArrow) {
                        arrow = reloadedArrow;
                        mine.addFreshEntity(arrow);
                        ProjectileImpactEvent delayedShot = new ProjectileImpactEvent(arrow,
                                new EntityHitResult(secondPlayer));
                        NeoForge.EVENT_BUS.post(delayedShot);
                        helper.assertTrue(delayedShot.isCanceled(),
                                "safe-zone projectile lost launch protection after serialization and owner removal");
                        ProjectileImpactEvent blockImpact = new ProjectileImpactEvent(arrow,
                                new BlockHitResult(Vec3.atCenterOf(orePos), Direction.UP, orePos, false));
                        NeoForge.EVENT_BUS.post(blockImpact);
                        LivingIncomingDamageEvent splashDamage = new LivingIncomingDamageEvent(secondPlayer,
                                new DamageContainer(mine.damageSources().arrow(arrow, null), 2.0F));
                        NeoForge.EVENT_BUS.post(splashDamage);
                        helper.assertTrue(splashDamage.isCanceled(),
                                "safe-zone projectile splash damage was allowed after a block impact");
                    }
                }
            }
        } finally {
            if (arrow != null) arrow.discard();
            if (hostile != null) hostile.discard();
            if (backup != null) backup.restore();
            if (player != null) player.discard();
            if (secondPlayer != null) secondPlayer.discard();
            data.restoreSnapshot(originalRunData, testLevel.registryAccess());
        }
        helper.assertTrue(data.snapshot(testLevel.registryAccess()).equals(originalRunData),
                "protection test failed to restore the preexisting shared run state");
        LOGGER.info("[publicmines] M2c mine-protection GameTest passed (break, placement, PvP, safe-zone effects/damage, serialized projectile boundary, run-state restore).");
        helper.succeed();
    }

    private static final class BlockStateBackup {
        private final ServerLevel level;
        private final BlockPos orePos;
        private final BlockPos torchPos;
        private final BlockPos supportPos;
        private final net.minecraft.world.level.block.state.BlockState oreState;
        private final net.minecraft.world.level.block.state.BlockState torchState;
        private final net.minecraft.world.level.block.state.BlockState supportState;

        BlockStateBackup(ServerLevel level, BlockPos orePos, BlockPos torchPos, BlockPos supportPos) {
            this.level = level;
            this.orePos = orePos.immutable();
            this.torchPos = torchPos.immutable();
            this.supportPos = supportPos.immutable();
            this.oreState = level.getBlockState(orePos);
            this.torchState = level.getBlockState(torchPos);
            this.supportState = level.getBlockState(supportPos);
        }

        void restore() {
            level.setBlock(orePos, oreState, 3);
            level.setBlock(torchPos, torchState, 3);
            level.setBlock(supportPos, supportState, 3);
        }
    }
}
