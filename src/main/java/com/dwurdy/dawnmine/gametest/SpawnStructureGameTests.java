package com.dwurdy.dawnmine.gametest;

import com.dwurdy.dawnmine.generation.SpawnStructure;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Behavioral coverage for {@link SpawnStructure}: no shipped structure
 * means no-op, never a crash, on a server that never saved one. */
@GameTestHolder("dawnmine")
@PrefixGameTestTemplate(false)
public final class SpawnStructureGameTests {
    private SpawnStructureGameTests() { }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void noSavedSpawnHubIsANoOp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(!SpawnStructure.placeIfSaved(level),
                "a server that never saved dawnmine:spawn_hub must leave the procedural hub untouched");
        helper.succeed();
    }
}
