package com.dwurdy.dawnmine.topology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-mutation gate for a {@link TopologyPlan}. Every check is a pure function
 * of the plan, its profile, and the recorded bounds, so an invalid plan fails
 * deterministically before any block is placed.
 *
 * <p>Rules enforced (acceptance criteria of the M2a issue):
 * connectivity from the entrance, main-route continuity, region order and
 * depth-band membership, bounds and corridor margins, duplicate ids and
 * coordinates, intentional dead ends, checkpoint placement on the main route,
 * and the configured generation budget.
 */
public final class TopologyValidator {

    private TopologyValidator() {}

    public static TopologyReport validate(TopologyPlan plan, TopologyProfile profile) {
        List<String> problems = new ArrayList<>();
        if (plan == null) {
            return new TopologyReport(List.of("plan: no topology plan was produced"));
        }
        if (profile == null) {
            return new TopologyReport(List.of("profile: no topology profile was supplied"));
        }
        TopoBounds bounds = plan.bounds();
        Map<String, TopoNode> map = plan.nodeMap();
        Map<String, List<TopoEdge>> adjacency = plan.adjacency();

        for (String issue : profile.validate()) {
            problems.add("profile: " + issue);
        }
        checkStructure(plan, map, problems);
        checkBounds(plan, profile, bounds, map, problems);
        checkEdges(plan, map, problems);
        checkConnectivity(plan, adjacency, problems);
        checkMainRoute(plan, profile, map, problems);
        checkRegions(plan, profile, map, problems);
        checkDeadEnds(plan, adjacency, problems);
        checkBudget(plan, profile, problems);

        return new TopologyReport(List.copyOf(problems));
    }

    private static void checkStructure(TopologyPlan plan, Map<String, TopoNode> map,
                                       List<String> problems) {
        if (plan.nodes().size() < 2) {
            problems.add("structure: plan has fewer than 2 nodes");
        }
        if (map.size() != plan.nodes().size()) {
            problems.add("structure: duplicate node ids present");
        }
        Set<TopoPos> seen = new HashSet<>();
        for (TopoNode node : plan.nodes()) {
            if (!seen.add(node.pos())) {
                problems.add("duplicates: two nodes share position " + node.pos());
            }
            if (node.id() == null || node.id().isBlank()) {
                problems.add("structure: a node has a blank id");
            }
        }
        TopoNode entrance = plan.entranceId() == null ? null : map.get(plan.entranceId());
        if (entrance == null || entrance.kind() != NodeKind.ENTRANCE) {
            problems.add("structure: entrance id '" + plan.entranceId()
                    + "' is missing or not an ENTRANCE node");
        }
        if (plan.terminusId() == null || map.get(plan.terminusId()) == null) {
            problems.add("structure: terminus id '" + plan.terminusId() + "' is missing");
        }
        if (plan.mainPath().size() < 2) {
            problems.add("route: main route has fewer than 2 nodes");
        }
    }

    private static void checkBounds(TopologyPlan plan, TopologyProfile profile,
                                    TopoBounds bounds, Map<String, TopoNode> map,
                                    List<String> problems) {
        if (bounds.isEmpty()) {
            problems.add("bounds: recorded bounds are empty");
            return;
        }
        int half = profile.corridorHalfWidth();
        for (TopoNode node : plan.nodes()) {
            if (!bounds.contains(node.pos())) {
                problems.add("bounds: node " + node.id() + " at " + node.pos()
                        + " is outside " + bounds);
            } else if (!bounds.containsBox(node.pos(), half)) {
                problems.add("bounds: node " + node.id() + " at " + node.pos()
                        + " leaves less than the " + half
                        + "-block corridor margin to the owned boundary");
            }
        }
    }

    private static void checkEdges(TopologyPlan plan, Map<String, TopoNode> map,
                                   List<String> problems) {
        Set<String> seen = new HashSet<>();
        for (TopoEdge edge : plan.edges()) {
            if (edge.a().equals(edge.b())) {
                problems.add("edges: self-edge on node " + edge.a());
            }
            if (!seen.add(edge.a() + "|" + edge.b())) {
                problems.add("edges: duplicate segment " + edge);
            }
            if (!map.containsKey(edge.a()) || !map.containsKey(edge.b())) {
                problems.add("edges: segment " + edge + " references a missing node");
            }
        }
    }

    private static void checkConnectivity(TopologyPlan plan,
                                          Map<String, List<TopoEdge>> adjacency,
                                          List<String> problems) {
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        if (plan.entranceId() != null && adjacency.containsKey(plan.entranceId())) {
            reached.add(plan.entranceId());
            queue.add(plan.entranceId());
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            for (TopoEdge edge : adjacency.getOrDefault(id, List.of())) {
                String next = edge.other(id);
                if (reached.add(next)) {
                    queue.add(next);
                }
            }
        }
        for (TopoNode node : plan.nodes()) {
            if (!reached.contains(node.id())) {
                problems.add("connectivity: node " + node.id()
                        + " is unreachable from the entrance");
            }
        }
    }

    private static void checkMainRoute(TopologyPlan plan, TopologyProfile profile,
                                       Map<String, TopoNode> map, List<String> problems) {
        List<String> path = plan.mainPath();
        Set<String> edgeKeys = new HashSet<>();
        for (TopoEdge edge : plan.edges()) {
            edgeKeys.add(edge.a() + "|" + edge.b() + "|" + edge.kind());
        }
        if (!path.isEmpty() && !path.get(0).equals(plan.entranceId())) {
            problems.add("route: main route does not start at the entrance");
        }
        if (!path.isEmpty() && !path.get(path.size() - 1).equals(plan.terminusId())) {
            problems.add("route: main route does not end at the terminus");
        }
        Set<String> onPath = new HashSet<>(path);
        if (onPath.size() != path.size()) {
            problems.add("route: main route revisits a node id");
        }
        for (int i = 0; i + 1 < path.size(); i++) {
            String a = path.get(i);
            String b = path.get(i + 1);
            String key = (a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a) + "|MAIN";
            if (!edgeKeys.contains(key)) {
                problems.add("route: no MAIN segment between consecutive nodes "
                        + a + " and " + b);
            }
        }
        for (TopoNode node : plan.nodes()) {
            if (node.has(NodeFeature.CHECKPOINT) && !onPath.contains(node.id())) {
                problems.add("route: checkpoint " + node.id()
                        + " is not on the main route");
            }
        }
        if (plan.checkpoints().isEmpty()) {
            problems.add("route: no checkpoint stations were planned");
        }
    }

    private static void checkRegions(TopologyPlan plan, TopologyProfile profile,
                                     Map<String, TopoNode> map, List<String> problems) {
        Map<String, Integer> order = new HashMap<>();
        Map<String, TopologyProfile.RegionSpec> specById = new HashMap<>();
        for (int i = 0; i < profile.regions().size(); i++) {
            TopologyProfile.RegionSpec spec = profile.regions().get(i);
            order.put(spec.id(), i);
            specById.put(spec.id(), spec);
        }
        for (TopoNode node : plan.nodes()) {
            TopologyProfile.RegionSpec spec = specById.get(node.region());
            if (spec == null) {
                problems.add("regions: node " + node.id() + " uses unknown region '"
                        + node.region() + "'");
            } else if (node.pos().y() < spec.bottomY() || node.pos().y() > spec.topY()) {
                problems.add("regions: node " + node.id() + " at y=" + node.pos().y()
                        + " is outside the '" + spec.id() + "' band ["
                        + spec.bottomY() + "," + spec.topY() + "]");
            }
        }
        int previous = 0;
        Set<Integer> visited = new HashSet<>();
        for (String id : plan.mainPath()) {
            TopoNode node = map.get(id);
            if (node == null) {
                problems.add("regions: main-route node '" + id + "' is missing");
                continue;
            }
            Integer idx = order.get(node.region());
            if (idx == null) {
                continue; // unknown region already reported
            }
            if (idx < previous) {
                problems.add("regions: main route steps back from region index "
                        + previous + " to " + idx + " at node " + id);
            }
            previous = Math.max(previous, idx);
            visited.add(idx);
        }
        for (int i = 0; i < profile.regions().size(); i++) {
            if (!visited.contains(i)) {
                problems.add("regions: main route never enters region '"
                        + profile.regions().get(i).id() + "'");
            }
        }
    }

    private static void checkDeadEnds(TopologyPlan plan,
                                      Map<String, List<TopoEdge>> adjacency,
                                      List<String> problems) {
        for (TopoNode node : plan.nodes()) {
            int degree = adjacency.getOrDefault(node.id(), List.of()).size();
            if (degree == 1 && node.kind() != NodeKind.ENTRANCE
                    && !node.has(NodeFeature.DEAD_END)) {
                problems.add("deadends: leaf node " + node.id()
                        + " is not marked as an intentional dead end");
            }
            if (degree > 1 && node.has(NodeFeature.DEAD_END)) {
                problems.add("deadends: node " + node.id()
                        + " is flagged DEAD_END but has " + degree + " connections");
            }
        }
    }

    private static void checkBudget(TopologyPlan plan, TopologyProfile profile,
                                    List<String> problems) {
        if (plan.nodes().size() > profile.maxNodes()) {
            problems.add("budget: " + plan.nodes().size() + " nodes exceed the "
                    + profile.maxNodes() + " node budget");
        }
        double length = plan.totalCorridorLength();
        if (length > profile.maxCorridorLength()) {
            problems.add("budget: corridor length " + Math.round(length)
                    + " exceeds the " + profile.maxCorridorLength() + " block budget");
        }
    }
}
