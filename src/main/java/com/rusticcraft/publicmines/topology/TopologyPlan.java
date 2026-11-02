package com.rusticcraft.publicmines.topology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable output of {@link TopologyPlanner}: a connected graph of nodes and
 * corridor segments plus the ordered main route, fully determined by
 * (seed, profile, bounds). Contains no block data - M2b turns this plan into
 * geometry.
 *
 * <p>{@link #canonical()} is the byte-for-byte comparable serialization used
 * to prove determinism; {@link #fingerprint()} is its SHA-256 and is what the
 * run record persists so a replanned topology can be compared across restart.
 */
public final class TopologyPlan {
    private final long seed;
    private final String profileId;
    private final TopoBounds bounds;
    private final List<TopoNode> nodes;
    private final List<TopoEdge> edges;
    private final List<String> mainPath;
    private final String entranceId;
    private final String terminusId;

    private volatile Map<String, TopoNode> nodeMap;
    private volatile Map<String, List<TopoEdge>> adjacency;
    private volatile String canonical;
    private volatile String fingerprint;

    public TopologyPlan(long seed, String profileId, TopoBounds bounds,
                        List<TopoNode> nodes, List<TopoEdge> edges, List<String> mainPath,
                        String entranceId, String terminusId) {
        this.seed = seed;
        this.profileId = profileId;
        this.bounds = bounds;
        List<TopoNode> sortedNodes = new ArrayList<>(nodes);
        sortedNodes.sort(Comparator.comparing(TopoNode::id));
        this.nodes = List.copyOf(sortedNodes);
        List<TopoEdge> sortedEdges = new ArrayList<>(edges);
        sortedEdges.sort(Comparator.comparing(TopoEdge::a)
                .thenComparing(TopoEdge::b)
                .thenComparing(e -> e.kind().name()));
        this.edges = List.copyOf(sortedEdges);
        this.mainPath = List.copyOf(mainPath);
        this.entranceId = entranceId;
        this.terminusId = terminusId;
    }

    public long seed() { return seed; }
    public String profileId() { return profileId; }
    public TopoBounds bounds() { return bounds; }
    public List<TopoNode> nodes() { return nodes; }
    public List<TopoEdge> edges() { return edges; }
    /** Ordered ids of the main route, entrance first, deepest node last. */
    public List<String> mainPath() { return mainPath; }
    public String entranceId() { return entranceId; }
    public String terminusId() { return terminusId; }

    public TopoNode node(String id) {
        return nodeMap().get(id);
    }

    public Map<String, TopoNode> nodeMap() {
        Map<String, TopoNode> map = nodeMap;
        if (map == null) {
            Map<String, TopoNode> built = new LinkedHashMap<>();
            for (TopoNode node : nodes) {
                built.put(node.id(), node);
            }
            nodeMap = map = Map.copyOf(built);
        }
        return map;
    }

    /** Deterministic adjacency: neighbours of each node in edge order. */
    public Map<String, List<TopoEdge>> adjacency() {
        Map<String, List<TopoEdge>> map = adjacency;
        if (map == null) {
            Map<String, List<TopoEdge>> built = new LinkedHashMap<>();
            for (TopoNode node : nodes) {
                built.put(node.id(), new ArrayList<>());
            }
            for (TopoEdge edge : edges) {
                // Keep malformed plans inspectable so the validator can
                // report missing endpoints instead of failing while building
                // adjacency before it reaches its structural checks.
                built.computeIfAbsent(edge.a(), ignored -> new ArrayList<>()).add(edge);
                built.computeIfAbsent(edge.b(), ignored -> new ArrayList<>()).add(edge);
            }
            Map<String, List<TopoEdge>> frozen = new LinkedHashMap<>();
            for (Map.Entry<String, List<TopoEdge>> e : built.entrySet()) {
                frozen.put(e.getKey(), List.copyOf(e.getValue()));
            }
            adjacency = map = Map.copyOf(frozen);
        }
        return map;
    }

    public List<TopoNode> checkpoints() {
        List<TopoNode> found = new ArrayList<>();
        for (TopoNode node : nodes) {
            if (node.has(NodeFeature.CHECKPOINT)) {
                found.add(node);
            }
        }
        return found;
    }

    public Set<String> regionIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (TopoNode node : nodes) {
            ids.add(node.region());
        }
        return ids;
    }

    public double totalCorridorLength() {
        double total = 0;
        Map<String, TopoNode> map = nodeMap();
        for (TopoEdge edge : edges) {
            TopoNode a = map.get(edge.a());
            TopoNode b = map.get(edge.b());
            if (a != null && b != null) {
                total += a.pos().distanceTo(b.pos());
            }
        }
        return total;
    }

    /**
     * Deterministic serialization: identical (seed, profile, bounds) inputs
     * always produce identical bytes across JVMs and restarts. Node and edge
     * order is sorted; feature sets are alphabetized; labels are
     * planner-generated and cannot contain newlines.
     */
    public String canonical() {
        String text = canonical;
        if (text == null) {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("profile=").append(profileId).append('\n');
            sb.append("seed=").append(seed).append('\n');
            sb.append("bounds=").append(bounds).append('\n');
            sb.append("entrance=").append(entranceId).append('\n');
            sb.append("terminus=").append(terminusId).append('\n');
            sb.append("main=");
            for (String id : mainPath) {
                sb.append(id).append(' ');
            }
            sb.append('\n');
            for (TopoNode node : nodes) {
                sb.append("node ").append(node.id())
                        .append(" kind=").append(node.kind())
                        .append(" region=").append(node.region())
                        .append(" pos=").append(node.pos())
                        .append(" features=");
                Set<String> names = new TreeSet<>();
                for (NodeFeature f : node.features()) {
                    names.add(f.name());
                }
                sb.append(String.join("|", names));
                if (!node.label().isEmpty()) {
                    sb.append(" label=").append(node.label());
                }
                sb.append('\n');
            }
            for (TopoEdge edge : edges) {
                sb.append("edge ").append(edge.a()).append(' ')
                        .append(edge.b()).append(' ').append(edge.kind()).append('\n');
            }
            canonical = text = sb.toString();
        }
        return text;
    }

    public String fingerprint() {
        String fp = fingerprint;
        if (fp == null) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(canonical().getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                    sb.append(Character.forDigit(b & 0xF, 16));
                }
                fingerprint = fp = sb.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
        return fp;
    }

    /** One-line operator-facing summary, e.g. for {@code /publicmine status}. */
    public String summary() {
        return nodes.size() + " nodes, " + edges.size() + " segments, "
                + checkpoints().size() + " checkpoints, " + regionIds().size()
                + " regions, fingerprint " + fingerprint().substring(0, 12);
    }
}
