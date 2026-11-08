package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.dwurdy.dawnmine.generation.LandmarkCatalog.Definition;
import com.dwurdy.dawnmine.generation.LandmarkLayout.Placement;
import com.dwurdy.dawnmine.generation.SegmentGeometry.BlockKind;
import com.dwurdy.dawnmine.generation.SegmentGeometry.BlockOperation;
import com.dwurdy.dawnmine.generation.SegmentGeometry.SegmentPlan;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoPos;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Literal-grid emission: deterministic ops, air slots carve, marker
 * slots carry full block-state strings, bounds clip. */
class StaticLandmarkBuilderTest {

    private static Placement fixture() {
        JsonObject json = JsonParser.parseString("""
                {
                  "id": "shelter",
                  "footprint": { "halfX": 1, "halfZ": 1, "height": 3, "reserveMargin": 0 },
                  "connectors": [
                    { "x": 1, "y": 1, "z": 0, "facing": "east" }
                  ],
                  "slots": {
                    "S": "minecraft:stone_bricks",
                    "A": "minecraft:air",
                    "T": "minecraft:wall_torch[facing=south]"
                  },
                  "layers": [
                    [ "SSS", "SSS", "SSS" ],
                    [ "STS", "SAA", "SSA" ],
                    [ "SSS", "SSA", "SSS" ]
                  ]
                }
                """).getAsJsonObject();
        Definition def = LandmarkCatalog.parse(json);
        return new Placement("n01", "shelter", new TopoPos(50, 30, 20), def);
    }

    private static TopoBounds bounds() {
        return new TopoBounds(-200, -60, -200, 200, 120, 200);
    }

    @Test
    void emitsLiteralGridDeterministically() {
        SegmentPlan a = StaticLandmarkBuilder.plan(fixture(), bounds());
        SegmentPlan b = StaticLandmarkBuilder.plan(fixture(), bounds());
        assertEquals("landmark:n01", a.id());
        assertEquals(a.operations(), b.operations());
        // 3x3x3 grid = 27 ops.
        assertEquals(27, a.operations().size());
    }

    @Test
    void resolvesSlotsAndAnchors() {
        SegmentPlan plan = StaticLandmarkBuilder.plan(fixture(), bounds());
        // Layer 0 row 0 col 0 = 'S' at anchor + (-1, 0, -1).
        BlockOperation corner = plan.operations().get(0);
        assertEquals(new TopoPos(49, 30, 19), corner.position());
        assertEquals(BlockKind.MARKER, corner.kind());
        assertEquals("minecraft:stone_bricks", corner.blockId());
        // Interior 'A' at (50, 31, 20) carves as OPEN.
        BlockOperation interior = plan.operations().stream()
                .filter(o -> o.position().equals(new TopoPos(50, 31, 20)))
                .findFirst().orElseThrow();
        assertEquals(BlockKind.OPEN, interior.kind());
        // 'T' keeps its full block-state string.
        BlockOperation torch = plan.operations().stream()
                .filter(o -> o.position().equals(new TopoPos(50, 31, 19)))
                .findFirst().orElseThrow();
        assertEquals("minecraft:wall_torch[facing=south]", torch.blockId());
    }

    @Test
    void clipsOutsideBounds() {
        // Clips the -x column of the 3x3x3 grid and the y=32 roof layer
        // (27 - 15 = 12 ops); the connector at x=51 stays inside.
        TopoBounds tight = new TopoBounds(50, 29, 19, 51, 31, 21);
        SegmentPlan plan = StaticLandmarkBuilder.plan(fixture(), tight);
        assertTrue(plan.operations().stream()
                .allMatch(o -> tight.contains(o.position())));
        assertEquals(12, plan.operations().size());
    }

    @Test
    void failsWhenAConnectorWouldClip() {
        // The declared connector sits at (51,31,20): bounds without it
        // would strand the corridor in rock, so this must fail closed.
        TopoBounds tight = new TopoBounds(49, 29, 19, 50, 31, 21);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> StaticLandmarkBuilder.plan(fixture(), tight));
    }
}
