package com.rusticcraft.publicmines;

import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.command.PublicMineCommand;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import com.rusticcraft.publicmines.generation.MineGenerator;
import com.rusticcraft.publicmines.gametest.MineLifecycleGameTests;
import com.rusticcraft.publicmines.generation.PublicMineWorldgen;
import com.rusticcraft.publicmines.run.MineManager;
import com.rusticcraft.publicmines.topology.TopologyProfiles;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Server-safe entry point for the Rustic Craft public-mine mod.
 *
 * <p>Owns mod registration and dispatches lifecycle, generation, and command
 * events to their domain components. Later milestones add ore placement,
 * encounters, rewards, checkpoint travel, Dispatch storage, and reset timing.
 */
@Mod(PublicMines.MOD_ID)
public class PublicMines {
    public static final String MOD_ID = "publicmines";
    private static final Logger LOGGER = LogUtils.getLogger();

    public PublicMines(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, PublicMineConfig.SPEC);
        PublicMineWorldgen.register(modEventBus);
        modEventBus.addListener(this::onRegisterGameTests);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        PublicMineCommand.register(event.getDispatcher());
    }

    private void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(MineLifecycleGameTests.class);
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new TopologyProfiles());
    }

    private void onServerStarted(ServerStartedEvent event) {
        MineManager.get(event.getServer()).onServerStarted();
        MineGenerator.get(event.getServer()).onServerStarted();
        LOGGER.info("Rustic Craft Public Mines loaded (M2b procedural generation)");
    }

    private void onServerTick(ServerTickEvent.Post event) {
        MineGenerator.get(event.getServer()).onServerTick(event);
    }
}
