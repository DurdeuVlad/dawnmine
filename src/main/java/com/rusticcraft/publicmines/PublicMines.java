package com.rusticcraft.publicmines;

import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.command.PublicMineCommand;
import com.rusticcraft.publicmines.command.OreBreakAuditCommand;
import com.rusticcraft.publicmines.command.MineEncounterAuditCommand;
import com.rusticcraft.publicmines.command.MineProgressionAuditCommand;
import com.rusticcraft.publicmines.config.PublicMineConfig;
import com.rusticcraft.publicmines.dispatch.DispatchManager;
import com.rusticcraft.publicmines.dispatch.DispatchGameTests;
import com.rusticcraft.publicmines.generation.MineGenerator;
import com.rusticcraft.publicmines.generation.OreCatalog;
import com.rusticcraft.publicmines.gametest.MineLifecycleGameTests;
import com.rusticcraft.publicmines.gametest.OreBreakGameTests;
import com.rusticcraft.publicmines.generation.PublicMineWorldgen;
import com.rusticcraft.publicmines.encounter.MineEncounterCatalog;
import com.rusticcraft.publicmines.encounter.MineRewardCatalog;
import com.rusticcraft.publicmines.encounter.MineEncounterManager;
import com.rusticcraft.publicmines.progression.ProgressionGraph;
import com.rusticcraft.publicmines.protection.MineProtectionEvents;
import com.rusticcraft.publicmines.run.MineManager;
import com.rusticcraft.publicmines.run.MineProtectionGameTests;
import com.rusticcraft.publicmines.run.MineProgressionGameTests;
import com.rusticcraft.publicmines.run.MineEncounterGameTests;
import com.rusticcraft.publicmines.topology.TopologyProfiles;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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
        MineProtectionEvents.register();
        MineEncounterManager.register();
        modEventBus.addListener(this::onRegisterGameTests);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        PublicMineCommand.register(event.getDispatcher());
        OreBreakAuditCommand.register(event.getDispatcher());
        MineEncounterAuditCommand.register(event.getDispatcher());
        MineProgressionAuditCommand.register(event.getDispatcher());
    }

    private void onRegisterGameTests(RegisterGameTestsEvent event) {
        event.register(MineLifecycleGameTests.class);
        event.register(MineProtectionGameTests.class);
        event.register(MineEncounterGameTests.class);
        event.register(MineProgressionGameTests.class);
        event.register(OreBreakGameTests.class);
        event.register(DispatchGameTests.class);
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new TopologyProfiles());
        event.addListener(new MineEncounterCatalog());
        event.addListener(new MineRewardCatalog());
        event.addListener(OreCatalog.createReloadListener());
        event.addListener(ProgressionGraph.createReloadListener());
    }

    private void onServerStarted(ServerStartedEvent event) {
        MineEncounterManager.validateEntityRegistry();
        MineManager.get(event.getServer()).onServerStarted();
        MineGenerator.get(event.getServer()).onServerStarted();
        LOGGER.info("Rustic Craft Public Mines loaded (server lifecycle, generation, and ore catalog ready)");
    }

    private void onServerTick(ServerTickEvent.Post event) {
        DispatchManager.get(event.getServer()).tick();
        MineGenerator.get(event.getServer()).onServerTick(event);
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DispatchManager.get(player.getServer()).onPlayerLogout(player);
        }
    }
}
