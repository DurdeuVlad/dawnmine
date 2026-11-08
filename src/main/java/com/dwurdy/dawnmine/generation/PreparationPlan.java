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

    /** Which phase a segment belongs to; structural before dress before ore. */
    public enum Phase { STRUCTURAL, DRESS, ORE }

    private PreparationPlan() { }

    /** Full ordered preparation list for a run: connector first so the
     * arrival always exists, then all structural nodes/edges, then all
     * dressing passes, then all ore passes. {@code placedLandmarkNodeIds}
     * names the safehouse nodes that actually anchored a landmark - a node
     * whose catalog entry degraded away expects no segment, so the run can
     * never be asked to build a landmark that was never placed. */
    public static List<String> expectedSegments(TopologyPlan topology,
                                                Set<String> placedLandmarkNodeIds) {
        List<String> structural = structuralSegments(topology);
        List<String> expected = new ArrayList<>(structural.size() * 3 + 1);
        expected.addAll(structural);
        // The generated hub build runs after all cave carving and before any
        // dressing or ore pass, as its own required preparation segment.
        expected.add("hub");
        // Static landmark safehouses are fixed-structure segments too: they
        // write after carving so their literal grids land in opened rooms,
        // and before dress/ore so those passes respect the placed reserve.
        for (TopoNode node : topology.nodes()) {
            if (node.has(com.dwurdy.dawnmine.topology.NodeFeature.SAFEHOUSE)
                    && placedLandmarkNodeIds.contains(node.id())) {
                expected.add(StaticLandmarkBuilder.segmentId(node.id()));
            }
        }
        for (String id : structural) {
            expected.add(dressSegmentId(id));
        }
        for (String id : structural) {
            expected.add(oreSegmentId(id));
        }
        return List.copyOf(expected);
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

    public static String oreSegmentId(String structuralId) {
        return "ores:" + structuralId;
    }

    public static boolean isDressSegment(String segmentId) {
        return segmentId.startsWith("dress:");
    }

    public static boolean isOreSegment(String segmentId) {
        return segmentId.startsWith("ores:");
    }

    /** The structural id a dressing or ore segment decorates. */
    public static String structuralIdFor(String segmentId) {
        if (isDressSegment(segmentId) || isOreSegment(segmentId)) {
            return segmentId.substring(segmentId.indexOf(':') + 1);
        }
        return segmentId;
    }

    public static Phase phaseOf(String segmentId) {
        if (isOreSegment(segmentId)) {
            return Phase.ORE;
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
