package com.rusticcraft.publicmines.topology;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Data-driven description of how a run's topology is laid out: geometry
 * parameters plus an ordered list of depth regions, shallowest first.
 *
 * <p>Profiles are loaded from {@code data/publicmines/topology_profiles/*.json}
 * (see {@link TopologyProfiles}); region counts and sizes can be tuned by a
 * datapack without code changes. {@link #validate()} is run before planning
 * so a broken profile fails loudly instead of producing a broken mine.
 */
public record TopologyProfile(
        String id,
        int boundsMargin,
        int corridorHalfWidth,
        int minStep,
        int maxStep,
        int maxTurnDeg,
        int loopMaxDistance,
        int maxNodes,
        int maxCorridorLength,
        int branchMaxLength,
        List<RegionSpec> regions) {

    /** Hard ceiling on a profile's worst-case graph size before planning. */
    private static final long MAX_PLANNED_NODES = 4096;
    /** Loop edges do not add nodes, so bound their retry work separately. */
    private static final long MAX_LOOP_COUNT = 256;

    /** One depth band of the mine, shallowest region first. Y values are
     * absolute block coordinates inside the mine dimension. */
    public record RegionSpec(
            String id,
            String name,
            int topY,
            int bottomY,
            int mainRouteNodes,
            int caverns,
            int branches,
            int loops,
            int pockets,
            int resourceFields,
            int landmarks,
            int checkpoints) {}

    public long totalMainNodes() {
        long total = 0;
        for (RegionSpec region : regions) {
            total = saturatedAdd(total, Math.max(0, region.mainRouteNodes()));
        }
        return total;
    }

    /** Conservative upper bound on nodes: entrance, full main route, every
     * possible branch step, and every pocket/resource field. */
    public long maximumPlannedNodes() {
        long total = saturatedAdd(1, totalMainNodes()); // entrance shaft
        for (RegionSpec region : regions) {
            total = saturatedAdd(total, saturatedMultiply(
                    Math.max(0, region.branches()), Math.max(0, branchMaxLength)));
            total = saturatedAdd(total, Math.max(0, region.pockets()));
            total = saturatedAdd(total, Math.max(0, region.resourceFields()));
        }
        return total;
    }

    /** Structural validation of the loaded data. Planning additionally checks
     * the bands against actual bounds, so a profile that is coherent but too
     * deep for the dimension still fails before anything is placed. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (id == null || id.isBlank()) {
            problems.add("profile id is blank");
        }
        if (regions.isEmpty()) {
            problems.add("profile has no regions");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < regions.size(); i++) {
            RegionSpec r = regions.get(i);
            if (r.id() == null || r.id().isBlank()) {
                problems.add("region " + i + " has a blank id");
            } else if (!ids.add(r.id())) {
                problems.add("duplicate region id '" + r.id() + "'");
            }
            if (r.topY() <= r.bottomY()) {
                problems.add("region '" + r.id() + "' band is inverted (topY "
                        + r.topY() + " <= bottomY " + r.bottomY() + ")");
            }
            if (i + 1 < regions.size() && regions.get(i + 1).topY() > r.bottomY()) {
                problems.add("region bands overlap or are out of order at '" + r.id()
                        + "' -> '" + regions.get(i + 1).id() + "'");
            }
            if (r.mainRouteNodes() < 1) {
                problems.add("region '" + r.id() + "' needs at least 1 main route node");
            }
            if (r.caverns() < 0 || r.branches() < 0 || r.loops() < 0 || r.pockets() < 0
                    || r.resourceFields() < 0 || r.landmarks() < 0 || r.checkpoints() < 0) {
                problems.add("region '" + r.id() + "' has a negative count");
            }
        }
        for (String required : List.of("upper", "middle", "chaos")) {
            if (!ids.contains(required)) {
                problems.add("profile is missing required '" + required + "' depth tier");
            }
        }
        int upperIndex = regionIndex("upper");
        int middleIndex = regionIndex("middle");
        int chaosIndex = regionIndex("chaos");
        if (upperIndex >= 0 && middleIndex >= 0 && chaosIndex >= 0
                && !(upperIndex < middleIndex && middleIndex < chaosIndex)) {
            problems.add("required depth tiers must be ordered upper -> middle -> chaos");
        }
        if (chaosIndex >= 0 && chaosIndex != regions.size() - 1) {
            problems.add("Chaos Depths must be the deepest (last) region");
        }
        RegionSpec chaos = regions.stream().filter(r -> "chaos".equals(r.id()))
                .findFirst().orElse(null);
        if (chaos != null && chaos.topY() >= 0) {
            problems.add("Chaos Depths tier must be below y=0 for Nether-themed progression");
        }
        long loopCount = 0;
        for (int i = 0; i < regions.size(); i++) {
            RegionSpec r = regions.get(i);
            loopCount = saturatedAdd(loopCount, Math.max(0, r.loops()));
            if (r.caverns() > Math.max(0, r.mainRouteNodes())) {
                problems.add("region '" + r.id() + "' requests more caverns than main-route nodes");
            }
            long regionNodeBound = Math.max(0, r.mainRouteNodes());
            if (i == 0) {
                regionNodeBound = saturatedAdd(regionNodeBound, 1); // entrance shaft
            }
            regionNodeBound = saturatedAdd(regionNodeBound, saturatedMultiply(
                    Math.max(0, r.branches()), Math.max(0, branchMaxLength)));
            regionNodeBound = saturatedAdd(regionNodeBound, Math.max(0, r.pockets()));
            regionNodeBound = saturatedAdd(regionNodeBound, Math.max(0, r.resourceFields()));
            if (r.landmarks() > regionNodeBound) {
                problems.add("region '" + r.id() + "' requests more landmarks than its maximum node count");
            }
        }
        if (boundsMargin < 0 || corridorHalfWidth < 1) {
            problems.add("boundsMargin must be non-negative and corridorHalfWidth must be positive");
        }
        if (boundsMargin < corridorHalfWidth) {
            problems.add("boundsMargin " + boundsMargin
                    + " is smaller than corridorHalfWidth " + corridorHalfWidth);
        }
        if (minStep < 8) {
            problems.add("minStep " + minStep + " is below the 8-block floor");
        }
        if (maxStep < minStep) {
            problems.add("maxStep " + maxStep + " < minStep " + minStep);
        }
        if (maxTurnDeg <= 0 || maxTurnDeg > 180) {
            problems.add("maxTurnDeg " + maxTurnDeg + " must be in (0, 180]");
        }
        if (loopMaxDistance < minStep) {
            problems.add("loopMaxDistance " + loopMaxDistance + " < minStep " + minStep);
        }
        if (branchMaxLength < 1) {
            problems.add("branchMaxLength must be at least 1");
        }
        long nodeBound = maximumPlannedNodes();
        if (maxNodes < nodeBound) {
            problems.add("maxNodes " + maxNodes + " is below the conservative planned node bound "
                    + nodeBound + " (main route, branches, pockets, and resource fields)");
        }
        if (nodeBound > MAX_PLANNED_NODES) {
            problems.add("profile may plan " + nodeBound + " nodes, above the hard limit of "
                    + MAX_PLANNED_NODES);
        }
        if (loopCount > MAX_LOOP_COUNT) {
            problems.add("profile requests " + loopCount + " loops, above the hard limit of "
                    + MAX_LOOP_COUNT);
        }
        if (maxCorridorLength < saturatedMultiply(Math.max(0, maxStep),
                saturatedAdd(totalMainNodes(), 1))) {
            problems.add("maxCorridorLength " + maxCorridorLength
                    + " cannot fit the main route this profile requests");
        }
        return problems;
    }

    private int regionIndex(String id) {
        for (int i = 0; i < regions.size(); i++) {
            if (id.equals(regions.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0 || right == 0) {
            return 0;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    public static TopologyProfile fromJson(JsonObject json) throws JsonParseException {
        String id = json.has("id") ? json.get("id").getAsString() : "";
        JsonArray arr = json.getAsJsonArray("regions");
        if (arr == null) {
            throw new JsonParseException("topology profile is missing 'regions'");
        }
        List<RegionSpec> regions = new ArrayList<>();
        for (JsonElement element : arr) {
            JsonObject r = element.getAsJsonObject();
            regions.add(new RegionSpec(
                    r.get("id").getAsString(),
                    r.has("name") ? r.get("name").getAsString() : r.get("id").getAsString(),
                    r.get("topY").getAsInt(),
                    r.get("bottomY").getAsInt(),
                    r.get("mainRouteNodes").getAsInt(),
                    r.get("caverns").getAsInt(),
                    r.get("branches").getAsInt(),
                    r.has("loops") ? r.get("loops").getAsInt() : 0,
                    r.get("pockets").getAsInt(),
                    r.get("resourceFields").getAsInt(),
                    r.get("landmarks").getAsInt(),
                    r.get("checkpoints").getAsInt()));
        }
        TopologyProfile profile = new TopologyProfile(
                id,
                json.get("boundsMargin").getAsInt(),
                json.get("corridorHalfWidth").getAsInt(),
                json.get("minStep").getAsInt(),
                json.get("maxStep").getAsInt(),
                json.get("maxTurnDeg").getAsInt(),
                json.get("loopMaxDistance").getAsInt(),
                json.get("maxNodes").getAsInt(),
                json.get("maxCorridorLength").getAsInt(),
                json.get("branchMaxLength").getAsInt(),
                List.copyOf(regions));
        List<String> problems = profile.validate();
        if (!problems.isEmpty()) {
            throw new JsonParseException("invalid topology profile '"
                    + (id.isBlank() ? "?" : id) + "': " + String.join("; ", problems));
        }
        return profile;
    }

    /** Compiled-in copy of the bundled {@code standard.json}, used only when
     * no profile resource loaded at all (a datapack cannot delete the mod's
     * own file, so this is a last-resort guard, not a shadowing path). */
    public static TopologyProfile builtin() {
        return new TopologyProfile("standard",
                24, 3, 18, 44, 75, 160, 260, 40000, 3,
                List.of(
                        new RegionSpec("upper", "Upper Galleries", 56, 24,
                                12, 2, 4, 1, 5, 5, 2, 1),
                        new RegionSpec("middle", "Middle Descent", 24, -16,
                                14, 3, 5, 2, 6, 7, 2, 1),
                        new RegionSpec("chaos", "Chaos Depths", -16, -56,
                                12, 3, 4, 2, 6, 7, 3, 1)));
    }
}
