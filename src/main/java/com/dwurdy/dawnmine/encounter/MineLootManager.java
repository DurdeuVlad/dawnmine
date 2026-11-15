package com.dwurdy.dawnmine.encounter;

import com.dwurdy.dawnmine.cave.LootSites;
import com.dwurdy.dawnmine.generation.CaveTerrain;
import com.dwurdy.dawnmine.run.MineManager;
import com.dwurdy.dawnmine.run.MineRunData;
import com.dwurdy.dawnmine.run.RunStatus;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import org.slf4j.Logger;

/**
 * M12d follow-up: attaches each loot container's vanilla loot table once a player is actually
 * near enough for the chunk to be loaded.
 *
 * <p>{@code CaveTerrain.carve} places the bare chest/barrel block correctly during generation,
 * but setting its loot table there does not persist: that call runs against a raw
 * {@code ChunkAccess} before the block entity is ever attached to a real {@code Level}, so the
 * reference is lost once the chunk is actually loaded (confirmed live: a generated container's
 * saved NBT carried no {@code LootTable} tag at all, only once {@code setLootTable} ran against
 * a real, loaded {@code ServerLevel} did it take effect and persist). Every other place in this
 * codebase that touches block-entity content the same way (the hub's teleporter sign text, in
 * {@code MineGenerator.finishSegment}) already does it post-generation against a real level, for
 * the same reason - this mirrors that, using the existing wake-near-player tick pattern instead
 * of a per-site claim schedule since it only ever needs to run once per site, cheaply checked.
 */
public final class MineLootManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RESOLVE_RADIUS = 48;
    private static final ResourceLocation[] CHEST_LOOT_TABLES = {
            ResourceLocation.fromNamespaceAndPath("dawnmine", "chests/upper_crate"),
            ResourceLocation.fromNamespaceAndPath("dawnmine", "chests/middle_crate"),
            ResourceLocation.fromNamespaceAndPath("dawnmine", "chests/chaos_crate"),
    };

    private MineLootManager() { }

    /** Once a second: attaches the loot table of any unresolved site near a player. */
    public static void resolveNearby(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        resolveNearbyNow(server);
    }

    /** The real work, with the tick-rate gate removed so GameTests can call it directly. */
    public static void resolveNearbyNow(MinecraftServer server) {
        ServerLevel mine = server.getLevel(MineManager.MINE_DIMENSION);
        if (mine == null || mine.players().isEmpty()) return;
        MineRunData data = MineManager.get(server).data();
        if (data == null || data.status() != RunStatus.READY || data.seed() == 0) return;
        int shell = com.dwurdy.dawnmine.config.DawnmineConfig.mineRegionRadiusChunks();
        LootSites sites = CaveTerrain.lootSitesIfReady(data.seed(), shell);
        if (sites == null) return;
        for (ServerPlayer player : mine.players()) {
            for (LootSites.Site site : sites.near(player.getBlockX(), player.getBlockZ(), RESOLVE_RADIUS)) {
                resolveOne(mine, data, site, data.seed());
            }
        }
    }

    private static void resolveOne(ServerLevel mine, MineRunData data, LootSites.Site site, long seed) {
        String claim = "lootresolve:" + site.id();
        if (data.hasClaimedEncounterSite(claim)) return;
        BlockPos pos = new BlockPos(site.x(), site.y(), site.z());
        if (!mine.isLoaded(pos)) return;
        if (!(mine.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container)) {
            // The chunk is loaded but the block entity isn't there yet this tick, or the
            // generated block was somehow replaced - either way, stop retrying this site so a
            // missing container can never spin a claim attempt every second forever.
            data.claimEncounterSite(claim);
            return;
        }
        int tier = switch (site.region()) {
            case "middle" -> 1;
            case "chaos" -> 2;
            default -> 0;
        };
        container.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, CHEST_LOOT_TABLES[tier]),
                seed ^ com.dwurdy.dawnmine.cave.CaveStack.key(site.x(), site.z()));
        data.claimEncounterSite(claim);
        LOGGER.debug("[dawnmine] Attached loot table for {} at {}", site.id(), pos);
    }
}
