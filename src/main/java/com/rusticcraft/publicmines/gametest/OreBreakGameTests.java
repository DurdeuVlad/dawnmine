package com.rusticcraft.publicmines.gametest;

import com.rusticcraft.publicmines.generation.OreBreakAudit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server-side GameTest coverage for native ore mining and player-break hooks. */
@GameTestHolder("publicmines")
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
}
