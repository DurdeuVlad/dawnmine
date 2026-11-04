package com.dwurdy.dawnmine.topology;

/**
 * Raised when topology planning cannot produce a plan at all: an invalid or
 * missing profile, or bounds too small for the configured corridor geometry.
 * Callers treat this as a clean planning failure (the run goes FAILED with a
 * human-readable reason), never as a partial world mutation - no blocks are
 * placed by topology code regardless.
 */
public class TopologyException extends RuntimeException {
    public TopologyException(String message) {
        super(message);
    }

    public TopologyException(String message, Throwable cause) {
        super(message, cause);
    }
}
