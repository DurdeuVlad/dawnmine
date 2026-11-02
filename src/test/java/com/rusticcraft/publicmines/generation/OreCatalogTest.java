package com.rusticcraft.publicmines.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class OreCatalogTest {
    private static final ResourceLocation RESOURCE_A = id("publicmines:test/a");
    private static final ResourceLocation RESOURCE_B = id("publicmines:test/b");

    @Test
    void reloadSnapshotsTrackTierWeightsAndFingerprintChanges() {
        OreCatalog.OreCatalogSnapshot first = OreCatalog.parseSnapshot(Map.of(
                RESOURCE_A, resource("minecraft:coal_ore", "upper", 4),
                RESOURCE_B, resource("minecraft:gold_ore", "middle", 2)));
        OreCatalog.OreCatalogSnapshot reloaded = OreCatalog.parseSnapshot(Map.of(
                RESOURCE_A, resource("minecraft:coal_ore", "upper", 5),
                RESOURCE_B, resource("minecraft:gold_ore", "middle", 2),
                id("publicmines:test/c"), resource("minecraft:diamond_ore", "chaos", 1)));

        assertEquals(List.of("coal_ore"), first.entries(OreCatalog.Tier.UPPER).stream()
                .map(entry -> entry.blockId().getPath()).toList());
        assertEquals(3, reloaded.entries().size());
        assertFalse(first.fingerprint().equals(reloaded.fingerprint()));
        assertTrue(reloaded.completeness(Set.of(), Set.of()).unassignedTiers().isEmpty());
    }

    @Test
    void completenessReportsMissingCommonTagIdsAndUnavailableCatalogIds() {
        OreCatalog.OreCatalogSnapshot snapshot = OreCatalog.parseSnapshot(Map.of(
                RESOURCE_A, resource("minecraft:coal_ore", "upper", 4),
                RESOURCE_B, resource("minecraft:gold_ore", "middle", 2),
                id("publicmines:test/c"), resource("minecraft:diamond_ore", "chaos", 1)));
        ResourceLocation missingCommon = id("examplemod:unassigned_ore");
        Set<ResourceLocation> commonOres = Set.of(id("minecraft:coal_ore"), missingCommon);
        Set<ResourceLocation> registered = Set.of(id("minecraft:coal_ore"), id("minecraft:diamond_ore"));

        OreCatalog.Completeness completeness = snapshot.completeness(commonOres, registered);

        assertEquals(List.of("examplemod:unassigned_ore"), completeness.missingOreTagEntries());
        assertEquals(List.of("minecraft:gold_ore"), completeness.unavailableCatalogEntries());
        assertEquals(2, completeness.registeredEntries());
        assertFalse(completeness.complete());
    }

    @Test
    void completenessReportsMissingEncounterEntityIds() {
        ResourceLocation encounterEntity = id("born_in_chaos_v1:diamond_termite");
        OreCatalog.OreCatalogSnapshot snapshot = OreCatalog.parseSnapshot(Map.of(
                RESOURCE_A, resource("minecraft:coal_ore", "upper", 4),
                RESOURCE_B, resource("minecraft:gold_ore", "middle", 2),
                id("publicmines:test/c"), resource("minecraft:diamond_ore", "chaos", 1),
                id("publicmines:test/encounter"), encounter("born_in_chaos_v1:infected_diamond_ore",
                        "chaos", encounterEntity.toString())));
        Set<ResourceLocation> blocks = Set.of(id("minecraft:coal_ore"), id("minecraft:gold_ore"),
                id("minecraft:diamond_ore"), id("born_in_chaos_v1:infected_diamond_ore"));

        OreCatalog.Completeness missing = snapshot.completeness(Set.of(), blocks, Set.of());
        OreCatalog.Completeness available = snapshot.completeness(Set.of(), blocks, Set.of(encounterEntity),
                Set.of(id("minecraft:raw_iron")));

        assertEquals(List.of(encounterEntity.toString()), missing.unavailableEncounterEntities());
        assertFalse(missing.complete());
        assertTrue(available.unavailableEncounterEntities().isEmpty());
        assertTrue(available.complete());
    }

    @Test
    void completenessReportsEveryUnavailableNativeDropItemId() {
        OreCatalog.OreCatalogSnapshot snapshot = OreCatalog.parseSnapshot(Map.of(
                RESOURCE_A, resource("minecraft:coal_ore", "upper", 4),
                RESOURCE_B, resource("minecraft:gold_ore", "middle", 2),
                id("publicmines:test/c"), resource("minecraft:diamond_ore", "chaos", 1)));
        Set<ResourceLocation> blocks = Set.of(id("minecraft:coal_ore"), id("minecraft:gold_ore"),
                id("minecraft:diamond_ore"));

        OreCatalog.Completeness missing = snapshot.completeness(Set.of(), blocks, Set.of(), Set.of());
        OreCatalog.Completeness available = snapshot.completeness(Set.of(), blocks, Set.of(),
                Set.of(id("minecraft:raw_iron")));

        assertEquals(List.of("minecraft:raw_iron"), missing.unavailableCatalogDropItems());
        assertFalse(missing.complete());
        assertTrue(available.unavailableCatalogDropItems().isEmpty());
        assertTrue(available.complete());
    }

    @Test
    void duplicateIdsAndMalformedOrEmptyTiersAreReportedAsErrors() {
        OreCatalog.OreCatalogSnapshot duplicate = OreCatalog.parseSnapshot(Map.of(
                RESOURCE_A, resource("minecraft:coal_ore", "upper", 4),
                RESOURCE_B, resource("minecraft:coal_ore", "upper", 5)));
        OreCatalog.OreCatalogSnapshot malformed = OreCatalog.parseSnapshot(Map.of(
                RESOURCE_A, JsonParser.parseString("{\"schema\":1,\"ores\":[{\"block\":\"bad id\"}]}") ));

        OreCatalog.Completeness duplicateStatus = duplicate.completeness(Set.of(), Set.of());
        assertTrue(duplicateStatus.catalogErrors().stream().anyMatch(error -> error.contains("duplicate catalog entry")));
        assertTrue(duplicateStatus.unassignedTiers().containsAll(
                List.of("middle", "chaos")));
        assertEquals(1, malformed.completeness(Set.of(), Set.of()).catalogErrors().size());
        assertNotEquals("", malformed.fingerprint());
    }

    private static ResourceLocation id(String value) {
        return ResourceLocation.parse(value);
    }

    private static JsonElement resource(String block, String tier, int weight) {
        return JsonParser.parseString("{\"schema\":1,\"ores\":[{"
                + "\"block\":\"" + block + "\",\"tier\":\"" + tier
                + "\",\"weight\":" + weight + ",\"max_vein_size\":2,"
                + "\"requires_correct_tool\":true,"
                + "\"minimum_tested_tool\":\"minecraft:iron_pickaxe\","
                + "\"native_drop_items\":[\"minecraft:raw_iron\"]}]} ");
    }

    private static JsonElement encounter(String block, String tier, String entity) {
        return JsonParser.parseString("{\"schema\":1,\"ores\":[{"
                + "\"block\":\"" + block + "\",\"tier\":\"" + tier + "\","
                + "\"kind\":\"encounter\",\"weight\":1,\"max_vein_size\":1,"
                + "\"requires_correct_tool\":true,"
                + "\"minimum_tested_tool\":\"minecraft:diamond_pickaxe\","
                + "\"native_drop_items\":[],\"encounter_entity\":\"" + entity + "\"}]} ");
    }
}
