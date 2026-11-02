package com.rusticcraft.publicmines.topology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic topology generator for one shared run.
 *
 * <p>{@link #plan} is a pure function of (seed, profile, bounds): the same
 * inputs always produce the same {@link TopologyPlan}, down to node order.
 * Randomness comes from a single {@link Random} consumed in a fixed sequence;
 * hash structures are used for membership checks only, never for iteration.
 *
 * <p>Shape: one wandering main route that starts below the arrival point and
 * descends through the profile's ordered depth bands (upper -> middle ->
 * Chaos). Branch chains, pockets, and resource fields spur off route nodes as
 * intentional dead ends or reconnect as loops. Checkpoints sit on the main
 * route at region exits; caverns and landmarks are seeded upgrades of route
 * and side nodes.
 */
public final class TopologyPlanner {

    private static final String[] LANDMARK_ADJECTIVES = {
            "Echoing", "Sunken", "Shattered", "Hollow", "Ashen",
            "Buried", "Silent", "Glimmering", "Collapsed", "Ancient"};
    private static final String[] LANDMARK_NOUNS = {
            "Pillar", "Gallery", "Dome", "Amphitheater", "Span",
            "Vault", "Chasm", "Rotunda", "Cascade", "Arch"};

    private TopologyPlanner() {}

    /**
     * Builds the topology plan for one run.
     *
     * @throws TopologyException when the profile is invalid or the bounds are
     *         too small for the configured corridor geometry. This is a clean
     *         failure: nothing is partially planned or placed.
     */
    public static TopologyPlan plan(long seed, TopologyProfile profile, TopoBounds bounds) {
        List<String> problems = profile.validate();
        if (!problems.isEmpty()) {
            throw new TopologyException("invalid topology profile '" + profile.id()
                    + "': " + String.join("; ", problems));
        }
        TopoBounds inner = bounds.inset(profile.boundsMargin());
        if (inner.isEmpty()
                || inner.sizeX() < profile.minStep() * 2
                || inner.sizeZ() < profile.minStep() * 2) {
            throw new TopologyException("mine bounds " + bounds + " leave only a "
                    + (inner.isEmpty() ? "empty" : inner.sizeX() + "x" + inner.sizeZ())
                    + " corridor field after the " + profile.boundsMargin()
                    + "-block margin; need at least " + (profile.minStep() * 2)
                    + "x" + (profile.minStep() * 2));
        }
        if (bounds.sizeY() < 16) {
            throw new TopologyException("mine bounds " + bounds
                    + " are too short vertically for a descending route");
        }

        Builder b = new Builder(profile, bounds, inner, new Random(seed));
        b.mainRoute();
        b.checkpoints();
        b.caverns();
        b.branches();
        b.loops();
        b.pockets();
        b.landmarks();
        b.deadEnds();
        return b.build(seed);
    }

    private static int jitter(Random rng, int range) {
        return range <= 0 ? 0 : rng.nextInt(range * 2 + 1) - range;
    }

    /** Mutable construction state; consumed only by {@link #plan}. */
    private static final class Builder {
        private final TopologyProfile profile;
        private final TopoBounds bounds;
        private final TopoBounds inner;
        private final int minNodeY;
        private final int maxNodeY;
        private final Random rng;
        private final List<TopoNode> nodes = new ArrayList<>();
        private final List<TopoEdge> edges = new ArrayList<>();
        private final List<String> mainPath = new ArrayList<>();
        private final Map<String, Integer> indexOf = new HashMap<>();
        /** Occupied block positions; membership checks only, never iterated. */
        private final Set<TopoPos> occupied = new HashSet<>();
        /** Node indices belonging to each region, in plan order. */
        private final List<List<Integer>> regionNodes = new ArrayList<>();
        /** Indices (into {@link #nodes}) of main-route nodes per region. */
        private final List<List<Integer>> regionMain = new ArrayList<>();
        private int branchSeq = 0;
        private int pocketSeq = 0;

        Builder(TopologyProfile profile, TopoBounds bounds, TopoBounds inner, Random rng) {
            this.profile = profile;
            this.bounds = bounds;
            this.inner = inner;
            // The horizontal margin keeps corridors inside the owned field;
            // vertically a node only needs its corridor half-width of rock.
            this.minNodeY = bounds.minY() + profile.corridorHalfWidth();
            this.maxNodeY = bounds.maxY() - profile.corridorHalfWidth();
            this.rng = rng;
            for (int i = 0; i < profile.regions().size(); i++) {
                regionNodes.add(new ArrayList<>());
                regionMain.add(new ArrayList<>());
            }
        }

        private void addNode(TopoNode node) {
            indexOf.put(node.id(), nodes.size());
            nodes.add(node);
            occupied.add(node.pos());
            regionNodes.get(regionIndex(node.region())).add(nodes.size() - 1);
        }

        private void replaceNode(int index, TopoNode node) {
            nodes.set(index, node);
        }

        private int regionIndex(String regionId) {
            List<TopologyProfile.RegionSpec> regions = profile.regions();
            for (int i = 0; i < regions.size(); i++) {
                if (regions.get(i).id().equals(regionId)) {
                    return i;
                }
            }
            throw new IllegalStateException("node assigned unknown region " + regionId);
        }

        // --------------------------------------------------------------
        // Main route: a wandering descending chain through every region.
        // --------------------------------------------------------------

        private void mainRoute() {
            List<TopologyProfile.RegionSpec> regions = profile.regions();
            TopologyProfile.RegionSpec first = regions.get(0);
            int cx = (inner.minX() + inner.maxX()) / 2;
            int cz = (inner.minZ() + inner.maxZ()) / 2;
            int entranceY = clamp(first.topY() - 2, inner.minY(), inner.maxY());
            TopoPos start = new TopoPos(cx, entranceY, cz);
            TopoNode entrance = new TopoNode("m000", NodeKind.ENTRANCE, first.id(),
                    start, Set.of(), "Entrance Shaft");
            addNode(entrance);
            mainPath.add(entrance.id());
            regionMain.get(0).add(0);

            TopoPos prev = start;
            String prevId = entrance.id();
            double angle = rng.nextDouble() * Math.PI * 2;
            TopoPos legTarget = null;
            int legCount = 0;
            int mainSeq = 0;
            for (int r = 0; r < regions.size(); r++) {
                TopologyProfile.RegionSpec region = regions.get(r);
                for (int k = 0; k < region.mainRouteNodes(); k++) {
                    mainSeq++;
                    double t = (k + 1.0) / region.mainRouteNodes();
                    int y = Math.round((float) (region.topY()
                            + (region.bottomY() - region.topY()) * t)) + jitter(rng, 3);
                    y = Math.max(region.bottomY(), Math.min(region.topY(), y));
                    y = clamp(y, minNodeY, maxNodeY);
                    int step = profile.minStep()
                            + rng.nextInt(profile.maxStep() - profile.minStep() + 1);
                    // Leg structure: every ~8 nodes steer toward an excursion
                    // target in a rotating sector so consecutive legs sweep
                    // different parts of the field instead of drifting one way.
                    if (legTarget == null
                            || prev.distanceSqTo(legTarget) < 80L * 80L
                            || mainSeq % 8 == 1) {
                        legCount++;
                        int innerHalf = Math.min(inner.sizeX(), inner.sizeZ()) / 2;
                        int sector = (legCount * 3 + rng.nextInt(2)) % 8;
                        double legAngle = Math.toRadians(sector * 45.0
                                + (rng.nextDouble() * 2 - 1) * 18);
                        double legRadius = innerHalf * (0.55 + rng.nextDouble() * 0.3);
                        legTarget = new TopoPos(
                                inner.clampX((int) Math.round(cx + Math.cos(legAngle) * legRadius)),
                                y,
                                inner.clampZ((int) Math.round(cz + Math.sin(legAngle) * legRadius)));
                    }
                    // Spread the route: try a fan of candidate headings and
                    // keep the one that advances the leg while staying far
                    // from route nodes and already-crowded areas.
                    double maxTurn = Math.toRadians(profile.maxTurnDeg());
                    TopoPos best = null;
                    double bestScore = Double.NEGATIVE_INFINITY;
                    double bestAngle = angle;
                    for (int c = 0; c < 6; c++) {
                        double candidate = angle + (c - 2.5) / 2.5 * maxTurn;
                        int nx = inner.clampX((int) Math.round(prev.x() + Math.cos(candidate) * step));
                        int nz = inner.clampZ((int) Math.round(prev.z() + Math.sin(candidate) * step));
                        TopoPos pos = new TopoPos(nx, y, nz);
                        if (pos.distanceSqTo(prev) == 0 || occupied.contains(pos)) {
                            continue;
                        }
                        double score = Math.sqrt(nearestRouteDistSq(pos))
                                - 0.8 * Math.sqrt(pos.distanceSqTo(legTarget))
                                - 4.0 * densityWithin(pos, 120);
                        score += rng.nextDouble() * 1.0E-6; // deterministic tie-break
                        if (score > bestScore) {
                            bestScore = score;
                            best = pos;
                            bestAngle = candidate;
                        }
                    }
                    if (best == null) {
                        throw new TopologyException("could not place main-route node "
                                + mainSeq + " inside bounds " + inner);
                    }
                    angle = bestAngle;
                    prev = best;
                    String id = String.format("m%03d", mainSeq);
                    TopoNode node = new TopoNode(id, NodeKind.ROUTE, region.id(),
                            best, Set.of(), "");
                    addNode(node);
                    mainPath.add(id);
                    regionMain.get(r).add(nodes.size() - 1);
                    edges.add(new TopoEdge(prevId, id, EdgeKind.MAIN));
                    prevId = id;
                }
            }
        }

        private double nearestRouteDistSq(TopoPos pos) {
            double best = Double.MAX_VALUE;
            for (String id : mainPath) {
                TopoNode node = nodes.get(indexOf.get(id));
                best = Math.min(best, node.pos().distanceSqTo(pos));
            }
            return best;
        }

        /** Count of existing nodes within {@code radius} blocks (XZ plane). */
        private int densityWithin(TopoPos pos, int radius) {
            int r2 = radius * radius;
            int count = 0;
            for (TopoNode node : nodes) {
                long dx = node.pos().x() - pos.x();
                long dz = node.pos().z() - pos.z();
                if (dx * dx + dz * dz <= r2) {
                    count++;
                }
            }
            return count;
        }

        // --------------------------------------------------------------
        // Feature assignment on the main route.
        // --------------------------------------------------------------

        private void checkpoints() {
            for (int r = 0; r < regionMain.size(); r++) {
                List<Integer> main = regionMain.get(r);
                TopologyProfile.RegionSpec spec = profile.regions().get(r);
                int count = Math.min(spec.checkpoints(), main.size());
                for (int c = 0; c < count; c++) {
                    // Evenly spaced, always including the region's last node.
                    int index = main.get(Math.round((float) ((c + 1.0) * (main.size() - 1) / count)));
                    TopoNode node = nodes.get(index);
                    if (!node.has(NodeFeature.CHECKPOINT)) {
                        replaceNode(index, node.withFeature(NodeFeature.CHECKPOINT));
                    }
                }
            }
        }

        private void caverns() {
            for (int r = 0; r < regionMain.size(); r++) {
                List<Integer> main = regionMain.get(r);
                TopologyProfile.RegionSpec spec = profile.regions().get(r);
                if (main.size() < 2) {
                    continue;
                }
                Set<Integer> chosen = new HashSet<>();
                int attempts = spec.caverns() * 4;
                while (chosen.size() < spec.caverns() && attempts-- > 0) {
                    int index = main.get(rng.nextInt(main.size()));
                    // Skip the entrance node so the shaft stays a shaft.
                    if (nodes.get(index).kind() == NodeKind.ENTRANCE) {
                        continue;
                    }
                    if (chosen.add(index)) {
                        replaceNode(index, nodes.get(index).withKind(NodeKind.CAVERN));
                    }
                }
            }
        }

        // --------------------------------------------------------------
        // Off-route structure.
        // --------------------------------------------------------------

        private void branches() {
            List<TopologyProfile.RegionSpec> regions = profile.regions();
            for (int r = 0; r < regions.size(); r++) {
                TopologyProfile.RegionSpec region = regions.get(r);
                List<Integer> main = regionMain.get(r);
                for (int i = 0; i < region.branches(); i++) {
                    TopoNode anchor = nodes.get(main.get(rng.nextInt(main.size())));
                    double dir = rng.nextDouble() * Math.PI * 2;
                    int length = 1 + rng.nextInt(profile.branchMaxLength());
                    TopoPos pos = anchor.pos();
                    String parentId = anchor.id();
                    String branchId = String.format("b%03d", branchSeq++);
                    for (int s = 0; s < length; s++) {
                        int dist = Math.max(8, (int) Math.round(profile.minStep() * 0.6))
                                + rng.nextInt(profile.maxStep() - profile.minStep() + 1);
                        dir += (rng.nextDouble() * 2 - 1) * Math.toRadians(profile.maxTurnDeg());
                        int ny = clamp(pos.y() + jitter(rng, 6), region.bottomY(), region.topY());
                        ny = clamp(ny, minNodeY, maxNodeY);
                        TopoPos next = freeSpot(pos, dir, dist, ny, region);
                        if (next == null) {
                            break; // chain stalls in a crowded area; keep what exists
                        }
                        String id = branchId + "-" + s;
                        addNode(new TopoNode(id, NodeKind.CAVE, region.id(), next,
                                Set.of(), ""));
                        edges.add(new TopoEdge(parentId, id, EdgeKind.BRANCH));
                        parentId = id;
                        pos = next;
                    }
                }
            }
        }

        private void loops() {
            List<TopologyProfile.RegionSpec> regions = profile.regions();
            for (int r = 0; r < regions.size(); r++) {
                TopologyProfile.RegionSpec region = regions.get(r);
                int wanted = region.loops();
                List<Integer> pool = regionNodes.get(r);
                int attempts = wanted * 8;
                int placed = 0;
                while (placed < wanted && attempts-- > 0) {
                    TopoNode a = nodes.get(pool.get(rng.nextInt(pool.size())));
                    TopoNode b = nodes.get(pool.get(rng.nextInt(pool.size())));
                    if (a.id().equals(b.id()) || a.kind() == NodeKind.ENTRANCE) {
                        continue;
                    }
                    double dist = a.pos().distanceTo(b.pos());
                    if (dist < profile.minStep() || dist > profile.loopMaxDistance()) {
                        continue;
                    }
                    if (hasEdge(a.id(), b.id()) || consecutiveOnMain(a.id(), b.id())) {
                        continue;
                    }
                    edges.add(new TopoEdge(a.id(), b.id(), EdgeKind.LOOP));
                    placed++;
                }
            }
        }

        private void pockets() {
            List<TopologyProfile.RegionSpec> regions = profile.regions();
            for (int r = 0; r < regions.size(); r++) {
                TopologyProfile.RegionSpec region = regions.get(r);
                int total = region.pockets() + region.resourceFields();
                List<Integer> hosts = regionNodes.get(r);
                for (int i = 0; i < total; i++) {
                    boolean resource = i < region.resourceFields();
                    TopoNode host = nodes.get(hosts.get(rng.nextInt(hosts.size())));
                    double dir = rng.nextDouble() * Math.PI * 2;
                    int dist = Math.max(8, (int) Math.round(profile.minStep() * 0.4))
                            + rng.nextInt(Math.max(1, (int) Math.round(profile.minStep() * 1.4)));
                    int ny = clamp(host.pos().y() + jitter(rng, 8),
                            region.bottomY(), region.topY());
                    ny = clamp(ny, minNodeY, maxNodeY);
                    TopoPos pos = freeSpot(host.pos(), dir, dist, ny, region);
                    if (pos == null) {
                        continue;
                    }
                    Set<NodeFeature> features = resource
                            ? Set.of(NodeFeature.RESOURCE_FIELD) : Set.of();
                    String id = String.format("p%03d", pocketSeq++);
                    addNode(new TopoNode(id, NodeKind.POCKET, region.id(), pos,
                            features, ""));
                    edges.add(new TopoEdge(host.id(), id, EdgeKind.SPUR));
                }
            }
        }

        /** Finds an unoccupied position roughly {@code dist} blocks from
         * {@code from} along {@code dir}; retries resampled directions a fixed
         * number of times before giving up (deterministically). */
        private TopoPos freeSpot(TopoPos from, double dir, int dist, int y,
                                 TopologyProfile.RegionSpec region) {
            for (int attempt = 0; attempt < 4; attempt++) {
                double d = dir + attempt * Math.toRadians(51);
                int nx = inner.clampX((int) Math.round(from.x() + Math.cos(d) * dist));
                int nz = inner.clampZ((int) Math.round(from.z() + Math.sin(d) * dist));
                int yy = clamp(Math.max(region.bottomY(), Math.min(region.topY(), y)),
                        minNodeY, maxNodeY);
                TopoPos pos = new TopoPos(nx, yy, nz);
                if (!occupied.contains(pos)) {
                    return pos;
                }
            }
            return null;
        }

        private void landmarks() {
            List<TopologyProfile.RegionSpec> regions = profile.regions();
            for (int r = 0; r < regions.size(); r++) {
                List<Integer> pool = regionNodes.get(r);
                int wanted = regions.get(r).landmarks();
                Set<Integer> chosen = new HashSet<>();
                int attempts = wanted * 6;
                while (chosen.size() < wanted && attempts-- > 0 && !pool.isEmpty()) {
                    int index = pool.get(rng.nextInt(pool.size()));
                    TopoNode node = nodes.get(index);
                    if (node.kind() == NodeKind.ENTRANCE || node.kind() == NodeKind.POCKET
                            || !chosen.add(index)) {
                        continue;
                    }
                    String name = LANDMARK_ADJECTIVES[rng.nextInt(LANDMARK_ADJECTIVES.length)]
                            + " " + LANDMARK_NOUNS[rng.nextInt(LANDMARK_NOUNS.length)];
                    replaceNode(index, node.withFeature(NodeFeature.LANDMARK).withLabel(name));
                }
            }
        }

        /** Marks every degree-1 node except the entrance as an intentional
         * dead end. Runs last so loop edges are already accounted for. */
        private void deadEnds() {
            Map<String, Integer> degree = new HashMap<>();
            for (TopoEdge edge : edges) {
                degree.merge(edge.a(), 1, Integer::sum);
                degree.merge(edge.b(), 1, Integer::sum);
            }
            for (int i = 0; i < nodes.size(); i++) {
                TopoNode node = nodes.get(i);
                if (degree.getOrDefault(node.id(), 0) == 1
                        && node.kind() != NodeKind.ENTRANCE) {
                    replaceNode(i, node.withFeature(NodeFeature.DEAD_END));
                }
            }
        }

        private boolean hasEdge(String a, String b) {
            for (TopoEdge edge : edges) {
                if (edge.connects(a) && edge.connects(b)) {
                    return true;
                }
            }
            return false;
        }

        private boolean consecutiveOnMain(String a, String b) {
            for (int i = 0; i + 1 < mainPath.size(); i++) {
                String x = mainPath.get(i);
                String y = mainPath.get(i + 1);
                if ((x.equals(a) && y.equals(b)) || (x.equals(b) && y.equals(a))) {
                    return true;
                }
            }
            return false;
        }

        private TopologyPlan build(long seed) {
            String terminus = mainPath.get(mainPath.size() - 1);
            return new TopologyPlan(seed, profile.id(), bounds, nodes, edges,
                    mainPath, mainPath.get(0), terminus);
        }

    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
