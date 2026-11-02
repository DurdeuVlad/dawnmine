package com.rusticcraft.publicmines.topology;

/**
 * Undirected corridor segment between two nodes. Endpoint ids are stored in
 * canonical (lexicographic) order so equality and serialization do not depend
 * on construction direction.
 */
public record TopoEdge(String a, String b, EdgeKind kind) {

    public TopoEdge {
        if (a.compareTo(b) > 0) {
            String t = a;
            a = b;
            b = t;
        }
    }

    public boolean connects(String id) {
        return a.equals(id) || b.equals(id);
    }

    public String other(String id) {
        if (a.equals(id)) {
            return b;
        }
        if (b.equals(id)) {
            return a;
        }
        throw new IllegalArgumentException(id + " is not an endpoint of " + this);
    }

    @Override
    public String toString() {
        return a + "-" + b + "(" + kind + ")";
    }
}
