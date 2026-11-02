package com.rusticcraft.publicmines.topology;

/**
 * Spatial role of a topology node. Gameplay roles (checkpoint, landmark,
 * resource field, dead end) are orthogonal {@link NodeFeature} flags so one
 * node can be, for example, a cavern that is also a checkpoint station.
 */
public enum NodeKind {
    /** Run start: the shaft/room immediately under the arrival pad. */
    ENTRANCE,
    /** Ordinary waypoint on the main route. */
    ROUTE,
    /** Large room on or near the main route. */
    CAVERN,
    /** Medium side room on a branch chain. */
    CAVE,
    /** Small pocket; hosts optional resource-field markers. */
    POCKET
}
