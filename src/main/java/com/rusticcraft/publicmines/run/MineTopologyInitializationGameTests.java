package com.rusticcraft.publicmines.run;

import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regression tests for M2a guards around reinitializing persisted READY runs. */
@GameTestHolder("publicmines")
@PrefixGameTestTemplate(false)
public class MineTopologyInitializationGameTests {

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void malformedReadyReceiptBlocksArrivalPadRepair(GameTestHelper helper) {
        MineManager manager = MineManager.get(helper.getLevel().getServer());
        MineRunData data = manager.data();
        data.markStatus(RunStatus.READY);
        data.setTopology("standard", "not-a-sha256-fingerprint", 100, 104);

        List<MineManager.Step> steps = manager.initialize();

        helper.assertValueEqual(steps.size(), 1,
                "invalid READY topology must return before arrival-pad verification or repair");
        helper.assertValueEqual(steps.get(0).name(), "topology",
                "invalid READY topology did not fail at the topology gate");
        helper.assertFalse(steps.get(0).ok(), "invalid READY topology was accepted");
        helper.assertTrue(steps.get(0).detail().contains("fingerprint is malformed"),
                "expected malformed receipt guidance, got: " + steps.get(0).detail());
        helper.assertValueEqual(data.status(), RunStatus.FAILED,
                "invalid READY run remained entry-enabled");

        // Avoid leaking this synthetic state into other GameTests on the same server.
        data.setTopology("", "", 0, 0);
        data.markStatus(RunStatus.UNINITIALIZED);
        helper.succeed();
    }
}
