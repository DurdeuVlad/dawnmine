package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.generation.LandmarkCatalog.Definition;
import com.dwurdy.dawnmine.generation.LandmarkLayout.Placement;
import com.dwurdy.dawnmine.generation.SegmentGeometry.BlockKind;
import com.dwurdy.dawnmine.generation.SegmentGeometry.BlockOperation;
import com.dwurdy.dawnmine.generation.SegmentGeometry.SegmentPlan;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Emits the literal per-layer block grid of a {@link Definition} as a
 * deterministic segment plan - the fixed-structure sibling of
 * {@code SegmentGeometry.hub()}. Owner writes bypass the foreign-write
 * filter exactly like the hub does: the segment is built directly rather
 * than through {@code finish()}, and every other segment family is
 * filtered out of the landmark's reserve by the generalized keepout.
 * Pure function of the definition and anchor, so restart replay is
 * idempotent.
 */
public final class StaticLandmarkBuilder {
    private StaticLandmarkBuilder() {}

    public static final String SEGMENT_PREFIX = "landmark:";

    public static String segmentId(String nodeId) {
        return SEGMENT_PREFIX + nodeId;
    }

    public static String nodeId(String segmentId) {
        return segmentId.substring(SEGMENT_PREFIX.length());
    }

    /**
     * The literal plan for {@code placement}: slot codes resolve to their
     * declared block-state strings as MARKER ops, air slots become OPEN
     * ops so the interior is carved even where the host node's room never
     * reached. Operations sort y/z/x like the hub for stable cursor
     * replay.
     */
    public static SegmentPlan plan(Placement placement, TopoBounds bounds) {
        Definition def = placement.definition();
        TopoPos anchor = placement.anchor();
        // Declared connectors are fixed anchors: a bounds-clipped doorway
        // would terminate its corridor in solid rock.
        for (TopoPos connector : placement.connectorPositions()) {
            if (!bounds.contains(connector)) {
                throw new IllegalStateException("landmark '" + def.id() + "' connector "
                        + connector + " at node '" + placement.nodeId()
                        + "' clips outside owned bounds - report this fixture");
            }
        }
        List<BlockOperation> ops = new ArrayList<>();
        for (int y = 0; y < def.layers().size(); y++) {
            List<String> rows = def.layers().get(y);
            for (int row = 0; row < rows.size(); row++) {
                String line = rows.get(row);
                int z = row - def.halfZ();
                for (int col = 0; col < line.length(); col++) {
                    int x = col - def.halfX();
                    TopoPos pos = new TopoPos(anchor.x() + x, anchor.y() + y,
                            anchor.z() + z);
                    if (!bounds.contains(pos)) {
                        continue;
                    }
                    String blockId = def.slots().get(line.charAt(col));
                    if ("minecraft:air".equals(blockId)) {
                        ops.add(new BlockOperation(pos, BlockKind.OPEN));
                    } else {
                        ops.add(new BlockOperation(pos, BlockKind.MARKER, blockId));
                    }
                }
            }
        }
        ops.sort(Comparator.comparingInt((BlockOperation o) -> o.position().y())
                .thenComparingInt(o -> o.position().z())
                .thenComparingInt(o -> o.position().x()));
        return new SegmentPlan(segmentId(placement.nodeId()), ops);
    }
}
