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
    private static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();
    /** Maximum a sample may be lowered from its original Y. */
    public static final int MAX_LOWERING = 12;
    private static final int MAX_PASSES = 128;
    /** Deterministic re-draw attempts when a meander set proves
     * unsatisfiable: each retry perturbs only the winding seeds, so a
     * resolvable draw is found without touching declared geometry. The
     * sequence is fixed per topology/seed, keeping runs reproducible. */
    private static final int MAX_RESOLVE_ATTEMPTS = 4;
    private static final long REROLL_MIX = 0x9E3779B97F4A7C15L;

    private static String cachedFingerprint = "";
    private static String cachedArrival = "";
    private static String cachedCatalogFingerprint = "";
    private static PassageNetwork cached;
    private static LandmarkLayout resolving;

    private final TopologyPlan topology;
    private final TopoPos arrival;
    private final Map<String, LandmarkCatalog.Definition> defs;
    private final String catalogFingerprint;
    private final LandmarkLayout landmarks;
    private final Map<String, List<TopoPos>> paths = new HashMap<>();
    private final Map<String, TopoPos> centers = new HashMap<>();
    private int reroll;

    /** Resolved geometry for a topology; fails (throws) only when a merge
     * would violate the fixed hub or landmark anchors or the lowering
     * bound. Epoch-sticky: when a network for this topology and arrival is
     * already resolved, this returns it verbatim even if the live catalog
     * has since reloaded - a mid-run datapack swap cannot fork a second
     * geometry epoch underneath the receipted one. Callers that need an
     * explicit epoch use the three-argument overload. */
    public static synchronized PassageNetwork of(TopologyPlan topology, TopoPos arrival) {
        String arrivalKey = arrival.x() + "," + arrival.y() + "," + arrival.z();
        if (cached != null && topology.fingerprint().equals(cachedFingerprint)
                && arrivalKey.equals(cachedArrival)) {
            resolving = null;
            return cached;
        }
        return of(topology, arrival, LandmarkCatalog.definitions());
    }

    /** Resolve against a specific landmark-definition set so a run pins
     * the catalog snapshot it was receipted under: a reload mid-run can
     * never swap room shapes, reserves, or connectors under it. The
     * snapshot's fingerprint keys the cache alongside the topology; a
     * reference-identical map (the caller's own pin) skips the rehash. */
    public static synchronized PassageNetwork of(TopologyPlan topology, TopoPos arrival,
                                                 Map<String, LandmarkCatalog.Definition> defs) {
        String arrivalKey = arrival.x() + "," + arrival.y() + "," + arrival.z();
        if (cached != null && topology.fingerprint().equals(cachedFingerprint)
                && arrivalKey.equals(cachedArrival)
                && (cached.defs == defs
                        || LandmarkCatalog.fingerprint(defs)
                                .equals(cachedCatalogFingerprint))) {
            resolving = null;
            return cached;
        }
        String catalogKey = LandmarkCatalog.fingerprint(defs);
        try {
            PassageNetwork network = new PassageNetwork(topology, arrival, defs);
            cachedFingerprint = topology.fingerprint();
            cachedArrival = arrivalKey;
            cachedCatalogFingerprint = catalogKey;
            cached = network;
            return network;
        } finally {
            resolving = null;
        }
    }

    /** The fingerprint of the landmark-definition snapshot this network
     * was resolved under - lets indexes keyed on resolved geometry
     * (clearance) detect a catalog-epoch switch. */
    public String catalogFingerprint() {
        return catalogFingerprint;
    }

    /** The placed static landmarks resolved for this topology: anchors,
     * connector cells, and the merged reserve predicates every foreign
     * write pass must respect. */
    public LandmarkLayout landmarks() {
        return landmarks;
    }

    /** The landmarks of the network being resolved or, once it finished,
     * of the currently cached network - the reserve view static write
     * filters ({@code SegmentGeometry} keepouts, routing predicates)
     * consult. Resolution is single-threaded behind {@link #of}'s monitor,
     * so {@code resolving} always names the in-flight layout; reading the
     * finished cache during construction was the live-carve bug that left
     * landmark edges reserve-blind. Empty before the first resolution. */
    public static synchronized LandmarkLayout activeLayout() {
        return resolving != null ? resolving
                : (cached == null ? LandmarkLayout.empty() : cached.landmarks());
    }

    /** Test seam: drop the cached network. */
    public static synchronized void clearCache() {
        cached = null;
        resolving = null;
        cachedFingerprint = "";
        cachedArrival = "";
        cachedCatalogFingerprint = "";
    }

    private PassageNetwork(TopologyPlan topology, TopoPos arrival,
                           Map<String, LandmarkCatalog.Definition> defs) {
        this.topology = topology;
        this.arrival = arrival;
        this.defs = Map.copyOf(defs);
        this.catalogFingerprint = LandmarkCatalog.fingerprint(this.defs);
        this.landmarks = placeLandmarks(topology, this.defs);
        resolving = this.landmarks;
        // Resolution is a constraint pass over a random draw and some
        // draws are unsatisfiable - a bound-pinned door cell, a ceiling
        // capped column, and a shared crossing column can demand a floor
        // no member may take. Rather than fail the day, the meanders are
        // deterministically re-drawn (attempt count folds into each
        // edge's winding seed) and resolution retried; the same inputs
        // always produce the same attempt sequence, and attempt 0's
        // draws are unchanged. Landmark placement, connector mouths, and
        // reserves never re-roll - they are declared geometry.
        TopoNode entrance = topology.node(topology.entranceId());
        IllegalStateException failure = null;
        Map<String, List<TopoPos>> resolved = null;
        for (int attempt = 0; attempt < MAX_RESOLVE_ATTEMPTS; attempt++) {
            this.reroll = attempt;
            Map<String, List<TopoPos>> raw = new HashMap<>();
            Map<String, List<TopoPos>> original = new HashMap<>();
            if (entrance != null
                    && entrance.kind() == com.dwurdy.dawnmine.topology.NodeKind.ENTRANCE) {
                // The arrival corridor never route-avoids landmark reserves:
                // any reserve reaching its cells would also cover the entrance
                // node, which fits()'s foreign-node rule already rejects.
                raw.put("connector:arrival:" + topology.entranceId(),
                        SegmentGeometry.connectorCenterlineRaw(topology, arrival));
            }
            for (TopoEdge edge : topology.edges()) {
                raw.put(SegmentGeometry.edgeSegmentId(edge), rawEdgePath(edge));
            }
            for (Map.Entry<String, List<TopoPos>> e : raw.entrySet()) {
                original.put(e.getKey(), List.copyOf(e.getValue()));
            }
            try {
                resolved = resolve(raw, original);
                break;
            } catch (IllegalStateException x) {
                failure = x;
                LOGGER.warn("[dawnmine] passage resolution attempt {}/{} failed"
                        + " - re-drawing meanders ({})",
                        attempt + 1, MAX_RESOLVE_ATTEMPTS, x.getMessage());
            }
        }
        if (resolved == null) {
            throw failure;
        }
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

    /** Resolve the placed landmarks for this topology: every node flagged
     * {@link NodeFeature#SAFEHOUSE} gets a deterministic definition from
     * the snapshot passed to {@link #of}, anchored on its authored position
     * (its resolved centre - no incident edge endpoint ever lands on its
     * column). */
    private static LandmarkLayout placeLandmarks(TopologyPlan topology,
            Map<String, LandmarkCatalog.Definition> defs) {
        List<String> definitionIds = new ArrayList<>(defs.keySet());
        java.util.Collections.sort(definitionIds);
        Map<String, TopoPos> anchors = new HashMap<>();
        Map<String, String> assignments = new HashMap<>();
        List<LandmarkLayout.Placement> placed = new ArrayList<>();
        for (TopoNode node : topology.nodes()) {
            if (!node.has(com.dwurdy.dawnmine.topology.NodeFeature.SAFEHOUSE)) {
                continue;
            }
            if (definitionIds.isEmpty()) {
                // The bundled prospect_shelter always loads on a real
                // server; an empty catalog means no landmarks are defined
                // at all (pack stripped them or a bare test runtime), so
                // the node degrades to its ordinary checkpoint pad - a
                // logged degrade, never a silently substituted room.
                LOGGER.warn("[dawnmine] topology assigns safehouse node '{}' "
                        + "but no landmark definitions are loaded - degrading "
                        + "to a plain checkpoint node", node.id());
                continue;
            }
            String chosen = chooseDefinition(topology, node, definitionIds, placed, defs);
            if (chosen == null) {
                // Same degrade as the empty-catalog path: a safehouse whose
                // anchor fits no definition keeps its checkpoint instead of
                // killing the run - a reserve collision is a content
                // problem (oversized def, crowded anchor), not a reason to
                // fail a whole seeded day.
                LOGGER.warn("[dawnmine] no landmark definition fits safehouse "
                        + "node '{}' at {} - degrading to a plain checkpoint "
                        + "node", node.id(), node.pos());
                continue;
            }
            anchors.put(node.id(), node.pos());
            assignments.put(node.id(), chosen);
            placed.add(new LandmarkLayout.Placement(node.id(), chosen,
                    node.pos().offset(0, -1, 0), defs.get(chosen)));
        }
        LandmarkLayout layout = LandmarkLayout.place(anchors, assignments, defs);
        validatePlacements(topology, layout);
        return layout;
    }

    /** The deterministic definition for {@code node}: the seeded pick, then
     * a stable fallback through the sorted catalog when the pick's reserve
     * would cover a foreign node, reach outside owned bounds, touch the
     * protected hub volume, or collide with an already-placed reserve.
     * Null when no legal definition exists - the caller degrades the node
     * to a plain checkpoint rather than failing the run. */
    private static String chooseDefinition(TopologyPlan topology, TopoNode node,
                                           List<String> definitionIds,
                                           List<LandmarkLayout.Placement> placed,
                                           Map<String, LandmarkCatalog.Definition> defs) {
        int start = Math.floorMod(
                (int) SegmentGeometry.stableHashPublic(node.id() + "|safehouse")
                        ^ (int) topology.seed(),
                definitionIds.size());
        for (int step = 0; step < definitionIds.size(); step++) {
            String id = definitionIds.get((start + step) % definitionIds.size());
            LandmarkCatalog.Definition def = defs.get(id);
            if (def != null && fits(topology, node, def, placed)) {
                return id;
            }
        }
        return null;
    }

    /** A definition fits when its reserve stays inside owned bounds, clear
     * of the protected hub volume, covers no foreign node position, and
     * overlaps no already-placed reserve. */
    private static boolean fits(TopologyPlan topology, TopoNode node,
                                LandmarkCatalog.Definition def,
                                List<LandmarkLayout.Placement> placed) {
        TopoPos anchor = node.pos().offset(0, -1, 0);
        int hx = def.halfX() + def.reserveMargin();
        int hz = def.halfZ() + def.reserveMargin();
        int minY = anchor.y() - def.reserveMargin();
        int maxY = anchor.y() + def.height() + def.reserveMargin() - 1;
        if (!topology.bounds().contains(new TopoPos(anchor.x() - hx, minY, anchor.z() - hz))
                || !topology.bounds().contains(
                        new TopoPos(anchor.x() + hx, maxY, anchor.z() + hz))) {
            return false;
        }
        for (TopoNode other : topology.nodes()) {
            if (other.id().equals(node.id())) {
                continue;
            }
            if (Math.abs(other.pos().x() - anchor.x()) <= hx
                    && Math.abs(other.pos().z() - anchor.z()) <= hz
                    && other.pos().y() >= minY && other.pos().y() <= maxY) {
                return false;
            }
        }
        // The hub's protected volume is centred on the origin; a reserve
        // reaching it - or the gateway row/apron the arrival connector is
        // spliced through - is only possible with an oversized datapack
        // def, and a miss here would fail closed at freeze() with no
        // definition fallback left to try.
        for (int dx = -hx; dx <= hx; dx++) {
            for (int dz = -hz; dz <= hz; dz++) {
                for (int dy = minY; dy <= maxY; dy++) {
                    TopoPos cell = new TopoPos(anchor.x() + dx, dy, anchor.z() + dz);
                    if (com.dwurdy.dawnmine.topology.HubLayout.isProtected(cell)
                            || SegmentGeometry.inHubFootprintStrict(cell)
                            || SegmentGeometry.onGatewayRow(cell)
                            || SegmentGeometry.inGatewayApron(cell)) {
                        return false;
                    }
                }
            }
        }
        // The connector openings must reach clear corridor: a door or
        // approach tunnel inside the hub footprint, gateway row, apron,
        // or an already-placed reserve would be deflected off its declared
        // cell (or strand the door) long before any merge can report it -
        // reject the definition so the deterministic fallback can pick a
        // shape that fits.
        LandmarkLayout.Placement candidate = new LandmarkLayout.Placement(
                node.id(), def.id(), anchor, def);
        for (TopoPos opening : LandmarkLayout.openingCells(candidate)) {
            if (SegmentGeometry.inHubFootprintStrict(opening)
                    || SegmentGeometry.onGatewayRow(opening)
                    || SegmentGeometry.inGatewayApron(opening)) {
                return false;
            }
            for (LandmarkLayout.Placement other : placed) {
                if (other.insideReserve(opening)) {
                    return false;
                }
            }
        }
        // Each tunnel mouth - the first cell outside this reserve on the
        // connector's facing - is pinned by the approach splice. It must
        // sit inside bounds and outside every placed reserve and hub zone,
        // or reserve avoidance would deflect it off the splice seam.
        for (LandmarkCatalog.Connector c : def.connectors()) {
            TopoPos door = new TopoPos(anchor.x() + c.x(),
                    anchor.y() + c.y(), anchor.z() + c.z());
            int[] step = switch (c.facing()) {
                case "east" -> new int[] {1, 0};
                case "west" -> new int[] {-1, 0};
                case "south" -> new int[] {0, 1};
                default -> new int[] {0, -1};
            };
            TopoPos mouth = door.offset(step[0], 0, step[1]);
            while (candidate.insideReserve(mouth)) {
                mouth = mouth.offset(step[0], 0, step[1]);
            }
            for (TopoPos cell : List.of(mouth, mouth.offset(0, 1, 0))) {
                if (!topology.bounds().contains(cell)
                        || SegmentGeometry.inHubFootprintStrict(cell)
                        || SegmentGeometry.onGatewayRow(cell)
                        || SegmentGeometry.inGatewayApron(cell)) {
                    return false;
                }
                for (LandmarkLayout.Placement other : placed) {
                    if (other.insideReserve(cell)) {
                        return false;
                    }
                }
            }
        }
        // Symmetric cover: a mouth lives just outside its own reserve, so
        // reserve-vs-reserve AABB tests never see it. If this candidate's
        // reserve swallowed an already-placed mouth, that placement's
        // splice would start inside forbidden rock and fail the run.
        for (LandmarkLayout.Placement other : placed) {
            LandmarkCatalog.Definition od = other.definition();
            for (LandmarkCatalog.Connector c : od.connectors()) {
                TopoPos door = new TopoPos(other.anchor().x() + c.x(),
                        other.anchor().y() + c.y(), other.anchor().z() + c.z());
                int[] step = switch (c.facing()) {
                    case "east" -> new int[] {1, 0};
                    case "west" -> new int[] {-1, 0};
                    case "south" -> new int[] {0, 1};
                    default -> new int[] {0, -1};
                };
                TopoPos placedMouth = door.offset(step[0], 0, step[1]);
                while (other.insideReserve(placedMouth)) {
                    placedMouth = placedMouth.offset(step[0], 0, step[1]);
                }
                if (candidate.insideReserve(placedMouth)
                        || candidate.insideReserve(placedMouth.offset(0, 1, 0))) {
                    return false;
                }
            }
        }
        for (LandmarkLayout.Placement other : placed) {
            LandmarkCatalog.Definition od = other.definition();
            if (Math.abs(other.anchor().x() - anchor.x())
                    <= od.halfX() + od.reserveMargin() + hx
                    && Math.abs(other.anchor().z() - anchor.z())
                    <= od.halfZ() + od.reserveMargin() + hz
                    && Math.abs(other.anchor().y() - anchor.y())
                    < Math.max(od.height(), def.height())
                            + od.reserveMargin() + def.reserveMargin()) {
                return false;
            }
        }
        return true;
    }

    /** A placed landmark must sit wholly inside owned bounds, clear of the
     * hub's protected volume, clear of every foreign node position, and
     * clear of every other reserve - a datapack definition that violates
     * any of those fails the run at plan time instead of silently clipping
     * walls, sealing a room over a neighbour node, or carving hub rock. */
    private static void validatePlacements(TopologyPlan topology, LandmarkLayout layout) {
        java.util.List<LandmarkLayout.Placement> placements =
                new java.util.ArrayList<>(layout.placements().values());
        for (LandmarkLayout.Placement placement : placements) {
            LandmarkCatalog.Definition def = placement.definition();
            TopoPos anchor = placement.anchor();
            int hx = def.halfX() + def.reserveMargin();
            int hz = def.halfZ() + def.reserveMargin();
            int minY = anchor.y() - def.reserveMargin();
            int maxY = anchor.y() + def.height() + def.reserveMargin() - 1;
            TopoPos lo = new TopoPos(anchor.x() - hx, minY, anchor.z() - hz);
            TopoPos hi = new TopoPos(anchor.x() + hx, maxY, anchor.z() + hz);
            if (!topology.bounds().contains(lo) || !topology.bounds().contains(hi)) {
                throw new IllegalStateException("landmark '" + def.id() + "' at node '"
                        + placement.nodeId() + "' reserve " + lo + ".." + hi
                        + " escapes the owned bounds - report this fixture");
            }
            // Walk the reserve surface for hub-protection and foreign-node
            // conflicts; the box is small enough to check exhaustively.
            for (int dx = -hx; dx <= hx; dx++) {
                for (int dy = minY - anchor.y(); dy <= maxY - anchor.y(); dy++) {
                    for (int dz = -hz; dz <= hz; dz++) {
                        TopoPos cell = new TopoPos(anchor.x() + dx, anchor.y() + dy,
                                anchor.z() + dz);
                        if (com.dwurdy.dawnmine.topology.HubLayout.isProtected(cell)
                                || com.dwurdy.dawnmine.topology.HubLayout.isProtected(
                                        cell.offset(0, 1, 0))) {
                            throw new IllegalStateException("landmark '" + def.id()
                                    + "' at node '" + placement.nodeId()
                                    + "' reserve overlaps the protected hub volume at "
                                    + cell + " - report this fixture");
                        }
                        if (SegmentGeometry.inHubFootprintStrict(cell)
                                || SegmentGeometry.onGatewayRow(cell)
                                || SegmentGeometry.inGatewayApron(cell)) {
                            throw new IllegalStateException("landmark '" + def.id()
                                    + "' at node '" + placement.nodeId()
                                    + "' reserve overlaps the gateway approach at "
                                    + cell + " - report this fixture");
                        }
                    }
                }
            }
            for (TopoNode node : topology.nodes()) {
                if (!node.id().equals(placement.nodeId())
                        && placement.insideReserve(node.pos())) {
                    throw new IllegalStateException("landmark '" + def.id()
                            + "' at node '" + placement.nodeId()
                            + "' reserve covers foreign node '" + node.id() + "' at "
                            + node.pos() + " - report this fixture");
                }
            }
        }
        for (int i = 0; i < placements.size(); i++) {
            for (int j = i + 1; j < placements.size(); j++) {
                LandmarkLayout.Placement a = placements.get(i);
                LandmarkLayout.Placement b = placements.get(j);
                LandmarkCatalog.Definition da = a.definition();
                LandmarkCatalog.Definition db = b.definition();
                if (Math.abs(a.anchor().x() - b.anchor().x())
                        <= da.halfX() + da.reserveMargin()
                                + db.halfX() + db.reserveMargin()
                        && Math.abs(a.anchor().z() - b.anchor().z())
                        <= da.halfZ() + da.reserveMargin()
                                + db.halfZ() + db.reserveMargin()
                        && Math.abs(a.anchor().y() - b.anchor().y())
                        < Math.max(da.height(), db.height())
                                + da.reserveMargin() + db.reserveMargin()) {
                    throw new IllegalStateException("landmark reserves overlap: '"
                            + a.nodeId() + "' (" + da.id() + ") and '" + b.nodeId()
                            + "' (" + db.id() + ") - report this fixture");
                }
            }
        }
        // Reserves may not cover another placement's connector mouth: the
        // mouth is outside its own reserve so the AABB test above cannot
        // see it, but a covered mouth strands that placement's approach.
        for (LandmarkLayout.Placement owner : placements) {
            LandmarkCatalog.Definition od = owner.definition();
            for (LandmarkCatalog.Connector c : od.connectors()) {
                TopoPos door = new TopoPos(owner.anchor().x() + c.x(),
                        owner.anchor().y() + c.y(), owner.anchor().z() + c.z());
                int[] step = switch (c.facing()) {
                    case "east" -> new int[] {1, 0};
                    case "west" -> new int[] {-1, 0};
                    case "south" -> new int[] {0, 1};
                    default -> new int[] {0, -1};
                };
                TopoPos mouth = door.offset(step[0], 0, step[1]);
                while (owner.insideReserve(mouth)) {
                    mouth = mouth.offset(step[0], 0, step[1]);
                }
                for (LandmarkLayout.Placement other : placements) {
                    if (other != owner && (other.insideReserve(mouth)
                            || other.insideReserve(mouth.offset(0, 1, 0)))) {
                        throw new IllegalStateException("landmark '" + other.nodeId()
                                + "' reserve covers the connector mouth of '"
                                + owner.nodeId() + "' at " + mouth
                                + " - report this fixture");
                    }
                }
            }
        }
    }

    private List<TopoPos> rawEdgePath(TopoEdge edge) {
        TopoNode a = topology.node(edge.a());
        TopoNode b = topology.node(edge.b());
        // Edges into a safehouse terminate at its declared connector cell
        // and arrive along the connector's own flat approach tunnel -
        // never the node centre, never an arbitrary boundary cell, never
        // over the reserve roof. The meander runs between the two tunnel
        // mouths and the approach tails are spliced on afterward.
        List<TopoPos> approachA = landmarks.placement(edge.a()) != null
                ? landmarks.connectorApproach(edge.a(), b.pos())
                : List.of(a.pos());
        List<TopoPos> approachB = landmarks.placement(edge.b()) != null
                ? landmarks.connectorApproach(edge.b(), approachA.get(0))
                : List.of(b.pos());
        List<TopoPos> middle = SegmentGeometry.windingPath(
                approachA.get(0), approachB.get(0),
                topology.seed() ^ SegmentGeometry.stableHashPublic(
                        SegmentGeometry.edgeSegmentId(edge))
                        ^ (reroll * REROLL_MIX));
        // Routes that do not depart the entrance skirt the gateway apron
        // and the hub reserve with the connector row folded in, so the
        // exclusive connector corridor can never be crossed, merged into,
        // or trenched by a foreign carve - and no later detour can
        // interpolate a sample back inside the clipped volume. Edges
        // touching the entrance keep their band samples: they leave
        // through the level junction at the floor.
        // Avoidance runs on the middle alone, before the tails are
        // spliced: every route sees each landmark reserve whole, so a
        // meander can never cross a door column over the roof and
        // merge-lock the drop beside it. The spliced approach cells are
        // declared geometry inside their own reserve - they must bypass
        // avoidance or the walk would deflect them off the doorway.
        boolean entranceEdge = edge.a().equals(topology.entranceId())
                || edge.b().equals(topology.entranceId());
        middle = entranceEdge
                ? SegmentGeometry.avoidEntranceLandmarkReserve(middle, landmarks)
                : SegmentGeometry.avoidForeignReserve(middle, landmarks);
        if (!middle.get(0).equals(approachA.get(0))
                || !middle.get(middle.size() - 1).equals(approachB.get(0))) {
            throw new IllegalStateException("reserve avoidance moved a "
                    + "tunnel mouth on "
                    + SegmentGeometry.edgeSegmentId(edge)
                    + " - the mouth sits inside a forbidden zone; report "
                    + "this fixture");
        }
        List<TopoPos> cells = new ArrayList<>(
                approachA.size() + middle.size() + approachB.size());
        for (int i = approachA.size() - 1; i >= 0; i--) {
            cells.add(approachA.get(i));
        }
        if (middle.size() > 2) {
            cells.addAll(middle.subList(1, middle.size() - 1));
        }
        // Two abutting reserves can share a tunnel mouth; never admit a
        // consecutive duplicate at the splice seam.
        int bStart = cells.get(cells.size() - 1).equals(approachB.get(0)) ? 1 : 0;
        cells.addAll(approachB.subList(bStart, approachB.size()));
        if (cells.size() > SegmentGeometry.MAX_CENTERLINE_STEPS) {
            throw new IllegalStateException("spliced centerline for "
                    + SegmentGeometry.edgeSegmentId(edge) + " exceeds "
                    + SegmentGeometry.MAX_CENTERLINE_STEPS + " steps ("
                    + cells.size() + ") - report this fixture");
        }
        return cells;
    }

    /** Every resolved centerline by segment id. */
    public Map<String, List<TopoPos>> paths() {
        return java.util.Collections.unmodifiableMap(paths);
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
            // Anchor columns are rebuilt per pass like ramp bounds:
            // endpoints may converge downward, so a cell's merge band
            // follows the anchor's current height, not its original.
            Map<Long, List<Integer>> anchors = anchorColumns(work, ids);
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
                        // A diagonal step's swept-corner group can point at
                        // an endpoint index, but the endpoint belongs only
                        // to its own column's family - a corner column must
                        // never drag it into a foreign junction merge.
                        TopoPos endCell = p.get(r[1]);
                        if (xzKey(endCell.x(), endCell.z()) != key) {
                            continue;
                        }
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
                    List<TopoPos> memberCells = new ArrayList<>(family.size());
                    for (int[] r : family) {
                        memberCells.add(work.get(ids.get(r[0])).get(r[1]));
                    }
                    int sharedDown = sharedFloorBelow(memberCells, floor);
                    int shared = sharedDown != Integer.MIN_VALUE
                            && sharedDown >= floorBound
                            ? sharedDown
                            : sharedFloorAbove(memberCells, floorBound);
                    if (shared != Integer.MAX_VALUE) {
                        floor = shared;
                    }
                    floor = snapBelowAnchorBands(anchors, memberCells, floor);
                    if (floor == Integer.MIN_VALUE) {
                        throw new IllegalStateException("junction merge "
                                + "cannot settle below anchor bands, members "
                                + memberCells + " - report this fixture");
                    }
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
                                    work.get(segmentId).size(), cell)
                                    || landmarks.isOpeningCell(cell)) {
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
                        List<TopoPos> memberCells = new ArrayList<>(end - start + 1);
                        for (int i = start; i <= end; i++) {
                            memberCells.add(work.get(ids.get(refs.get(i)[0]))
                                    .get(refs.get(i)[1]));
                        }
                        int sharedDown = sharedFloorBelow(memberCells, floor);
                        int shared = sharedDown != Integer.MIN_VALUE
                                && sharedDown >= floorBound
                                ? sharedDown
                                : sharedFloorAbove(memberCells, floorBound);
                        floor = shared != Integer.MAX_VALUE ? shared : floor;
                        // When the down-floor would trench a member deeper
                        // than MAX_LOWERING or below a bound, converge
                        // upward instead: the cluster's top member stays,
                        // and everyone below rises at most the cluster
                        // span. The up-floor must be legal in EVERY member
                        // column - a per-column maximum only proves legality
                        // in the demanding column - so it iterates to a
                        // fixed point, and it may raise no fixed anchor,
                        // connector, or opening cell.
                        boolean downFeasible = true;
                        for (int i = start; i <= end && downFeasible; i++) {
                            String segmentId = ids.get(refs.get(i)[0]);
                            int idx = refs.get(i)[1];
                            TopoPos c = work.get(segmentId).get(idx);
                            if (landmarks.isOpeningCell(c) || c.y() <= floor) {
                                continue;
                            }
                            int origY = original.get(segmentId).get(idx).y();
                            if (origY - floor > MAX_LOWERING
                                    || floor < Math.max(bound.get(segmentId)[idx],
                                            stair.get(segmentId)[idx])) {
                                downFeasible = false;
                            }
                        }
                        if (!downFeasible) {
                            int top = Integer.MIN_VALUE;
                            for (int i = start; i <= end; i++) {
                                TopoPos c = work.get(ids.get(refs.get(i)[0]))
                                        .get(refs.get(i)[1]);
                                top = Math.max(top, c.y());
                            }
                            int upFloor = sharedFloorAbove(memberCells,
                                    Math.max(top, floorBound));
                            boolean upFeasible = upFloor != Integer.MAX_VALUE;
                            for (int i = start; i <= end && upFeasible; i++) {
                                String segmentId = ids.get(refs.get(i)[0]);
                                int idx = refs.get(i)[1];
                                List<TopoPos> path = work.get(segmentId);
                                TopoPos c = path.get(idx);
                                if (upFloor > c.y()
                                        && (upFloor - c.y() > MAX_LOWERING
                                                || landmarks.isOpeningCell(c)
                                                || isFixedAnchor(segmentId, idx,
                                                        path.size(), c))) {
                                    upFeasible = false;
                                }
                            }
                            if (upFeasible) {
                                floor = upFloor;
                            }
                        }
                        // Never lift a member into the merge band above a
                        // neighbouring anchor: anchors cannot move, so the
                        // cluster that later contains both would deadlock.
                        // Snap the floor below any anchor band it enters.
                        floor = snapBelowAnchorBands(anchors, memberCells, floor);
                        if (floor == Integer.MIN_VALUE) {
                            throw new IllegalStateException("crossing merge "
                                    + "cannot settle below anchor bands, refs "
                                    + clusterDebug(ids, refs, start, end, work)
                                    + " - report this fixture");
                        }
                        for (int i = start; i <= end; i++) {
                            String segmentId = ids.get(refs.get(i)[0]);
                            int idx = refs.get(i)[1];
                            List<TopoPos> path = work.get(segmentId);
                            TopoPos c = path.get(idx);
                            if (landmarks.isOpeningCell(c)) {
                                // Declared opening cells are absolutely
                                // immovable: a doorway or its approach
                                // tunnel cannot drift a block without
                                // severing the shelter. Cluster-mates
                                // converge around them instead.
                                continue;
                            }
                            if (c.y() != floor) {
                                if (floor < c.y()) {
                                    try {
                                        requireLowerable(segmentId, idx, c, floor,
                                                original.get(segmentId),
                                                Math.max(bound.get(segmentId)[idx],
                                                        stair.get(segmentId)[idx]));
                                    } catch (IllegalStateException x) {
                                        StringBuilder bounds = new StringBuilder("[");
                                        for (int j = start; j <= end; j++) {
                                            String s = ids.get(refs.get(j)[0]);
                                            int q = refs.get(j)[1];
                                            if (j > start) {
                                                bounds.append(", ");
                                            }
                                            bounds.append(s).append('[').append(q)
                                                    .append("]b=").append(bound.get(s)[q])
                                                    .append("s=").append(stair.get(s)[q]);
                                        }
                                        throw new IllegalStateException(
                                                x.getMessage() + " floor " + floor
                                                + " floorBound " + floorBound
                                                + " refs " + clusterDebug(ids, refs, start, end, work)
                                                + " memberBounds " + bounds.append(']')
                                                + " anchors " + anchorsNear(anchors, c)
                                                + " moves " + lastChanged);
                                    }
                                } else if (isFixedAnchor(segmentId, idx,
                                        path.size(), c)) {
                                    throw new IllegalStateException(
                                            "crossing merge would raise the fixed anchor "
                                                    + c + " in segment " + segmentId
                                                    + " to floor " + floor
                                                    + " (floorBound " + floorBound
                                                    + ", anchorFloor " + anchorFloor
                                                    + ", refs " + clusterDebug(ids, refs, start, end, work)
                                                    + ") - report this fixture. Last moves: " + lastChanged);
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
                        bound.get(id), stair.get(id), lastChanged, anchors);
            }
            if (!changed) {
                return freeze(work);
            }
        }
        throw new IllegalStateException("passage crossing resolution did not converge in "
                + MAX_PASSES + " passes - report this fixture; no partial geometry admitted."
                + " Last moves: " + lastChanged);
    }

    private static String clusterDebug(List<String> ids, List<int[]> refs,
                                       int start, int end,
                                       Map<String, List<TopoPos>> work) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = start; i <= end; i++) {
            String segmentId = ids.get(refs.get(i)[0]);
            int idx = refs.get(i)[1];
            if (i > start) {
                sb.append(", ");
            }
            sb.append(segmentId).append('[').append(idx).append("]=")
                    .append(work.get(segmentId).get(idx));
        }
        return sb.append(']').toString();
    }

    /** Column key to sorted anchor heights - path endpoints, declared
     * landmark openings, and hub anchors - the cells a merge may never
     * lift a neighbour above. Rebuilt per pass alongside ramp bounds
     * because endpoints converge downward: a cell's merge band follows
     * the anchor's current height, not its original. */
    private Map<Long, List<Integer>> anchorColumns(Map<String, List<TopoPos>> work,
                                                   List<String> ids) {
        Map<Long, List<Integer>> cols = new HashMap<>();
        for (String id : ids) {
            List<TopoPos> path = work.get(id);
            for (int i = 0; i < path.size(); i++) {
                TopoPos c = path.get(i);
                if (isFixedAnchor(id, i, path.size(), c)
                        || landmarks.isOpeningCell(c)) {
                    cols.computeIfAbsent(xzKey(c.x(), c.z()),
                            k -> new ArrayList<>()).add(c.y());
                }
            }
        }
        for (List<Integer> ys : cols.values()) {
            ys.sort(Integer::compareTo);
        }
        return cols;
    }

    /** The highest height {@code floor} may take without landing a member
     * inside the merge band above a neighbouring anchor. Anchors cannot
     * move, so a member raised into {@code (anchorY, anchorY + 3]} of one -
     * the shared-column span the clusterer reconciles - only defers the
     * contradiction to the cluster that later contains both. Snapping is
     * iterated to a fixed point because dropping below one anchor can
     * descend into another's band; anchor heights are indexed over the
     * Chebyshev-1 neighbourhood, which covers every swept-corner column a
     * diagonal step can touch. {@code Integer.MIN_VALUE} when no stable
     * floor exists. */
    /** Diagnostic: anchor heights within a cell's merge-band columns,
     * for failure reports. */
    private String anchorsNear(Map<Long, List<Integer>> anchors, TopoPos m) {
        StringBuilder sb = new StringBuilder("{");
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                List<Integer> ys = anchors.get(xzKey(m.x() + dx, m.z() + dz));
                if (ys != null) {
                    sb.append("[").append(m.x() + dx).append(",").append(m.z() + dz)
                            .append("]=").append(ys);
                }
            }
        }
        return sb.append('}').toString();
    }

    private int snapBelowAnchorBands(Map<Long, List<Integer>> anchors,
                                     List<TopoPos> members, int floor) {
        for (int iter = 0; iter < 16; iter++) {
            int next = floor;
            for (TopoPos m : members) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> ys = anchors.get(
                                xzKey(m.x() + dx, m.z() + dz));
                        if (ys == null) {
                            continue;
                        }
                        for (int ay : ys) {
                            if (next > ay && next <= ay + 3) {
                                next = ay;
                            }
                        }
                    }
                }
            }
            if (next == floor) {
                return floor;
            }
            floor = next;
        }
        return Integer.MIN_VALUE;
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
                if (isHubAnchor(id, path.get(i))
                        || landmarks.isOpeningCell(path.get(i))) {
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
     * headroom survive the arrival keepout clip and every placed
     * landmark's write-protected reserve (connector openings stay legal
     * so corridors may stand at the door). */
    private boolean legalFeetY(TopoPos p) {
        return !HubLayout.isProtected(p) && !HubLayout.isProtected(p.offset(0, 1, 0))
                && !landmarks.isWriteProtected(p)
                && !landmarks.isWriteProtected(p.offset(0, 1, 0))
                // finish() drops OPEN ops above the cave ceiling, so a feet
                // cell whose headroom exceeds it resolves unwalkable.
                && p.y() < SegmentGeometry.MAX_CAVE_OPEN_Y;
    }

    /** The nearest height at or above {@code y} whose column leaves a legal
     * two-high cell; {@code y} itself when already legal. */
    private int legalFeetYAtOrAbove(TopoPos column, int y) {
        for (int d = 0; d <= 64; d++) {
            if (legalFeetY(new TopoPos(column.x(), y + d, column.z()))) {
                return y + d;
            }
        }
        return y;
    }

    /** The nearest legal two-high height at or below {@code y}, falling
     * back to the nearest above when the whole column below is clipped. */
    private int legalFeetYAtOrBelow(TopoPos column, int y) {
        for (int d = 0; d <= 64; d++) {
            if (legalFeetY(new TopoPos(column.x(), y - d, column.z()))) {
                return y - d;
            }
        }
        return legalFeetYAtOrAbove(column, y);
    }

    /** Strict variant of {@link #legalFeetYAtOrBelow} with no upward
     * fallback: {@code Integer.MIN_VALUE} when the column has no legal
     * height within the window, so a shared-floor search can tell
     * "clipped all the way down" apart from "legal at {@code y}". */
    private int firstLegalBelow(TopoPos column, int y) {
        for (int d = 0; d <= 64; d++) {
            if (legalFeetY(new TopoPos(column.x(), y - d, column.z()))) {
                return y - d;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Strict variant of {@link #legalFeetYAtOrAbove}: {@code
     * Integer.MAX_VALUE} when the column has no legal height within the
     * window. */
    private int firstLegalAbove(TopoPos column, int y) {
        for (int d = 0; d <= 64; d++) {
            if (legalFeetY(new TopoPos(column.x(), y + d, column.z()))) {
                return y + d;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** The greatest height at or below {@code y} that is legal in EVERY
     * member column, or {@code Integer.MIN_VALUE} when some column is
     * clipped all the way down. A per-column minimum is not enough: the
     * smallest first-legal may itself be protected in another column, so
     * the search iterates to a fixed point where every member is legal at
     * the same height. */
    private int sharedFloorBelow(List<TopoPos> members, int y) {
        int cur = y;
        for (int iter = 0; iter < 16; iter++) {
            int next = cur;
            for (TopoPos member : members) {
                int l = firstLegalBelow(member, next);
                if (l == Integer.MIN_VALUE) {
                    return Integer.MIN_VALUE;
                }
                next = Math.min(next, l);
            }
            if (next == cur) {
                return cur;
            }
            cur = next;
        }
        return Integer.MIN_VALUE;
    }

    /** The least height at or above {@code y} legal in EVERY member
     * column, or {@code Integer.MAX_VALUE} when no such height exists. */
    private int sharedFloorAbove(List<TopoPos> members, int y) {
        int cur = y;
        for (int iter = 0; iter < 16; iter++) {
            int next = cur;
            for (TopoPos member : members) {
                int l = firstLegalAbove(member, next);
                if (l == Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
                next = Math.max(next, l);
            }
            if (next == cur) {
                return cur;
            }
            cur = next;
        }
        return Integer.MAX_VALUE;
    }

    private boolean smooth(List<TopoPos> path, String id, List<TopoPos> originalPath,
                           int[] bound, int[] stair, List<String> log,
                           Map<Long, List<Integer>> anchors) {
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
                if (!isHubAnchor(id, b) && !landmarks.isOpeningCell(b)
                        && lowered < b.y()) {
                    requireLowerable(id, i + 1, b, lowered, originalPath,
                            Math.max(bound[i + 1], stair[i + 1]));
                    path.set(i + 1, new TopoPos(b.x(), lowered, b.z()));
                    b = path.get(i + 1);
                    changed = true;
                }
                if (b.y() > a.y() + 1) {
                    if (isFixedAnchor(id, i, path.size(), a)
                            || landmarks.isOpeningCell(a)) {
                        throw new IllegalStateException("smoothing would raise the fixed anchor "
                                + a + " toward bound-locked " + b + " in segment " + id
                                + " - report this fixture");
                    }
                    // A smooth raise faces the same rule as a merge floor:
                    // never lift the cell into the merge band above a
                    // neighbouring anchor - that only strands the pair in
                    // a later cluster.
                    int raised = snapBelowAnchorBands(anchors, List.of(a),
                            legalFeetYAtOrAbove(a, b.y() - 1));
                    if (raised != Integer.MIN_VALUE && raised > a.y()) {
                        path.set(i, new TopoPos(a.x(), raised, a.z()));
                        stair[i] = Math.max(stair[i], raised);
                        changed = true;
                    }
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
                if (!isHubAnchor(id, a) && !landmarks.isOpeningCell(a)
                        && lowered < a.y()) {
                    requireLowerable(id, i, a, lowered, originalPath,
                            Math.max(bound[i], stair[i]));
                    path.set(i, new TopoPos(a.x(), lowered, a.z()));
                    a = path.get(i);
                    changed = true;
                }
                if (a.y() > b.y() + 1) {
                    if (isFixedAnchor(id, i + 1, path.size(), b)
                            || landmarks.isOpeningCell(b)) {
                        throw new IllegalStateException("smoothing would raise the fixed anchor "
                                + b + " toward bound-locked " + a + " in segment " + id
                                + " - report this fixture");
                    }
                    int raised = snapBelowAnchorBands(anchors, List.of(b),
                            legalFeetYAtOrAbove(b, a.y() - 1));
                    if (raised != Integer.MIN_VALUE && raised > b.y()) {
                        path.set(i + 1, new TopoPos(b.x(), raised, b.z()));
                        stair[i + 1] = Math.max(stair[i + 1], raised);
                        changed = true;
                    }
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
        if (landmarks.isOpeningCell(cell)) {
            throw new IllegalStateException("crossing merge would lower the declared "
                    + "landmark opening " + cell + " in segment " + segmentId
                    + " - report this fixture");
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
            List<TopoPos> path = e.getValue();
            for (int i = 0; i < path.size(); i++) {
                TopoPos c = path.get(i);
                // The admission gate owns the walk contract too: a raise
                // the anchor bands refused leaves a |dy|>1 step that a
                // converge-quiet pass would otherwise receipt - an
                // impassable corridor shipped as resolved. Nothing
                // unwalkable freezes.
                if (i > 0) {
                    TopoPos prev = path.get(i - 1);
                    if (Math.abs(c.y() - prev.y()) > 1) {
                        throw new IllegalStateException("resolved centreline "
                                + "keeps an unwalkable step " + prev + " -> " + c
                                + " in segment " + e.getKey() + " at index " + i
                                + " - report this fixture");
                    }
                }
                // Resolution only moves samples in Y, so a centreline cell
                // that ended inside the clipped hub volume can only have
                // been dragged here by a bound-forced raise - admit nothing.
                if (HubLayout.isProtected(c) || HubLayout.isProtected(c.offset(0, 1, 0))
                        || landmarks.isWriteProtected(c)
                        || landmarks.isWriteProtected(c.offset(0, 1, 0))) {
                    StringBuilder dbg = new StringBuilder();
                    for (int j = Math.max(0, i - 4); j < Math.min(path.size(), i + 5); j++) {
                        dbg.append(j == i ? " >>" : "   ").append(j)
                                .append(' ').append(path.get(j)).append('\n');
                    }
                    throw new IllegalStateException("resolved centreline cell sits inside a "
                            + "protected volume: " + c + " in segment " + e.getKey()
                            + " at index " + i + " - report this fixture\n" + dbg);
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
