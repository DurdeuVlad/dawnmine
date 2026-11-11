package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure description of the whole-run preparation contract: the deterministic,
 * ordered set of generation segments a run must complete before any player
 * is admitted. Structural carves come first, then the dressing pass
 * (floors, timber, rails, lights) so no later structural write can bury a
 * support, and finally the ore pass so no structural write can bury a vein.
 *
 * <p>Ids are stable strings persisted in the run record's generation queues,
 * so a restarted server re-derives the same expectation and deduplicates
 * already-completed work instead of regenerating.
 */
public final class PreparationPlan {

    /** Which phase a segment belongs to: structural, then dress, then
     * motif decoration, then ore last so no later write buries a vein. */
    public enum Phase { STRUCTURAL, DRESS, MOTIF, ORE }

    private PreparationPlan() { }

    /** Preparation list for a run. Mine v2 terrain is generated with the chunks themselves (see
     * {@link CaveTerrain}), so the only segment left is the hand-placed expedition hub. The
     * arguments are kept until the old topology is removed. */
    public static List<String> expectedSegments(TopologyPlan topology,
                                                Set<String> placedLandmarkNodeIds) {
        return List.of("hub");
    }

    /** Structural segment ids only, in deterministic enqueue order. */
    public static List<String> structuralSegments(TopologyPlan topology) {
        List<String> ids = new ArrayList<>(topology.nodes().size() + topology.edges().size() + 1);
        ids.add(SegmentGeometry.connectorSegmentId(topology.entranceId()));
        for (TopoNode node : topology.nodes()) {
            ids.add("node:" + node.id());
        }
        for (TopoEdge edge : topology.edges()) {
            ids.add(SegmentGeometry.edgeSegmentId(edge));
        }
        return List.copyOf(ids);
    }

    public static String dressSegmentId(String structuralId) {
        return "dress:" + structuralId;
    }

    public static String motifSegmentId(String structuralId) {
        return "motif:" + structuralId;
    }

    public static String oreSegmentId(String structuralId) {
        return "ores:" + structuralId;
    }

    public static boolean isDressSegment(String segmentId) {
        return segmentId.startsWith("dress:");
    }

    public static boolean isMotifSegment(String segmentId) {
        return segmentId.startsWith("motif:");
    }

    public static boolean isOreSegment(String segmentId) {
        return segmentId.startsWith("ores:");
    }

    /** The structural id a dressing, motif, or ore segment decorates. */
    public static String structuralIdFor(String segmentId) {
        if (isDressSegment(segmentId) || isOreSegment(segmentId)
                || isMotifSegment(segmentId)) {
            return segmentId.substring(segmentId.indexOf(':') + 1);
        }
        return segmentId;
    }

    public static Phase phaseOf(String segmentId) {
        if (isOreSegment(segmentId)) {
            return Phase.ORE;
        }
        if (isMotifSegment(segmentId)) {
            return Phase.MOTIF;
        }
        return isDressSegment(segmentId) ? Phase.DRESS : Phase.STRUCTURAL;
    }

    /** Snapshot of preparation progress for status surfaces and gating. */
    public record Progress(int expected, int completed, int pending, String activeId) {
        public boolean complete() {
            return expected > 0 && completed >= expected;
        }

        public int percent() {
            return expected <= 0 ? 0 : (int) Math.min(100, (completed * 100L) / expected);
        }
    }

    /** Derives progress from the expected set and the persisted completed
     * receipts: completion means every expected segment is complete, not just
     * that the run record says READY. */
    public static Progress progress(TopologyPlan topology,
                                    Set<String> placedLandmarkNodeIds,
                                    Set<String> completed,
                                    int pendingCount, String activeId) {
        Set<String> expected = new LinkedHashSet<>(
                expectedSegments(topology, placedLandmarkNodeIds));
        int done = 0;
        for (String id : expected) {
            if (completed.contains(id)) {
                done++;
            }
        }
        return new Progress(expected.size(), done, pendingCount,
                activeId == null ? "" : activeId);
    }
}
