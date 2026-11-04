package com.dwurdy.dawnmine.protection;

/** Pure policy for mine PvP and explicitly registered safe-zone combat rules. */
public final class CombatPolicy {
    private CombatPolicy() { }

    public enum CombatantKind {
        PLAYER,
        HOSTILE,
        OTHER
    }

    /** Location membership for each end of an attempted interaction. */
    public record Context(boolean attackerInMine, boolean targetInMine,
                          boolean attackerInSafeZone, boolean targetInSafeZone) { }

    /**
     * Returns whether a damage/effect interaction should be denied.
     * PvP is blocked anywhere in the active mine. Player/hostile interactions
     * are blocked whenever either endpoint is inside a registered safe zone,
     * preventing attacks into or out of the zone.
     */
    public static boolean shouldBlock(CombatantKind attacker, CombatantKind target, Context context) {
        if (attacker == CombatantKind.PLAYER && target == CombatantKind.PLAYER) {
            return context.attackerInMine() || context.targetInMine();
        }
        boolean playerHostile = (attacker == CombatantKind.PLAYER && target == CombatantKind.HOSTILE)
                || (attacker == CombatantKind.HOSTILE && target == CombatantKind.PLAYER);
        return playerHostile && (context.attackerInSafeZone() || context.targetInSafeZone());
    }

    /** Source-less harmful effects cannot be attributed, so protect combatants located in safe zones. */
    public static boolean shouldBlockUnattributedHarmfulEffect(CombatantKind target, boolean targetInSafeZone) {
        return targetInSafeZone && (target == CombatantKind.PLAYER || target == CombatantKind.HOSTILE);
    }
}
