package com.dwurdy.dawnmine.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombatPolicyTest {
    private static final CombatPolicy.CombatantKind PLAYER = CombatPolicy.CombatantKind.PLAYER;
    private static final CombatPolicy.CombatantKind HOSTILE = CombatPolicy.CombatantKind.HOSTILE;
    private static final CombatPolicy.CombatantKind OTHER = CombatPolicy.CombatantKind.OTHER;

    @Test
    void pvpIsDeniedWhenEitherPlayerIsInsideTheMine() {
        assertTrue(blocked(PLAYER, PLAYER, new CombatPolicy.Context(true, false, false, false)));
        assertTrue(blocked(PLAYER, PLAYER, new CombatPolicy.Context(false, true, false, false)));
        assertFalse(blocked(PLAYER, PLAYER, new CombatPolicy.Context(false, false, false, false)));
    }

    @Test
    void safeZoneBlocksPlayerToHostileCombatAcrossBothBoundariesAndDirections() {
        CombatPolicy.Context attackerInside = new CombatPolicy.Context(false, false, true, false);
        CombatPolicy.Context targetInside = new CombatPolicy.Context(false, false, false, true);

        assertTrue(blocked(PLAYER, HOSTILE, attackerInside));
        assertTrue(blocked(PLAYER, HOSTILE, targetInside));
        assertTrue(blocked(HOSTILE, PLAYER, attackerInside));
        assertTrue(blocked(HOSTILE, PLAYER, targetInside));
        assertFalse(blocked(PLAYER, HOSTILE, new CombatPolicy.Context(true, true, false, false)));
        assertFalse(blocked(HOSTILE, PLAYER, new CombatPolicy.Context(false, false, false, false)));
    }

    @Test
    void unrelatedInteractionsAndHostileVsHostileRemainUnaffected() {
        CombatPolicy.Context allInside = new CombatPolicy.Context(true, true, true, true);

        assertFalse(blocked(OTHER, PLAYER, allInside));
        assertFalse(blocked(PLAYER, OTHER, allInside));
        assertFalse(blocked(HOSTILE, HOSTILE, allInside));
        assertFalse(blocked(OTHER, OTHER, allInside));
    }

    @Test
    void sourceLessHarmfulEffectsAreDeniedForCombatantsInsideSafeZones() {
        assertTrue(CombatPolicy.shouldBlockUnattributedHarmfulEffect(PLAYER, true));
        assertTrue(CombatPolicy.shouldBlockUnattributedHarmfulEffect(HOSTILE, true));
        assertFalse(CombatPolicy.shouldBlockUnattributedHarmfulEffect(PLAYER, false));
        assertFalse(CombatPolicy.shouldBlockUnattributedHarmfulEffect(HOSTILE, false));
        assertFalse(CombatPolicy.shouldBlockUnattributedHarmfulEffect(OTHER, true));
    }

    @Test
    void projectileSourceRetainsSafeZoneMembershipAfterShooterLeaves() {
        CombatPolicy.Context launchInsideImpactOutside = new CombatPolicy.Context(false, false, true, false);
        assertTrue(blocked(HOSTILE, PLAYER, launchInsideImpactOutside));
    }

    private static boolean blocked(CombatPolicy.CombatantKind attacker,
                                   CombatPolicy.CombatantKind target,
                                   CombatPolicy.Context context) {
        return CombatPolicy.shouldBlock(attacker, target, context);
    }
}
