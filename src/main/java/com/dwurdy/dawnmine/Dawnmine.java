package com.dwurdy.dawnmine;

import com.mojang.logging.LogUtils;
import com.dwurdy.dawnmine.command.DawnmineCommand;
import com.dwurdy.dawnmine.command.OreBreakAuditCommand;
import com.dwurdy.dawnmine.command.MineEncounterAuditCommand;
import com.dwurdy.dawnmine.command.MineProgressionAuditCommand;
import com.dwurdy.dawnmine.config.DawnmineConfig;
import com.dwurdy.dawnmine.dispatch.DispatchManager;
import com.dwurdy.dawnmine.dispatch.DispatchGameTests;
import com.dwurdy.dawnmine.generation.MineGenerator;
import com.dwurdy.dawnmine.generation.OreCatalog;
import com.dwurdy.dawnmine.gametest.TeleporterGameTests;
import com.dwurdy.dawnmine.gametest.MineLifecycleGameTests;
import com.dwurdy.dawnmine.gametest.OreBreakGameTests;
import com.dwurdy.dawnmine.generation.DawnmineWorldgen;
import com.dwurdy.dawnmine.encounter.MineEncounterCatalog;
import com.dwurdy.dawnmine.encounter.MineRewardCatalog;
import com.dwurdy.dawnmine.encounter.MineEncounterManager;
import com.dwurdy.dawnmine.progression.ProgressionGraph;
import com.dwurdy.dawnmine.protection.MineProtectionEvents;
import com.dwurdy.dawnmine.run.MineDailyReset;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.MineProtectionGameTests;
import com.dwurdy.dawnmine.run.MineProgressionGameTests;
import com.dwurdy.dawnmine.run.MineEncounterGameTests;
import com.dwurdy.dawnmine.topology.TopologyProfiles;
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
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Server-safe entry point for the Dawnmine mod.
 *
 * <p>Owns mod registration and dispatches lifecycle, generation, and command
 * events to their domain components. Later milestones add ore placement,
 * encounters, rewards, checkpoint travel, Dispatch storage, and reset timing.
 */
@Mod(Dawnmine.MOD_ID)
public class Dawnmine {
    public static final String MOD_ID = "dawnmine";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Dawnmine(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, DawnmineConfig.SPEC);
        DawnmineWorldgen.register(modEventBus);
        MineProtectionEvents.register();
        MineEncounterManager.register();
        com.dwurdy.dawnmine.teleporter.TeleporterManager.register();
        modEventBus.addListener(this::onRegisterGameTests);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        // Securing an open menu's carried stack must run before any listener
        // that force-closes menus on travel; delivery happens once the level
        // swap has actually completed.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,
                (net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent e) -> {
                    if (e.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
                        DispatchManager.get(player.getServer()).secureCarriedForTravel(player);
                    }
                });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player && player.getServer() != null) {
                DispatchManager.get(player.getServer()).deliverTravelEscrow(player);
            }
        });
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        DawnmineCommand.register(event.getDispatcher());
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
        event.register(TeleporterGameTests.class);
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new TopologyProfiles());
        event.addListener(new MineEncounterCatalog());
        event.addListener(new MineRewardCatalog());
        event.addListener(OreCatalog.createReloadListener());
        event.addListener(ProgressionGraph.createReloadListener());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        com.dwurdy.dawnmine.teleporter.TeleporterManager.clearSessionState();
        MineGenerator.get(event.getServer()).onServerStopping();
    }

    private void onServerStarted(ServerStartedEvent event) {
        MineEncounterManager.validateEntityRegistry();
        MineManager.get(event.getServer()).onServerStarted();
        MineDailyReset.get(event.getServer()).onServerStarted();
        MineGenerator.get(event.getServer()).onServerStarted();
        LOGGER.info("Dawnmine loaded (server lifecycle, generation, daily reset, and ore catalog ready)");
    }

    private void onServerTick(ServerTickEvent.Post event) {
        DispatchManager.get(event.getServer()).tick();
        MineDailyReset.get(event.getServer()).onServerTick(event);
        MineGenerator.get(event.getServer()).onServerTick(event);
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MineManager.get(player.getServer()).evacuateUnsafeMineLogin(player);
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DispatchManager.get(player.getServer()).onPlayerLogout(player);
            com.dwurdy.dawnmine.teleporter.TeleporterManager.clearSessionState(player.getUUID());
        }
    }
}
