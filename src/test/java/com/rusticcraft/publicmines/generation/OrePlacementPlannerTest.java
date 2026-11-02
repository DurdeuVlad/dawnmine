package com.rusticcraft.publicmines.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rusticcraft.publicmines.topology.NodeFeature;
import com.rusticcraft.publicmines.topology.TopoBounds;
import com.rusticcraft.publicmines.topology.TopoNode;
import com.rusticcraft.publicmines.topology.TopologyPlanner;
import com.rusticcraft.publicmines.topology.TopologyProfile;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

class OrePlacementPlannerTest {
    private static final TopoBounds BOUNDS = new TopoBounds(-1024, -64, -1024, 1023, 319, 1023);
    private static final OreCatalog.OreCatalogSnapshot CATALOG = OreCatalog.OreCatalogSnapshot.fromEntries(List.of(
            entry("minecraft:coal_ore", OreCatalog.Tier.UPPER, 1, 3),
            entry("minecraft:gold_ore", OreCatalog.Tier.MIDDLE, 1, 2),
            entry("minecraft:diamond_ore", OreCatalog.Tier.CHAOS, 1, 1)));

    @Test
    void resourceFieldsGetDeterministicNativeOreOperationsInTheNodeTier() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopoNode node = topology.nodes().stream().filter(candidate -> candidate.has(NodeFeature.RESOURCE_FIELD))
                .findFirst().orElseThrow();
        SegmentGeometry.SegmentPlan base = SegmentGeometry.node(topology, node, BOUNDS,
                new com.rusticcraft.publicmines.topology.TopoPos(0, 63, 0));

        SegmentGeometry.SegmentPlan first = OrePlacementPlanner.addOres(node, base, CATALOG, topology.seed());
        SegmentGeometry.SegmentPlan replay = OrePlacementPlanner.addOres(node, base, CATALOG, topology.seed());

        assertEquals(first, replay);
        List<SegmentGeometry.BlockOperation> ores = first.operations().stream()
                .filter(operation -> operation.kind() == SegmentGeometry.BlockKind.ORE).toList();
        assertFalse(ores.isEmpty());
        assertTrue(ores.size() <= 8, "two clusters must be capped at four blocks each");
        Map<com.rusticcraft.publicmines.topology.TopoPos, SegmentGeometry.BlockKind> baseBlocks = new HashMap<>();
        for (SegmentGeometry.BlockOperation operation : base.operations()) {
            baseBlocks.put(operation.position(), operation.kind());
        }
        for (SegmentGeometry.BlockOperation ore : ores) {
            assertEquals(SegmentGeometry.BlockKind.ROCK, baseBlocks.get(ore.position()));
            assertTrue(CATALOG.entry(ResourceLocation.parse(ore.blockId())).orElseThrow().tier()
                    == OreCatalog.Tier.parse(node.region()));
        }
    }

    @Test
    void nodesWithoutResourceFieldsAreNotChanged() {
        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        TopoNode node = topology.nodes().stream().filter(candidate -> !candidate.has(NodeFeature.RESOURCE_FIELD))
                .findFirst().orElseThrow();
        SegmentGeometry.SegmentPlan base = SegmentGeometry.node(topology, node, BOUNDS,
                new com.rusticcraft.publicmines.topology.TopoPos(0, 63, 0));

        assertEquals(base, OrePlacementPlanner.addOres(node, base, CATALOG, topology.seed()));
    }

    @Test
    void bundledCatalogReloadsAndPlansOnlyNativeResourcesInEveryDepthTier() throws Exception {
        Map<ResourceLocation, JsonElement> bundled = bundledCatalogResource();
        OreCatalog listener = OreCatalog.createReloadListener();
        listener.apply(bundled, null, null);
        OreCatalog.OreCatalogSnapshot original = OreCatalog.active();

        assertEquals(32, original.entries().size());
        assertTrue(original.completeness(java.util.Set.of(), java.util.Set.of()).unassignedTiers().isEmpty());
        assertEquals(2, original.entries().values().stream()
                .filter(entry -> entry.kind() == OreCatalog.Kind.ENCOUNTER).count());
        for (OreCatalog.Tier tier : OreCatalog.Tier.values()) {
            assertFalse(original.resourceEntries(tier).isEmpty(), "no native ores assigned to " + tier);
            for (var entry : original.entries().values()) {
                if (entry.kind() == OreCatalog.Kind.ENCOUNTER) continue;
                assertEquals(entry.tier() == tier,
                        original.resourceEntries(tier).stream().anyMatch(candidate -> candidate.blockId().equals(entry.blockId())),
                        entry.blockId() + " must only be selectable in its assigned depth tier");
            }
            assertEquals(OreCatalog.Kind.RESOURCE,
                    original.choose(tier, RandomSource.create(42L)).kind(),
                    "encounter ores must not be selected for native resource fields");
        }

        var topology = TopologyPlanner.plan(42, TopologyProfile.builtin(), BOUNDS);
        for (OreCatalog.Tier tier : OreCatalog.Tier.values()) {
            TopoNode node = topology.nodes().stream()
                    .filter(candidate -> candidate.has(NodeFeature.RESOURCE_FIELD))
                    .filter(candidate -> candidate.region().equals(tier.id()))
                    .findFirst().orElse(null);
            assertNotNull(node, "topology must include a resource field in " + tier);
            SegmentGeometry.SegmentPlan room = SegmentGeometry.node(topology, node, BOUNDS,
                    new com.rusticcraft.publicmines.topology.TopoPos(0, 63, 0));
            SegmentGeometry.SegmentPlan generated = OrePlacementPlanner.addOres(node, room, original, topology.seed());
            List<SegmentGeometry.BlockOperation> ores = generated.operations().stream()
                    .filter(operation -> operation.kind() == SegmentGeometry.BlockKind.ORE).toList();
            assertFalse(ores.isEmpty(), "production catalog should populate a " + tier + " resource field");
            for (SegmentGeometry.BlockOperation ore : ores) {
                var entry = original.entry(ResourceLocation.parse(ore.blockId())).orElseThrow();
                assertEquals(OreCatalog.Kind.RESOURCE, entry.kind());
                assertEquals(tier, entry.tier());
            }
        }

        JsonObject changed = bundled.get(ResourceLocation.parse("publicmines:standard")).getAsJsonObject().deepCopy();
        JsonObject firstOre = changed.getAsJsonArray("ores").get(0).getAsJsonObject();
        firstOre.addProperty("weight", firstOre.get("weight").getAsInt() + 1);
        listener.apply(Map.of(ResourceLocation.parse("publicmines:standard"), changed), null, null);
        OreCatalog.OreCatalogSnapshot reloaded = OreCatalog.active();
        assertNotEquals(original.fingerprint(), reloaded.fingerprint());
        assertNotEquals(original.entries().get(ResourceLocation.parse("minecraft:coal_ore")).weight(),
                reloaded.entries().get(ResourceLocation.parse("minecraft:coal_ore")).weight());
        listener.apply(bundled, null, null);
    }

    private static Map<ResourceLocation, JsonElement> bundledCatalogResource() throws Exception {
        try (var stream = OrePlacementPlannerTest.class.getResourceAsStream(
                "/data/publicmines/ore_catalog/standard.json")) {
            assertNotNull(stream, "bundled production ore catalog resource must be on the test classpath");
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return Map.of(ResourceLocation.parse("publicmines:standard"), JsonParser.parseReader(reader));
            }
        }
    }

    private static OreCatalog.Entry entry(String id, OreCatalog.Tier tier, int weight, int maxSize) {
        return new OreCatalog.Entry(ResourceLocation.parse(id), tier, weight, maxSize,
                OreCatalog.Kind.RESOURCE, true, ResourceLocation.parse("minecraft:stone_pickaxe"),
                List.of(ResourceLocation.parse(id)), null);
    }
}
