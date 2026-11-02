package com.rusticcraft.publicmines.generation;

import com.rusticcraft.publicmines.topology.TopoEdge;
import com.rusticcraft.publicmines.topology.TopoNode;
import com.rusticcraft.publicmines.topology.TopoPos;
import com.rusticcraft.publicmines.topology.TopologyPlan;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Pure frontier selection: completed rooms near a player expose their edges. */
public final class FrontierPlanner {
    private FrontierPlanner() { }

    public static List<String> adjacentEdges(TopologyPlan topology,
                                            Collection<TopoNode> candidates,
                                            Set<String> completedNodeIds,
                                            double playerX, double playerY, double playerZ,
                                            int radius) {
        long radiusSquared = (long) radius * radius;
        Set<String> result = new TreeSet<>();
        for (TopoNode node : candidates) {
            if (!completedNodeIds.contains(node.id()) || !near(playerX, playerY, playerZ,
                    node.pos(), radiusSquared)) {
                continue;
            }
            for (TopoEdge edge : topology.adjacency().getOrDefault(node.id(), java.util.List.of())) {
                result.add("edge:" + edge.a() + ":" + edge.b());
            }
        }
        return List.copyOf(result);
    }

    private static boolean near(double x, double y, double z, TopoPos pos, long radiusSquared) {
        double dx = x - (pos.x() + 0.5);
        double dy = y - pos.y();
        double dz = z - (pos.z() + 0.5);
        return dx * dx + dy * dy + dz * dz <= radiusSquared;
    }
}
