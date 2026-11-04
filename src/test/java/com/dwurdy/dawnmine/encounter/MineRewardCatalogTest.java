package com.dwurdy.dawnmine.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class MineRewardCatalogTest {
    private static Map<String, MineRewardCatalog.Reward> bundled() throws Exception {
        InputStream input = MineRewardCatalogTest.class.getResourceAsStream(
                "/data/dawnmine/mine_rewards/standard.json");
        if (input == null) throw new AssertionError("bundled mine reward catalog is missing");
        try (input) {
            return MineRewardCatalog.parse(JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    @Test
    void bundledRewardsMapEachDepthToBoundedPackProgressionInputs() throws Exception {
        Map<String, MineRewardCatalog.Reward> rewards = bundled();
        assertEquals(3, rewards.size());
        assertEquals(ResourceLocation.parse("minecraft:raw_iron"), rewards.get("upper").itemId());
        assertEquals(2_000, rewards.get("upper").chanceBasisPoints());
        assertEquals(ResourceLocation.parse("werewolves:raw_silver"), rewards.get("middle").itemId());
        assertEquals(1_000, rewards.get("middle").chanceBasisPoints());
        assertEquals(ResourceLocation.parse("minecraft:diamond"), rewards.get("chaos").itemId());
        assertEquals(200, rewards.get("chaos").chanceBasisPoints());
        for (MineRewardCatalog.Reward reward : rewards.values()) {
            assertEquals(1, reward.minCount());
            assertEquals(1, reward.maxCount());
            assertFalse(reward.purpose().isBlank());
            assertFalse(reward.sourceAndSink().isBlank());
        }
    }

    @Test
    void missingTierHasNoRewardAndMalformedOrUnboundedRewardsFailClosed() {
        Map<String, MineRewardCatalog.Reward> upperOnly = MineRewardCatalog.parse(JsonParser.parseString(
                "{\"schema\":1,\"rewards\":[{\"tier\":\"upper\",\"item\":\"minecraft:iron_ingot\","
                        + "\"chanceBasisPoints\":100,\"minCount\":1,\"maxCount\":1,"
                        + "\"purpose\":\"test\",\"sourceAndSink\":\"test\"}]}").getAsJsonObject());
        assertTrue(upperOnly.containsKey("upper"));
        assertFalse(upperOnly.containsKey("chaos"));
        assertThrows(JsonParseException.class, () -> MineRewardCatalog.parse(JsonParser.parseString(
                "{\"schema\":1,\"rewards\":[{\"tier\":\"upper\",\"item\":\"minecraft:iron_ingot\","
                        + "\"chanceBasisPoints\":10001,\"minCount\":1,\"maxCount\":1,"
                        + "\"purpose\":\"test\",\"sourceAndSink\":\"test\"}]}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> new MineRewardCatalog.Reward("upper",
                ResourceLocation.parse("minecraft:iron_ingot"), 1, 1, 17, "test", "test"));
    }
}
