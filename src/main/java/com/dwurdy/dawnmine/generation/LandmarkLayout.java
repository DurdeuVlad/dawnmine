package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.generation.LandmarkCatalog.Connector;
import com.dwurdy.dawnmine.generation.LandmarkCatalog.Definition;
import com.dwurdy.dawnmine.topology.TopoPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The placed static landmarks for one run: which node hosts which
 * definition at which anchor, the declared connector cells for edge
 * termination, and the merged reserved-footprint predicates every foreign
 * write pass (structural carve, dressing, ore, motifs) must respect. The
 * hub contributes its own reserve separately via {@link HubLayout}; this
 * layout is the landmark-owned half of the generalized reserve the
 * generator checks. Anchors are derived from the authored node position
 * alone - a safehouse node's resolved centre is always its authored pos
 * because no incident edge endpoint lands on its column, so the layout is
 * fully determined before {@link PassageNetwork} resolves.
 */
public final class LandmarkLayout {
    /** A definition anchored in the world: {@code anchor} is the footprint
     * centre at the floor-slab layer (grid y offset 0, one below feet). */
    public record Placement(String nodeId, String definitionId, TopoPos anchor,
                            Definition definition) {
        /** Connector cells in absolute plan coordinates - the only cells
         * edges are permitted to terminate at. */
        List<TopoPos> connectorPositions() {
            return definition.connectors().stream()
                    .map(c -> new TopoPos(anchor.x() + c.x(), anchor.y() + c.y(),
                            anchor.z() + c.z()))
                    .toList();
        }

        /** Reserve-box test in plan coordinates, including the margin. */
        boolean insideReserve(TopoPos pos) {
            int hx = definition.halfX() + definition.reserveMargin();
            int hz = definition.halfZ() + definition.reserveMargin();
            return Math.abs(pos.x() - anchor.x()) <= hx
                    && Math.abs(pos.z() - anchor.z()) <= hz
                    && pos.y() >= anchor.y() - definition.reserveMargin()
                    && pos.y() < anchor.y() + definition.height()
                            + definition.reserveMargin();
        }
    }

    private final Map<String, Placement> placements;
    /** Absolute cells inside any placed footprint's reserved box. */
    private final Set<TopoPos> reservedCells;
    /** Sanctioned openings through the reserve, per node: each connector
     * cell, its headroom, and the straight approach tunnel from the
     * connector out through the margin (plus its headroom). The node's own
     * edges may write and stand here; ore and motif writes may not. */
    private final Map<String, Set<TopoPos>> openingsByNode;
    private final Set<TopoPos> openingCells;

    private LandmarkLayout(Map<String, Placement> placements,
                           Set<TopoPos> reservedCells,
                           Map<String, Set<TopoPos>> openingsByNode) {
        this.placements = placements;
        this.reservedCells = reservedCells;
        this.openingsByNode = openingsByNode;
        Set<TopoPos> merged = new java.util.HashSet<>();
        for (Set<TopoPos> set : openingsByNode.values()) {
            merged.addAll(set);
        }
        this.openingCells = Set.copyOf(merged);
    }

    public static LandmarkLayout empty() {
        return new LandmarkLayout(Map.of(), Set.of(), Map.of());
    }

    /**
     * Anchor each selected node to its definition and compute the merged
     * reserve and openings. {@code anchors} maps node id to its authored
     * TopoPos (the room's feet centre); the floor-slab anchor sits one
     * below it. {@code assignments} maps node id to the chosen definition
     * id. Missing definitions or anchors are a caller error - selection is
     * fail-closed upstream.
     */
    public static LandmarkLayout place(Map<String, TopoPos> anchors,
                                       Map<String, String> assignments,
                                       Map<String, Definition> defs) {
        Map<String, Placement> placed = new LinkedHashMap<>();
        Set<TopoPos> reserved = new java.util.HashSet<>();
        Map<String, Set<TopoPos>> openingsByNode = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            Definition definition = defs.get(entry.getValue());
            TopoPos feet = anchors.get(entry.getKey());
            if (definition == null || feet == null) {
                throw new IllegalStateException("landmark placement for node '" + entry.getKey()
                        + "' has no definition ('" + entry.getValue() + "') or no anchor");
            }
            TopoPos anchor = feet.offset(0, -1, 0);
            Placement placement = new Placement(entry.getKey(), entry.getValue(),
                    anchor, definition);
            placed.put(entry.getKey(), placement);
            int hx = definition.halfX() + definition.reserveMargin();
            int hz = definition.halfZ() + definition.reserveMargin();
            for (int dx = -hx; dx <= hx; dx++) {
                for (int dy = -definition.reserveMargin(); dy < definition.height()
                        + definition.reserveMargin(); dy++) {
                    for (int dz = -hz; dz <= hz; dz++) {
                        reserved.add(new TopoPos(anchor.x() + dx, anchor.y() + dy,
                                anchor.z() + dz));
                    }
                }
            }
            openingsByNode.put(entry.getKey(),
                    Set.copyOf(openingCells(placement)));
        }
        return new LandmarkLayout(Map.copyOf(placed), Set.copyOf(reserved),
                Map.copyOf(openingsByNode));
    }

    /** The sanctioned opening cells of a candidate placement: every
     * declared door cell, its headroom, and the straight approach tunnel
     * outward through the margin (plus headroom). Shared by placement and
     * by {@code PassageNetwork.fits} so the cells a definition would claim
     * are checked before it is ever anchored. */
    static Set<TopoPos> openingCells(Placement placement) {
        Definition definition = placement.definition();
        TopoPos anchor = placement.anchor();
        Set<TopoPos> openings = new java.util.HashSet<>();
        for (Connector c : definition.connectors()) {
            TopoPos door = new TopoPos(anchor.x() + c.x(), anchor.y() + c.y(),
                    anchor.z() + c.z());
            openings.add(door);
            openings.add(door.offset(0, 1, 0));
            // Tunnel outward through the margin so the terminating
            // corridor can stand and breathe at the doorway.
            int[] step = switch (c.facing()) {
                case "east" -> new int[] {1, 0};
                case "west" -> new int[] {-1, 0};
                case "south" -> new int[] {0, 1};
                default -> new int[] {0, -1};
            };
            TopoPos out = door.offset(step[0], 0, step[1]);
            while (placement.insideReserve(out)) {
                openings.add(out);
                openings.add(out.offset(0, 1, 0));
                out = out.offset(step[0], 0, step[1]);
            }
            // The tunnel mouth - the first cell outside the reserve - is
            // declared geometry too: it is the splice seam between the
            // pinned approach and the routed corridor, so merges and
            // smoothing may never move it either, and fits() must reject
            // candidates whose mouth lands somewhere forbidden.
            openings.add(out);
            openings.add(out.offset(0, 1, 0));
        }
        return openings;
    }

    public Placement placement(String nodeId) {
        return placements.get(nodeId);
    }

    public Map<String, Placement> placements() {
        return placements;
    }

    /** True when {@code pos} falls inside any placed landmark's reserved
     * box - ore, motif, and other content writes must skip it regardless
     * of openings. */
    public boolean isReserved(TopoPos pos) {
        return reservedCells.contains(pos);
    }

    /** True when {@code pos} is reserved AND outside every sanctioned
     * connector opening - structural carve and dressing writes must skip
     * it, while corridor endpoints may open the doorway approach. */
    public boolean isWriteProtected(TopoPos pos) {
        return reservedCells.contains(pos) && !openingCells.contains(pos);
    }

    /** Declared connector cells for an edge ending at {@code nodeId}, or
     * empty when the node hosts no landmark. */
    public List<TopoPos> connectors(String nodeId) {
        Placement placement = placements.get(nodeId);
        return placement == null ? List.of() : placement.connectorPositions();
    }

    /** True when {@code pos} is any sanctioned opening: a door, its
     * headroom, or an approach-tunnel cell. Openings are declared
     * landmark geometry like the doors themselves - merges and smoothing
     * may never move a cell off them. */
    public boolean isOpeningCell(TopoPos pos) {
        return openingCells.contains(pos);
    }

    /** The opening cells owned by the given endpoint nodes - the union an
     * edge between them may legally traverse. */
    public Set<TopoPos> openingsFor(String aNodeId, String bNodeId) {
        Set<TopoPos> out = new java.util.HashSet<>();
        Set<TopoPos> a = openingsByNode.get(aNodeId);
        Set<TopoPos> b = openingsByNode.get(bNodeId);
        if (a != null) {
            out.addAll(a);
        }
        if (b != null) {
            out.addAll(b);
        }
        return out;
    }

    /** The connector cell an edge into {@code nodeId} should terminate at:
     * the declared connector nearest {@code toward} by horizontal
     * distance, ties broken by declaration order. Null when the node hosts
     * no landmark. */
    public TopoPos connectorTarget(String nodeId, TopoPos toward) {
        Placement placement = placements.get(nodeId);
        if (placement == null) {
            return null;
        }
        Connector selected = selectConnector(placement, toward);
        return selected == null ? null
                : new TopoPos(placement.anchor().x() + selected.x(),
                        placement.anchor().y() + selected.y(),
                        placement.anchor().z() + selected.z());
    }

    /** The ordered approach cells for the connector nearest {@code
     * toward}: from the tunnel mouth - the first cell outside the reserve
     * on the connector's facing axis - inward through the declared door,
     * every cell at the door's own height. Edges splice this flat tail so
     * a corridor can only ever arrive at a doorway along its declared
     * approach, never over the reserve roof. Null when the node hosts no
     * landmark. */
    public List<TopoPos> connectorApproach(String nodeId, TopoPos toward) {
        Placement placement = placements.get(nodeId);
        if (placement == null) {
            return null;
        }
        Connector c = selectConnector(placement, toward);
        if (c == null) {
            throw new IllegalStateException("landmark '" + placement.definitionId()
                    + "' at node '" + nodeId + "' declares no usable connector toward "
                    + toward + " - report this fixture");
        }
        TopoPos door = new TopoPos(placement.anchor().x() + c.x(),
                placement.anchor().y() + c.y(), placement.anchor().z() + c.z());
        int[] step = switch (c.facing()) {
            case "east" -> new int[] {1, 0};
            case "west" -> new int[] {-1, 0};
            case "south" -> new int[] {0, 1};
            default -> new int[] {0, -1};
        };
        TopoPos mouth = door.offset(step[0], 0, step[1]);
        while (placement.insideReserve(mouth)) {
            mouth = mouth.offset(step[0], 0, step[1]);
        }
        java.util.List<TopoPos> approach = new java.util.ArrayList<>();
        TopoPos cur = mouth;
        while (!cur.equals(door)) {
            approach.add(cur);
            cur = cur.offset(-step[0], 0, -step[1]);
        }
        approach.add(door);
        return List.copyOf(approach);
    }

    /** The declared connector record nearest {@code toward} by horizontal
     * distance, ties broken by declaration order. */
    private Connector selectConnector(Placement placement, TopoPos toward) {
        Connector best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Connector c : placement.definition().connectors()) {
            int dist = Math.abs(placement.anchor().x() + c.x() - toward.x())
                    + Math.abs(placement.anchor().z() + c.z() - toward.z());
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }
}
