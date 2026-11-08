package com.dwurdy.dawnmine.run;

import com.dwurdy.dawnmine.config.DawnmineConfig;
import com.dwurdy.dawnmine.encounter.MineEncounterCatalog;
import com.dwurdy.dawnmine.encounter.MineEncounterManager;
import com.dwurdy.dawnmine.encounter.MobLevelScaling;
import com.dwurdy.dawnmine.topology.NodeKind;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyProfiles;
import com.mojang.logging.LogUtils;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/**
 * M9a server integration checks: scheduled encounter mobs carry
 * {@code "Lv. <N> <Name>"} nametags and bounded health-only scaling, while the
 * native infected-ore summon path is untouched.
 */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public final class MobLevelGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TEMPORARY_WORLD_NAME = "dawnmine-m9a-level-audit";
    private static final ResourceLocation TERMITE_ID = ResourceLocation.parse(
            "born_in_chaos_v1:diamond_termite");

    private MobLevelGameTests() { }

    /** Direct presentation/scaling check on vanilla mobs: no pack content or
     * run state needed, so this runs on plain GameTestServer too. */
    @GameTest(template = "empty", batch = "m9a_levels", timeoutTicks = 200)
    public static void leveledMobsShowNamesAndBoundedHealth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob scaledHigh = EntityType.ZOMBIE.create(level);
        helper.assertTrue(scaledHigh != null, "could not create level fixture mob");
        if (scaledHigh != null) {
            double base = scaledHigh.getAttributeBaseValue(Attributes.MAX_HEALTH);
            MobLevelScaling.apply(scaledHigh, 8);
            helper.assertTrue(scaledHigh.getPersistentData().getInt(MobLevelScaling.LEVEL_TAG) == 8,
                    "scaled mob did not record its level tag");
            helper.assertTrue(scaledHigh.getCustomName() != null
                            && scaledHigh.getCustomName().getString().equals("Lv. 8 Zombie"),
                    "scaled mob name mismatch: " + scaledHigh.getCustomName());
            helper.assertTrue(scaledHigh.isCustomNameVisible(),
                    "scaled mob nametag is not visible");
            AttributeInstance health = scaledHigh.getAttribute(Attributes.MAX_HEALTH);
            helper.assertTrue(health != null
                            && health.getModifier(MobLevelScaling.HEALTH_MODIFIER_ID) != null,
                    "scaled mob did not receive the bounded health modifier");
            double expected = base * (1.0 + MobLevelScaling.healthBonusMultiplier(8));
            helper.assertTrue(Math.abs(scaledHigh.getMaxHealth() - expected) < 0.01,
                    "scaled mob max health " + scaledHigh.getMaxHealth()
                            + " != expected " + expected);
            helper.assertTrue(scaledHigh.getHealth() == scaledHigh.getMaxHealth(),
                    "scaled mob was not healed to its new maximum");
            scaledHigh.discard();
        }
        Mob scaledMid = EntityType.ZOMBIE.create(level);
        helper.assertTrue(scaledMid != null, "could not create mid-band fixture mob");
        if (scaledMid != null) {
            double base = scaledMid.getAttributeBaseValue(Attributes.MAX_HEALTH);
            MobLevelScaling.apply(scaledMid, 5);
            AttributeInstance health = scaledMid.getAttribute(Attributes.MAX_HEALTH);
            helper.assertTrue(health != null
                            && health.getModifier(MobLevelScaling.HEALTH_MODIFIER_ID) != null,
                    "mid-band mob did not receive the health modifier");
            double expected = base * (1.0 + MobLevelScaling.BONUS_BAND_3_TO_5);
            helper.assertTrue(Math.abs(scaledMid.getMaxHealth() - expected) < 0.01,
                    "mid-band mob max health " + scaledMid.getMaxHealth()
                            + " != expected " + expected);
            scaledMid.discard();
        }
        Mob easiest = EntityType.ZOMBIE.create(level);
        helper.assertTrue(easiest != null, "could not create level-1 fixture mob");
        if (easiest != null) {
            double base = easiest.getAttributeBaseValue(Attributes.MAX_HEALTH);
            MobLevelScaling.apply(easiest, 1);
            AttributeInstance health = easiest.getAttribute(Attributes.MAX_HEALTH);
            helper.assertTrue(health != null
                            && health.getModifier(MobLevelScaling.HEALTH_MODIFIER_ID) == null,
                    "level-1 mob received a health modifier it must not have");
            helper.assertTrue(Math.abs(easiest.getMaxHealth() - base) < 0.001,
                    "level-1 mob lost its unmodified native health");
            helper.assertTrue(easiest.getCustomName() != null
                            && easiest.getCustomName().getString().equals("Lv. 1 Zombie")
                            && easiest.isCustomNameVisible(),
                    "level-1 mob did not get a readable nametag");
            easiest.discard();
        }
        helper.succeed();
    }

    /** End-to-end: drive the real scheduled-spawn path per region and verify
     * every spawned mob's tag, nametag, and resolved MAX_HEALTH. */
    @GameTest(template = "empty", batch = "m9a_levels", timeoutTicks = 1200)
    public static void scheduledEncountersCarryConfiguredLevels(GameTestHelper helper) {
        ServerLevel testLevel = helper.getLevel();
        ServerLevel mine = testLevel.getServer().getLevel(MineManager.MINE_DIMENSION);
        if (mine == null || BuiltInRegistries.ENTITY_TYPE.getOptional(
                ResourceLocation.parse("born_in_chaos_v1:decrepit_skeleton")).isEmpty()) {
            LOGGER.warn("[dawnmine] M9a scheduled-spawn GameTest skipped: mine level or Born in Chaos is unavailable.");
            helper.succeed();
            return;
        }
        if (!TEMPORARY_WORLD_NAME.equals(testLevel.getServer().getWorldData().getLevelName())) {
            LOGGER.warn("[dawnmine] M9a scheduled-spawn GameTest skipped outside disposable world '{}'.",
                    TEMPORARY_WORLD_NAME);
            helper.succeed();
            return;
        }

        MineManager manager = MineManager.get(testLevel.getServer());
        MineRunData data = manager.data();
        var original = data.snapshot(testLevel.registryAccess());
        int radius = DawnmineConfig.mineRegionRadiusChunks();
        OwnedVolume owned = new OwnedVolume("m9a_gametest_region", MineManager.MINE_DIMENSION,
                new BlockPos(-radius * 16, mine.getMinBuildHeight(), -radius * 16),
                new BlockPos(radius * 16 - 1, mine.getMaxBuildHeight() - 1, radius * 16 - 1));
        MineEncounterCatalog.Profile profile = MineEncounterCatalog.forProfile(
                TopologyProfiles.activeProfileId());
        helper.assertTrue(profile != null, "no active encounter profile for level checks");
        try {
            data.beginNewRun("m9a-gametest-" + UUID.randomUUID(), 1, 0x4d3941L,
                    LocalDate.now(DawnmineConfig.resetZoneId()).toString());
            data.setBounds(-radius, -radius, radius - 1, radius - 1);
            data.addOwnedVolume(owned);
            data.markStatus(RunStatus.READY);

            String[][] regions = {
                    {"upper", "128"}, {"middle", "256"}, {"chaos", "384"}};
            for (String[] region : regions) {
                int bx = Integer.parseInt(region[1]);
                BlockPos center = new BlockPos(bx, 60, bx);
                preparePlatform(mine, center);
                TopoNode node = new TopoNode("m9a-" + region[0], NodeKind.CAVERN,
                        region[0], new TopoPos(center.getX(), center.getY(), center.getZ()),
                        Set.of(), "");
                MineEncounterManager.onNodeGenerated(testLevel.getServer(), node);
                List<Mob> spawned = mine.getEntitiesOfClass(Mob.class,
                        new AABB(center).inflate(12)).stream()
                        .filter(mob -> data.runId().equals(
                                mob.getPersistentData().getString("dawnmineRunId")))
                        .toList();
                helper.assertTrue(!spawned.isEmpty(),
                        "scheduled encounter spawned nothing in region " + region[0]);
                for (Mob mob : spawned) {
                    assertLeveledMob(helper, profile, mob, region[0]);
                }
            }
        } finally {
            MineEncounterManager.cleanupRun(mine, data, data.runId());
            data.restoreSnapshot(original, testLevel.registryAccess());
        }
        helper.assertTrue(data.snapshot(testLevel.registryAccess()).equals(original),
                "M9a level GameTest failed to restore shared run state");
        LOGGER.info("[dawnmine] M9a scheduled-encounter level GameTest passed.");
        helper.succeed();
    }

    /** Criterion 4: the native infected-ore summon path must never receive a
     * level tag, custom name, or attribute modification. */
    @GameTest(template = "empty", batch = "m9a_levels", timeoutTicks = 400)
    public static void nativeTermiteSummonsGetNoLevelOrScaling(GameTestHelper helper) {
        ServerLevel testLevel = helper.getLevel();
        ServerLevel mine = testLevel.getServer().getLevel(MineManager.MINE_DIMENSION);
        var termiteType = BuiltInRegistries.ENTITY_TYPE.getOptional(TERMITE_ID).orElse(null);
        if (mine == null || termiteType == null) {
            LOGGER.warn("[dawnmine] M9a native-termite GameTest skipped: mine level or Born in Chaos is unavailable.");
            helper.succeed();
            return;
        }
        if (!TEMPORARY_WORLD_NAME.equals(testLevel.getServer().getWorldData().getLevelName())) {
            LOGGER.warn("[dawnmine] M9a native-termite GameTest skipped outside disposable world '{}'.",
                    TEMPORARY_WORLD_NAME);
            helper.succeed();
            return;
        }
        MineManager manager = MineManager.get(testLevel.getServer());
        MineRunData data = manager.data();
        var original = data.snapshot(testLevel.registryAccess());
        int radius = DawnmineConfig.mineRegionRadiusChunks();
        OwnedVolume owned = new OwnedVolume("m9a_gametest_region", MineManager.MINE_DIMENSION,
                new BlockPos(-radius * 16, mine.getMinBuildHeight(), -radius * 16),
                new BlockPos(radius * 16 - 1, mine.getMaxBuildHeight() - 1, radius * 16 - 1));
        Mob termite = null;
        try {
            data.beginNewRun("m9a-termites-" + UUID.randomUUID(), 1, 0x4d3941L,
                    LocalDate.now(DawnmineConfig.resetZoneId()).toString());
            data.setBounds(-radius, -radius, radius - 1, radius - 1);
            data.addOwnedVolume(owned);
            data.markStatus(RunStatus.READY);
            BlockPos pos = new BlockPos(448, 60, 448);
            mine.getChunkAt(pos);
            var created = termiteType.create(mine);
            helper.assertTrue(created instanceof Mob, "native termite did not create a Mob");
            if (created instanceof Mob mob) {
                termite = mob;
                mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
                helper.assertTrue(mine.addFreshEntity(mob), "native termite did not join the mine");
                helper.assertTrue(!mob.getPersistentData().contains(MobLevelScaling.LEVEL_TAG),
                        "native termite received a dawnmineLevel tag");
                helper.assertTrue(mob.getCustomName() == null,
                        "native termite received a custom name");
                AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
                helper.assertTrue(health == null
                                || health.getModifier(MobLevelScaling.HEALTH_MODIFIER_ID) == null,
                        "native termite received the level health modifier");
            }
        } finally {
            if (termite != null) termite.discard();
            MineEncounterManager.cleanupRun(mine, data, data.runId());
            data.restoreSnapshot(original, testLevel.registryAccess());
        }
        helper.assertTrue(data.snapshot(testLevel.registryAccess()).equals(original),
                "M9a termite GameTest failed to restore shared run state");
        LOGGER.info("[dawnmine] M9a native-termite exclusion GameTest passed.");
        helper.succeed();
    }

    private static void assertLeveledMob(GameTestHelper helper,
            MineEncounterCatalog.Profile profile, Mob mob, String regionId) {
        String source = mob.getPersistentData().getString("dawnmineSourceId");
        helper.assertTrue(source.startsWith("encounter:"),
                "spawned mob missing encounter source id: " + source);
        String compositionId = source.split(":").length > 1 ? source.split(":")[1] : "";
        MineEncounterCatalog.Region region = profile.region(regionId);
        helper.assertTrue(region != null, "no encounter region " + regionId);
        MineEncounterCatalog.Composition composition = region == null ? null
                : region.compositions().stream()
                        .filter(c -> c.id().equals(compositionId)).findFirst().orElse(null);
        helper.assertTrue(composition != null,
                "spawned mob references unknown composition " + compositionId);
        if (composition == null) return;
        int tagLevel = mob.getPersistentData().getInt(MobLevelScaling.LEVEL_TAG);
        helper.assertTrue(tagLevel == composition.level(),
                "mob level tag " + tagLevel + " != composition level " + composition.level());
        String expectedName = "Lv. " + composition.level() + " "
                + mob.getType().getDescription().getString();
        helper.assertTrue(mob.getCustomName() != null
                        && mob.getCustomName().getString().equals(expectedName),
                "mob name '" + mob.getCustomName() + "' != '" + expectedName + "'");
        helper.assertTrue(mob.isCustomNameVisible(), "mob nametag is not visible");
        AttributeInstance health = mob.getAttribute(Attributes.MAX_HEALTH);
        double bonus = MobLevelScaling.healthBonusMultiplier(composition.level());
        if (health == null) {
            helper.assertTrue(bonus == 0.0,
                    "mob has no MAX_HEALTH attribute but required a " + bonus + " bonus");
            return;
        }
        if (bonus == 0.0) {
            helper.assertTrue(health.getModifier(MobLevelScaling.HEALTH_MODIFIER_ID) == null,
                    "no-scaling-band mob received the level health modifier");
        } else {
            var modifier = health.getModifier(MobLevelScaling.HEALTH_MODIFIER_ID);
            helper.assertTrue(modifier != null && Math.abs(modifier.amount() - bonus) < 0.0001,
                    "mob health modifier missing or wrong for level " + composition.level());
            double expected = health.getBaseValue() * (1.0 + bonus);
            helper.assertTrue(Math.abs(health.getValue() - expected) < 0.01,
                    "mob resolved health " + health.getValue() + " != expected " + expected);
        }
    }

    private static void preparePlatform(ServerLevel mine, BlockPos center) {
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                BlockPos floor = center.offset(dx, -1, dz);
                mine.getChunkAt(floor);
                mine.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy <= 3; dy++) {
                    mine.setBlock(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }
}
