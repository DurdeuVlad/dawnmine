package com.dwurdy.dawnmine.topology;

import java.util.List;

/**
 * Result of validating a {@link TopologyPlan}. An empty problem list means the
 * plan is safe for block placement; any entry is a human-readable rule
 * violation (code + detail) that must fail the run before world mutation.
 */
public record TopologyReport(List<String> problems) {

    public boolean ok() {
        return problems.isEmpty();
    }

    public String firstProblem() {
        return problems.isEmpty() ? "" : problems.get(0);
    }

    /** Compact multi-problem summary for setup/validate output. */
    public String describe() {
        if (problems.isEmpty()) {
            return "no problems";
        }
        if (problems.size() == 1) {
            return problems.get(0);
        }
        return problems.get(0) + " (+" + (problems.size() - 1) + " more)";
    }
}
