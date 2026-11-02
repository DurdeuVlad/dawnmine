package com.rusticcraft.publicmines;

import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.command.PublicMineCommand;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import com.rusticcraft.publicmines.gametest.MineLifecycleGameTests;
import com.rusticcraft.publicmines.run.MineManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Server-safe entry point for the Rustic Craft public-mine mod.
 *
 * <p>M1 scope: one shared persistent run per day with an explicit
 * ownership/recovery boundary, plus permission-gated in-game admin
 * onboarding. No procedural geometry, ore placement, mobs, rewards,
 * checkpoint travel, Dispatch storage, or scheduled reset lives here yet.
 */
@Mod(PublicMines.MOD_ID)
public class PublicMines {
    public static final String MOD_ID = "publicmines";
    private static final Logger LOGGER = LogUtils.getLogger();

    public PublicMines(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, PublicMineConfig.SPEC);
        modEventBus.addListener(this::onRegisterGameTests);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        PublicMineCommand.register(event.getDispatcher());
    }

    private void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(MineLifecycleGameTests.class);
    }

    private void onServerStarted(ServerStartedEvent event) {
        MineManager.get(event.getServer()).onServerStarted();
        LOGGER.info("Rustic Craft Public Mines loaded (M1 lifecycle/admin)");
    }
}
