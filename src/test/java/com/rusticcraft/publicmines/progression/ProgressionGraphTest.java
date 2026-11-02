package com.rusticcraft.publicmines.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.rusticcraft.publicmines.encounter.MineRewardCatalog;
import com.rusticcraft.publicmines.generation.OreCatalog;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Structural and cross-catalog checks for the bundled progression graph.
 * Runtime recipe/item resolution is covered by the exact-pack audit; these
 * tests prove the graph is well-formed, bounded, evidence-backed, and
 * consistent with the shipped ore catalog and mine reward table.
 */
class ProgressionGraphTest {

    private record Graph(Map<ResourceLocation, ProgressionGraph.Material> materials,
                         List<ProgressionGraph.Upgrade> upgrades,
                         List<ProgressionGraph.BlockedNode> blocked) { }

    private static JsonObject bundled(String path) throws Exception {
        InputStream input = ProgressionGraphTest.class.getResourceAsStream(path);
        if (input == null) throw new AssertionError("bundled resource is missing: " + path);
        try (input) {
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static Graph parse(JsonObject json) {
        Map<ResourceLocation, ProgressionGraph.Material> materials = new LinkedHashMap<>();
        List<ProgressionGraph.Upgrade> upgrades = new ArrayList<>();
        List<ProgressionGraph.BlockedNode> blocked = new ArrayList<>();
        ProgressionGraph.parseInto(json, materials, upgrades, blocked);
        return new Graph(materials, upgrades, blocked);
    }

    private static Graph bundledGraph() throws Exception {
        return parse(bundled("/data/publicmines/progression/standard.json"));
    }

    /** Ore block id -> native drop ids, straight from the shipped ore catalog. */
    private static Map<ResourceLocation, Set<ResourceLocation>> bundledOreDrops() throws Exception {
        Map<ResourceLocation, Set<ResourceLocation>> drops = new TreeMap<>();
        JsonObject catalog = bundled("/data/publicmines/ore_catalog/standard.json");
        for (JsonElement element : catalog.getAsJsonArray("ores")) {
            JsonObject ore = element.getAsJsonObject();
            Set<ResourceLocation> items = new TreeSet<>();
            for (JsonElement drop : ore.getAsJsonArray("native_drop_items")) {
                items.add(ResourceLocation.parse(drop.getAsString()));
            }
            drops.put(ResourceLocation.parse(ore.get("block").getAsString()), items);
        }
        return drops;
    }

    /** Reward tier -> item id, straight from the shipped mine reward table. */
    private static Map<String, ResourceLocation> bundledRewardItems() throws Exception {
        Map<String, ResourceLocation> rewards = new TreeMap<>();
        JsonObject catalog = bundled("/data/publicmines/mine_rewards/standard.json");
        for (JsonElement element : catalog.getAsJsonArray("rewards")) {
            JsonObject reward = element.getAsJsonObject();
            rewards.put(reward.get("tier").getAsString(),
                    ResourceLocation.parse(reward.get("item").getAsString()));
        }
        return rewards;
    }

    @Test
    void bundledGraphParsesWithMaterialsUpgradesAndBlockedNodes() throws Exception {
        Graph graph = bundledGraph();
        assertTrue(graph.materials().size() >= 15,
                "expected at least 15 progression materials, got " + graph.materials().size());
        assertTrue(graph.upgrades().size() >= 8,
                "expected at least 8 upgrades, got " + graph.upgrades().size());
        assertTrue(graph.blocked().size() >= 3,
                "verified pack/config gaps must be recorded, got " + graph.blocked().size());
    }

    @Test
    void everyMaterialHasABoundedSourceASinkAYieldBoundAndEvidence() throws Exception {
        for (ProgressionGraph.Material material : bundledGraph().materials().values()) {
            assertFalse(material.sources().isEmpty(), material.item() + " has no source");
            assertFalse(material.sinks().isEmpty(), material.item() + " has no sink");
            assertFalse(material.boundedYield().isBlank(), material.item() + " has no yield bound");
            assertFalse(material.evidence().isEmpty(), material.item() + " has no evidence");
        }
    }

    @Test
    void everyUpgradeNamesTheProblemItSolvesAndTheDifferentProblemItDoesNot() throws Exception {
        for (ProgressionGraph.Upgrade upgrade : bundledGraph().upgrades()) {
            assertFalse(upgrade.solves().isBlank(), upgrade.id() + " names no solved problem");
            assertFalse(upgrade.doesNotSolve().isBlank(), upgrade.id() + " names no unsolved problem");
            assertFalse(upgrade.solves().equals(upgrade.doesNotSolve()),
                    upgrade.id() + " claims the same problem is solved and unsolved");
            assertFalse(upgrade.activities().isEmpty(), upgrade.id() + " has no contributing activity");
            assertFalse(upgrade.evidence().isEmpty(), upgrade.id() + " cites no pack evidence");
            assertTrue(upgrade.verification().equals("verified")
                            || upgrade.verification().equals("candidate"),
                    upgrade.id() + " has an unknown verification state");
        }
    }

    @Test
    void miningSourcesResolveToOreCatalogBlocksWhoseDropsContainTheMaterial() throws Exception {
        Map<ResourceLocation, Set<ResourceLocation>> oreDrops = bundledOreDrops();
        for (ProgressionGraph.Material material : bundledGraph().materials().values()) {
            for (ProgressionGraph.Source source : material.sources()) {
                if (source.kind() != ProgressionGraph.SourceKind.MINING) continue;
                ResourceLocation block = ResourceLocation.parse(source.ref().substring(6));
                assertTrue(oreDrops.containsKey(block),
                        material.item() + " mines " + block + ", which is not in the ore catalog");
                Set<ResourceLocation> drops = oreDrops.get(block);
                assertTrue(material.allItems().stream().anyMatch(drops::contains),
                        material.item() + " is not a native drop of " + block);
            }
        }
    }

    @Test
    void rewardSourcesResolveToConfiguredTiersThatYieldTheMaterial() throws Exception {
        Map<String, ResourceLocation> rewards = bundledRewardItems();
        for (ProgressionGraph.Material material : bundledGraph().materials().values()) {
            for (ProgressionGraph.Source source : material.sources()) {
                if (source.kind() != ProgressionGraph.SourceKind.PVE
                        || !source.ref().startsWith("reward:")) continue;
                String tier = source.ref().substring(7);
                assertTrue(rewards.containsKey(tier),
                        material.item() + " references unconfigured reward tier " + tier);
                assertEquals(material.item(), rewards.get(tier),
                        "reward tier " + tier + " yields a different item than " + material.item());
            }
        }
    }

    @Test
    void miningPveAndExplorationEachContributeToTheGraph() throws Exception {
        Graph graph = bundledGraph();
        Set<ProgressionGraph.SourceKind> kinds = new TreeSet<>();
        for (ProgressionGraph.Material material : graph.materials().values()) {
            for (ProgressionGraph.Source source : material.sources()) {
                kinds.add(source.kind());
            }
        }
        assertTrue(kinds.contains(ProgressionGraph.SourceKind.MINING), "no mining contribution");
        assertTrue(kinds.contains(ProgressionGraph.SourceKind.PVE), "no combat contribution");
        assertTrue(kinds.contains(ProgressionGraph.SourceKind.EXPLORATION),
                "no exploration contribution");
        Set<String> activities = new TreeSet<>();
        for (ProgressionGraph.Upgrade upgrade : graph.upgrades()) {
            activities.addAll(upgrade.activities());
        }
        assertTrue(activities.contains("mining"), "no upgrade uses mining");
        assertTrue(activities.contains("pve"), "no upgrade uses combat");
        assertTrue(activities.contains("exploration"), "no upgrade uses exploration");
    }

    @Test
    void blockedNodesRecordVerifiedGapsWithoutFabricatingSources() throws Exception {
        for (ProgressionGraph.BlockedNode node : bundledGraph().blocked()) {
            assertFalse(node.neededFor().isBlank());
            assertFalse(node.reason().isBlank());
            assertFalse(node.evidence().isEmpty(), node.item() + " blocked without evidence");
        }
    }

    @Test
    void missingItemsAreFlaggedNotDropped() throws Exception {
        ResourceLocation real = ResourceLocation.parse("minecraft:raw_iron");
        ResourceLocation missing = ResourceLocation.parse("nonexistentmod:fake_ore_chunk");
        Map<ResourceLocation, ProgressionGraph.Material> materials = new LinkedHashMap<>();
        List<ProgressionGraph.Upgrade> upgrades = new ArrayList<>();
        List<ProgressionGraph.BlockedNode> blocked = new ArrayList<>();
        ProgressionGraph.parseInto(JsonParser.parseString("{\"schema\":1,\"materials\":["
                + "{\"item\":\"" + missing + "\",\"tier\":\"upper\",\"sources\":["
                + "{\"kind\":\"mining\",\"ref\":\"block:minecraft:iron_ore\"}],"
                + "\"sinks\":[{\"kind\":\"mechanic\",\"ref\":\"test sink\"}],"
                + "\"boundedYield\":\"test bound\",\"evidence\":[\"test\"]}],"
                + "\"upgrades\":[],\"blocked\":[]}").getAsJsonObject(), materials, upgrades, blocked);
        assertTrue(materials.containsKey(missing), "unavailable material was dropped at parse time");

        Map<ResourceLocation, OreCatalog.Entry> ores = new LinkedHashMap<>();
        ores.put(ResourceLocation.parse("minecraft:iron_ore"),
                new OreCatalog.Entry(ResourceLocation.parse("minecraft:iron_ore"),
                        OreCatalog.Tier.UPPER, 1, 2, OreCatalog.Kind.RESOURCE, true,
                        ResourceLocation.parse("minecraft:iron_pickaxe"), List.of(real), null));
        ProgressionGraph.ProgressionSnapshot snapshot =
                new ProgressionGraph.ProgressionSnapshot(materials, upgrades, blocked, List.of());
        ProgressionGraph.Completeness completeness = snapshot.completeness(
                item -> item.equals(real), ores, tier -> null, entity -> false);
        assertTrue(completeness.unavailableItems().contains(missing.toString()),
                "missing item was not flagged: " + completeness.unavailableItems());
        assertTrue(completeness.miningDropMismatches().stream()
                .anyMatch(line -> line.contains("minecraft:iron_ore")),
                "iron ore drop mismatch for " + missing + " was not flagged");
        assertFalse(completeness.complete());
    }

    @Test
    void malformedGraphsFailClosed() {
        // Unsupported schema
        assertThrows(JsonParseException.class, () -> parse(JsonParser.parseString(
                "{\"schema\":2,\"materials\":[],\"upgrades\":[]}").getAsJsonObject()));
        // Empty materials
        assertThrows(JsonParseException.class, () -> parse(JsonParser.parseString(
                "{\"schema\":1,\"materials\":[],\"upgrades\":[]}").getAsJsonObject()));
        // Material without a sink
        assertThrows(JsonParseException.class, () -> parse(JsonParser.parseString(
                "{\"schema\":1,\"materials\":[{\"item\":\"minecraft:coal\",\"tier\":\"upper\","
                        + "\"sources\":[{\"kind\":\"mining\",\"ref\":\"block:minecraft:coal_ore\"}],"
                        + "\"sinks\":[],\"boundedYield\":\"x\",\"evidence\":[\"x\"]}],"
                        + "\"upgrades\":[]}").getAsJsonObject()));
        // Mining source not prefixed block:
        assertThrows(JsonParseException.class, () -> parse(JsonParser.parseString(
                "{\"schema\":1,\"materials\":[{\"item\":\"minecraft:coal\",\"tier\":\"upper\","
                        + "\"sources\":[{\"kind\":\"mining\",\"ref\":\"minecraft:coal_ore\"}],"
                        + "\"sinks\":[{\"kind\":\"mechanic\",\"ref\":\"fuel\"}],"
                        + "\"boundedYield\":\"x\",\"evidence\":[\"x\"]}],"
                        + "\"upgrades\":[]}").getAsJsonObject()));
        // Upgrade whose solves equals doesNotSolve
        assertThrows(JsonParseException.class, () -> parse(JsonParser.parseString(
                "{\"schema\":1,\"materials\":[{\"item\":\"minecraft:coal\",\"tier\":\"upper\","
                        + "\"sources\":[{\"kind\":\"mining\",\"ref\":\"block:minecraft:coal_ore\"}],"
                        + "\"sinks\":[{\"kind\":\"mechanic\",\"ref\":\"fuel\"}],"
                        + "\"boundedYield\":\"x\",\"evidence\":[\"x\"]}],"
                        + "\"upgrades\":[{\"id\":\"bad\",\"tier\":\"upper\",\"activities\":[\"mining\"],"
                        + "\"output\":\"minecraft:coal\",\"recipe\":\"minecraft:coal\","
                        + "\"inputs\":[\"x\"],\"solves\":\"same\",\"doesNotSolve\":\"same\","
                        + "\"verification\":\"verified\",\"evidence\":[\"x\"]}]}").getAsJsonObject()));
    }
}

