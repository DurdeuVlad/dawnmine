package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.dwurdy.dawnmine.generation.MotifCatalog.Motif;
import org.junit.jupiter.api.Test;

/** Parser contract for {@code data/dawnmine/motifs/*.json}: parametric
 * rule sets with abstract block slots, fail-closed on every malformed
 * shape - a bad motif must never crash generation. */
class MotifCatalogTest {

    private static JsonObject fixture() {
        return JsonParser.parseString("""
                {
                  "motif": "test_rubble",
                  "appliesTo": ["edge", "node"],
                  "regions": ["upper", "middle"],
                  "spacing": [8, 16],
                  "chance": 0.5,
                  "elements": [
                    {
                      "type": "scatter",
                      "blockSlot": "rubble",
                      "countRange": [2, 4],
                      "offsetRule": "floor",
                      "radius": 2,
                      "chance": 0.8
                    },
                    {
                      "type": "vein",
                      "blockSlot": "ore_slot",
                      "countRange": [1, 2],
                      "offsetRule": "wall",
                      "radius": 3
                    }
                  ]
                }
                """).getAsJsonObject();
    }

    @Test
    void parsesParametricRuleSet() {
        Motif motif = MotifCatalog.parse(fixture());
        assertEquals("test_rubble", motif.id());
        assertEquals(2, motif.appliesTo().size());
        assertEquals(2, motif.regions().size());
        assertEquals(8, motif.spacing().min());
        assertEquals(16, motif.spacing().max());
        assertEquals(0.5, motif.chance());
        assertEquals(2, motif.elements().size());
        assertEquals(MotifCatalog.ElementType.SCATTER, motif.elements().get(0).type());
        assertEquals(MotifCatalog.BlockSlot.RUBBLE, motif.elements().get(0).slot());
        assertEquals(MotifCatalog.OffsetRule.WALL, motif.elements().get(1).offset());
    }

    @Test
    void defaultsRegionsToAllWhenAbsent() {
        JsonObject json = fixture();
        json.remove("regions");
        Motif motif = MotifCatalog.parse(json);
        assertEquals(3, motif.regions().size());
    }

    @Test
    void rejectsUnknownAppliesToToken() {
        JsonObject json = fixture();
        json.getAsJsonArray("appliesTo").add("void");
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> MotifCatalog.parse(json));
        assertTrue(e.getMessage().contains("appliesTo"));
    }

    @Test
    void rejectsUnknownRegion() {
        JsonObject json = fixture();
        json.getAsJsonArray("regions").add("nether");
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> MotifCatalog.parse(json));
        assertTrue(e.getMessage().contains("region"));
    }

    @Test
    void rejectsInvertedSpacing() {
        JsonObject json = fixture();
        com.google.gson.JsonArray spacing = new com.google.gson.JsonArray();
        spacing.add(20); spacing.add(8);
        json.add("spacing", spacing);
        assertThrows(JsonParseException.class, () -> MotifCatalog.parse(json));
    }

    @Test
    void rejectsConcreteBlockIdSlot() {
        JsonObject json = fixture();
        json.getAsJsonArray("elements").get(0).getAsJsonObject()
                .addProperty("blockSlot", "minecraft:diamond_block");
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> MotifCatalog.parse(json));
        assertTrue(e.getMessage().contains("blockSlot"));
    }

    @Test
    void rejectsOreSlotOutsideWalls() {
        JsonObject json = fixture();
        json.getAsJsonArray("elements").get(1).getAsJsonObject()
                .addProperty("offsetRule", "floor");
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> MotifCatalog.parse(json));
        assertTrue(e.getMessage().contains("ore_slot"));
    }

    @Test
    void rejectsBlockingKindOnPath() {
        JsonObject json = fixture();
        json.getAsJsonArray("elements").get(0).getAsJsonObject()
                .addProperty("offsetRule", "path");
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> MotifCatalog.parse(json));
        assertTrue(e.getMessage().contains("path"));
    }

    @Test
    void rejectsEmptyElements() {
        JsonObject json = fixture();
        json.add("elements", new com.google.gson.JsonArray());
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> MotifCatalog.parse(json));
        assertTrue(e.getMessage().contains("elements"));
    }

    @Test
    void rejectsBadMotifId() {
        JsonObject json = fixture();
        json.addProperty("motif", "Has Spaces");
        assertThrows(JsonParseException.class, () -> MotifCatalog.parse(json));
    }

    @Test
    void shippedMotifsParse() throws Exception {
        // The bundled rule sets must always be valid: they are what the
        // production motif pass actually places.
        for (String name : new String[] {"collapsed_support", "rubble_vein", "abandoned_cart"}) {
            try (var in = MotifCatalogTest.class.getResourceAsStream(
                    "/data/dawnmine/motifs/" + name + ".json")) {
                JsonObject json = JsonParser.parseString(
                        new String(in.readAllBytes())).getAsJsonObject();
                Motif motif = MotifCatalog.parse(json);
                assertEquals(name, motif.id());
            }
        }
    }
}
