package com.dwurdy.dawnmine.topology;

/** Role of a corridor segment between two topology nodes. */
public enum EdgeKind {
    /** Consecutive pair on the main route. */
    MAIN,
    /** Branch chain growing off the route or another branch. */
    BRANCH,
    /** Short spur attaching a pocket/resource field to a host node. */
    SPUR,
    /** Reconnection between two existing nodes, creating a loop. */
    LOOP
}
