package com.rusticcraft.publicmines.encounter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MineEncounterCatalogTest {
    private static MineEncounterCatalog.Profile bundled() throws Exception {
        InputStream input = MineEncounterCatalogTest.class.getResourceAsStream(
                "/data/publicmines/encounters/standard.json");
        if (input == null) throw new AssertionError("bundled encounter profile is missing");
        try (input) {
            return MineEncounterCatalog.parse(JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    @Test
    void bundledProfileHasDepthSpecificBoundedCounterplayTables() throws Exception {
        MineEncounterCatalog.Profile profile = bundled();
        assertEquals("standard", profile.id());
        assertEquals(28, profile.totalPopulationCap());
        assertEquals(List.of("upper", "middle", "chaos"),
                List.of("upper", "middle", "chaos").stream()
                        .filter(id -> profile.region(id) != null).toList());
        for (String regionId : List.of("upper", "middle", "chaos")) {
            MineEncounterCatalog.Region region = profile.region(regionId);
            assertTrue(region.compositions().size() >= 2);
            for (MineEncounterCatalog.Composition composition : region.compositions()) {
                assertTrue(!composition.threat().isBlank());
                assertTrue(!composition.counterplay().isBlank());
                assertTrue(composition.population() <= region.populationCap());
            }
        }
    }

    @Test
    void invalidCompositionAboveCapAndMissingDepthRegionAreRejected() {
        String valid = "{\"id\":\"standard\",\"totalPopulationCap\":4,\"regions\":["
                + "{\"id\":\"upper\",\"populationCap\":2,\"compositions\":["
                + "{\"id\":\"x\",\"weight\":1,\"threat\":\"t\",\"counterplay\":\"c\","
                + "\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":3}]}]},"
                + "{\"id\":\"middle\",\"populationCap\":2,\"compositions\":["
                + "{\"id\":\"x\",\"weight\":1,\"threat\":\"t\",\"counterplay\":\"c\","
                + "\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":1}]}]},"
                + "{\"id\":\"chaos\",\"populationCap\":2,\"compositions\":["
                + "{\"id\":\"x\",\"weight\":1,\"threat\":\"t\",\"counterplay\":\"c\","
                + "\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":1}]}]}]}";
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString(valid).getAsJsonObject()));
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString("{\"id\":\"standard\",\"totalPopulationCap\":4,"
                        + "\"regions\":[]}").getAsJsonObject()));
    }
}
