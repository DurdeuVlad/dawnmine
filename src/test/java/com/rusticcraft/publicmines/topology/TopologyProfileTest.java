package com.rusticcraft.publicmines.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bundled {@code standard.json} is the product's data-driven region
 * progression; these tests prove it parses, stays coherent with the
 * compiled-in fallback, and carries the required upper/middle/Chaos labels.
 */
class TopologyProfileTest {

    private static JsonObject bundledProfileJson() throws Exception {
        InputStream in = TopologyProfileTest.class.getResourceAsStream(
                "/data/publicmines/topology_profiles/standard.json");
        assertNotNull(in, "bundled topology profile resource is missing");
        return JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void bundledStandardProfileParses() throws Exception {
        TopologyProfile profile = TopologyProfile.fromJson(bundledProfileJson());
        assertEquals("standard", profile.id());
        assertEquals(List.of("upper", "middle", "chaos"),
                profile.regions().stream().map(TopologyProfile.RegionSpec::id).toList(),
                "progression must carry upper, middle, and Chaos region labels");
        assertTrue(profile.validate().isEmpty());
    }

    @Test
    void bundledProfileMatchesBuiltinFallback() throws Exception {
        // If a datapack shadowing removed the file, the built-in fallback must
        // produce byte-identical topology output, not a different mine.
        TopologyProfile loaded = TopologyProfile.fromJson(bundledProfileJson());
        assertEquals(loaded, TopologyProfile.builtin());
    }

    @Test
    void malformedProfileJsonIsRejected() {
        JsonObject missing = new JsonObject();
        assertThrows(JsonParseException.class, () -> TopologyProfile.fromJson(missing));
        JsonObject bad = JsonParser.parseString(
                "{\"id\":\"x\",\"boundsMargin\":24,\"corridorHalfWidth\":3,"
                        + "\"minStep\":18,\"maxStep\":44,\"maxTurnDeg\":75,"
                        + "\"loopMaxDistance\":160,\"maxNodes\":260,"
                        + "\"maxCorridorLength\":40000,\"branchMaxLength\":3,"
                        + "\"regions\":[{\"id\":\"a\",\"topY\":10,\"bottomY\":40,"
                        + "\"mainRouteNodes\":2,\"caverns\":0,\"branches\":0,"
                        + "\"pockets\":0,\"resourceFields\":0,\"landmarks\":0,"
                        + "\"checkpoints\":0}]}").getAsJsonObject();
        assertThrows(JsonParseException.class, () -> TopologyProfile.fromJson(bad));
    }

    @Test
    void profilesRequireUpperMiddleAndNetherThemedChaosDepthTiers() throws Exception {
        TopologyProfile standard = TopologyProfile.fromJson(bundledProfileJson());
        for (String required : List.of("upper", "middle", "chaos")) {
            List<TopologyProfile.RegionSpec> missingTier = standard.regions().stream()
                    .filter(region -> !region.id().equals(required)).toList();
            TopologyProfile incomplete = withRegions(missingTier);

            assertTrue(incomplete.validate().stream()
                    .anyMatch(problem -> problem.contains("missing required '" + required + "'")),
                    "profile without " + required + " tier was accepted: " + incomplete.validate());
        }

        List<TopologyProfile.RegionSpec> nonNegativeChaos = standard.regions().stream()
                .map(region -> region.id().equals("chaos")
                        ? new TopologyProfile.RegionSpec(region.id(), region.name(), 8, 1,
                                region.mainRouteNodes(), region.caverns(), region.branches(),
                                region.loops(), region.pockets(), region.resourceFields(),
                                region.landmarks(), region.checkpoints())
                        : region)
                .toList();
        assertTrue(withRegions(nonNegativeChaos).validate().stream()
                .anyMatch(problem -> problem.contains("Nether-themed progression")));
    }

    @Test
    void requiredDepthTiersMustRemainInShallowToDeepOrder() {
        TopologyProfile.RegionSpec upper = new TopologyProfile.RegionSpec(
                "upper", "Upper", 56, -16, 12, 2, 4, 1, 5, 5, 2, 1);
        TopologyProfile.RegionSpec chaos = new TopologyProfile.RegionSpec(
                "chaos", "Chaos", -17, -40, 14, 3, 5, 2, 6, 7, 2, 1);
        TopologyProfile.RegionSpec middle = new TopologyProfile.RegionSpec(
                "middle", "Middle", -41, -56, 12, 3, 4, 2, 6, 7, 3, 1);

        TopologyProfile wrongOrder = withRegions(List.of(upper, chaos, middle));
        assertTrue(wrongOrder.validate().stream()
                .anyMatch(problem -> problem.contains("upper -> middle -> chaos")),
                "profile with valid non-overlapping bands but wrong semantic tier order was accepted");

        TopologyProfile.RegionSpec shallowUpper = new TopologyProfile.RegionSpec(
                "upper", "Upper", 56, 24, 12, 2, 4, 1, 5, 5, 2, 1);
        TopologyProfile.RegionSpec interstitial = new TopologyProfile.RegionSpec(
                "transition", "Transition", 23, 16, 1, 0, 0, 0, 0, 0, 0, 0);
        TopologyProfile.RegionSpec lowerMiddle = new TopologyProfile.RegionSpec(
                "middle", "Middle", 15, -16, 14, 3, 5, 2, 6, 7, 2, 1);
        TopologyProfile.RegionSpec lowerChaos = new TopologyProfile.RegionSpec(
                "chaos", "Chaos", -17, -56, 12, 3, 4, 2, 6, 7, 3, 1);
        TopologyProfile withExtraRegion = withRegions(
                List.of(shallowUpper, interstitial, lowerMiddle, lowerChaos));
        assertTrue(withExtraRegion.validate().isEmpty(),
                "data-driven transition regions between required tiers should remain supported: "
                        + withExtraRegion.validate());

        TopologyProfile.RegionSpec deeperThanChaos = new TopologyProfile.RegionSpec(
                "abyss", "Abyss", -57, -72, 1, 0, 0, 0, 0, 0, 0, 0);
        TopologyProfile chaosNotDeepest = withRegions(
                List.of(shallowUpper, lowerMiddle, lowerChaos, deeperThanChaos));
        assertTrue(chaosNotDeepest.validate().stream()
                .anyMatch(problem -> problem.contains("Chaos Depths must be the deepest")),
                "profile with a region deeper than Chaos was accepted");
    }

    private static TopologyProfile withRegions(List<TopologyProfile.RegionSpec> regions) {
        TopologyProfile base = TopologyProfile.builtin();
        return new TopologyProfile("test", base.boundsMargin(), base.corridorHalfWidth(),
                base.minStep(), base.maxStep(), base.maxTurnDeg(), base.loopMaxDistance(),
                base.maxNodes(), base.maxCorridorLength(), base.branchMaxLength(), regions);
    }

    @Test
    void fallbackProfileSelectionIsStableAcrossInputOrder() {
        assertEquals("alpha", TopologyProfiles.selectActiveProfileId(
                new LinkedHashSet<>(List.of("zeta", "alpha"))));
        assertEquals("alpha", TopologyProfiles.selectActiveProfileId(
                new LinkedHashSet<>(List.of("alpha", "zeta"))));
        assertEquals("standard", TopologyProfiles.selectActiveProfileId(
                new LinkedHashSet<>(List.of("zeta", "standard", "alpha"))));
    }

    @Test
    void oversizedRouteCountsDoNotOverflowProfileBudgetValidation() {
        TopologyProfile huge = new TopologyProfile("huge", 24, 3, 18, 44, 75,
                160, Integer.MAX_VALUE, Integer.MAX_VALUE, 3,
                List.of(
                        new TopologyProfile.RegionSpec("upper", "U", 56, 24,
                                Integer.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0),
                        new TopologyProfile.RegionSpec("middle", "M", 23, -16,
                                Integer.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0),
                        new TopologyProfile.RegionSpec("chaos", "C", -17, -56,
                                1, 0, 0, 0, 0, 0, 0, 0)));

        assertTrue(huge.validate().stream().anyMatch(problem -> problem.contains("maxNodes")));
    }
}
