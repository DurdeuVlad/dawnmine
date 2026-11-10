package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.topology.TopoPos;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Finds every standable OPEN/RAIL cell within a node's own keep-out bounds
 * that is NOT part of the single, whole-mine reachable set computed once
 * from the true arrival point.
 *
 * <p>Deliberately takes that {@code reachable} set as a precomputed,
 * immutable snapshot rather than computing it itself per node or mutating
 * the world as it goes: an earlier version of this class re-ran its own
 * BFS per node on a world map it was simultaneously sealing into, and a
 * single wrong seal near one node's own path cut off a later node's BFS
 * from the rest of the mine, cascading into catastrophic over-sealing
 * (confirmed empirically: 700k+ cells sealed, reachable area collapsed
 * from tens of thousands of cells to roughly a thousand). Every seal
 * decision here is judged against the exact same pristine snapshot, so one
 * node's result can never influence another's, and the caller is expected
 * to apply every returned position in a single batch only after scanning
 * every node - never incrementally.
 *
 * <p>Must run after the WHOLE real world (every segment: node, edge, hub,
 * landmarks, dress, motifs) is already placed. An earlier, different
 * attempt at this same idea ran inside a single segment's own local plan,
 * before doorways carved by neighboring edges existed yet, and mistook
 * legitimate not-yet-carved connections for traps - it shrank cavern span
 * and broke gateway reachability. Scoped to each node's own bounded box
 * (not the whole dimension) so a full-world scan stays affordable.
 */
public final class UnreachablePocketSealer {
    private UnreachablePocketSealer() { }

    /** Returns every STANDABLE OPEN/RAIL cell inside [min,max] that is not
     * in {@code reachable}. Does not mutate {@code world} or {@code
     * reachable} - the caller applies the result as one batch.
     *
     * <p>Deliberately filters to standable cells only (floor support and
     * headroom both present, i.e. the same check {@link
     * MineTraversal#reachableFrom} itself uses to seed a search) rather
     * than every OPEN cell: a cavern's own airy headroom and upper volume
     * is legitimately open but not itself a position a player ever stands
     * in, so it is not a trap and must never be sealed. Omitting this
     * filter was tried and measured first: it sealed 700k+ cells per seed
     * and collapsed reachable area from ~135k cells to ~1k, because nearly
     * all of a cavern's own non-floor air falls inside some node's box and
     * is, correctly, not part of the ground-walking reachable set. */
    public static Set<TopoPos> findUnreachableIn(Map<TopoPos, SegmentGeometry.BlockKind> world,
            Set<TopoPos> reachable, TopoPos min, TopoPos max) {
        Set<TopoPos> sealed = new HashSet<>();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    TopoPos pos = new TopoPos(x, y, z);
                    SegmentGeometry.BlockKind kind = world.get(pos);
                    if ((kind == SegmentGeometry.BlockKind.OPEN
                            || kind == SegmentGeometry.BlockKind.RAIL)
                            && !reachable.contains(pos)
                            && MineTraversal.reachableFrom(pos, world, 1).contains(pos)) {
                        sealed.add(pos);
                    }
                }
            }
        }
        return sealed;
    }
}
