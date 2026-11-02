package com.rusticcraft.publicmines.gametest;

import com.rusticcraft.publicmines.generation.OreBreakAudit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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
        OreBreakAudit.Result result = OreBreakAudit.run(helper.getLevel(), position);
        helper.assertTrue(result.passed(), "ore break audit failed: " + result.failed());
        helper.assertTrue(result.tested() > 0, "ore break audit did not test any block/tool cases");
        helper.succeed();
    }
}
