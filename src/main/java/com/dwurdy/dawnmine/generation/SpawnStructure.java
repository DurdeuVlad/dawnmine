package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.topology.HubLayout;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/**
 * Loads an admin-authored spawn hub saved with a vanilla structure block
 * instead of the code-generated shell in {@link SegmentGeometry#hub}, per
 * the AGENTS.md exception for the spawn/hub area only - the mine itself
 * stays fully procedural.
 */
public final class SpawnStructure {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation SPAWN_HUB_ID =
            ResourceLocation.fromNamespaceAndPath("dawnmine", "spawn_hub");

    /** Anchor corner admins must align their structure-block save region to:
     * the hub's southwest floor corner, one block below the walkable floor
     * so the structure's own bottom slab becomes {@link HubLayout#FLOOR_Y}. */
    private static final BlockPos ANCHOR = new BlockPos(
            -HubLayout.OUTER_HALF, HubLayout.FLOOR_Y, -HubLayout.OUTER_HALF);

    private SpawnStructure() { }

    /** Places the saved {@code dawnmine:spawn_hub} structure if one exists.
     * Returns false (no-op) when none has been saved, so the caller falls
     * back to the procedural hub shell. */
    public static boolean placeIfSaved(ServerLevel mine) {
        var template = mine.getStructureManager().get(SPAWN_HUB_ID).orElse(null);
        if (template == null) {
            return false;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings();
        template.placeInWorld(mine, ANCHOR, ANCHOR, settings, mine.getRandom(), 2);
        LOGGER.info("[dawnmine] Placed admin-authored spawn hub structure '{}' at {}.",
                SPAWN_HUB_ID, ANCHOR);
        return true;
    }
}
