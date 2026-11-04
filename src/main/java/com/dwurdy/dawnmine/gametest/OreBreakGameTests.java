package com.dwurdy.dawnmine.gametest;

import com.dwurdy.dawnmine.generation.OreBreakAudit;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.MineRunData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server-side GameTest coverage for native ore mining and player-break hooks. */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public final class OreBreakGameTests {
    private OreBreakGameTests() { }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void everyCatalogOreUsesNativePlayerBreakBehavior(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));
        // The pack world spawns mobs that can wander into the fixture volume;
        // the audit precondition requires an empty, entity-free position, so
        // evict non-player entities and guarantee air before running. The
        // audit is synchronous, so nothing can re-enter before it finishes.
        helper.getLevel().getEntitiesOfClass(Entity.class, new AABB(position).inflate(6)).stream()
                .filter(entity -> !(entity instanceof Player))
                .forEach(Entity::discard);
        helper.getLevel().setBlock(position, Blocks.AIR.defaultBlockState(), 3);
        OreBreakAudit.Result result = OreBreakAudit.run(helper.getLevel(), position);
        helper.assertTrue(result.passed(), "ore break audit failed: " + result.failed());
        helper.assertTrue(result.tested() > 0, "ore break audit did not test any block/tool cases");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200, batch = "dawnmine_hub_prepared")
    public static void generatedOresArePresentAndExposedOnWalls(GameTestHelper helper) {
        // Runtime wall audit on the finished world: every ore block the
        // generator recorded must still be in place, and the majority must
        // have at least one face opening onto cave air (visible while
        // walking the passages) - the original "I don't see any ores" defect.
        var server = helper.getLevel().getServer();
        helper.assertTrue(MineManager.get(server).preparationComplete(),
                "fixture requires a prepared mine");
        ServerLevel mine = server.getLevel(MineManager.MINE_DIMENSION);
        helper.assertTrue(mine != null, "mine dimension not loaded");
        List<MineRunData.MineOreOrigin> origins =
                MineManager.get(server).data().mineOreOrigins();
        helper.assertTrue(!origins.isEmpty(), "run recorded no placed ore origins");
        int missing = 0;
        int buried = 0;
        int exposed = 0;
        Map<String, Integer> exposedById = new TreeMap<>();
        List<String> missingSamples = new ArrayList<>();
        for (MineRunData.MineOreOrigin origin : origins) {
            var state = mine.getBlockState(origin.pos());
            if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(origin.blockId())) {
                missing++;
                if (missingSamples.size() < 3) {
                    missingSamples.add(origin.blockId() + "@" + origin.pos().toShortString()
                            + " now " + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                }
                continue;
            }
            boolean open = false;
            for (Direction face : Direction.values()) {
                if (mine.getBlockState(origin.pos().relative(face)).isAir()) {
                    open = true;
                    break;
                }
            }
            if (open) {
                exposed++;
                exposedById.merge(origin.blockId().toString(), 1, Integer::sum);
            } else {
                buried++;
            }
        }
        helper.assertTrue(missing == 0, missing + " planned ore blocks absent"
                + (missingSamples.isEmpty() ? "" : " e.g. " + missingSamples));
        helper.assertTrue(exposed > buried,
                "only " + exposed + " of " + origins.size()
                        + " placed ores open to air (" + buried + " buried): " + exposedById);
        helper.assertTrue(exposedById.size() >= 6,
                "too few ore types visible from passages: " + exposedById);
        helper.succeed();
    }
}
