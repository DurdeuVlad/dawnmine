package com.dwurdy.dawnmine.topology;

import java.util.EnumSet;
import java.util.Set;

/**
 * One topology node: a place M2b will shape (waypoint, cavern, cave, pocket)
 * annotated with gameplay features. {@code region} is the id of the
 * {@link TopologyProfile.RegionSpec} band the node belongs to.
 */
public record TopoNode(String id, NodeKind kind, String region, TopoPos pos,
                       Set<NodeFeature> features, String label) {

    public TopoNode {
        features = Set.copyOf(features);
        if (label == null) {
            label = "";
        }
    }

    public boolean has(NodeFeature feature) {
        return features.contains(feature);
    }

    public TopoNode withKind(NodeKind newKind) {
        return new TopoNode(id, newKind, region, pos, features, label);
    }

    public TopoNode withFeature(NodeFeature feature) {
        Set<NodeFeature> next = features.isEmpty()
                ? EnumSet.of(feature) : EnumSet.copyOf(features);
        next.add(feature);
        return new TopoNode(id, kind, region, pos, next, label);
    }

    public TopoNode withoutFeature(NodeFeature feature) {
        if (!features.contains(feature)) {
            return this;
        }
        Set<NodeFeature> next = EnumSet.copyOf(features);
        next.remove(feature);
        return new TopoNode(id, kind, region, pos, next, label);
    }

    public TopoNode withLabel(String newLabel) {
        return new TopoNode(id, kind, region, pos, features, newLabel);
    }
}
