package com.rusticcraft.publicmines.run;

/**
 * Persisted phase of the daily-reset transaction recorded in
 * {@link MineRunData}. The phase survives restarts so an interrupted cleanup
 * is detected, resumed, and never duplicated.
 *
 * <ul>
 *   <li>{@link #NONE} - no reset is in progress.</li>
 *   <li>{@link #CLEANING} - entry is locked, occupants are evacuated, and the
 *       recorded owned volumes are being restored to the generated baseline a
 *       bounded number of chunk columns per tick.</li>
 *   <li>{@link #FINALIZING} - cleanup is complete; the new shared run is being
 *       created through the normal initialization path.</li>
 * </ul>
 */
public enum ResetPhase {
    NONE,
    CLEANING,
    FINALIZING;

    static ResetPhase fromSaved(int value) {
        return switch (value) {
            case 0 -> NONE;
            case 1 -> CLEANING;
            case 2 -> FINALIZING;
            default -> null;
        };
    }

    int saved() {
        return switch (this) {
            case NONE -> 0;
            case CLEANING -> 1;
            case FINALIZING -> 2;
        };
    }
}
