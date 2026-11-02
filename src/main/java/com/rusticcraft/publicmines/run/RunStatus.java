package com.rusticcraft.publicmines.run;

/**
 * Lifecycle of the single shared mine run.
 *
 * <ul>
 *   <li>{@link #UNINITIALIZED} - no run has ever been created on this world.
 *   <li>{@link #INITIALIZING} - setup is in progress; a restart while in this
 *       state is detected on load and reclassified as {@link #FAILED}.
 *   <li>{@link #READY} - the run is initialized and entry is permitted.
 *   <li>{@link #RESETTING} - the scheduled daily reset owns the run; entry is
 *       locked while mine-owned terrain/entities are cleared and a new run is
 *       created. Unlike {@link #INITIALIZING}, a restart in this state resumes
 *       the persisted reset transaction instead of failing the run.
 *   <li>{@link #FAILED} - initialization failed or was interrupted; recover
 *       repair can safely retry it.
 * </ul>
 */
public enum RunStatus {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    RESETTING,
    FAILED;

    public boolean entryAllowed() {
        return this == READY;
    }
}
