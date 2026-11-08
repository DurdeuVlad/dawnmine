package com.dwurdy.dawnmine.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.dwurdy.dawnmine.generation.LandmarkCatalog.Definition;
import org.junit.jupiter.api.Test;

/** Parser contract for {@code data/dawnmine/landmarks/*.json}: literal
 * per-layer grids with declared connectors, fail-closed on every malformed
 * shape - never a silent substitute room. */
class LandmarkCatalogTest {

    private static JsonObject fixture() {
        return JsonParser.parseString("""
                {
                  "id": "prospect_shelter",
                  "footprint": { "halfX": 2, "halfZ": 1, "height": 3, "reserveMargin": 1 },
                  "connectors": [
                    { "x": 2, "y": 1, "z": 0, "facing": "east" }
                  ],
                  "slots": {
                    "S": "minecraft:stone_bricks",
                    "P": "minecraft:oak_planks",
                    "A": "minecraft:air",
                    "T": "minecraft:wall_torch[facing=south]"
                  },
                  "layers": [
                    [ "SSSSS", "SPPPS", "SSSSS" ],
                    [ "SSSSS", "SAAAA", "SSSSA" ],
                    [ "SSSSS", "SATAA", "SSSSA" ]
                  ]
                }
                """).getAsJsonObject();
    }

    @Test
    void parsesLiteralGridWithConnectors() {
        Definition def = LandmarkCatalog.parse(fixture());
        assertEquals("prospect_shelter", def.id());
        assertEquals(2, def.halfX());
        assertEquals(1, def.halfZ());
        assertEquals(3, def.height());
        assertEquals(1, def.reserveMargin());
        assertEquals(1, def.connectors().size());
        assertEquals(2, def.connectors().get(0).x());
        assertEquals("east", def.connectors().get(0).facing());
        assertEquals(3, def.layers().size());
        assertEquals("SSSSS", def.layers().get(0).get(0));
        assertEquals("minecraft:wall_torch[facing=south]", def.slots().get('T'));
    }

    @Test
    void rejectsInteriorConnector() {
        JsonObject json = fixture();
        json.getAsJsonArray("connectors").get(0).getAsJsonObject()
                .addProperty("x", 0);
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> LandmarkCatalog.parse(json));
        assertTrue(e.getMessage().contains("not on the footprint boundary"));
    }

    @Test
    void rejectsUndeclaredSlotInLayer() {
        JsonObject json = fixture();
        json.getAsJsonArray("layers").get(1).getAsJsonArray()
                .set(1, new com.google.gson.JsonPrimitive("SAZAS"));
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> LandmarkCatalog.parse(json));
        assertTrue(e.getMessage().contains("undeclared slot"));
    }

    @Test
    void rejectsWrongLayerCount() {
        JsonObject json = fixture();
        json.getAsJsonArray("layers").remove(2);
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> LandmarkCatalog.parse(json));
        assertTrue(e.getMessage().contains("footprint height"));
    }

    @Test
    void rejectsWrongRowWidth() {
        JsonObject json = fixture();
        json.getAsJsonArray("layers").get(0).getAsJsonArray()
                .set(0, new com.google.gson.JsonPrimitive("SSSS"));
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> LandmarkCatalog.parse(json));
        assertTrue(e.getMessage().contains("columns"));
    }

    @Test
    void rejectsInvalidBlockId() {
        JsonObject json = fixture();
        json.getAsJsonObject("slots").addProperty("S", "not a block");
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> LandmarkCatalog.parse(json));
        assertTrue(e.getMessage().contains("invalid block state"));
    }

    @Test
    void rejectsMissingConnectors() {
        JsonObject json = fixture();
        json.remove("connectors");
        assertThrows(JsonParseException.class, () -> LandmarkCatalog.parse(json));
    }

    @Test
    void shippedProspectShelterParses() throws Exception {
        // The bundled definition must always be valid: it is the room
        // safehouse nodes actually build.
        try (var in = LandmarkCatalogTest.class.getResourceAsStream(
                "/data/dawnmine/landmarks/prospect_shelter.json")) {
            JsonObject json = JsonParser.parseString(
                    new String(in.readAllBytes())).getAsJsonObject();
            Definition def = LandmarkCatalog.parse(json);
            assertEquals("prospect_shelter", def.id());
            assertEquals(5, def.layers().size());
            assertEquals(1, def.connectors().size());
        }
    }
}
