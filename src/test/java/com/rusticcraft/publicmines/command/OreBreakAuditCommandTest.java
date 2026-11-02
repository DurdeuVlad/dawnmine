package com.rusticcraft.publicmines.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rusticcraft.publicmines.generation.OreBreakAudit;
import java.util.List;
import org.junit.jupiter.api.Test;

class OreBreakAuditCommandTest {
    @Test
    void hookScanErrorsFailAuditEvenWhenPlayerBreakCasesPass() {
        OreBreakAudit.Result passingBreaks = new OreBreakAudit.Result(60, List.of());
        OreBreakAudit.Result failingBreaks = new OreBreakAudit.Result(0, List.of("catalog incomplete"));
        OreBreakAudit.HookScanResult cleanScan = new OreBreakAudit.HookScanResult(10, List.of(), List.of());
        OreBreakAudit.HookScanResult failedScan = new OreBreakAudit.HookScanResult(10, List.of(),
                List.of("example:broken_block: IllegalAccessException"));

        assertTrue(OreBreakAuditCommand.isPassed(passingBreaks, cleanScan));
        assertFalse(OreBreakAuditCommand.isPassed(passingBreaks, failedScan));
        assertFalse(OreBreakAuditCommand.isPassed(failingBreaks, cleanScan));
    }
}
