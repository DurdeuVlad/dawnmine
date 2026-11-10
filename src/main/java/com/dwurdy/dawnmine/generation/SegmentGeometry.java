package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.topology.EdgeKind;
import com.dwurdy.dawnmine.topology.HubLayout;
import com.dwurdy.dawnmine.topology.NodeFeature;
import com.dwurdy.dawnmine.topology.NodeKind;
import com.dwurdy.dawnmine.topology.TopoBounds;
import com.dwurdy.dawnmine.topology.TopoEdge;
import com.dwurdy.dawnmine.topology.TopoNode;
import com.dwurdy.dawnmine.topology.TopoPos;
import com.dwurdy.dawnmine.topology.TopologyPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Pure deterministic conversion of one topology node/edge into block
 * operations. It knows positions and semantic block kinds only; the server
 * adapter resolves block states and performs bounded writes.
 *
 * <p>Three plan families exist per structural segment: the structural carve
 * ({@code node:}, {@code edge:}, {@code connector:arrival:}), the dressing
 * pass ({@code dress:...}: supported floors, timber frames, rails, lights)
 * and the ore pass ({@code ores:...}, produced by OrePlacementPlanner). All
 * are deterministic functions of the topology so a replay after restart is
 * byte-identical.
 */
public final class SegmentGeometry {
    static final int MAX_CENTERLINE_STEPS = 8192;
    private static final int MAX_OPERATIONS = 250_000;

    /** Normalized-distance margin beyond an ellipsoid carve's threshold
     * that still gets an explicit wall-skin ROCK write. Near a lobe's
     * radius (12-18), this covers roughly a 1-2 block shell - deeper cells
     * are never visible to a player anyway (Minecraft only renders faces
     * touching air), so writing further than this has no benefit. */
    private static final double WALL_SKIN_MARGIN = 0.22;
    /** Leave two untouched rock layers below the flat world's stone top (Y62). */
    static final int MAX_CAVE_OPEN_Y = 60;

    private static final Comparator<TopoPos> POSITION_ORDER = Comparator
            .comparingInt(TopoPos::y).thenComparingInt(TopoPos::z).thenComparingInt(TopoPos::x);

    private SegmentGeometry() {}

    /** Block palette-independent intent for a single deterministic write. */
    public enum BlockKind {
        ROCK,
        /** Cosmetic wall/ceiling/pillar skin: resolves through the same
         * natural-rock palette as ROCK, but the apply step must never let
         * it overwrite a cell another segment already carved open - unlike
         * ROCK's floor-bridging writes, this one is only a guess at where
         * solid rock belongs, never a structural guarantee. */
        WALL_SKIN,
        OPEN,
        ORE,
        HAZARD_BOUNDARY,
        HAZARD_SOURCE,
        TIMBER,
        PLANKS,
        FENCE,
        RAIL,
        LIGHT,
        /** Direct vanilla block id (hub architecture, consoles, furniture). */
        MARKER
    }

    public record BlockOperation(TopoPos position, BlockKind kind, String blockId) {
        public BlockOperation(TopoPos position, BlockKind kind) {
            this(position, kind, "");
        }

        public BlockOperation {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(kind, "kind");
            blockId = blockId == null ? "" : blockId;
            if (kind == BlockKind.ORE && blockId.isBlank()) {
                throw new IllegalArgumentException("ORE operations require a registered block id");
            }
            if (kind == BlockKind.MARKER && blockId.isBlank()) {
                throw new IllegalArgumentException("MARKER operations require a vanilla block id");
            }
            if (kind != BlockKind.ORE && kind != BlockKind.MARKER && !blockId.isBlank()) {
                throw new IllegalArgumentException("only ORE/MARKER operations may carry a block id");
            }
        }
    }

    private record RankedBlock(BlockKind kind, int priority) {}

    /** Immutable, stable-ordered operations. Cursor offsets count operations. */
    public record SegmentPlan(String id, List<BlockOperation> operations) {
        public SegmentPlan {
            Objects.requireNonNull(id, "id");
            operations = List.copyOf(operations);
        }

        public List<BlockOperation> operations(int cursor, int limit) {
            if (cursor < 0 || cursor > operations.size()) {
                throw new IllegalArgumentException("cursor outside segment: " + cursor);
            }
            if (limit < 0) {
                throw new IllegalArgumentException("operation limit must be non-negative");
            }
            int end = cursor + Math.min(limit, operations.size() - cursor);
            return operations.subList(cursor, end);
        }
    }

    /** Interior passage cross-section for one centerline step. */
    record PassageProfile(int halfWidth, int height, boolean mineshaft) {}

    // ------------------------------------------------------------------
    // Winding centerline
    // ------------------------------------------------------------------

    /**
     * Deterministic irregular centerline between two endpoints: smooth
     * lateral detours make every passage wind like a natural cave instead of
     * running straight. Adjacent cells differ by at most one block per axis
     * and the post-pass clamps the vertical step to at most one block per
     * horizontal step, so the result is a supported 2-high walkable route.
     */
    public static List<TopoPos> windingPath(TopoPos from, TopoPos to, long seed) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double hdist = Math.hypot(dx, dz);
        if (hdist < 2.0) {
            return clampYSteps(line(from, to));
        }
        double ux = dx / hdist;
        double uz = dz / hdist;
        double px = -uz;
        double pz = ux;
        Random rng = new Random(seed ^ 0x5DEECE66DL);
        double amp1 = Math.min(0.30 * hdist, 22.0) * (0.45 + 0.75 * rng.nextDouble());
        double amp2 = Math.min(0.14 * hdist, 9.0) * rng.nextDouble();
        double amp3 = Math.min(0.06 * hdist, 4.0) * rng.nextDouble();
        double ph2 = rng.nextDouble() * Math.PI * 2;
        double ph3 = rng.nextDouble() * Math.PI * 2;
        int waves2 = 2 + rng.nextInt(2); // 2 or 3 side-to-side sweeps
        int waves3 = 3 + rng.nextInt(3); // finer wobble
        double vamp = Math.min(3.5, 1.0 + Math.abs(to.y() - from.y()) * 0.2)
                * rng.nextDouble();
        double vph = rng.nextDouble() * Math.PI * 2;
        int samples = Math.max(32, (int) Math.ceil(hdist * 4));
        List<TopoPos> cells = new ArrayList<>(samples * 2);
        TopoPos prev = from;
        cells.add(from);
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            double lateral = amp1 * Math.sin(Math.PI * t)
                    + amp2 * Math.sin(waves2 * Math.PI * t + ph2)
                    + amp3 * Math.sin(waves3 * Math.PI * t + ph3);
            double rise = vamp * Math.sin(Math.PI * t + vph);
            int sx = (int) Math.round(from.x() + dx * t + px * lateral);
            int sz = (int) Math.round(from.z() + dz * t + pz * lateral);
            int sy = (int) Math.round(from.y() + (to.y() - from.y()) * t + rise);
            TopoPos target = new TopoPos(sx, sy, sz);
            if (i == samples) {
                target = to;
            }
            for (TopoPos step : line(prev, target)) {
                if (!step.equals(cells.get(cells.size() - 1))) {
                    cells.add(step);
                }
            }
            prev = target;
        }
        if (cells.size() > MAX_CENTERLINE_STEPS) {
            throw new IllegalArgumentException("segment centerline exceeds "
                    + MAX_CENTERLINE_STEPS + " steps");
        }
        return clampYSteps(cells);
    }

    /** One-block-max axis steps between two cells (Bresenham-style). */
    private static List<TopoPos> line(TopoPos from, TopoPos to) {
        long dx = (long) to.x() - from.x();
        long dy = (long) to.y() - from.y();
        long dz = (long) to.z() - from.z();
        long steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps > MAX_CENTERLINE_STEPS) {
            throw new IllegalArgumentException("edge centerline exceeds " + MAX_CENTERLINE_STEPS + " steps");
        }
        List<TopoPos> path = new ArrayList<>((int) steps + 1);
        if (steps == 0) {
            path.add(from);
            return path;
        }
        for (int i = 0; i <= steps; i++) {
            path.add(new TopoPos(
                    from.x() + (int) Math.round(dx * (double) i / steps),
                    from.y() + (int) Math.round(dy * (double) i / steps),
                    from.z() + (int) Math.round(dz * (double) i / steps)));
        }
        return path;
    }

    /** Interpolation that refuses blocked cells: walks unit axis steps from
     * {@code from} to {@code to}, always choosing the largest remaining
     * delta that lands somewhere legal, so height changes happen in clear
     * columns instead of inside protected space. Only when every candidate
     * step is blocked does it take one anyway and leave the residue for the
     * surrounding deflection loop. */
    private static List<TopoPos> lineClearing(TopoPos from, TopoPos to,
                                            java.util.function.Predicate<TopoPos> blocked) {
        List<TopoPos> path = new ArrayList<>();
        Set<TopoPos> seen = new HashSet<>();
        TopoPos cur = from;
        path.add(cur);
        seen.add(cur);
        int guard = 0;
        while (!cur.equals(to)) {
            if (guard++ > MAX_CENTERLINE_STEPS * 4) {
                throw new IllegalStateException("protected-space interpolation did not converge "
                        + from + " -> " + to + " - report this fixture");
            }
            int dx = Integer.compare(to.x(), cur.x());
            int dy = Integer.compare(to.y(), cur.y());
            int dz = Integer.compare(to.z(), cur.z());
            int[][] moves = {
                    {dx, 0, 0, Math.abs(to.x() - cur.x())},
                    {0, dy, 0, Math.abs(to.y() - cur.y())},
                    {0, 0, dz, Math.abs(to.z() - cur.z())}};
            java.util.Arrays.sort(moves, java.util.Comparator.comparingInt(m -> -m[3]));
            TopoPos next = null;
            for (int[] m : moves) {
                if (m[3] == 0) {
                    continue;
                }
                TopoPos cand = cur.offset(m[0], m[1], m[2]);
                // A seen cell's frontier only shrank since it was walked -
                // re-entering it can never reach anywhere new, so progress
                // steps must skip it or the walk oscillates into dead ends.
                if (!blocked.test(cand) && !seen.contains(cand)) {
                    next = cand;
                    break;
                }
            }
            if (next == null) {
                // Every progress move is walled off (e.g. a straight chord
                // through the apron): detour. Among all six unit steps pick
                // the legal one that least increases the remaining distance.
                // There is deliberately no distance window: the retreat scan
                // below uses the same legality predicate as this move, so a
                // bounded acceptance would let the walk abandon cells whose
                // only exits lay outside the window - an artificial dead end
                // where a legal route exists. Permanent {@code seen} plus the
                // outer guard still bound the search.
                int bestDist = Integer.MAX_VALUE;
                int bestOut = Integer.MIN_VALUE;
                for (int[] m : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
                        {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    TopoPos cand = cur.offset(m[0], m[1], m[2]);
                    if (blocked.test(cand) || seen.contains(cand)) {
                        continue;
                    }
                    int dist = Math.max(Math.abs(to.x() - cand.x()),
                            Math.max(Math.abs(to.y() - cand.y()),
                                    Math.abs(to.z() - cand.z())));
                    int out = Math.abs(cand.x()) + Math.abs(cand.z());
                    if (dist < bestDist || (dist == bestDist && out > bestOut)) {
                        next = cand;
                        bestDist = dist;
                        bestOut = out;
                    }
                }
                if (next == null) {
                    // Dead end: backtrack to the deepest walked cell that
                    // still has an unexplored legal neighbour and resume
                    // from there. Permanent {@code seen} means a greedy
                    // detour can paint itself into a reserve-face corner;
                    // a stack walk always finds the pocket's exit when one
                    // exists, and still fails closed when none does.
                    boolean retreated = false;
                    while (path.size() > 1) {
                        path.remove(path.size() - 1);
                        TopoPos top = path.get(path.size() - 1);
                        for (int[] m : new int[][] {{1, 0, 0}, {-1, 0, 0},
                                {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                            TopoPos cand = top.offset(m[0], m[1], m[2]);
                            if (!blocked.test(cand) && !seen.contains(cand)) {
                                retreated = true;
                                break;
                            }
                        }
                        if (retreated) {
                            cur = top;
                            break;
                        }
                    }
                    if (!retreated) {
                        throw new IllegalStateException(
                                "protected-space interpolation is surrounded "
                                        + from + " -> " + to + " stuck at " + cur
                                        + " - report this fixture");
                    }
                    continue;
                }
            }
            cur = next;
            path.add(cur);
            seen.add(cur);
        }
        return path;
    }

    /** Forward/backward clamp so the walkable route never steps more than
     * one vertical block per horizontal step while preserving endpoints. */
    private static List<TopoPos> clampYSteps(List<TopoPos> cells) {
        return clampYSteps(cells, p -> false);
    }

    private static List<TopoPos> clampYSteps(List<TopoPos> cells,
                                             java.util.function.Predicate<TopoPos> forbidden) {
        int[] ys = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            ys[i] = cells.get(i).y();
        }
        for (int i = 1; i < ys.length; i++) {
            ys[i] = Math.max(ys[i - 1] - 1, Math.min(ys[i - 1] + 1, ys[i]));
        }
        for (int i = ys.length - 2; i >= 0; i--) {
            ys[i] = Math.max(ys[i + 1] - 1, Math.min(ys[i + 1] + 1, ys[i]));
        }
        List<TopoPos> fixed = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            TopoPos cell = cells.get(i);
            fixed.add(new TopoPos(cell.x(), ys[i], cell.z()));
        }
        return withoutVerticalPairs(fixed, forbidden);
    }

    /** Guarantees every vertical transition also moves horizontally: a pure
     * up/down step is replaced by a one-block sideways jog so the walk never
     * contains a ladder-only cell. */
    static List<TopoPos> withoutVerticalPairs(List<TopoPos> cells) {
        return withoutVerticalPairs(cells, p -> false);
    }

    static List<TopoPos> withoutVerticalPairs(List<TopoPos> cells,
                                              java.util.function.Predicate<TopoPos> forbidden) {
        List<TopoPos> result = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            TopoPos cell = cells.get(i);
            if (i > 0 && cell.x() == cells.get(i - 1).x() && cell.z() == cells.get(i - 1).z()
                    && cell.y() != cells.get(i - 1).y()) {
                // Sideways jog determined by the incoming travel direction.
                int jx = 0, jz = 0;
                for (int k = Math.max(0, i - 2); k < i; k++) {
                    int dx = cells.get(k + 1).x() - cells.get(k).x();
                    int dz = cells.get(k + 1).z() - cells.get(k).z();
                    if (dx != 0 || dz != 0) {
                        jx = -Integer.compare(dz, 0);
                        jz = Integer.compare(dx, 0);
                    }
                }
                if (jx == 0 && jz == 0) {
                    jx = 1;
                }
                TopoPos jog = cell.offset(jx, 0, jz);
                if (forbidden.test(jog)) {
                    // Try the mirrored perpendicular, then any lateral step -
                    // a jog into protected space defeats the detour entirely.
                    jog = null;
                    for (int[] alt : new int[][] {{-jx, -jz}, {1, 0}, {-1, 0},
                            {0, 1}, {0, -1}}) {
                        TopoPos cand = cell.offset(alt[0], 0, alt[1]);
                        if (!forbidden.test(cand)) {
                            jog = cand;
                            break;
                        }
                    }
                }
                if (jog != null) {
                    result.add(jog);
                }
            }
            result.add(cell);
        }
        return result;
    }

    /**
     * Canonical arrival-connector centerline: an explicit extended switchback
     * so a tall descent is always a walkable ramp - every leg moves at least
     * two blocks horizontally per block of descent, counter-legs run in
     * parallel corridors offset far enough apart that their floors never
     * collide, and only the legs themselves are allowed to wander.
     */
    public static List<TopoPos> connectorCenterline(TopologyPlan topology, TopoPos arrival) {
        return connectorCenterlineRaw(topology, arrival);
    }

    /** Raw gateway centerline before crossing resolution. */
    public static List<TopoPos> connectorCenterlineRaw(TopologyPlan topology, TopoPos arrival) {
        TopoNode entrance = topology.node(topology.entranceId());
        if (entrance == null || entrance.kind() != NodeKind.ENTRANCE) {
            throw new IllegalArgumentException("topology has no valid entrance node");
        }
        TopoPos start = new TopoPos(HubLayout.EAST_EXIT.x(),
                HubLayout.EAST_EXIT.y(), HubLayout.EAST_EXIT.z());
        return line(start, entrance.pos());
    }

    /** Hash seam for PassageNetwork's raw path seeds. */
    public static long stableHashPublic(String text) {
        return stableHash(text);
    }

    // ------------------------------------------------------------------
    // Passage style
    // ------------------------------------------------------------------

    /** Deterministic style split: a share of the main-route legs and of the
     * side edges are abandoned timber workings; the rest stay irregular
     * natural cave. */
    static boolean isMineshaft(String segmentId, long seed, EdgeKind kind) {
        long hash = mix(seed ^ stableHash(segmentId) ^ 0x9E3779B97F4A7C15L);
        if (kind == EdgeKind.MAIN) {
            return Math.floorMod(hash, 5) < 2;
        }
        return Math.floorMod(hash, 2) == 0;
    }

    /** Corridor cells per discrete mineshaft piece before the cross-section
     * is re-rolled - Better Mineshafts' own corridor pieces are built at
     * roughly this scale (its BigTunnel/SmallTunnel pieces run 5-9 cells). */
    private static final int MINESHAFT_PIECE_LENGTH = 7;

    private static PassageProfile passageProfile(int index, int steps,
                                                 boolean mineshaft, long seed, String segmentId) {
        if (mineshaft) {
            // Discrete piece steps, not a smooth continuous tube: each
            // piece holds a fixed cross-section for its whole length, then
            // jumps abruptly to the next piece's roll - the actual reason
            // Better Mineshafts reads as built from distinct pieces rather
            // than one poured tunnel.
            int pieceIndex = index / MINESHAFT_PIECE_LENGTH;
            Random pieceRng = new Random(seed ^ stableHash(segmentId)
                    ^ (pieceIndex * 0xA24BAED4963EE407L) ^ 0xC2B2AE3D27D4EB4FL);
            int hw = 2 + pieceRng.nextInt(2); // 2-3
            int h = 3 + pieceRng.nextInt(2);  // 3-4
            return new PassageProfile(hw, h, true);
        }
        Random rng = new Random(seed ^ stableHash(segmentId) ^ 0xC2B2AE3D27D4EB4FL);
        double phaseA = rng.nextDouble() * Math.PI * 2;
        double phaseB = rng.nextDouble() * Math.PI * 2;
        double t = steps <= 1 ? 0.0 : (double) index / (steps - 1);
        // Smooth slow-varying noise so the tunnel widens and narrows instead
        // of jittering per block.
        double n = 0.5 + 0.28 * Math.sin(index * 0.11 + phaseA)
                + 0.22 * Math.sin(index * 0.033 + phaseB);
        n = Math.max(0.0, Math.min(1.0, n));
        int hw = 3 + (int) Math.round(n * 3.0); // 3..6 interior halfwidth
        int h = 4 + (int) Math.round(n * 4.0);  // 4..8 blocks tall
        return new PassageProfile(hw, h, false);
    }

    // ------------------------------------------------------------------
    // Structural plans
    // ------------------------------------------------------------------

    /** Carves a room centered on the node. Checkpoint rooms keep the
     * explicit flat 5x5 landing pad; caverns become connected irregular
     * lobes with standing pillars. */
    public static SegmentPlan node(TopologyPlan topology, TopoNode node,
                                   TopoBounds bounds, TopoPos arrival) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(arrival, "arrival");
        TopoNode canonicalNode = topology.node(node.id());
        if (canonicalNode == null || !canonicalNode.equals(node)) {
            throw new IllegalArgumentException("node is not part of the supplied topology: " + node.id());
        }
        Map<TopoPos, RankedBlock> blocks = new HashMap<>();
        TopoPos resolved = PassageNetwork.of(topology, arrival).center(node.id());
        TopoPos center = resolved != null ? resolved : node.pos();
        Random rng = new Random(topology.seed() ^ stableHash(node.id()) ^ 0x165667B19E3779F9L);

        switch (node.kind()) {
            case CAVERN -> addLobedCavern(blocks, center, rng, node.region());
            case ENTRANCE -> addRoom(blocks, center, rng, 3, 6, 9, 4, 6, node.region());
            case ROUTE -> addRoom(blocks, center, rng, 2, 5, 8, 3, 5, node.region());
            case CAVE -> addRoom(blocks, center, rng, 2, 4, 7, 3, 5, node.region());
            case POCKET -> addRoom(blocks, center, rng, 2, 3, 5, 2, 4, node.region());
        }

        if (node.has(NodeFeature.LANDMARK)) {
            addLandmark(blocks, center, roomReach(node.kind()));
        }
        Set<TopoPos> headCells = wholeRunHeadCells(topology, arrival);
        addWalkableFloor(blocks, center, Math.min(5, roomReach(node.kind()) - 1), headCells);
        if (node.has(NodeFeature.CHECKPOINT)) {
            addCheckpointPad(blocks, center, headCells);
        }
        if ("chaos".equals(node.region()) && node.kind() == NodeKind.CAVERN
                && !node.has(NodeFeature.CHECKPOINT)) {
            addContainedLavaPocket(blocks, center);
        }
        return finish("node:" + node.id(), blocks, bounds, arrival,
                wholeRunFeetCells(topology, arrival), topology);
    }

    static int roomReach(NodeKind kind) {
        return switch (kind) {
            case CAVERN -> 18;
            case ENTRANCE -> 9;
            case ROUTE -> 8;
            case CAVE -> 7;
            case POCKET -> 5;
        };
    }

    /** Horizontal keep-out: max lobe-center distance + max lobe radius + 2,
     * derived from the addLobedCavern/addRoom call-site params above so
     * external structures can never overlap carved room volume. Anchor at
     * the resolved (possibly lowered) node center, not node.pos(). */
    static int roomCarveHalfWidth(NodeKind kind) {
        return switch (kind) {
            case CAVERN -> 11 + 18 + 2;
            case ENTRANCE -> 6 + 9 + 2;
            case ROUTE -> 6 + 8 + 2;
            case CAVE -> 6 + 7 + 2;
            case POCKET -> 6 + 5 + 2;
        };
    }

    /** Vertical keep-out: max lobe offset + max lobe half-height + 2. */
    static int roomCarveHalfHeight(NodeKind kind) {
        return switch (kind) {
            case CAVERN -> 5 + 10 + 2;
            case ENTRANCE -> 2 + 6 + 2;
            case ROUTE -> 2 + 5 + 2;
            case CAVE -> 2 + 5 + 2;
            case POCKET -> 2 + 4 + 2;
        };
    }

    /** Max passage half-width (natural tunnels widen up to 6; mineshaft
     * pieces stay narrower) plus a margin for the wall-skin roughening
     * band, and max passage height likewise - the same keep-out-box
     * sizing idea as {@link #roomCarveHalfWidth} / {@link
     * #roomCarveHalfHeight}, but for edges instead of nodes. */
    private static final int EDGE_SEAL_HALF_WIDTH = 6 + 2;
    private static final int EDGE_SEAL_HALF_HEIGHT = 8 + 2;

    /** {@link UnreachablePocketSealer} originally only scanned each node's
     * own bounding box, because the sealer predates mineshaft corridors
     * and natural wall roughening both being able to leave a standable,
     * disconnected pocket inside an EDGE's own passage - not just inside a
     * room. That left every edge entirely unscanned: confirmed empirically
     * by running the sealer to full convergence (no cap) and finding it
     * stopped making progress (toSeal went empty) while hundreds of
     * trapped cells per seed still remained reachable from nowhere.
     * Returns a window of [min,max] box pairs along the edge's centerline,
     * strided so consecutive windows always overlap - a single box sized
     * to the whole edge's endpoint bounding box was considered and
     * rejected: a long diagonal edge's endpoint bbox can be far larger
     * than the actual carved corridor, which would turn an affordable
     * windowed scan into an unaffordable one across most of the dimension. */
    static List<TopoPos[]> edgeSealWindows(List<TopoPos> path) {
        List<TopoPos[]> windows = new ArrayList<>();
        if (path.isEmpty()) {
            return windows;
        }
        int stride = Math.max(1, EDGE_SEAL_HALF_WIDTH); // guarantees window overlap
        for (int i = 0; i < path.size(); i += stride) {
            TopoPos p = path.get(i);
            windows.add(new TopoPos[] {
                    p.offset(-EDGE_SEAL_HALF_WIDTH, -EDGE_SEAL_HALF_HEIGHT, -EDGE_SEAL_HALF_WIDTH),
                    p.offset(EDGE_SEAL_HALF_WIDTH, EDGE_SEAL_HALF_HEIGHT, EDGE_SEAL_HALF_WIDTH)});
        }
        TopoPos last = path.get(path.size() - 1);
        TopoPos[] lastWindow = windows.get(windows.size() - 1);
        if (!lastWindow[1].equals(last.offset(EDGE_SEAL_HALF_WIDTH, EDGE_SEAL_HALF_HEIGHT, EDGE_SEAL_HALF_WIDTH))) {
            windows.add(new TopoPos[] {
                    last.offset(-EDGE_SEAL_HALF_WIDTH, -EDGE_SEAL_HALF_HEIGHT, -EDGE_SEAL_HALF_WIDTH),
                    last.offset(EDGE_SEAL_HALF_WIDTH, EDGE_SEAL_HALF_HEIGHT, EDGE_SEAL_HALF_WIDTH)});
        }
        return windows;
    }

    /** A connected cluster of overlapping ellipsoid lobes around the center
     * with a handful of un-carved rock pillars left standing. Extent reaches
     * roughly 36+ blocks horizontally and 16+ vertically. */
    private static void addLobedCavern(Map<TopoPos, RankedBlock> blocks,
                                       TopoPos center, Random rng, String region) {
        int lobes = 3 + rng.nextInt(3); // 3-5 lobes
        List<int[]> pillarOffsets = new ArrayList<>();
        int pillars = 1 + rng.nextInt(3); // 1-3 standing pillars
        for (int p = 0; p < pillars; p++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            int dist = 6 + rng.nextInt(8);
            pillarOffsets.add(new int[] {
                    (int) Math.round(Math.cos(angle) * dist),
                    (int) Math.round(Math.sin(angle) * dist), 2});
        }
        WallRoughness wallRoughness = WallRoughness.forNode(rng, region);
        int[] previous = {0, 0, 0};
        for (int l = 0; l < lobes; l++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = l == 0 ? 0.0 : 4.0 + rng.nextDouble() * 7.0;
            int ox = (int) Math.round(Math.cos(angle) * dist);
            int oz = (int) Math.round(Math.sin(angle) * dist);
            // At least one lobe is vertically offset so the open span is
            // taller than a single lobe's height.
            int oy = l == 0 ? 0 : -5 + rng.nextInt(11);
            int rx = 12 + rng.nextInt(7);  // 12-18 horizontal lobe radius
            int ry = 7 + rng.nextInt(4);   // 7-10 lobe half-height
            if (l > 0) {
                // Two large ellipsoids at a modest center distance almost
                // always overlap in raw volume, but a vertically offset
                // lobe can still meet the rest of the cavern at a seam too
                // thin or short for a two-high step-1 walk - a standable
                // pocket a player can fall into and never climb out of
                // without placing a block. A generous connecting throat
                // guarantees real walkable clearance instead of leaving it
                // to ellipsoid-boundary luck. The throat stays a clean,
                // unroughened shape so it keeps the exact clearance the
                // connectivity fix depends on.
                connectLobes(blocks, center, previous, new int[] {ox, oy, oz}, pillarOffsets, 4, 3);
            }
            addEllipsoid(blocks, center.offset(ox, oy, oz), rx, ry, pillarOffsets, wallRoughness);
            previous = new int[] {ox, oy, oz};
        }
    }

    /** A continuous, smoothly-varying perturbation of the ellipsoid cutoff
     * (vanilla's own caves, and YUNG's Better Caves, threshold noise
     * instead of a hard geometric boundary - which is why both read as
     * irregular rock instead of a clean carved sphere). Three independently
     * seeded fields, each a closed-form sine sum rather than a
     * lattice-noise class (same "threshold a continuous field" idea, no
     * external API to get wrong from memory), combined so ALL THREE must
     * agree before the perturbation grows - Better Caves' actual technique
     * is carving only where every sampled noise layer clears its own
     * threshold, not one blended average. Amplitude is region-ramped: the
     * owner asked for this to read like a per-Y threshold table that gets
     * rougher "near the surface" - the "upper" region is this mine's
     * shallow band, so it gets the strongest perturbation, with "chaos"
     * (the deepest region) the tightest. */
    private record WallRoughness(double[] phaseA, double[] freqA,
                                 double[] phaseB, double[] freqB,
                                 double[] phaseC, double[] freqC, double amplitude) {
        static WallRoughness forNode(Random rng, String region) {
            double[] phaseA = new double[3];
            double[] freqA = new double[3];
            double[] phaseB = new double[3];
            double[] freqB = new double[3];
            double[] phaseC = new double[3];
            double[] freqC = new double[3];
            for (int i = 0; i < 3; i++) {
                phaseA[i] = rng.nextDouble() * Math.PI * 2;
                freqA[i] = 0.10 + rng.nextDouble() * 0.15;
                phaseB[i] = rng.nextDouble() * Math.PI * 2;
                freqB[i] = 0.25 + rng.nextDouble() * 0.35; // finer-grained second layer
                phaseC[i] = rng.nextDouble() * Math.PI * 2;
                freqC[i] = 0.45 + rng.nextDouble() * 0.35; // finest third layer
            }
            double amplitude = switch (region == null ? "" : region) {
                case "upper" -> 0.34;
                case "chaos" -> 0.20;
                default -> 0.26; // "middle" and any unrecognized region
            };
            return new WallRoughness(phaseA, freqA, phaseB, freqB, phaseC, freqC, amplitude);
        }

        private static double layer(int dx, int dy, int dz, double[] phase, double[] freq) {
            return (Math.sin(dx * freq[0] + phase[0]) + Math.sin(dy * freq[1] + phase[1])
                    + Math.sin(dz * freq[2] + phase[2])) / 3.0;
        }

        double sample(int dx, int dy, int dz) {
            double a = layer(dx, dy, dz, phaseA, freqA);
            double b = layer(dx, dy, dz, phaseB, freqB);
            double c = layer(dx, dy, dz, phaseC, freqC);
            if (Math.signum(a) != Math.signum(b) || Math.signum(a) != Math.signum(c)) {
                return 0.0; // the three layers disagree - no perturbation here
            }
            double sign = a >= 0 ? 1.0 : -1.0;
            double magnitude = Math.min(Math.abs(a), Math.min(Math.abs(b), Math.abs(c)));
            return sign * magnitude * amplitude;
        }
    }

    /** Carves a chain of overlapping spheres between two lobe centers,
     * purely additive, so it can only widen a connection and never removes
     * carved volume another pass (doorways, landmarks) may depend on. */
    private static void connectLobes(Map<TopoPos, RankedBlock> blocks, TopoPos center,
                                     int[] from, int[] to, List<int[]> pillars,
                                     int throatRx, int throatRy) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double dz = to[2] - from[2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(dist / Math.max(2, throatRx - 1)));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int px = from[0] + (int) Math.round(dx * t);
            int py = from[1] + (int) Math.round(dy * t);
            int pz = from[2] + (int) Math.round(dz * t);
            addEllipsoid(blocks, center.offset(px, py, pz), throatRx, throatRy, pillars);
        }
    }

    /** Small-to-medium irregular room of 2-3 overlapping lobes. */
    private static void addRoom(Map<TopoPos, RankedBlock> blocks, TopoPos center,
                                Random rng, int lobeCount, int rxMin, int rxMax,
                                int ryMin, int ryMax, String region) {
        WallRoughness wallRoughness = WallRoughness.forNode(rng, region);
        int[] previous = {0, 0, 0};
        for (int l = 0; l < lobeCount; l++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = l == 0 ? 0.0 : 2.0 + rng.nextDouble() * 4.0;
            int ox = (int) Math.round(Math.cos(angle) * dist);
            int oz = (int) Math.round(Math.sin(angle) * dist);
            int oy = l == 0 ? 0 : -2 + rng.nextInt(5);
            int rx = rxMin + rng.nextInt(Math.max(1, rxMax - rxMin + 1));
            int ry = ryMin + rng.nextInt(Math.max(1, ryMax - ryMin + 1));
            // Same unguaranteed-overlap gap as addLobedCavern's lobes: see
            // connectLobes for why a vertically offset lobe needs a real
            // connecting throat, not just ellipsoid-boundary luck.
            if (l > 0) {
                connectLobes(blocks, center, previous, new int[] {ox, oy, oz}, List.of(),
                        Math.max(2, rxMin - 1), Math.max(2, ryMin - 1));
            }
            addEllipsoid(blocks, center.offset(ox, oy, oz), rx, ry, List.of(), wallRoughness);
            previous = new int[] {ox, oy, oz};
        }
    }

    /** Carves OPEN cells inside the ellipsoid; cells inside a pillar column
     * stay solid to leave standing rock. */
    private static void addEllipsoid(Map<TopoPos, RankedBlock> blocks,
                                     TopoPos center, int rx, int ry,
                                     List<int[]> pillars) {
        addEllipsoid(blocks, center, rx, ry, pillars, null);
    }

    private static void addEllipsoid(Map<TopoPos, RankedBlock> blocks,
                                     TopoPos center, int rx, int ry,
                                     List<int[]> pillars, WallRoughness roughness) {
        for (int dy = -ry - 1; dy <= ry + 1; dy++) {
            for (int dz = -rx - 1; dz <= rx + 1; dz++) {
                for (int dx = -rx - 1; dx <= rx + 1; dx++) {
                    double n = normalized(dx, rx) + normalized(dy, ry) + normalized(dz, rx);
                    double threshold = 1.0;
                    // Roughen only the upper half of the lobe (walls and
                    // ceiling). The floor band below keeps the exact clean
                    // shape MineTraversal's step-1 walk model, and the M10c
                    // connectivity fix, already proved fully connected.
                    if (roughness != null && dy > -ry / 2) {
                        threshold += roughness.sample(dx, dy, dz);
                    }
                    if (n > threshold) {
                        // Just outside the carve: the wall/ceiling skin a
                        // player actually sees. Without this, nothing ever
                        // writes these cells and they stay whatever the
                        // dimension's default fill is (plain, untextured
                        // stone) - the palette/roughness above only ever
                        // decided what to CARVE, never what the solid rock
                        // bordering the carve looks like. Low priority (1,
                        // below OPEN's 2) so a legitimate open cell from an
                        // overlapping lobe or throat, processed in any
                        // order, always wins over this cosmetic skin.
                        if (n <= threshold + WALL_SKIN_MARGIN) {
                            put(blocks, center.offset(dx, dy, dz), BlockKind.WALL_SKIN, 1);
                        }
                        continue;
                    }
                    if (insidePillar(pillars, center, dx, dz)) {
                        // A standing pillar is solid, player-visible rock,
                        // not empty space left untouched - give it the same
                        // palette/texture write as the wall skin above.
                        put(blocks, center.offset(dx, dy, dz), BlockKind.WALL_SKIN, 1);
                        continue;
                    }
                    put(blocks, center.offset(dx, dy, dz), BlockKind.OPEN, 2);
                }
            }
        }
    }

    private static boolean insidePillar(List<int[]> pillars, TopoPos center,
                                        int dx, int dz) {
        for (int[] pillar : pillars) {
            int px = pillar[0];
            int pz = pillar[1];
            int pr = pillar[2];
            long ddx = dx - px;
            long ddz = dz - pz;
            if (ddx * ddx + ddz * ddz <= (long) pr * pr) {
                return true;
            }
        }
        return false;
    }

    /** Carves the winding passage connecting the two planned node centers. */
    public static SegmentPlan edge(TopologyPlan topology, TopoEdge edge,
                                   TopoBounds bounds, TopoPos arrival) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(edge, "edge");
        TopoNode a = topology.node(edge.a());
        TopoNode b = topology.node(edge.b());
        if (a == null || b == null) {
            throw new IllegalArgumentException("edge has an endpoint missing from topology: " + edge);
        }
        String id = edgeSegmentId(edge);
        List<TopoPos> path = passageCenterline(topology, edge);
        Map<TopoPos, RankedBlock> blocks = new HashMap<>();
        addPassage(blocks, path, isMineshaft(id, topology.seed(), edge.kind()),
                topology.seed(), id);
        return finish(id, blocks, bounds, arrival,
                wholeRunFeetCells(topology, arrival), topology);
    }

    /** The centerline a passage will follow (used by the dressing and ore
     * passes to stay consistent with the structural carve). */
    public static List<TopoPos> passageCenterline(TopologyPlan topology, TopoEdge edge) {
        TopoNode a = topology.node(edge.a());
        TopoNode b = topology.node(edge.b());
        if (a == null || b == null) {
            throw new IllegalArgumentException("edge has an endpoint missing from topology: " + edge);
        }
        String id = edgeSegmentId(edge);
        List<TopoPos> resolved = PassageNetwork.of(topology,
                new TopoPos(HubLayout.LANDING.x(), HubLayout.LANDING.y(),
                        HubLayout.LANDING.z())).path(id);
        if (resolved != null) {
            return resolved;
        }
        return avoidHubReserve(windingPath(a.pos(), b.pos(), topology.seed() ^ stableHash(id)));
    }

    /** Centerlines may never enter the generated-hub reserve: cells inside it
     * are deflected radially outward and the detour is re-walked, repeatedly,
     * until the whole line skirts the wall. The gateway band remains usable
     * because it is not part of the protected region. */
    static List<TopoPos> avoidHubReserve(List<TopoPos> cells) {
        return routeProtected(cells, SegmentGeometry::inHubFootprint);
    }

    /** Foreign edges get the full treatment: the gateway apron detour first
     * (it only knows its own zone), then the hub reserve with the connector
     * row and apron folded into the forbidden set - without the apron term,
     * a footprint deflection could exit through the east face straight into
     * apron columns whose carves would open the exclusive corridor. Every
     * placed landmark reserve is folded in whole: foreign routes have no
     * business at a safehouse wall, doorway included. */
    static List<TopoPos> avoidForeignReserve(List<TopoPos> cells,
                                             LandmarkLayout landmarks) {
        cells = avoidGatewayApron(cells);
        return routeProtected(cells,
                p -> inHubFootprintStrict(p) || onGatewayRow(p)
                        || inGatewayApron(p) || insideLandmarkReserve(p, landmarks));
    }

    /** An edge ending at a landmark still routes its middle like a foreign
     * edge - the whole reserve stays forbidden, doorway included. The only
     * path through a declared opening is the flat approach splice appended
     * at the endpoints; a meander that could legally cross its own door
     * column mid-route would arrive over the roof and merge-lock the drop.
     * The layout is threaded in, never read from the network cache: this
     * runs inside the PassageNetwork constructor, before any network could
     * be cached. */
    static List<TopoPos> avoidLandmarkReserve(List<TopoPos> cells,
                                              LandmarkLayout landmarks) {
        return avoidForeignReserve(cells, landmarks);
    }

    /** An entrance-touching edge keeps the hub's band-exempt treatment -
     * it leaves through the level junction at the floor - and honours
     * every landmark reserve whole. The second pass must keep the hub
     * predicate in its forbidden set: {@code clampYSteps}'s lateral jogs
     * could otherwise slide a sample into the clipped hub volume that the
     * landmark-only predicate cannot see, where {@code freeze} would then
     * refuse the resolved centreline. */
    static List<TopoPos> avoidEntranceLandmarkReserve(List<TopoPos> cells,
                                                      LandmarkLayout landmarks) {
        cells = avoidHubReserve(cells);
        return routeProtected(cells,
                p -> inHubFootprint(p) || insideLandmarkReserve(p, landmarks));
    }

    /** A route must clear the reserve 2-high: feet inside the box is
     * forbidden and feet one block below the box is equally forbidden -
     * otherwise the headroom cell lands inside write-protected rock and
     * {@code freeze} fails the whole run closed. */
    private static boolean insideLandmarkReserve(TopoPos p, LandmarkLayout landmarks) {
        return landmarks.isReserved(p) || landmarks.isReserved(p.offset(0, 1, 0));
    }

    /** The reserve footprint without the gateway-band exemption: foreign
     * routes may not occupy band columns at all. The band is open junction
     * space only to the connector and entrance edges - for any other route
     * it is a sealed pocket whose every approach column is reserve- or
     * apron-blocked. Cells below the reserve floor stay exempt so
     * legitimate under-hub corridors still pass. */
    static boolean inHubFootprintStrict(TopoPos p) {
        return Math.abs(p.x()) <= HubLayout.RESERVE_HALF
                && Math.abs(p.z()) <= HubLayout.RESERVE_HALF
                && p.y() >= HubLayout.INTERIOR_MIN_Y - 2;
    }

    private static List<TopoPos> routeProtected(List<TopoPos> cells,
                                                java.util.function.Predicate<TopoPos> inside) {
        // The y-clamp's sideways jogs can re-enter the footprint after the
        // deflection settles, so the whole detour+clamp retries until the
        // clamped output is provably clear.
        for (int attempt = 0; attempt < 6; attempt++) {
            cells = deflectFootprint(cells, inside);
            cells = clampYSteps(cells, inside);
            boolean clear = true;
            for (TopoPos c : cells) {
                if (inside.test(c)) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return cells;
            }
        }
        for (int i = 0; i < cells.size(); i++) {
            TopoPos c = cells.get(i);
            if (inside.test(c)) {
                StringBuilder dump = new StringBuilder();
                for (int j = Math.max(0, i - 4); j < Math.min(cells.size(), i + 5); j++) {
                    dump.append(j == i ? " >>" : "   ").append(cells.get(j)).append('\n');
                }
                throw new IllegalStateException("path could not be routed out of the "
                        + "protected reserve footprint; still inside after deflection: " + c
                        + " - report this fixture\n" + dump);
            }
        }
        return cells;
    }

    private static List<TopoPos> deflectFootprint(List<TopoPos> cells,
                                                  java.util.function.Predicate<TopoPos> inside) {
        for (int pass = 0; pass < 16; pass++) {
            boolean any = false;
            List<TopoPos> out = new ArrayList<>(cells.size());
            for (TopoPos c : cells) {
                TopoPos p = c;
                // The whole footprint column is off-limits, not just cells at
                // a protected height: resolution only ever moves samples in
                // Y, so a column kept outside here can never be dragged into
                // the clipped volume later. Cells step out through the
                // nearest reserve face.
                while (inside.test(p)) {
                    any = true;
                    int xExit = HubLayout.RESERVE_HALF + 1 - Math.abs(p.x());
                    int zExit = HubLayout.RESERVE_HALF + 1 - Math.abs(p.z());
                    if (xExit <= zExit) {
                        p = p.offset(p.x() >= 0 ? 1 : -1, 0, 0);
                    } else {
                        p = p.offset(0, 0, p.z() >= 0 ? 1 : -1);
                    }
                }
                out.add(p);
            }
            // Re-walk jumps created by the deflection.
            List<TopoPos> fixed = new ArrayList<>(out.size() * 2);
            for (TopoPos cur : out) {
                if (!fixed.isEmpty()) {
                    TopoPos prev = fixed.get(fixed.size() - 1);
                    int dm = Math.max(Math.abs(prev.x() - cur.x()),
                            Math.max(Math.abs(prev.y() - cur.y()), Math.abs(prev.z() - cur.z())));
                    if (dm > 1) {
                        for (TopoPos mid : lineClearing(prev, cur, inside)) {
                            if (!mid.equals(prev)) {
                                fixed.add(mid);
                            }
                        }
                        continue;
                    }
                }
                fixed.add(cur);
            }
            cells = fixed;
            // Re-walk mids can land back inside the reserve even when no
            // original cell needed deflecting this pass - they are only
            // safe once the whole settled line is clear.
            for (TopoPos c : cells) {
                if (inside.test(c)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                break;
            }
        }
        return cells;
    }

    /** The protected reserve as a floor-to-ceiling footprint: the reserved
     * square minus the gateway band columns, which are open junction space
     * shared with the connector by design. Routes below the reserve floor
     * are exempt - under-hub corridors at FEET_Y <= 37 keep their feet and
     * headroom below the clipped volume, and nothing in the merge can raise
     * them into it (no anchors exist outside the band). */
    static boolean inHubFootprint(TopoPos p) {
        // The band columns stay usable only inside the gateway band's own
        // y window - a centreline at y 39 or 60 there is still clipped by
        // the keepout, so it counts as inside and is deflected like any
        // other dead cell.
        return Math.abs(p.x()) <= HubLayout.RESERVE_HALF
                && Math.abs(p.z()) <= HubLayout.RESERVE_HALF
                && !(p.x() >= HubLayout.INTERIOR_HALF && Math.abs(p.z()) <= 3
                        && p.y() >= HubLayout.INTERIOR_MIN_Y
                        && p.y() <= HubLayout.ROOF_TOP_Y)
                && p.y() >= HubLayout.INTERIOR_MIN_Y - 2;
    }

    /** The gateway apron: the connector band plus its carved walls. Foreign
     * centerline samples inside it are pushed toward the nearest open exit
     * (east around the entrance flank, or north/south around the walls -
     * never west, which is the hub reserve) so no route crosses the
     * exclusive corridor within reach of its volume. Deeper or higher
     * routes are left alone. First and last cells stay pinned to their
     * node endpoints. */
    static List<TopoPos> avoidGatewayApron(List<TopoPos> cells) {
        for (int pass = 0; pass < 8; pass++) {
            boolean any = false;
            List<TopoPos> out = new ArrayList<>(cells.size());
            for (int i = 0; i < cells.size(); i++) {
                TopoPos p = cells.get(i);
                if (i != 0 && i != cells.size() - 1) {
                    while (inGatewayApron(p)) {
                        any = true;
                        p = apronStep(p);
                    }
                }
                out.add(p);
            }
            List<TopoPos> fixed = new ArrayList<>(out.size() * 2);
            for (TopoPos cur : out) {
                if (!fixed.isEmpty()) {
                    TopoPos prev = fixed.get(fixed.size() - 1);
                    int dm = Math.max(Math.abs(prev.x() - cur.x()),
                            Math.max(Math.abs(prev.y() - cur.y()), Math.abs(prev.z() - cur.z())));
                    if (dm > 1) {
                        for (TopoPos mid : lineClearing(prev, cur,
                                SegmentGeometry::inGatewayApron)) {
                            if (!mid.equals(prev)) {
                                fixed.add(mid);
                            }
                        }
                        continue;
                    }
                }
                fixed.add(cur);
            }
            cells = fixed;
            for (int i = 1; i < cells.size() - 1; i++) {
                if (inGatewayApron(cells.get(i))) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                break;
            }
        }
        // Hard guarantee: no interior sample may remain on the connector's
        // own column row (z == 0 inside the band) - that is the one place a
        // foreign path can cluster against the fixed anchor and oscillate.
        // A crossing can only be expelled through the east flank, so each
        // residual cell marches +x until it leaves the band; interpolation
        // stragglers are swept on the next round. Cells that stop on the
        // entrance room's east flank are safe: they share no column with
        // the connector and merge into the chamber pocket.
        for (int round = 0; round < 16; round++) {
            boolean residue = false;
            List<TopoPos> swept = new ArrayList<>(cells.size());
            for (int i = 0; i < cells.size(); i++) {
                TopoPos p = cells.get(i);
                if (i != 0 && i != cells.size() - 1) {
                    while (onGatewayRow(p)) {
                        residue = true;
                        p = p.offset(1, 0, 0);
                    }
                }
                swept.add(p);
            }
            List<TopoPos> fixed = new ArrayList<>(swept.size() * 2);
            for (TopoPos cur : swept) {
                if (!fixed.isEmpty()) {
                    TopoPos prev = fixed.get(fixed.size() - 1);
                    int dm = Math.max(Math.abs(prev.x() - cur.x()),
                            Math.max(Math.abs(prev.y() - cur.y()), Math.abs(prev.z() - cur.z())));
                    if (dm > 1) {
                        for (TopoPos mid : lineClearing(prev, cur,
                                SegmentGeometry::onGatewayRow)) {
                            if (!mid.equals(prev)) {
                                fixed.add(mid);
                            }
                        }
                        continue;
                    }
                }
                fixed.add(cur);
            }
            cells = fixed;
            for (int i = 1; i < cells.size() - 1; i++) {
                if (onGatewayRow(cells.get(i))) {
                    residue = true;
                    break;
                }
            }
            if (!residue) {
                return clampYSteps(cells);
            }
        }
        for (int i = 1; i < cells.size() - 1; i++) {
            if (onGatewayRow(cells.get(i))) {
                throw new IllegalStateException("path could not be routed off the gateway "
                        + "connector row; still occupied after sweeping: " + cells.get(i)
                        + " - report this fixture");
            }
        }
        return clampYSteps(cells);
    }

    /** The connector's own column row: only cells sharing the band's z == 0
     * columns inside its x span can cluster against the fixed anchor. */
    static boolean onGatewayRow(TopoPos p) {
        return p.z() == 0 && p.x() >= HubLayout.EAST_EXIT.x()
                && p.x() <= HubLayout.ENTRANCE.x() + 2
                && p.y() >= HubLayout.FEET_Y - 9 && p.y() <= HubLayout.FEET_Y + 9;
    }

    /** One step toward the closest apron exit: east past the entrance
     * flank, or the nearer of the two z walls. Ties prefer the z exits so
     * crossings tend to wrap the corridor, not crowd the entrance room. */
    private static TopoPos apronStep(TopoPos p) {
        int east = HubLayout.ENTRANCE.x() + 3 - p.x();
        int north = 9 - Math.abs(p.z());
        if (east < north) {
            return p.offset(1, 0, 0);
        }
        return p.offset(0, 0, p.z() >= 0 ? 1 : -1);
    }

    /** A cell whose carved volume could meet the gateway corridor: the
     * connector band plus its side walls and enough vertical window that a
     * tall foreign carve cannot open into its floor or ceiling. */
    static boolean inGatewayApron(TopoPos p) {
        return p.x() >= HubLayout.EAST_EXIT.x() && p.x() <= HubLayout.ENTRANCE.x() + 2
                && Math.abs(p.z()) <= 8
                && p.y() >= HubLayout.FEET_Y - 9 && p.y() <= HubLayout.FEET_Y + 9;
    }

    /**
     * Bootstrap connector from the outer edge of the intact M1 arrival pad to
     * the entrance node: a winding descending natural passage that never
     * edits the reserved pad.
     */
    public static SegmentPlan entryConnector(TopologyPlan topology, TopoBounds bounds,
                                             TopoPos arrival) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(arrival, "arrival");
        TopoNode entrance = topology.node(topology.entranceId());
        if (entrance == null || entrance.kind() != NodeKind.ENTRANCE) {
            throw new IllegalArgumentException("topology has no valid entrance node");
        }
        String id = connectorSegmentId(entrance.id());
        List<TopoPos> path = connectorCenterline(topology, arrival);
        Map<TopoPos, RankedBlock> blocks = new HashMap<>();
        addPassage(blocks, path, false, topology.seed(), id);
        return finish(id, blocks, bounds, arrival,
                wholeRunFeetCells(topology, arrival), topology);
    }

    /**
     * The support/decoration pass for one structural segment. Floors are
     * rewritten so every centerline step stands on solid ground even where a
     * later carve removed rock; mineshaft passages gain timber frames,
     * rails, and lanterns; nodes gain lit landmark points. Decorations never
     * occupy the two-high walk column above the centerline.
     */
    /** Whole-run walkable clearance index, computed once per topology:
     * every centerline feet/head cell (connector + all edges) and every
     * checkpoint pad column. Dressing solids and lamps are filtered against
     * it so supports, beams, and lanterns can never occupy another route's
     * two-high walkable space. Cached by topology fingerprint. */
    private record Clearance(String fingerprint, TopoPos arrival, String catalog,
                             Set<TopoPos> all, Set<TopoPos> feet, Set<TopoPos> heads) {}
    private static Clearance cachedClearance;

    public static Set<TopoPos> wholeRunClearance(TopologyPlan topology, TopoPos arrival) {
        return clearance(topology, arrival).all();
    }

    /** Centerline feet cells only (no heads). */
    public static Set<TopoPos> wholeRunFeetCells(TopologyPlan topology, TopoPos arrival) {
        return clearance(topology, arrival).feet();
    }

    /** Cells that are some route's head space without being anyone's feet. */
    public static Set<TopoPos> wholeRunHeadCells(TopologyPlan topology, TopoPos arrival) {
        return clearance(topology, arrival).heads();
    }

    private static synchronized Clearance clearance(TopologyPlan topology, TopoPos arrival) {
        // The resolved centerlines depend on the landmark-definition epoch;
        // key the index on it so a catalog reload cannot serve a stale set.
        String catalogKey = PassageNetwork.of(topology, arrival).catalogFingerprint();
        if (cachedClearance != null
                && cachedClearance.fingerprint().equals(topology.fingerprint())
                && cachedClearance.arrival().equals(arrival)
                && cachedClearance.catalog().equals(catalogKey)) {
            return cachedClearance;
        }
        Set<TopoPos> reserved = new HashSet<>();
        Set<TopoPos> feet = new HashSet<>();
        List<List<TopoPos>> lines = new ArrayList<>();
        TopoNode hubEntrance = topology.node(topology.entranceId());
        if (hubEntrance != null && hubEntrance.kind() == NodeKind.ENTRANCE) {
            lines.add(connectorCenterline(topology, arrival));
        }
        for (TopoEdge edge : topology.edges()) {
            lines.add(passageCenterline(topology, edge));
        }
        for (List<TopoPos> line : lines) {
            for (TopoPos cell : line) {
                feet.add(cell);
                reserved.add(cell);
                // The cell a route stands on is load-bearing: decorations,
                // lamps, and junction clutter must never displace it, and
                // cross-plan writes must treat it as occupied walk space.
                reserved.add(cell.offset(0, -1, 0));
            }
        }
        Set<TopoPos> head = new HashSet<>();
        for (List<TopoPos> line : lines) {
            for (TopoPos cell : line) {
                TopoPos above = cell.offset(0, 1, 0);
                reserved.add(above);
                if (!feet.contains(above)) {
                    head.add(above);
                }
            }
            for (int i = 1; i < line.size(); i++) {
                TopoPos a = line.get(i - 1);
                TopoPos b = line.get(i);
                if (a.x() != b.x() && a.z() != b.z()) {
                    int y = Math.max(a.y(), b.y());
                    for (TopoPos corner : List.of(new TopoPos(a.x(), y, b.z()),
                            new TopoPos(b.x(), y, a.z()))) {
                        for (int dy = 0; dy <= 1; dy++) {
                            TopoPos body = corner.offset(0, dy, 0);
                            reserved.add(body);
                            if (!feet.contains(body)) head.add(body);
                        }
                    }
                }
                if (a.y() != b.y()) {
                    TopoPos lower = a.y() < b.y() ? a : b;
                    TopoPos jumpHead = lower.offset(0, 2, 0);
                    reserved.add(jumpHead);
                    if (!feet.contains(jumpHead)) head.add(jumpHead);
                }
            }
        }
        for (int x = -HubLayout.INTERIOR_HALF; x <= HubLayout.INTERIOR_HALF; x++) {
            for (int z = -HubLayout.INTERIOR_HALF; z <= HubLayout.INTERIOR_HALF; z++) {
                for (int y = HubLayout.INTERIOR_MIN_Y; y <= HubLayout.ROOF_TOP_Y; y++) {
                    reserved.add(new TopoPos(x, y, z));
                }
            }
        }
        for (TopoNode node : topology.nodes()) {
            if (node.has(NodeFeature.CHECKPOINT)) {
                TopoPos center = PassageNetwork.of(topology, arrival).center(node.id());
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dy = 0; dy <= 2; dy++) {
                            reserved.add(center.offset(dx, dy, dz));
                        }
                    }
                }
            }
        }
        cachedClearance = new Clearance(topology.fingerprint(), arrival, catalogKey,
                Set.copyOf(reserved), Set.copyOf(feet), Set.copyOf(head));
        return cachedClearance;
    }

    public static SegmentPlan dress(TopologyPlan topology, String structuralId,
                                    TopoBounds bounds, TopoPos arrival) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(structuralId, "structuralId");
        Map<TopoPos, RankedBlock> blocks = new HashMap<>();
        if (structuralId.startsWith("edge:")) {
            TopoEdge edge = edgeFromSegmentId(topology, structuralId);
            if (edge == null) {
                throw new IllegalArgumentException("no edge for dress segment " + structuralId);
            }
            dressEdge(blocks, topology, edge, structuralId, arrival);
        } else if (structuralId.startsWith("node:")) {
            TopoNode node = topology.node(structuralId.substring("node:".length()));
            if (node == null) {
                throw new IllegalArgumentException("no node for dress segment " + structuralId);
            }
            dressNode(blocks, topology, node, arrival);
        } else if (structuralId.startsWith("connector:arrival:")) {
            TopoNode entrance = topology.node(topology.entranceId());
            if (entrance == null) {
                throw new IllegalArgumentException("topology has no entrance for " + structuralId);
            }
            List<TopoPos> path = connectorCenterline(topology, arrival);
            addSupportedFloor(blocks, path, BlockKind.ROCK,
                    wholeRunHeadCells(topology, arrival),
                    wholeRunClearance(topology, arrival),
                    wholeRunFeetCells(topology, arrival));
        } else {
            throw new IllegalArgumentException("cannot dress unknown segment " + structuralId);
        }
        // No dressing may occupy another route's two-high walkable space:
        // supports and lamps that would land on a crossing path cell are
        // dropped (floors still write - a paved cell is a step, not a hole).
        Set<TopoPos> clearance = wholeRunClearance(topology, arrival);
        blocks.entrySet().removeIf(entry ->
                clearance.contains(entry.getKey()) && isJunctionObstacle(entry.getValue()));
        return finish("dress:" + structuralId, blocks, bounds, arrival, clearance,
                topology);
    }

    /** Anything that would wall off a shared walk cell: posts, beams,
     * fences, lanterns, and lamp sills. Floors (priority >= 7), rails,
     * open cells, and pads stay. */
    private static boolean isJunctionObstacle(RankedBlock block) {
        return switch (block.kind()) {
            case TIMBER, FENCE, LIGHT -> true;
            case ROCK, PLANKS -> block.priority() < 7;
            default -> false;
        };
    }

    private static void dressEdge(Map<TopoPos, RankedBlock> blocks,
                                  TopologyPlan topology, TopoEdge edge, String id,
                                  TopoPos arrival) {
        List<TopoPos> path = passageCenterline(topology, edge);
        boolean mineshaft = isMineshaft(id, topology.seed(), edge.kind());
        Set<TopoPos> clearance = wholeRunClearance(topology, arrival);
        addSupportedFloor(blocks, path, mineshaft ? BlockKind.PLANKS : BlockKind.ROCK,
                wholeRunHeadCells(topology, arrival), clearance,
                wholeRunFeetCells(topology, arrival));
        if (!mineshaft) {
            addPassageLights(blocks, path, topology.seed(), id, clearance,
                    PassageNetwork.activeLayout());
            return;
        }
        Random rng = new Random(topology.seed() ^ stableHash(id) ^ 0xBF58476D1CE4E5B9L);
        int spacing = 7 + rng.nextInt(3); // frames every 7-9 centerline cells
        int offset = rng.nextInt(spacing);
        LandmarkLayout landmarks = PassageNetwork.activeLayout();
        for (int i = 0; i < path.size(); i++) {
            TopoPos cell = path.get(i);
            PassageProfile profile = passageProfile(i, path.size(), true, topology.seed(), id);
            if ((i + offset) % spacing == 0 && i > 0 && i < path.size() - 1) {
                // Posts sit to the sides and the beam overhead (see
                // addTimberFrame), never on the centerline floor cell, so a
                // support frame never breaks the rail passing under it.
                addTimberFrame(blocks, path, i, profile, clearance, landmarks);
            }
            if (!landmarks.isReserved(cell)) {
                // Every centerline cell gets a rail: a real minecart line is
                // one continuous track, not alternating rail/gap tiles. Rails
                // sit on the plank walkway; the apply-side support check
                // drops any that would float. Rails are walk-through
                // decoration, so shared junction cells may carry them too.
                // Landmark reserves are off-limits even where the corridor
                // carves through an opening - the room owns its doorway.
                put(blocks, cell, BlockKind.RAIL, 4);
            }
        }
    }

    /** Timber frame: two posts just inside the walls, a beam under the
     * ceiling, and a lantern on each post head. Nothing below y+3 on the
     * centerline, so players always keep three blocks of clearance. */
    private static void addTimberFrame(Map<TopoPos, RankedBlock> blocks,
                                       List<TopoPos> path, int i,
                                       PassageProfile profile, Set<TopoPos> clearance,
                                       LandmarkLayout landmarks) {
        TopoPos cell = path.get(i);
        TopoPos ahead = path.get(Math.min(i + 1, path.size() - 1));
        TopoPos behind = path.get(Math.max(i - 1, 0));
        int ddx = ahead.x() - behind.x();
        int ddz = ahead.z() - behind.z();
        int len = Math.max(1, Math.max(Math.abs(ddx), Math.abs(ddz)));
        ddx /= len;
        ddz /= len;
        int px = -ddz;
        int pz = ddx;
        int off = Math.max(1, profile.halfWidth() - 1);
        for (int side = -1; side <= 1; side += 2) {
            TopoPos post = cell.offset(px * off * side, 0, pz * off * side);
            if (clearance.contains(post) || clearance.contains(post.offset(0, 1, 0))
                    || landmarks.isReserved(post)) {
                continue; // would stand inside another route's walk column
                          // or inside a landmark reserve
            }
            put(blocks, post.offset(0, -1, 0), BlockKind.ROCK, 4); // sill under the post
            for (int dy = 0; dy <= 2; dy++) {
                put(blocks, post.offset(0, dy, 0), BlockKind.TIMBER, 5);
            }
            put(blocks, post.offset(0, 3, 0), BlockKind.LIGHT, 6); // lantern on the post head
        }
        for (int k = -(off - 1); k <= off - 1; k++) {
            TopoPos beam = cell.offset(px * k, 3, pz * k);
            if (!clearance.contains(beam) && !landmarks.isReserved(beam)) {
                put(blocks, beam, BlockKind.TIMBER, 4); // beam
            }
        }
    }

    /** Sparse warm lamps beside the route on natural passages: off to one
     * side at floor level so the walls and ore stay readable. */
    private static void addPassageLights(Map<TopoPos, RankedBlock> blocks,
                                         List<TopoPos> path, long seed, String id,
                                         Set<TopoPos> clearance,
                                         LandmarkLayout landmarks) {
        Random rng = new Random(seed ^ stableHash(id) ^ 0x94D049BB133111EBL);
        int spacing = 11 + rng.nextInt(6); // every 11-16 cells
        int offset = rng.nextInt(spacing);
        for (int i = 2; i < path.size() - 2; i++) {
            if ((i + offset) % spacing != 0) {
                continue;
            }
            TopoPos cell = path.get(i);
            PassageProfile profile = passageProfile(i, path.size(), false, seed, id);
            int side = (i / spacing) % 2 == 0 ? 1 : -1;
            int reach = Math.max(1, profile.halfWidth() - 1);
            TopoPos ahead = path.get(Math.min(i + 1, path.size() - 1));
            TopoPos behind = path.get(Math.max(i - 1, 0));
            int ddx = ahead.x() - behind.x();
            int ddz = ahead.z() - behind.z();
            int len = Math.max(1, Math.max(Math.abs(ddx), Math.abs(ddz)));
            ddx /= len;
            ddz /= len;
            TopoPos lamp = cell.offset(-ddz * reach * side, 0, ddx * reach * side);
            if (clearance.contains(lamp) || clearance.contains(lamp.offset(0, 1, 0))
                    || landmarks.isReserved(lamp)) {
                continue;
            }
            put(blocks, lamp.offset(0, -1, 0), BlockKind.ROCK, 4);
            put(blocks, lamp, BlockKind.LIGHT, 5);
        }
    }

    private static void dressNode(Map<TopoPos, RankedBlock> blocks,
                                  TopologyPlan topology, TopoNode node, TopoPos arrival) {
        TopoPos resolved = PassageNetwork.of(topology, arrival).center(node.id());
        TopoPos center = resolved != null ? resolved : node.pos();
        LandmarkLayout landmarks = PassageNetwork.activeLayout();
        if (node.has(NodeFeature.CHECKPOINT)) {
            // Lanterns on the four pad corners light the shared station.
            for (int[] corner : new int[][] {{3, 3}, {3, -3}, {-3, 3}, {-3, -3}}) {
                TopoPos sill = center.offset(corner[0], -1, corner[1]);
                TopoPos lamp = center.offset(corner[0], 0, corner[1]);
                if (!landmarks.isReserved(sill) && !landmarks.isReserved(lamp)) {
                    put(blocks, sill, BlockKind.ROCK, 6);
                    put(blocks, lamp, BlockKind.LIGHT, 6);
                }
            }
        }
        // A small ring of lamps around the room center marks the junction.
        Random rng = new Random(topology.seed() ^ stableHash(node.id()) ^ 0x2545F4914F6CDD1DL);
        int lamps = node.kind() == NodeKind.CAVERN ? 5 : 3;
        int ring = node.kind() == NodeKind.POCKET ? 2 : 3;
        for (int l = 0; l < lamps; l++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            TopoPos lamp = center.offset(
                    (int) Math.round(Math.cos(angle) * ring), 0,
                    (int) Math.round(Math.sin(angle) * ring));
            if (landmarks.isReserved(lamp) || landmarks.isReserved(lamp.offset(0, -1, 0))) {
                continue;
            }
            put(blocks, lamp.offset(0, -1, 0), BlockKind.ROCK, 4);
            put(blocks, lamp, BlockKind.LIGHT, 5);
        }
    }

    /** Up-to-3-wide solid floor under every centerline step, perpendicular
     * to travel where space permits. Highest priority: a support cell must
     * survive decoration collisions (e.g. a frame post landing on a lower
     * step's floor level). Cells inside another route's two-high clearance
     * are left alone - that crossing resolves as a shared open junction. */
    private static void addSupportedFloor(Map<TopoPos, RankedBlock> blocks,
                                          List<TopoPos> path, BlockKind floor,
                                          Set<TopoPos> headCells,
                                          Set<TopoPos> clearance,
                                          Set<TopoPos> feetCells) {
        for (int i = 0; i < path.size(); i++) {
            TopoPos cell = path.get(i);
            // Write the support unless it would wall off another route's
            // head space: paving a head cell creates an impassable two-high
            // wall, while paving a feet cell is just a one-block bump the
            // crossing route steps over (a shared junction, not a hole).
            TopoPos below = cell.offset(0, -1, 0);
            if (headCells != null && headCells.contains(below)) {
                continue;
            }
            put(blocks, below, floor, 8);
            if (feetCells != null && feetCells.contains(below)) {
                // Shared junction bump: the other route's feet cell is now a
                // solid step - open its headroom so both routes can stand on
                // it instead of finding a two-high wall.
                put(blocks, below.offset(0, 2, 0), BlockKind.OPEN, 9);
            }
            TopoPos ahead = path.get(Math.min(i + 1, path.size() - 1));
            TopoPos behind = path.get(Math.max(i - 1, 0));
            int ddx = ahead.x() - behind.x();
            int ddz = ahead.z() - behind.z();
            if (ddx == 0 && ddz == 0) {
                continue;
            }
            int len = Math.max(1, Math.max(Math.abs(ddx), Math.abs(ddz)));
            int px = -ddz / len;
            int pz = ddx / len;
            for (int side = -1; side <= 1; side += 2) {
                TopoPos wing = cell.offset(px * side, -1, pz * side);
                if (!clearance.contains(wing) && !clearance.contains(wing.offset(0, 1, 0))
                        && !clearance.contains(wing.offset(0, 2, 0))) {
                    put(blocks, wing, floor, 7);
                }
            }
        }
    }

    /** Carves the open interior of a winding passage: per-cell elliptical
     * cross-section with smoothly varying halfwidth/height. Walls are left
     * as natural rock; the dressing pass adds floors and frames. */
    private static void addPassage(Map<TopoPos, RankedBlock> blocks, List<TopoPos> path,
                                   boolean mineshaft, long seed, String segmentId) {
        if (path.size() > MAX_CENTERLINE_STEPS) {
            throw new IllegalArgumentException("segment centerline exceeds " + MAX_CENTERLINE_STEPS + " steps");
        }
        for (int i = 0; i < path.size(); i++) {
            TopoPos cell = path.get(i);
            PassageProfile profile = passageProfile(i, path.size(), mineshaft, seed, segmentId);
            int hw = profile.halfWidth();
            int h = profile.height();
            int reach = hw + 1;
            for (int dy = -1; dy <= h + 1; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    for (int dx = -reach; dx <= reach; dx++) {
                        boolean openBand = dy >= 0 && dy < h;
                        boolean inside;
                        boolean nearSkin;
                        if (profile.mineshaft()) {
                            // Boxy, piece-like cross-section (the actual
                            // technique YUNG's Better Mineshafts uses:
                            // rectangular pieces with probability-rolled
                            // edge fill) instead of a smooth ellipse - a
                            // rectangle with a per-cell roughened edge so
                            // it reads as dug and timbered, not poured.
                            boolean inBox = Math.abs(dx) <= hw && Math.abs(dz) <= hw;
                            boolean onEdge = Math.abs(dx) == hw || Math.abs(dz) == hw;
                            inside = inBox && (!onEdge
                                    || roughenKeepsOpen(cell, dx, dy, dz, seed, segmentId));
                            nearSkin = !inside && Math.abs(dx) <= hw + 1 && Math.abs(dz) <= hw + 1;
                        } else {
                            double n = normalized(dx, hw) + normalized(dz, hw);
                            inside = n <= 1.0;
                            nearSkin = !inside && n <= 1.0 + WALL_SKIN_MARGIN;
                        }
                        if (!inside) {
                            // Side-wall skin just outside the carved
                            // cross-section: otherwise nothing ever writes
                            // it and it stays whatever the dimension's
                            // default fill is (plain, untextured stone),
                            // same gap the dressing pass's floor write
                            // doesn't cover. Low priority so an overlapping
                            // carve elsewhere always wins.
                            if (openBand && nearSkin) {
                                put(blocks, cell.offset(dx, dy, dz), BlockKind.WALL_SKIN, 1);
                            }
                            continue;
                        }
                        if (openBand) {
                            put(blocks, cell.offset(dx, dy, dz), BlockKind.OPEN, 2);
                        } else if (dy == h) {
                            // Ceiling skin: the loop reaches dy==h but the
                            // open band excludes it, so it was never
                            // written either.
                            put(blocks, cell.offset(dx, dy, dz), BlockKind.WALL_SKIN, 1);
                        }
                    }
                }
            }
        }
    }

    /** Deterministic per-cell roll: most of a mineshaft box's edge cells
     * stay open, a minority stay solid, roughening an otherwise perfectly
     * boxy cross-section into something that reads as dug rather than
     * extruded. The exact center column (dx=dz=0) is never an edge cell,
     * so the walkable path itself is never affected by this roll. */
    private static boolean roughenKeepsOpen(TopoPos cell, int dx, int dy, int dz,
                                            long seed, String segmentId) {
        long hash = mix(seed ^ stableHash(segmentId)
                ^ ((long) (cell.x() + dx) * 0x9E3779B97F4A7C15L)
                ^ ((long) (cell.y() + dy) * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) (cell.z() + dz) * 0x165667B19E3779F9L));
        return Math.floorMod(hash, 10) < 7;
    }

    // ------------------------------------------------------------------
    /**
     * The generated expedition hub: a 45x45 underground hall (floor y=41,
     * feet y=42, roof capped at y=59) built entirely in code - carved
     * interior, stone-brick shell, timber, lanterns, portal-arch dressing,
     * the lodestone return console, the Dispatch barrel console, and a clear
     * 5x5 landing. Runs as its own preparation segment after structural
     * carving and is the only segment allowed to write the hub reserve.
     */
    public static SegmentPlan hub(TopologyPlan topology, TopoBounds bounds, TopoPos arrival) {
        Map<TopoPos, BlockOperation> ops = new HashMap<>();
        java.util.function.BiConsumer<TopoPos, BlockOperation> put = (pos, op) -> {
            if (bounds.contains(pos)) ops.put(pos, op);
        };
        int hi = HubLayout.OUTER_HALF;      // 22
        int ii = HubLayout.INTERIOR_HALF;   // 21
        int fy = HubLayout.FLOOR_Y;         // 41
        int gy = HubLayout.ROOF_TOP_Y;      // 59 roof cap
        // Interior air so the hub is open regardless of surrounding fill.
        for (int x = -ii; x <= ii; x++) for (int z = -ii; z <= ii; z++)
            for (int y = fy + 1; y <= gy - 2; y++)
                put.accept(new TopoPos(x, y, z),
                        new BlockOperation(new TopoPos(x, y, z), BlockKind.OPEN));
        // Floor slab (y=41) and roof cap (y=58..59).
        for (int x = -hi; x <= hi; x++) for (int z = -hi; z <= hi; z++) {
            boolean edge = Math.abs(x) == hi || Math.abs(z) == hi;
            put.accept(new TopoPos(x, fy, z), marker(x, fy, z,
                    edge ? "minecraft:deepslate_bricks" : "minecraft:stone_bricks"));
            put.accept(new TopoPos(x, gy - 1, z), marker(x, gy - 1, z,
                    "minecraft:stone_bricks"));
            if (edge) put.accept(new TopoPos(x, gy, z), marker(x, gy, z,
                    "minecraft:deepslate_bricks"));
        }
        // Shell walls y42..57: stone bricks with deepslate pilasters and an
        // oak beam course at the top. Three of the four walls leave a
        // 5-wide gateway opening (east: the real topology-connected
        // gateway; west and south: cosmetic doors carved out to the same
        // connector corridor below) - the north wall, where the portal
        // frame stands, stays fully solid.
        for (int y = fy + 1; y <= gy - 2; y++) {
            for (int v = -hi; v <= hi; v++) {
                for (int[] w : new int[][] {{hi, v}, {-hi, v}, {v, hi}, {v, -hi}}) {
                    TopoPos pos = new TopoPos(w[0], y, w[1]);
                    if (y >= fy + 1 && y <= fy + 4) {
                        if (w[0] == hi && Math.abs(w[1]) <= 2) continue; // east gateway
                        if (w[0] == -hi && Math.abs(w[1]) <= 2) continue; // west door
                        if (w[1] == hi && Math.abs(w[0]) <= 2) continue; // south door
                    }
                    boolean pilaster = Math.floorMod(v, 8) == 0;
                    String block = y == gy - 2 ? "minecraft:oak_log"
                            : pilaster ? "minecraft:deepslate_bricks"
                            : "minecraft:stone_bricks";
                    put.accept(pos, marker(pos.x(), pos.y(), pos.z(), block));
                }
            }
        }
        // Free-standing portal frame around the teleporter console (M10e):
        // a 7-wide x 9-high crying-obsidian/copper ring players walk through
        // to reach it, instead of just a lodestone sitting in the open.
        // TeleporterManager's interaction is purely position-based (it
        // matches HubLayout.TELEPORTER_CONSOLE exactly, not a block type),
        // so dressing the console this way needs no change there.
        int portalZ = HubLayout.TELEPORTER_CONSOLE.z();
        for (int x = -4; x <= 4; x++) for (int y = fy + 1; y <= fy + 9; y++) {
            boolean frame = Math.abs(x) == 4 || y == fy + 9;
            if (!frame) continue; // leave the interior open to walk through
            String block = Math.floorMod(x + y, 2) == 0
                    ? "minecraft:crying_obsidian" : "minecraft:copper_block";
            put.accept(new TopoPos(x, y, portalZ), marker(x, y, portalZ, block));
        }
        // Timber columns with lanterns on top, placed away from the lanes.
        for (int[] c : new int[][] {{14, 14}, {-14, 14}, {14, -10}, {-14, -10}}) {
            for (int y = fy + 1; y <= fy + 8; y++)
                put.accept(new TopoPos(c[0], y, c[1]),
                        marker(c[0], y, c[1], "minecraft:oak_log"));
            put.accept(new TopoPos(c[0], fy + 9, c[1]),
                    marker(c[0], fy + 9, c[1], "minecraft:lantern"));
        }
        // Gathering benches along the south wall, away from the landing lane.
        for (int x = -16; x <= -6; x += 3) for (int x2 = x; x2 <= x + 1; x2++)
            put.accept(new TopoPos(x2, fy + 1, 18),
                    marker(x2, fy + 1, 18, "minecraft:oak_slab"));
        for (int x = 8; x <= 18; x += 3) for (int x2 = x; x2 <= x + 1; x2++)
            put.accept(new TopoPos(x2, fy + 1, 19),
                    marker(x2, fy + 1, 19, "minecraft:oak_slab"));
        // West and south doors are cosmetic extra routes into the mine
        // (owner's request for 3 visible paths off the spawn room): all
        // three doors lead into the exact same single open hub interior,
        // which already connects to the real east gateway - no separate
        // routing is needed or wanted. Each gets a short straight approach
        // stub outside the wall (an entrance foyer, not a flat hole in
        // rock), stopping the instant it would re-enter the hub's own
        // footprint, which the interior-air loop already carved open.
        //
        // Starts exactly AT the wall (x=-hi / z=hi), not one block beyond
        // it: the wall-opening loop above only skips placing a wall
        // marker there, it never carves that exact cell open itself - the
        // real east gateway only works because entryConnector's own path
        // happens to carve through that same cell as a side effect. These
        // two doors have no such segment, so the stub must cover it
        // directly or the "opening" stays solid, uncarved default fill
        // behind an undecorated patch of wall. doorY matches FEET_Y (feet
        // at fy+1, head at fy+2), the same convention the rest of the hub
        // and the door gap itself (fy+1..fy+4) already use.
        int doorY = fy + 1;
        carveStubCorridor(put, new TopoPos(-hi, doorY, 0), new TopoPos(-hi - 8, doorY, 0));
        carveStubCorridor(put, new TopoPos(0, doorY, hi), new TopoPos(0, doorY, hi + 8));

        // Consoles: lodestone return console, Dispatch barrel, teleporter sign.
        TopoPos console = HubLayout.TELEPORTER_CONSOLE;
        put.accept(console, marker(console.x(), console.y(), console.z(),
                "minecraft:lodestone"));
        TopoPos barrel = HubLayout.DISPATCH_CONSOLE;
        put.accept(barrel, marker(barrel.x(), barrel.y(), barrel.z(),
                "minecraft:barrel"));
        put.accept(console.offset(1, 0, 0), marker(console.x() + 1,
                console.y(), console.z(), "minecraft:oak_sign"));

        List<BlockOperation> operations = new ArrayList<>(ops.values());
        operations.sort(Comparator.comparingInt((BlockOperation o) -> o.position().y())
                .thenComparingInt(o -> o.position().z())
                .thenComparingInt(o -> o.position().x()));
        return new SegmentPlan("hub", operations);
    }


    /** A short, straight, single-file tunnel (floor, ceiling, and both side
     * walls solid - a 1-wide, 2-tall open column through the center,
     * matching the step-1 walk model exactly) with a textured wall skin
     * (same tiered palette as the rest of the mine), carved purely
     * additively from {@code from} to {@code to} - never overwrites
     * anything, since every written cell is either already open (the door
     * gap) or previously-untouched rock. */
    private static void carveStubCorridor(java.util.function.BiConsumer<TopoPos, BlockOperation> put,
            TopoPos from, TopoPos to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(dist));
        int ii = HubLayout.INTERIOR_HALF;
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int cx = from.x() + (int) Math.round(dx * t);
            int cy = from.y() + (int) Math.round(dy * t);
            int cz = from.z() + (int) Math.round(dz * t);
            if (Math.abs(cx) <= ii && Math.abs(cz) <= ii) {
                // Inside the hub's own footprint, which the interior-air
                // loop already carved fully open - writing a WALL_SKIN
                // shell here would wall straight through the middle of the
                // room instead of just connecting to it. Stop as soon as
                // the straight line enters the hub; the already-open
                // interior carries the rest of the way to the console.
                break;
            }
            for (int ddy = -1; ddy <= 2; ddy++) {
                for (int ddx = -1; ddx <= 1; ddx++) {
                    for (int ddz = -1; ddz <= 1; ddz++) {
                        TopoPos pos = new TopoPos(cx + ddx, cy + ddy, cz + ddz);
                        boolean open = (ddy == 0 || ddy == 1) && ddx == 0 && ddz == 0;
                        put.accept(pos, new BlockOperation(pos,
                                open ? BlockKind.OPEN : BlockKind.WALL_SKIN));
                    }
                }
            }
        }
    }

    private static BlockOperation marker(int x, int y, int z, String blockId) {
        return new BlockOperation(new TopoPos(x, y, z), BlockKind.MARKER, blockId);
    }

    // Shared helpers (kept from the previous geometry iteration)
    // ------------------------------------------------------------------

    /** True for generated-structure reserves that foreign segments may not
     * touch: the hub reserve (always centred on world (0,0); the arrival
     * anchor argument is retained only for call-site compatibility; the
     * east gateway band is the single allowed opening) plus every placed
     * landmark's write-protected box minus its sanctioned connector
     * openings. Landmark segments never pass through this filter - they
     * build their plans directly like the hub does. */
    public static boolean isArrivalKeepOut(TopoPos pos, TopoPos arrival) {
        return HubLayout.isProtected(pos)
                || PassageNetwork.activeLayout().isWriteProtected(pos);
    }

    private static void addCheckpointPad(Map<TopoPos, RankedBlock> blocks, TopoPos feet,
                                         Set<TopoPos> headCells) {
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (!headCells.contains(feet.offset(dx, -1, dz))) {
                    put(blocks, feet.offset(dx, -1, dz), BlockKind.ROCK, 6);
                }
                for (int dy = 0; dy <= 2; dy++) {
                    put(blocks, feet.offset(dx, dy, dz), BlockKind.OPEN, 6);
                }
            }
        }
    }

    /** A single source block in a basalt-lined cup; the ring and floor keep
     * the liquid local without relying on the outer shell. */
    private static void addContainedLavaPocket(Map<TopoPos, RankedBlock> blocks, TopoPos center) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dz != 0) {
                    put(blocks, center.offset(dx, -2, dz), BlockKind.HAZARD_BOUNDARY, 6);
                }
                put(blocks, center.offset(dx, -3, dz), BlockKind.HAZARD_BOUNDARY, 6);
            }
        }
        put(blocks, center.offset(0, -2, 0), BlockKind.HAZARD_SOURCE, 7);
    }

    private static void addWalkableFloor(Map<TopoPos, RankedBlock> blocks,
                                         TopoPos feet, int radius, Set<TopoPos> headCells) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radius * radius
                        && !headCells.contains(feet.offset(dx, -1, dz))) {
                    put(blocks, feet.offset(dx, -1, dz), BlockKind.ROCK, 3);
                }
            }
        }
    }

    private static void addLandmark(Map<TopoPos, RankedBlock> blocks, TopoPos center, int radius) {
        int offset = Math.max(2, radius - 2);
        for (int[] direction : new int[][] {{offset, 0}, {-offset, 0}, {0, offset}, {0, -offset}}) {
            int height = 2 + Math.floorMod(center.x() * 31 + center.z() * 17 + direction[0] * 7
                    + direction[1] * 13, 3);
            for (int dy = -1; dy < height; dy++) {
                put(blocks, center.offset(direction[0], dy, direction[1]), BlockKind.ROCK, 5);
            }
        }
    }

    private static double normalized(int offset, int radius) {
        return (double) offset * offset / ((double) radius * radius);
    }

    private static void put(Map<TopoPos, RankedBlock> blocks, TopoPos pos,
                            BlockKind kind, int priority) {
        RankedBlock existing = blocks.get(pos);
        if (existing == null || priority >= existing.priority()) {
            blocks.put(pos, new RankedBlock(kind, priority));
        }
    }

    static String connectorSegmentId(String entranceId) {
        return "connector:arrival:" + entranceId;
    }

    static String edgeSegmentId(TopoEdge edge) {
        return "edge:" + edge.a() + ":" + edge.b();
    }

    static TopoEdge edgeFromSegmentId(TopologyPlan topology, String id) {
        String[] parts = id.split(":");
        if (parts.length != 3) {
            return null;
        }
        for (TopoEdge edge : topology.edges()) {
            if (edge.a().equals(parts[1]) && edge.b().equals(parts[2])) {
                return edge;
            }
        }
        return null;
    }

    private static long stableHash(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static SegmentPlan finish(String id, Map<TopoPos, RankedBlock> blocks,
                                      TopoBounds bounds, TopoPos arrival,
                                      Set<TopoPos> feetCells, TopologyPlan topology) {
        if (blocks.size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("segment " + id + " exceeds " + MAX_OPERATIONS + " operations");
        }
        LandmarkLayout landmarks = PassageNetwork.activeLayout();
        Set<TopoPos> ownedOpenings = ownedOpenings(id, topology, landmarks);
        List<TopoPos> positions = new ArrayList<>(blocks.keySet());
        positions.sort(POSITION_ORDER);
        List<BlockOperation> operations = new ArrayList<>(positions.size());
        for (TopoPos pos : positions) {
            if (!bounds.contains(pos)) {
                // Cavern lobes and detours clamp at the owned boundary instead
                // of failing the whole segment.
                continue;
            }
            if (isArrivalKeepOut(pos, arrival)) {
                // The hub reserve is a wall, not an error: segments that reach
                // it are clipped so the generated hub stays sealed.
                continue;
            }
            if (landmarks.isReserved(pos) && !ownedOpenings.contains(pos)) {
                // Landmark reserves belong to their rooms: only the edge that
                // terminates at a safehouse may write its connector openings.
                // Foreign structure, dressing, and node rooms are clipped so
                // no stray op can seal a doorway or intrude into the reserve.
                continue;
            }
            if (blocks.get(pos).kind() == BlockKind.OPEN && pos.y() > MAX_CAVE_OPEN_Y) {
                continue;
            }
            operations.add(new BlockOperation(pos, blocks.get(pos).kind()));
        }
        if (feetCells != null) {
            // Shared-junction rule: whenever a solid op lands on another
            // route's feet cell it forms a one-block bump - open headroom
            // above the shared feet so both routes can stand on it.
            List<BlockOperation> headroom = new ArrayList<>();
            Set<TopoPos> taken = new HashSet<>();
            for (BlockOperation op : operations) {
                taken.add(op.position());
            }
            for (BlockOperation op : operations) {
                if (op.kind() != BlockKind.OPEN && op.kind() != BlockKind.RAIL
                        && feetCells.contains(op.position())) {
                    TopoPos above = op.position().offset(0, 2, 0);
                    if (bounds.contains(above) && !isArrivalKeepOut(above, arrival)
                            && (!landmarks.isReserved(above)
                                    || ownedOpenings.contains(above))
                            && taken.add(above)) {
                        headroom.add(new BlockOperation(above, BlockKind.OPEN));
                    }
                }
            }
            operations.addAll(headroom);
        }
        operations.sort(Comparator.comparingInt(operation ->
                operation.kind() == BlockKind.HAZARD_SOURCE ? 1 : 0));
        return new SegmentPlan(id, operations);
    }

    /** Openings a segment legitimately writes: the connector mouth, doors,
     * and approach tunnel of the safehouses its centerline terminates at.
     * Everything else - foreign edges, node rooms, dress passes - owns no
     * landmark opening and is clipped from the reserve entirely. */
    private static Set<TopoPos> ownedOpenings(String id, TopologyPlan topology,
                                              LandmarkLayout landmarks) {
        String structural = id.startsWith("dress:") ? id.substring("dress:".length()) : id;
        if (!structural.startsWith("edge:")) {
            return Set.of();
        }
        TopoEdge edge = edgeFromSegmentId(topology, structural);
        if (edge == null) {
            return Set.of();
        }
        return landmarks.openingsFor(edge.a(), edge.b());
    }
}
