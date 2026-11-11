package com.dwurdy.dawnmine.census;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Plain results of one census, with a deterministic text rendering. */
public record CensusReport(
        int chunks, int openCells, int fluidCells, int oreBlocks, int mobs, int loot, int landmarks,
        int standable, int reachable, int[] standablePerBand, int[] unreachablePerBand,
        List<String> bandIds,
        long wallFaces, long oreFaces, long[] wallFacesPerBand, long[] oreFacesPerBand,
        Map<String, Integer> exposedOreByType,
        boolean startWalkable, int routeSteps, int[] routeStepsPerBand, int[] routeEnd,
        double walkBlocksPerSecond,
        int[] routeOrePerBand, int[] routeMobsPerBand, int[] routeLootPerBand,
        int[] routeLandmarksPerBand,
        int emptyAny, int emptyOre, int emptyMob, int emptyLoot, int emptyLandmark,
        List<int[]> unreachableSamples, double routeBlocks, List<String> waypointLines) {

    public int unreachable() { return standable - reachable; }

    public double rushSeconds() { return routeBlocks / walkBlocksPerSecond; }

    private static double per100(int count, int steps) {
        return steps == 0 ? 0 : 100.0 * count / steps;
    }

    /** Fixed-order, locale-independent lines; identical input gives identical text. */
    public List<String> lines() {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT,
                "scan: chunks=%d open=%d fluid=%d ore=%d mobs=%d loot=%d landmarks=%d",
                chunks, openCells, fluidCells, oreBlocks, mobs, loot, landmarks));
        out.add(String.format(Locale.ROOT, "walkable: standable=%d reachable=%d unreachable=%d",
                standable, reachable, unreachable()));
        if (!unreachableSamples.isEmpty()) {
            StringBuilder sb = new StringBuilder("unreachable samples (first 8 by packed order):");
            for (int[] c : unreachableSamples) {
                sb.append(" (").append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(')');
            }
            out.add(sb.toString());
        }
        for (int i = 0; i < bandIds.size(); i++) {
            out.add(String.format(Locale.ROOT,
                    "  band %s: standable=%d unreachable=%d wallFaces=%d oreFaces=%d oreShare=%.3f%%",
                    bandIds.get(i), standablePerBand[i], unreachablePerBand[i],
                    wallFacesPerBand[i], oreFacesPerBand[i],
                    wallFacesPerBand[i] == 0 ? 0.0 : 100.0 * oreFacesPerBand[i] / wallFacesPerBand[i]));
        }
        out.add(String.format(Locale.ROOT, "walls: faces=%d oreFaces=%d oreShare=%.3f%%",
                wallFaces, oreFaces, wallFaces == 0 ? 0.0 : 100.0 * oreFaces / wallFaces));
        Map<String, Integer> sorted = new TreeMap<>(exposedOreByType);
        StringBuilder ores = new StringBuilder("exposedOre:");
        sorted.forEach((k, v) -> ores.append(' ').append(k).append('=').append(v));
        out.add(ores.toString());
        if (!startWalkable) {
            out.add("route: start cell is not walkable - no route metrics");
            return out;
        }
        out.add(String.format(Locale.ROOT,
                "route(rush, hub to deepest reachable): steps=%d blocks=%.0f end=(%d,%d,%d) rush=%.1f min at %.1f blocks/s",
                routeSteps, routeBlocks, routeEnd[0], routeEnd[1], routeEnd[2], rushSeconds() / 60.0,
                walkBlocksPerSecond));
        for (int i = 0; i < bandIds.size(); i++) {
            int s = routeStepsPerBand[i];
            out.add(String.format(Locale.ROOT,
                    "  route band %s: steps=%d ore/100=%.1f mobs/100=%.1f loot/100=%.1f landmarks/100=%.1f",
                    bandIds.get(i), s, per100(routeOrePerBand[i], s), per100(routeMobsPerBand[i], s),
                    per100(routeLootPerBand[i], s), per100(routeLandmarksPerBand[i], s)));
        }
        out.addAll(waypointLines);
        out.add(String.format(Locale.ROOT,
                "longestEmptyStretch(steps, interest radius): any=%d ore=%d mob=%d loot=%d landmark=%d",
                emptyAny, emptyOre, emptyMob, emptyLoot, emptyLandmark));
        return out;
    }
}
