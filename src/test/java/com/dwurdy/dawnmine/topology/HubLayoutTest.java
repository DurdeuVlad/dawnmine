package com.dwurdy.dawnmine.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class HubLayoutTest {
    private static final TopoBounds BOUNDS = new TopoBounds(-1024, -64, -1024, 1023, 319, 1023);

    @Test
    void standardEntranceAndArrivalUseTheSameFixedHub() {
        var entrance = HubLayout.entrancePos(TopologyProfile.builtin(), BOUNDS);
        assertEquals(HubLayout.ENTRANCE, entrance);
        assertEquals(HubLayout.LANDING, HubLayout.landingFor(entrance));
    }

    @Test
    void incompatibleHeightFailsInsteadOfRelocatingOnlyTheEntrance() {
        var profile = TopologyProfile.builtin();
        var regions = new ArrayList<>(profile.regions());
        var first = regions.getFirst();
        regions.set(0, new TopologyProfile.RegionSpec(first.id(), first.name(), 40, first.bottomY(),
                first.mainRouteNodes(), first.caverns(), first.branches(), first.loops(),
                first.pockets(), first.resourceFields(), first.landmarks(), first.checkpoints(), 0));
        var incompatible = new TopologyProfile(profile.id(), profile.boundsMargin(),
                profile.corridorHalfWidth(), profile.minStep(), profile.maxStep(),
                profile.maxTurnDeg(), profile.loopMaxDistance(), profile.maxNodes(),
                profile.maxCorridorLength(), profile.branchMaxLength(), regions);
        assertThrows(TopologyException.class, () -> HubLayout.entrancePos(incompatible, BOUNDS));
    }

    @Test
    void boundsMustContainTheWholeHubNotOnlyTheEntrance() {
        assertThrows(TopologyException.class, () -> HubLayout.entrancePos(
                TopologyProfile.builtin(), new TopoBounds(0, -64, 0, 1023, 319, 1023)));
    }
}
