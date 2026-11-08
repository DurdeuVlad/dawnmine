package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical resolved-geometry owner: takes the raw deterministic centerlines
 * and resolves XZ-sharing crossings that are separated by at most two blocks
 * into a shared floor - samples merge down to the lowest floor and the
 * approach is smoothed into supported one-step ramps on every incident
 * path, on both sides. Grade-separated crossings (3+ blocks apart) are left
 * alone. Node endpoints converge to a common lowest floor and every
 * post-process (dress, ore, clearance, traversal, registration) reads the
 * same resolved coordinates. Cached once per topology fingerprint.
 */
public final class PassageNetwork {
    /** Maximum a sample may be lowered from its original Y. */
    public static final int MAX_LOWERING = 12;
    private static final int MAX_PASSES = 128;

    private static String cachedFingerprint = "";
    private static String cachedArrival = "";
    private static PassageNetwork cached;

    private final TopologyPlan topology;
    private final TopoPos arrival;
    private final Map<String, List<TopoPos>> paths = new HashMap<>();
    private final Map<String, TopoPos> centers = new HashMap<>();

    /** Resolved geometry for a topology; fails (throws) only when a merge
     * would violate the fixed hub anchors or the lowering bound. */
    public static synchronized PassageNetwork of(TopologyPlan topology, TopoPos arrival) {
        String arrivalKey = arrival.x() + "," + arrival.y() + "," + arrival.z();
        if (cached != null && topology.fingerprint().equals(cachedFingerprint)
                && arrivalKey.equals(cachedArrival)) {
            return cached;
        }
        PassageNetwork network = new PassageNetwork(topology, arrival);
        cachedFingerprint = topology.fingerprint();
        cachedArrival = arrivalKey;
        cached = network;
        return network;
    }

    /** Test seam: drop the cached network. */
    public static synchronized void clearCache() {
        cached = null;
        cachedFingerprint = "";
        cachedArrival = "";
    }

    private PassageNetwork(TopologyPlan topology, TopoPos arrival) {
        this.topology = topology;
        this.arrival = arrival;
        Map<String, List<TopoPos>> raw = new HashMap<>();
        Map<String, List<TopoPos>> original = new HashMap<>();
        TopoNode entrance = topology.node(topology.entranceId());
        if (entrance != null
                && entrance.kind() == com.dwurdy.dawnmine.topology.NodeKind.ENTRANCE) {
            raw.put("connector:arrival:" + topology.entranceId(),
                    SegmentGeometry.connectorCenterlineRaw(topology, arrival));
        }
        for (TopoEdge edge : topology.edges()) {
            raw.put(SegmentGeometry.edgeSegmentId(edge), rawEdgePath(edge));
        }
        for (Map.Entry<String, List<TopoPos>> e : raw.entrySet()) {
            original.put(e.getKey(), List.copyOf(e.getValue()));
        }
        Map<String, List<TopoPos>> resolved = resolve(raw, original);
        for (Map.Entry<String, List<TopoPos>> e : resolved.entrySet()) {
            paths.put(e.getKey(), List.copyOf(e.getValue()));
        }
        // Resolved node centres: the common lowest floor across incident
        // endpoints, keeping id/features/region semantics.
        for (TopoNode node : topology.nodes()) {
            int y = node.pos().y();
            for (Map.Entry<String, List<TopoPos>> e : paths.entrySet()) {
                for (TopoPos end : List.of(e.getValue().get(0),
                        e.getValue().get(e.getValue().size() - 1))) {
                    if (end.x() == node.pos().x() && end.z() == node.pos().z()) {
                        y = Math.min(y, end.y());
                    }
                }
            }
            centers.put(node.id(), new TopoPos(node.pos().x(), y, node.pos().z()));
        }
    }

    private List<TopoPos> rawEdgePath(TopoEdge edge) {
        TopoNode a = topology.node(edge.a());
        TopoNode b = topology.node(edge.b());
        List<TopoPos> cells = SegmentGeometry.windingPath(a.pos(), b.pos(),
                topology.seed() ^ SegmentGeometry.stableHashPublic(
                        SegmentGeometry.edgeSegmentId(edge)));
        // Routes that do not depart the entrance skirt the gateway apron
        // and the hub reserve with the connector row folded in, so the
        // exclusive connector corridor can never be crossed, merged into,
        // or trenched by a foreign carve - and no later detour can
        // interpolate a sample back inside the clipped volume. Edges
        // touching the entrance keep their band samples: they leave
        // through the level junction at the floor.
        if (edge.a().equals(topology.entranceId())
                || edge.b().equals(topology.entranceId())) {
            return SegmentGeometry.avoidHubReserve(cells);
        }
        return SegmentGeometry.avoidForeignReserve(cells);
    }

    /** Resolved centerline for a preparation/geometry segment id. */
    public List<TopoPos> path(String segmentId) {
        List<TopoPos> path = paths.get(segmentId);
        if (path == null) {
            throw new IllegalArgumentException("no resolved path for " + segmentId);
        }
        return path;
    }

    /** Resolved physical centre for a node id (may differ in Y only). */
    public TopoPos center(String nodeId) {
        TopoPos center = centers.get(nodeId);
        return center == null ? null : center;
    }

    /**
     * The settled merge: group same-XZ samples whose heights differ by at
     * most 2, collapse each cluster to its lowest feasible floor, then
     * smooth each path into supported one-step ramps, repeating until
     * stable. Feasibility is bound-driven: anchors bound their own height,
     * ramp bounds decay one block per path step away from every anchor,
     * and every forced raise records a stair bound so neither a later
     * merge nor the smoother can pull the raised cell back down - which is
     * what makes the iteration monotone instead of oscillating. Lowering
     * is still capped by MAX_LOWERING and the bounds floor as hard
     * failures.
     */
    private Map<String, List<TopoPos>> resolve(Map<String, List<TopoPos>> raw,
                                             Map<String, List<TopoPos>> original) {
        Map<String, List<TopoPos>> work = new HashMap<>();
        for (Map.Entry<String, List<TopoPos>> e : raw.entrySet()) {
            work.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        // Stair bounds raised by merges/smoothing persist across passes;
        // anchor ramp bounds are rebuilt each pass from current positions.
        Map<String, int[]> stair = new HashMap<>();
        List<String> lastChanged = new ArrayList<>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            lastChanged.clear();
            List<String> ids = new ArrayList<>(work.keySet());
            ids.sort(String::compareTo);
            Map<String, int[]> bound = rampBounds(work, ids, stair);
            // Group same-XZ samples across all paths.
            Map<Long, List<int[]>> groups = new HashMap<>();
            for (int p = 0; p < ids.size(); p++) {
                List<TopoPos> path = work.get(ids.get(p));
                for (int i = 0; i < path.size(); i++) {
                    TopoPos c = path.get(i);
                    long key = xzKey(c.x(), c.z());
                    groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[] {p, i});
                    if (i > 0) {
                        TopoPos previous = path.get(i - 1);
                        if (previous.x() != c.x() && previous.z() != c.z()) {
                            int higher = previous.y() > c.y() ? i - 1 : i;
                            // A player's body sweeps both corners of a diagonal
                            // step, not just the integer centerline endpoints.
                            groups.computeIfAbsent(xzKey(previous.x(), c.z()),
                                    k -> new ArrayList<>()).add(new int[] {p, higher});
                            groups.computeIfAbsent(xzKey(c.x(), previous.z()),
                                    k -> new ArrayList<>()).add(new int[] {p, higher});
                        }
                    }
                }
            }
            List<Long> keys = new ArrayList<>(groups.keySet());
            keys.sort(Long::compare);
            for (Long key : keys) {
                List<int[]> refs = new ArrayList<>(groups.get(key));
                // Junction convergence: incident endpoints of one node share
                // the node's column and original height, so group endpoints
                // by their raw Y - same original Y means same junction. Every
                // family with 2+ members converges to a common floor BEFORE
                // ordinary span clustering, so a crossing sample can never
                // wedge incident paths onto different floors (which carves
                // corridors that start in mid-air above their own room).
                Map<Integer, List<int[]>> endpointFamilies = new HashMap<>();
                for (int[] r : refs) {
                    List<TopoPos> p = work.get(ids.get(r[0]));
                    if (r[1] == 0 || r[1] == p.size() - 1) {
                        int origY = original.get(ids.get(r[0])).get(r[1]).y();
                        List<int[]> family = endpointFamilies
                                .computeIfAbsent(origY, k -> new ArrayList<>());
                        boolean dup = false;
                        for (int[] member : family) {
                            if (member[0] == r[0] && member[1] == r[1]) {
                                dup = true;
                                break;
                            }
                        }
                        if (!dup) {
                            family.add(r);
                        }
                    }
                }
                for (List<int[]> family : endpointFamilies.values()) {
                    if (family.size() < 2) {
                        continue;
                    }
                    // Common floor: the lowest member, never below a member's
                    // bound, legalized per member column. Members can only
                    // move down (endpoints are fixed anchors); a junction
                    // that would have to rise is unresolvable and fails loud.
                    int floor = Integer.MAX_VALUE;
                    int floorBound = Integer.MIN_VALUE;
                    for (int[] r : family) {
                        String segmentId = ids.get(r[0]);
                        floor = Math.min(floor, work.get(segmentId).get(r[1]).y());
                        floorBound = Math.max(floorBound,
                                Math.max(bound.get(segmentId)[r[1]],
                                        stair.get(segmentId)[r[1]]));
                    }
                    int snapped = floor;
                    for (int[] r : family) {
                        snapped = Math.min(snapped, legalFeetYAtOrBelow(
                                work.get(ids.get(r[0])).get(r[1]), floor));
                    }
                    if (snapped < floorBound) {
                        snapped = Integer.MIN_VALUE;
                        for (int[] r : family) {
                            snapped = Math.max(snapped, legalFeetYAtOrAbove(
                                    work.get(ids.get(r[0])).get(r[1]), floorBound));
                        }
                    }
                    floor = snapped;
                    for (int[] r : family) {
                        String segmentId = ids.get(r[0]);
                        int idx = r[1];
                        List<TopoPos> path = work.get(segmentId);
                        TopoPos c = path.get(idx);
                        if (c.y() == floor) {
                            continue;
                        }
                        if (floor < c.y()) {
                            requireLowerable(segmentId, idx, c, floor,
                                    original.get(segmentId),
                                    Math.max(bound.get(segmentId)[idx],
                                            stair.get(segmentId)[idx]));
                        } else {
                            throw new IllegalStateException(
                                    "junction convergence would raise node endpoint "
                                            + c + " in segment " + segmentId
                                            + " - report this fixture");
                        }
                        path.set(idx, new TopoPos(c.x(), floor, c.z()));
                        changed = true;
                        if (lastChanged.size() < 12) {
                            lastChanged.add(segmentId + "[" + idx + "] junction "
                                    + c + " -> " + floor);
                        }
                    }
                }
                // Sort samples by height, then merge each contiguous cluster
                // whose heights span at most 3 - a 3-block stack puts the
                // upper route's floor exactly on the lower route's headroom,
                // so it must share the floor rather than float. The floor is
                // the lowest feasible level: the lowest member's height or
                // the highest member bound, whichever wins - a bound-forced
                // raise is a junction stair, recorded so it is never undone.
                // A fixed anchor pins its cluster's floor outright: anchors
                // never move, so the floor can never sit above one.
                refs.sort(java.util.Comparator.comparingInt(
                        (int[] r) -> work.get(ids.get(r[0])).get(r[1]).y())
                        .thenComparingInt(r -> r[0]).thenComparingInt(r -> r[1]));
                int start = 0;
                while (start < refs.size()) {
                    int end = start;
                    while (end + 1 < refs.size()
                            && work.get(ids.get(refs.get(end + 1)[0])).get(refs.get(end + 1)[1]).y()
                                    - work.get(ids.get(refs.get(start)[0])).get(refs.get(start)[1]).y() <= 3) {
                        end++;
                    }
                    if (end > start) {
                        int floor = Integer.MAX_VALUE;
                        int floorBound = Integer.MIN_VALUE;
                        int anchorFloor = Integer.MAX_VALUE;
                        for (int i = start; i <= end; i++) {
                            String segmentId = ids.get(refs.get(i)[0]);
                            int idx = refs.get(i)[1];
                            TopoPos cell = work.get(segmentId).get(idx);
                            floor = Math.min(floor, cell.y());
                            floorBound = Math.max(floorBound,
                                    Math.max(bound.get(segmentId)[idx],
                                            stair.get(segmentId)[idx]));
                            if (isFixedAnchor(segmentId, idx,
                                    work.get(segmentId).size(), cell)) {
                                anchorFloor = Math.min(anchorFloor, cell.y());
                            }
                        }
                        floor = Math.min(Math.max(floor, floorBound), anchorFloor);
                        // The merged floor must be legal in every member's
                        // column, not just the lowest-sorted one - corner
                        // groups can mix protected hub columns with open
                        // ones. Snap down where every column allows it; a
                        // bound-forced floor instead snaps every column up
                        // from the bound and takes the highest, which is
                        // legal everywhere because the keepout ends at the
                        // reserve roof.
                        int snapped = floor;
                        for (int i = start; i <= end; i++) {
                            snapped = Math.min(snapped, legalFeetYAtOrBelow(
                                    work.get(ids.get(refs.get(i)[0])).get(refs.get(i)[1]),
                                    floor));
                        }
                        if (snapped < floorBound) {
                            snapped = Integer.MIN_VALUE;
                            for (int i = start; i <= end; i++) {
                                snapped = Math.max(snapped, legalFeetYAtOrAbove(
                                        work.get(ids.get(refs.get(i)[0])).get(refs.get(i)[1]),
                                        floorBound));
                            }
                        }
                        floor = snapped;
                        for (int i = start; i <= end; i++) {
                            String segmentId = ids.get(refs.get(i)[0]);
                            int idx = refs.get(i)[1];
                            List<TopoPos> path = work.get(segmentId);
                            TopoPos c = path.get(idx);
                            if (c.y() != floor) {
                                if (floor < c.y()) {
                                    requireLowerable(segmentId, idx, c, floor,
                                            original.get(segmentId),
                                            Math.max(bound.get(segmentId)[idx],
                                                    stair.get(segmentId)[idx]));
                                } else if (isFixedAnchor(segmentId, idx,
                                        path.size(), c)) {
                                    throw new IllegalStateException(
                                            "crossing merge would raise the fixed hub anchor "
                                                    + c + " in segment " + segmentId
                                                    + " - report this fixture");
                                } else {
                                    stair.get(segmentId)[idx]
                                            = Math.max(stair.get(segmentId)[idx], floor);
                                }
                                path.set(idx, new TopoPos(c.x(), floor, c.z()));
                                changed = true;
                                if (lastChanged.size() < 12) {
                                    lastChanged.add(segmentId + "[" + idx + "] "
                                            + c + " -> " + floor);
                                }
                            }
                        }
                    }
                    start = end + 1;
                }
            }
            // Smooth each path so |dy| <= 1 between neighbours: lower the
            // high side only as far as its bound permits, then raise the
            // low side over the residual gap and bind it there.
            for (String id : ids) {
                changed |= smooth(work.get(id), id, original.get(id),
                        bound.get(id), stair.get(id), lastChanged);
            }
            if (!changed) {
                return freeze(work);
            }
        }
        throw new IllegalStateException("passage crossing resolution did not converge in "
                + MAX_PASSES + " passes - report this fixture; no partial geometry admitted."
                + " Last moves: " + lastChanged);
    }

    /** Per-cell lower bounds: fixed anchors pin their own height and the
     * walkable ramp requirement propagates one block of slack per path
     * step away from every anchor. Stair bounds are point protections for
     * cells a merge or the smoother already raised - they never propagate,
     * or one raised cell would drag its whole path upward. Cells inside the
     * hub footprint column can never be bound above the dead band's floor:
     * a bound that would push them into the clipped volume is capped so the
     * merge is always free to pull them back below it. */
    private Map<String, int[]> rampBounds(Map<String, List<TopoPos>> work,
                                          List<String> ids, Map<String, int[]> stair) {
        Map<String, int[]> bound = new HashMap<>();
        for (String id : ids) {
            List<TopoPos> path = work.get(id);
            int[] st = stair.computeIfAbsent(id, k -> {
                int[] a = new int[path.size()];
                java.util.Arrays.fill(a, Integer.MIN_VALUE);
                return a;
            });
            int[] b = new int[path.size()];
            java.util.Arrays.fill(b, Integer.MIN_VALUE);
            for (int i = 0; i < path.size(); i++) {
                if (isHubAnchor(id, path.get(i))) {
                    b[i] = path.get(i).y();
                }
            }
            for (int i = 1; i < b.length; i++) {
                if (b[i - 1] != Integer.MIN_VALUE) {
                    b[i] = Math.max(b[i], b[i - 1] - 1);
                }
            }
            for (int i = b.length - 2; i >= 0; i--) {
                if (b[i + 1] != Integer.MIN_VALUE) {
                    b[i] = Math.max(b[i], b[i + 1] - 1);
                }
            }
            for (int i = 0; i < b.length; i++) {
                b[i] = Math.max(b[i], st[i]);
                // A bound is a lower limit, so it legalizes upward: a bound
                // that lands inside the clipped hub volume rises to the
                // nearest walkable height above it rather than demanding a
                // floor the reserve would erase.
                if (b[i] != Integer.MIN_VALUE) {
                    b[i] = legalFeetYAtOrAbove(path.get(i), b[i]);
                }
            }
            bound.put(id, b);
        }
        return bound;
    }

    /** A resolved centreline cell is only meaningful where both it and its
     * headroom survive the arrival keepout clip. */
    private static boolean legalFeetY(TopoPos p) {
        return !HubLayout.isProtected(p) && !HubLayout.isProtected(p.offset(0, 1, 0));
    }

    /** The nearest height at or above {@code y} whose column leaves a legal
     * two-high cell; {@code y} itself when already legal. */
    private static int legalFeetYAtOrAbove(TopoPos column, int y) {
        for (int d = 0; d <= 64; d++) {
            if (legalFeetY(new TopoPos(column.x(), y + d, column.z()))) {
                return y + d;
            }
        }
        return y;
    }

    /** The nearest legal two-high height at or below {@code y}, falling
     * back to the nearest above when the whole column below is clipped. */
    private static int legalFeetYAtOrBelow(TopoPos column, int y) {
        for (int d = 0; d <= 64; d++) {
            if (legalFeetY(new TopoPos(column.x(), y - d, column.z()))) {
                return y - d;
            }
        }
        return legalFeetYAtOrAbove(column, y);
    }

    private boolean smooth(List<TopoPos> path, String id, List<TopoPos> originalPath,
                           int[] bound, int[] stair, List<String> log) {
        boolean changed = false;
        for (int i = 0; i + 1 < path.size(); i++) {
            TopoPos a = path.get(i);
            TopoPos b = path.get(i + 1);
            if (b.y() > a.y() + 1) {
                int lowered = Math.max(a.y() + 1,
                        Math.max(bound[i + 1], stair[i + 1]));
                // Bounds are pre-merge state; a cell merged onto the gateway
                // floor since then is a live anchor that must never be
                // lowered - the raise branch below takes over instead.
                if (!legalFeetY(new TopoPos(b.x(), lowered, b.z()))) {
                    int below = legalFeetYAtOrBelow(b, lowered);
                    lowered = below >= Math.max(bound[i + 1], stair[i + 1])
                            ? below
                            : legalFeetYAtOrAbove(b, lowered);
                }
                if (!isHubAnchor(id, b) && lowered < b.y()) {
                    requireLowerable(id, i + 1, b, lowered, originalPath,
                            Math.max(bound[i + 1], stair[i + 1]));
                    path.set(i + 1, new TopoPos(b.x(), lowered, b.z()));
                    b = path.get(i + 1);
                    changed = true;
                }
                if (b.y() > a.y() + 1) {
                    if (isFixedAnchor(id, i, path.size(), a)) {
                        throw new IllegalStateException("smoothing would raise the fixed hub anchor "
                                + a + " toward bound-locked " + b + " in segment " + id
                                + " - report this fixture");
                    }
                    int raised = legalFeetYAtOrAbove(a, b.y() - 1);
                    path.set(i, new TopoPos(a.x(), raised, a.z()));
                    stair[i] = Math.max(stair[i], raised);
                    changed = true;
                }
                if (log.size() < 12) {
                    log.add(id + "[" + i + "] smooth fwd");
                }
            }
        }
        for (int i = path.size() - 2; i >= 0; i--) {
            TopoPos a = path.get(i);
            TopoPos b = path.get(i + 1);
            if (a.y() > b.y() + 1) {
                int lowered = Math.max(b.y() + 1,
                        Math.max(bound[i], stair[i]));
                if (!legalFeetY(new TopoPos(a.x(), lowered, a.z()))) {
                    int below = legalFeetYAtOrBelow(a, lowered);
                    lowered = below >= Math.max(bound[i], stair[i])
                            ? below
                            : legalFeetYAtOrAbove(a, lowered);
                }
                if (!isHubAnchor(id, a) && lowered < a.y()) {
                    requireLowerable(id, i, a, lowered, originalPath,
                            Math.max(bound[i], stair[i]));
                    path.set(i, new TopoPos(a.x(), lowered, a.z()));
                    a = path.get(i);
                    changed = true;
                }
                if (a.y() > b.y() + 1) {
                    if (isFixedAnchor(id, i + 1, path.size(), b)) {
                        throw new IllegalStateException("smoothing would raise the fixed hub anchor "
                                + b + " toward bound-locked " + a + " in segment " + id
                                + " - report this fixture");
                    }
                    int raised = legalFeetYAtOrAbove(b, a.y() - 1);
                    path.set(i + 1, new TopoPos(b.x(), raised, b.z()));
                    stair[i + 1] = Math.max(stair[i + 1], raised);
                    changed = true;
                }
                if (log.size() < 12) {
                    log.add(id + "[" + i + "] smooth bwd");
                }
            }
        }
        return changed;
    }

    /** Hub anchors (reserve + gateway band + landing) may never be lowered,
     * no cell may drop below its junction/stair bound, every other sample
     * may sink at most MAX_LOWERING and never below the bounds floor. */
    private void requireLowerable(String segmentId, int index, TopoPos cell, int newY,
                                  List<TopoPos> orig, int bound) {
        if (isHubAnchor(segmentId, cell)) {
            throw new IllegalStateException("crossing merge would lower the fixed hub anchor "
                    + cell + " in segment " + segmentId + " - report this fixture");
        }
        if (newY < bound) {
            throw new IllegalStateException("crossing merge would push " + cell
                    + " below its junction ramp bound " + bound + " in segment "
                    + segmentId + " - report this fixture");
        }
        int origY = orig != null && index < orig.size() ? orig.get(index).y() : cell.y();
        if (origY - newY > MAX_LOWERING) {
            throw new IllegalStateException("crossing merge would lower " + cell + " by "
                    + (origY - newY) + " blocks from its original height (> " + MAX_LOWERING
                    + ") in segment " + segmentId + " - report this fixture");
        }
        if (newY < topology.bounds().minY() + 4) {
            throw new IllegalStateException("crossing merge would push " + cell
                    + " below the owned bounds floor in segment " + segmentId);
        }
    }

    /** The genuinely fixed points of the run. The arrival connector's own
     * band samples are always fixed. A foreign segment's sample is fixed
     * only once it sits exactly on the gateway floor (y == FEET_Y): that is
     * a junction crossing, and the smoother may raise its neighbours toward
     * it but must never push it back into the floor. Foreign samples above
     * or below the floor are not fixed - they merge into the junction or
     * stay grade-separated. The landing apron is protected by the hub
     * reserve, not by this predicate. */
    private static boolean isFixedAnchor(String segmentId, int index, int pathLength,
                                         TopoPos pos) {
        // Node endpoints may merge down into a nearby crossing - their room
        // floor follows the resolved centre - but they may never be raised
        // above their node, so they pin cluster floors and reject raises
        // exactly like a hub anchor.
        if (index == 0 || (pathLength > 0 && index == pathLength - 1)) {
            return true;
        }
        return isHubAnchor(segmentId, pos);
    }

    private static boolean isHubAnchor(String segmentId, TopoPos pos) {
        boolean inBand = pos.x() >= HubLayout.EAST_EXIT.x()
                && pos.x() <= HubLayout.ENTRANCE.x() && pos.z() == 0;
        if (!inBand) {
            return false;
        }
        if (segmentId.startsWith("connector:arrival:")) {
            return Math.abs(pos.y() - HubLayout.FEET_Y) <= 2;
        }
        return pos.y() == HubLayout.FEET_Y;
    }

    private Map<String, List<TopoPos>> freeze(Map<String, List<TopoPos>> work) {
        Map<String, List<TopoPos>> out = new HashMap<>();
        for (Map.Entry<String, List<TopoPos>> e : work.entrySet()) {
            for (TopoPos c : e.getValue()) {
                // Resolution only moves samples in Y, so a centreline cell
                // that ended inside the clipped hub volume can only have
                // been dragged here by a bound-forced raise - admit nothing.
                if (HubLayout.isProtected(c) || HubLayout.isProtected(c.offset(0, 1, 0))) {
                    throw new IllegalStateException("resolved centreline cell sits inside the "
                            + "clipped hub volume: " + c + " in segment " + e.getKey()
                            + " - report this fixture");
                }
            }
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return out;
    }

    private static long xzKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFF_FFFFL);
    }
}
