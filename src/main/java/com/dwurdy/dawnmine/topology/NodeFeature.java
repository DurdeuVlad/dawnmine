package com.dwurdy.dawnmine.topology;

/** Gameplay flags carried by a topology node. */
public enum NodeFeature {
    /** Checkpoint station location for M5 travel/Dispatch pads. */
    CHECKPOINT,
    /** Recognizable algorithmic landmark for M2b shaping. */
    LANDMARK,
    /** Bounded resource-field location for the M3 ore ecology. */
    RESOURCE_FIELD,
    /**
     * Intentional dead end: a reachable leaf where the route stops by design.
     * Every degree-1 node except the entrance must carry this flag so
     * validation can distinguish planned endings from broken connections.
     */
    DEAD_END
}
