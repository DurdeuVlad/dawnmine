package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.dwurdy.dawnmine.generation.LandmarkCatalog.Definition;
import com.dwurdy.dawnmine.generation.LandmarkLayout.Placement;
import com.dwurdy.dawnmine.topology.TopoPos;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reserve + connector + opening semantics for placed landmarks. */
class LandmarkLayoutTest {

    private static final TopoPos FEET = new TopoPos(100, 40, -60);

    @BeforeEach
    void installFixture() {
        LandmarkCatalog.installForTest(Map.of("shelter", parseShelter()));
    }

    @AfterEach
    void clearFixture() {
        LandmarkCatalog.installForTest(Map.of());
    }

    private static Definition parseShelter() {
        JsonObject json = JsonParser.parseString("""
                {
                  "id": "shelter",
                  "footprint": { "halfX": 2, "halfZ": 1, "height": 3, "reserveMargin": 1 },
                  "connectors": [
                    { "x": 2, "y": 1, "z": 0, "facing": "east" }
                  ],
                  "slots": { "S": "minecraft:stone_bricks", "A": "minecraft:air" },
                  "layers": [
                    [ "SSSSS", "SSSSS", "SSSSS" ],
                    [ "SSSSS", "SAAAA", "SSSSA" ],
                    [ "SSSSS", "SAAAA", "SSSSA" ]
                  ]
                }
                """).getAsJsonObject();
        return LandmarkCatalog.parse(json);
    }

    private static LandmarkLayout place() {
        return LandmarkLayout.place(Map.of("n01", FEET), Map.of("n01", "shelter"),
                LandmarkCatalog.definitions());
    }

    @Test
    void anchorsFloorSlabOneBelowFeet() {
        Placement p = place().placement("n01");
        assertEquals(new TopoPos(100, 39, -60), p.anchor());
    }

    @Test
    void connectorResolvesToDeclaredCell() {
        // Anchor (100,39,-60) + connector (2,1,0) = door bottom at feet level.
        List<TopoPos> connectors = place().connectors("n01");
        assertEquals(List.of(new TopoPos(102, 40, -60)), connectors);
    }

    @Test
    void reserveCoversFootprintPlusMargin() {
        LandmarkLayout layout = place();
        // Interior wall cell.
        assertTrue(layout.isReserved(new TopoPos(100, 40, -60)));
        // Margin ring cell outside the footprint (halfZ 1 + margin 1 = 2).
        assertTrue(layout.isReserved(new TopoPos(100, 40, -58)));
        // Beyond the margin.
        assertFalse(layout.isReserved(new TopoPos(100, 40, -57)));
    }

    @Test
    void openingsPermitDoorAndApproachOnly() {
        LandmarkLayout layout = place();
        TopoPos door = new TopoPos(102, 40, -60);
        TopoPos doorHead = new TopoPos(102, 41, -60);
        TopoPos approach = new TopoPos(103, 40, -60); // margin cell in front
        TopoPos approachHead = new TopoPos(103, 41, -60);
        assertFalse(layout.isWriteProtected(door));
        assertFalse(layout.isWriteProtected(doorHead));
        assertFalse(layout.isWriteProtected(approach));
        assertFalse(layout.isWriteProtected(approachHead));
        // A margin cell NOT on the door line stays protected.
        assertTrue(layout.isWriteProtected(new TopoPos(103, 40, -59)));
        // Reserved regardless of opening for ore/motif writes.
        assertTrue(layout.isReserved(door));
    }

    @Test
    void connectorTargetChoosesNearestDeclared() {
        LandmarkLayout layout = place();
        assertNull(layout.connectorTarget("n99", FEET));
        assertEquals(new TopoPos(102, 40, -60),
                layout.connectorTarget("n01", new TopoPos(140, 40, -60)));
    }

    @Test
    void failsClosedOnMissingDefinitionOrAnchor() {
        assertThrows(IllegalStateException.class,
                () -> LandmarkLayout.place(Map.of("n01", FEET), Map.of("n01", "ghost"),
                        LandmarkCatalog.definitions()));
        assertThrows(IllegalStateException.class,
                () -> LandmarkLayout.place(Map.of(), Map.of("n01", "shelter"),
                        LandmarkCatalog.definitions()));
    }

    @Test
    void emptyLayoutProtectsNothing() {
        LandmarkLayout empty = LandmarkLayout.empty();
        assertFalse(empty.isReserved(FEET));
        assertFalse(empty.isWriteProtected(FEET));
        assertTrue(empty.connectors("n01").isEmpty());
        assertNull(empty.connectorTarget("n01", FEET));
    }
}
