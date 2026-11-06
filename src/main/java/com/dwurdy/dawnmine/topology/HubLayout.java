package com.dwurdy.dawnmine.topology;

/**
 * Canonical owner of every expedition-hub layout anchor. The hub is a fixed
 * underground structure centred on world (0, 0); all coordinates are pure
 * topology positions so topology, geometry, and run code share one source of
 * truth without importing server classes.
 */
public final class HubLayout {
    public static final String SAFE_ZONE_ID = "dawnmine:hub";
    /** Floor slab Y and standing-feet Y inside the hub. */
    public static final int FLOOR_Y = 41;
    public static final int FEET_Y = 42;
    /** Interior half-extent (interior air -21..21, outer shell -22..22). */
    public static final int OUTER_HALF = 22;
    public static final int INTERIOR_HALF = 21;
    /** Lowest interior air Y and the highest covered roof Y (baseline 62). */
    public static final int INTERIOR_MIN_Y = 40;
    public static final int ROOF_TOP_Y = 59;
    /** Extra reserve margin around the outer shell for other segments. */
    public static final int RESERVE_HALF = 23;
    /** Player landing (feet) inside the hub, facing north to the portal hall. */
    public static final TopoPos LANDING = new TopoPos(0, FEET_Y, 8);
    /** Generated return-teleporter lodestone console. */
    public static final TopoPos TELEPORTER_CONSOLE = new TopoPos(0, FEET_Y, -14);
    /** Generated Dispatch console barrel in the west wing. */
    public static final TopoPos DISPATCH_CONSOLE = new TopoPos(-12, FEET_Y, 0);
    /** East gateway threshold on the hub wall. */
    public static final TopoPos EAST_EXIT = new TopoPos(OUTER_HALF, FEET_Y, 0);
    public static final TopoPos ENTRANCE = new TopoPos(28, FEET_Y, 0);
    public static final TopoPos SAFE_MIN = new TopoPos(-OUTER_HALF, INTERIOR_MIN_Y, -OUTER_HALF);
    public static final TopoPos SAFE_MAX = new TopoPos(24, ROOF_TOP_Y, OUTER_HALF);
    /** Horizontal offset from the entrance node to the landing. */
    public static final TopoPos ENTRANCE_TO_LANDING = new TopoPos(-28, 0, 8);

    private HubLayout() { }

    /** Fixed underground entrance; unsupported profiles fail before any mutation. */
    public static TopoPos entrancePos(TopologyProfile profile, TopoBounds bounds) {
        TopologyProfile.RegionSpec first = profile.regions().get(0);
        if (FEET_Y < first.bottomY() + 4 || FEET_Y > first.topY() - 4) {
            throw new TopologyException("the first region must contain the fixed hub entrance at Y"
                    + FEET_Y + " with four blocks of vertical margin");
        }
        if (!bounds.contains(new TopoPos(-RESERVE_HALF, INTERIOR_MIN_Y - 1, -RESERVE_HALF))
                || !bounds.contains(new TopoPos(ENTRANCE.x() + 1, ROOF_TOP_Y + 1, RESERVE_HALF))) {
            throw new TopologyException("mine bounds must contain the whole underground hub "
                    + "and its east gateway at world (0,42,0)");
        }
        return ENTRANCE;
    }

    /** Player landing derived from the entrance node. */
    public static TopoPos landingFor(TopoPos entrancePos) {
        return entrancePos.offset(ENTRANCE_TO_LANDING.x(),
                ENTRANCE_TO_LANDING.y(), ENTRANCE_TO_LANDING.z());
    }

    /** Full hub reserve: nothing may carve, dress, or place ore here except
     * the hub build itself and the east gateway opening band. */
    public static boolean isReserved(TopoPos pos) {
        return pos.x() >= -RESERVE_HALF && pos.x() <= RESERVE_HALF
                && pos.z() >= -RESERVE_HALF && pos.z() <= RESERVE_HALF
                && pos.y() >= INTERIOR_MIN_Y - 1 && pos.y() <= ROOF_TOP_Y + 1;
    }

    /** The gateway band through the east wall - the one place other segments
     * are allowed to open through the reserve. */
    public static boolean isGatewayBand(TopoPos pos) {
        return pos.x() >= INTERIOR_HALF && pos.x() <= 25
                && Math.abs(pos.z()) <= 3
                && pos.y() >= INTERIOR_MIN_Y && pos.y() <= ROOF_TOP_Y;
    }

    /** Reserve minus gateway: the "arrival keepout" every non-hub segment
     * must respect. */
    public static boolean isProtected(TopoPos pos) {
        return isReserved(pos) && !isGatewayBand(pos);
    }

}
