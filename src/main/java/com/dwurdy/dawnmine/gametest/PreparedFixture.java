package com.dwurdy.dawnmine.gametest;

import com.dwurdy.dawnmine.generation.MineGenerator;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;

/** Shared gate for the {@code dawnmine_hub_prepared} batch: tests assert
 * against a fully carved run, and the gate must wait instead of asserting
 * once because two windows can close it on a live server:
 *
 * <ul>
 * <li>post-restart plan refresh - the topology is re-ensured on the first
 *     readiness read after boot, and
 * <li>an active daily-reset transaction - m7 tests open real ones.
 * </ul>
 *
 * <p>The gate is deliberately {@code preparation().complete()} - every
 * expected segment of the current run receipted - rather than
 * {@code preparationComplete()}: the latter also requires the shell-column
 * pregen scan, a player-entry concern no GameTest touches. Worse, any reset
 * transaction re-arms the scan, which would close the gate for a full
 * 16k-column rescan mid-suite. The test's timeoutTicks bounds the wait. */
public final class PreparedFixture {
    private PreparedFixture() { }

    /** Retries the preparation gate every tick until it holds, then runs the
     * test body once. Succeeds when the body completes; the enclosing
     * timeoutTicks is the only bound. */
    public static void awaitPrepared(GameTestHelper helper,
            Consumer<GameTestHelper> body) {
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        MineGenerator.get(helper.getLevel().getServer())
                                .preparation().complete(),
                        "fixture requires a prepared mine"))
                .thenExecute(() -> body.accept(helper))
                .thenSucceed();
    }
}
