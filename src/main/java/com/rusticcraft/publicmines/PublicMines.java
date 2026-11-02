package com.rusticcraft.publicmines;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

/**
 * Server-safe entry point for the Rustic Craft public-mine mod.
 *
 * <p>M0 scope: the mod must load on a dedicated server beside the Rustic
 * Craft 2 pack without pulling in client-only classes. No gameplay,
 * world generation, entities, checkpoints, storage, or reset logic
 * lives here yet.
 */
@Mod(PublicMines.MOD_ID)
public class PublicMines {
    public static final String MOD_ID = "publicmines";
    private static final Logger LOGGER = LogUtils.getLogger();

    public PublicMines(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
    }

    private void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Rustic Craft Public Mines loaded (server-safe M0 scaffold; no gameplay features active)");
    }
}
