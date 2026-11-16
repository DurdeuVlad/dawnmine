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
import java.util.List;
import org.junit.jupiter.api.Test;

class MineEncounterCatalogTest {
    private static MineEncounterCatalog.Profile bundled() throws Exception {
        InputStream input = MineEncounterCatalogTest.class.getResourceAsStream(
                "/data/dawnmine/encounters/standard.json");
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
        assertEquals(600, profile.totalPopulationCap());
        assertEquals(List.of("upper", "middle", "chaos", "nether"),
                List.of("upper", "middle", "chaos", "nether").stream()
                        .filter(id -> profile.region(id) != null).toList());
        for (String regionId : List.of("upper", "middle", "chaos", "nether")) {
            MineEncounterCatalog.Region region = profile.region(regionId);
            assertTrue(region.compositions().size() >= 2);
            for (MineEncounterCatalog.Composition composition : region.compositions()) {
                assertTrue(!composition.threat().isBlank());
                assertTrue(!composition.counterplay().isBlank());
                assertTrue(composition.maxPopulation() <= region.populationCap());
                assertTrue(!composition.entities().isEmpty(),
                        "composition " + composition.id() + " has no always-available base entities");
            }
        }
    }

    @Test
    void bundledProfileCarriesExplicitLevelsThatDeepenByRegion() throws Exception {
        MineEncounterCatalog.Profile profile = bundled();
        int previousMinimum = 0;
        for (String regionId : List.of("upper", "middle", "chaos", "nether")) {
            int regionMinimum = profile.region(regionId).compositions().stream()
                    .mapToInt(MineEncounterCatalog.Composition::level).min().orElseThrow();
            assertTrue(regionMinimum > previousMinimum,
                    "region " + regionId + " does not start deeper than the previous band");
            previousMinimum = regionMinimum;
            for (MineEncounterCatalog.Composition composition : profile.region(regionId).compositions()) {
                assertTrue(composition.level() >= 1 && composition.level() <= 16,
                        "composition " + composition.id() + " has an out-of-range level");
            }
        }
    }

    /** M14c: the pair the owner's depth-escalation ask flagged as most at risk of feeling
     * identical - chaos already uses wither_skeleton/blaze, so nether's own roster must still
     * read as noticeably more mobs, higher level, and at least as varied, not just "the same
     * threat with different names." */
    @Test
    void netherEscalatesOverChaosInPopulationAndLevel() throws Exception {
        MineEncounterCatalog.Profile profile = bundled();
        MineEncounterCatalog.Region chaos = profile.region("chaos");
        MineEncounterCatalog.Region nether = profile.region("nether");
        double chaosAvgPop = chaos.compositions().stream()
                .mapToInt(MineEncounterCatalog.Composition::population).average().orElseThrow();
        double netherAvgPop = nether.compositions().stream()
                .mapToInt(MineEncounterCatalog.Composition::population).average().orElseThrow();
        assertTrue(netherAvgPop > chaosAvgPop,
                "nether average population " + netherAvgPop + " does not exceed chaos's " + chaosAvgPop);
        double chaosAvgLevel = chaos.compositions().stream()
                .mapToInt(MineEncounterCatalog.Composition::level).average().orElseThrow();
        double netherAvgLevel = nether.compositions().stream()
                .mapToInt(MineEncounterCatalog.Composition::level).average().orElseThrow();
        assertTrue(netherAvgLevel > chaosAvgLevel,
                "nether average level " + netherAvgLevel + " does not exceed chaos's " + chaosAvgLevel);
        java.util.Set<net.minecraft.resources.ResourceLocation> chaosEntities = chaos.compositions().stream()
                .flatMap(c -> c.entities().stream()).map(MineEncounterCatalog.EntityCount::entityId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<net.minecraft.resources.ResourceLocation> netherEntities = nether.compositions().stream()
                .flatMap(c -> c.entities().stream()).map(MineEncounterCatalog.EntityCount::entityId)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(netherEntities.size() >= chaosEntities.size(),
                "nether has fewer distinct base entity types (" + netherEntities.size()
                        + ") than chaos (" + chaosEntities.size() + ")");
        // The exact overlap the owner's planning conversation flagged as a risk: nether's own
        // roster should read as distinctly nether-themed, not a repeat of chaos's signature mobs.
        assertFalse(netherEntities.contains(net.minecraft.resources.ResourceLocation.parse("minecraft:wither_skeleton")),
                "nether roster reuses chaos's wither_skeleton");
        assertFalse(netherEntities.contains(net.minecraft.resources.ResourceLocation.parse("minecraft:blaze")),
                "nether roster reuses chaos's blaze");
    }

    @Test
    void missingAndOutOfRangeLevelAreRejected() {
        String validComposition = "{\"id\":\"x\",\"weight\":1,\"level\":3,\"threat\":\"t\","
                + "\"counterplay\":\"c\",\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":1}]}";
        String profile = "{\"id\":\"standard\",\"totalPopulationCap\":4,\"regions\":["
                + "{\"id\":\"upper\",\"populationCap\":2,\"compositions\":[" + validComposition + "]},"
                + "{\"id\":\"middle\",\"populationCap\":2,\"compositions\":[" + validComposition + "]},"
                + "{\"id\":\"chaos\",\"populationCap\":2,\"compositions\":[" + validComposition + "]},"
                + "{\"id\":\"nether\",\"populationCap\":2,\"compositions\":[" + validComposition + "]}]}";
        assertEquals(3, MineEncounterCatalog.parse(JsonParser.parseString(profile)
                .getAsJsonObject()).region("upper").compositions().get(0).level());
        String missingLevel = profile.replace("\"level\":3,", "");
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString(missingLevel).getAsJsonObject()));
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString(profile.replace("\"level\":3", "\"level\":0"))
                        .getAsJsonObject()));
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString(profile.replace("\"level\":3", "\"level\":17"))
                        .getAsJsonObject()));
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString(profile.replace("\"level\":3", "\"level\":\"high\""))
                        .getAsJsonObject()));
    }

    @Test
    void bonusEntitiesAreOptionalAndCountTowardTheCap() {
        String composition = "{\"id\":\"x\",\"weight\":1,\"level\":1,\"threat\":\"t\",\"counterplay\":\"c\","
                + "\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":1}],"
                + "\"bonusEntities\":[{\"id\":\"born_in_chaos_v1:bone_imp\",\"count\":1}]}";
        String profile = "{\"id\":\"standard\",\"totalPopulationCap\":4,\"regions\":["
                + "{\"id\":\"upper\",\"populationCap\":2,\"compositions\":[" + composition + "]},"
                + "{\"id\":\"middle\",\"populationCap\":2,\"compositions\":[" + composition + "]},"
                + "{\"id\":\"chaos\",\"populationCap\":2,\"compositions\":[" + composition + "]},"
                + "{\"id\":\"nether\",\"populationCap\":2,\"compositions\":[" + composition + "]}]}";
        MineEncounterCatalog.Composition parsed = MineEncounterCatalog.parse(
                JsonParser.parseString(profile).getAsJsonObject())
                .region("upper").compositions().get(0);
        assertEquals(1, parsed.population());
        assertEquals(2, parsed.maxPopulation());
        // Base + bonus population must still respect the region cap (2 here).
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString(profile.replace(
                        "\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":1}]",
                        "\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":2}]"))
                        .getAsJsonObject()));
    }

    @Test
    void colonAndUppercaseCompositionIdsAreRejected() {
        String validComposition = "{\"id\":\"x\",\"weight\":1,\"level\":3,\"threat\":\"t\","
                + "\"counterplay\":\"c\",\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":1}]}";
        String profile = "{\"id\":\"standard\",\"totalPopulationCap\":4,\"regions\":["
                + "{\"id\":\"upper\",\"populationCap\":2,\"compositions\":[" + validComposition + "]},"
                + "{\"id\":\"middle\",\"populationCap\":2,\"compositions\":[" + validComposition + "]},"
                + "{\"id\":\"chaos\",\"populationCap\":2,\"compositions\":[" + validComposition + "]}]}";
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString(profile.replace("\"id\":\"x\"",
                        "\"id\":\"boss:tier1\"")).getAsJsonObject()));
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString(profile.replace("\"id\":\"x\"",
                        "\"id\":\"Boss\"")).getAsJsonObject()));
    }

    @Test
    void invalidCompositionAboveCapAndMissingDepthRegionAreRejected() {
        String valid = "{\"id\":\"standard\",\"totalPopulationCap\":4,\"regions\":["
                + "{\"id\":\"upper\",\"populationCap\":2,\"compositions\":["
                + "{\"id\":\"x\",\"weight\":1,\"level\":1,\"threat\":\"t\",\"counterplay\":\"c\","
                + "\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":3}]}]},"
                + "{\"id\":\"middle\",\"populationCap\":2,\"compositions\":["
                + "{\"id\":\"x\",\"weight\":1,\"level\":1,\"threat\":\"t\",\"counterplay\":\"c\","
                + "\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":1}]}]},"
                + "{\"id\":\"chaos\",\"populationCap\":2,\"compositions\":["
                + "{\"id\":\"x\",\"weight\":1,\"level\":1,\"threat\":\"t\",\"counterplay\":\"c\","
                + "\"entities\":[{\"id\":\"minecraft:zombie\",\"count\":1}]}]}]}";
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString(valid).getAsJsonObject()));
        assertThrows(JsonParseException.class, () -> MineEncounterCatalog.parse(
                JsonParser.parseString("{\"id\":\"standard\",\"totalPopulationCap\":4,"
                        + "\"regions\":[]}").getAsJsonObject()));
    }
}
