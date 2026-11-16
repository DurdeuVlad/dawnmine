package com.dwurdy.dawnmine.generation;

import com.dwurdy.dawnmine.cave.CavePlanner;
import com.dwurdy.dawnmine.cave.CaveSheet;
import com.dwurdy.dawnmine.cave.CaveStack;
import com.dwurdy.dawnmine.cave.Landmarks;
import com.dwurdy.dawnmine.cave.OreField;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Applies the run's planned cave stack (three layers joined by descent tunnels) to terrain.
 * The stack is a pure function of the run seed and the shell size, so chunk generation,
 * restarts and the daily reset all see the same caves with nothing to record: the reset's
 * "restore to baseline" step simply regenerates them from the new run's seed.
 */
public final class CaveTerrain {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState COBBLESTONE = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState MOSSY_COBBLESTONE = Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    private static final BlockState ANDESITE = Blocks.ANDESITE.defaultBlockState();
    private static final BlockState GRANITE = Blocks.GRANITE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState BASALT = Blocks.BASALT.defaultBlockState();
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** Least-recently-used cache; each value (including a failure) is shared by every waiter. */
    private static final java.util.LinkedHashMap<String, CompletableFuture<CaveStack>> CACHE =
            new java.util.LinkedHashMap<>(8, 0.75f, true);
    private static final int CACHE_SIZE = 4;

    private CaveTerrain() { }

    /** The ore field for a stack with the block of each ore resolved once. */
    private record Ores(OreField field, BlockState[] states, String fingerprint) { }

    /** The landmark plan for a stack with every palette entry resolved once. */
    private record Marks(Landmarks plan, BlockState[] states, com.dwurdy.dawnmine.cave.EncounterSites sites,
                         com.dwurdy.dawnmine.cave.LootSites loot) { }

    private static final java.util.IdentityHashMap<CaveStack, CompletableFuture<Marks>> MARK_PLANS =
            new java.util.IdentityHashMap<>();
    private static volatile CaveStack lastMarkStack;
    private static volatile Marks lastMarks;

    /** The landmarks of a stack: planned once per stack outside any lock; the current and previous run are held. */
    private static Marks marks(CaveStack stack, long seed) {
        Marks fast = lastMarks;
        if (fast != null && lastMarkStack == stack) {
            return fast;
        }
        CompletableFuture<Marks> future;
        boolean planner = false;
        synchronized (MARK_PLANS) {
            future = MARK_PLANS.get(stack);
            if (future == null) {
                future = new CompletableFuture<>();
                if (MARK_PLANS.size() >= 2) {
                    MARK_PLANS.clear();
                }
                MARK_PLANS.put(stack, future);
                planner = true;
            }
        }
        if (planner) {
            try {
                Landmarks plan = Landmarks.plan(stack, seed);
                BlockState[] states = new BlockState[plan.palette().size()];
                for (int i = 0; i < states.length; i++) {
                    states[i] = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                            plan.palette().get(i), false).blockState();
                }
                future.complete(new Marks(plan, states, com.dwurdy.dawnmine.cave.EncounterSites.plan(stack, plan, seed),
                        com.dwurdy.dawnmine.cave.LootSites.plan(stack, plan, seed)));
            } catch (Throwable t) {
                LOGGER.error("[dawnmine] landmark planning failed for seed {}", seed, t);
                future.completeExceptionally(t);
            }
        }
        Marks made = future.join();
        lastMarks = made;
        lastMarkStack = stack;
        return made;
    }

    /** Encounter sites of the run, or null while its stack and landmarks are still being planned (never blocks). */
    public static com.dwurdy.dawnmine.cave.EncounterSites sitesIfReady(long seed, int shellRadiusChunks) {
        CompletableFuture<CaveStack> stackFuture;
        synchronized (CACHE) {
            stackFuture = CACHE.get(seed + ":" + shellRadiusChunks);
        }
        if (stackFuture == null || !stackFuture.isDone() || stackFuture.isCompletedExceptionally()) {
            return null;
        }
        CompletableFuture<Marks> marksFuture;
        synchronized (MARK_PLANS) {
            marksFuture = MARK_PLANS.get(stackFuture.join());
        }
        if (marksFuture == null || !marksFuture.isDone() || marksFuture.isCompletedExceptionally()) {
            return null;
        }
        return marksFuture.join().sites();
    }

    /** Loot sites of the run, or null while its stack and landmarks are still being planned. */
    public static com.dwurdy.dawnmine.cave.LootSites lootSitesIfReady(long seed, int shellRadiusChunks) {
        CompletableFuture<CaveStack> stackFuture;
        synchronized (CACHE) {
            stackFuture = CACHE.get(seed + ":" + shellRadiusChunks);
        }
        if (stackFuture == null || !stackFuture.isDone() || stackFuture.isCompletedExceptionally()) {
            return null;
        }
        CompletableFuture<Marks> marksFuture;
        synchronized (MARK_PLANS) {
            marksFuture = MARK_PLANS.get(stackFuture.join());
        }
        if (marksFuture == null || !marksFuture.isDone() || marksFuture.isCompletedExceptionally()) {
            return null;
        }
        return marksFuture.join().loot();
    }

    /** Encounter sites of a stack (blocking: plans on first use), for the census. */
    public static com.dwurdy.dawnmine.cave.EncounterSites encounterSites(CaveStack stack, long seed) {
        return marks(stack, seed).sites();
    }

    /** Loot sites of a stack (blocking: plans on first use), for the census. */
    public static com.dwurdy.dawnmine.cave.LootSites lootSites(CaveStack stack, long seed) {
        return marks(stack, seed).loot();
    }

    /** Placed landmarks of a stack, for the census and later systems. */
    public static Landmarks landmarks(CaveStack stack, long seed) {
        return marks(stack, seed).plan();
    }

    private static final java.util.IdentityHashMap<CaveStack, Ores> ORES = new java.util.IdentityHashMap<>();

    /** Ore veins for a stack, built from the active catalogue (layer i uses ore tier i). */
    private static Ores ores(CaveStack stack, long seed) {
        OreCatalog.OreCatalogSnapshot catalog = OreCatalog.active();
        synchronized (ORES) {
            Ores held = ORES.get(stack);
            if (held != null) {
                return held; // pinned for the stack lifetime: a catalogue reload never changes a run's ore
            }
            List<List<OreField.Ore>> tables = new ArrayList<>();
            OreCatalog.Tier[] tiers = OreCatalog.Tier.values();
            // M14a: one real catalog tier per cave layer (upper/middle/chaos/nether) - no more
            // reusing the deepest tier's table for a layer beyond the catalog's own tiers.
            if (tiers.length != CavePlanner.LAYERS) {
                throw new IllegalStateException("OreCatalog.Tier has " + tiers.length
                        + " values but the cave stack has " + CavePlanner.LAYERS + " layers");
            }
            double[] veinsPerCell = new double[CavePlanner.LAYERS];
            for (int layer = 0; layer < CavePlanner.LAYERS; layer++) {
                OreCatalog.Tier tier = tiers[layer];
                List<OreField.Ore> table = new ArrayList<>();
                for (OreCatalog.Entry e : catalog.resourceEntries(tier)) {
                    String blockId = e.blockId().toString();
                    OreRarity.Range size = OreRarity.sizeFor(blockId);
                    int weight = e.weight();
                    int sizeMin = size.min();
                    int sizeMax = size.max();
                    // M14b: an admin's per-block override replaces weight and/or vein size,
                    // leaving whichever half wasn't set at the shipped value.
                    var override = com.dwurdy.dawnmine.config.DawnmineOverrides.ore(tier.id(), blockId);
                    if (override.isPresent()) {
                        var o = override.get();
                        if (o.weight() != null) weight = o.weight();
                        if (o.veinMin() != null) sizeMin = o.veinMin();
                        if (o.veinMax() != null) sizeMax = o.veinMax();
                    }
                    table.add(new OreField.Ore(blockId, weight, sizeMin, sizeMax));
                }
                tables.add(table);
                veinsPerCell[layer] = com.dwurdy.dawnmine.config.DawnmineOverrides.density(tier.id())
                        .orElse(OreField.VEINS_PER_CELL[Math.min(layer, OreField.VEINS_PER_CELL.length - 1)]);
            }
            OreField field = new OreField(stack, seed, tables, veinsPerCell);
            int n = field.oreCount();
            BlockState[] states = new BlockState[n];
            for (int i = 0; i < n; i++) {
                states[i] = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .get(net.minecraft.resources.ResourceLocation.parse(field.ore(i).id())).defaultBlockState();
            }
            Ores made = new Ores(field, states, catalog.fingerprint());
            if (!catalog.entries().isEmpty()) { // never pin the empty catalogue seen before data loads
                if (ORES.size() >= 2) {
                    ORES.clear(); // each held field pins its stack: keep the current and previous run only
                }
                ORES.put(stack, made);
            }
            return made;
        }
    }

    /** The cave stack for a run seed inside a shell of the given radius (cached; planning takes seconds). */
    public static CaveStack stack(long seed, int shellRadiusChunks) {
        String key = seed + ":" + shellRadiusChunks;
        CompletableFuture<CaveStack> future;
        boolean planner = false;
        synchronized (CACHE) {
            future = CACHE.get(key);
            if (future == null) {
                future = new CompletableFuture<>();
                CACHE.put(key, future);
                planner = true;
                while (CACHE.size() > CACHE_SIZE) {
                    CACHE.remove(CACHE.keySet().iterator().next()); // least recently used
                }
            }
        }
        if (planner) {
            try {
                future.complete(CaveStack.plan(shellRadiusChunks, seed));
            } catch (Throwable t) {
                // keep the failed future cached: waiters fail at once instead of each re-planning
                LOGGER.error("[dawnmine] cave stack planning failed for seed {} shell {}", seed,
                        shellRadiusChunks, t);
                future.completeExceptionally(t);
            }
        }
        return future.join();
    }

    /** Plans the stack on a background thread so the first chunk or reset step does not wait for it. */
    public static void prepareAsync(long seed, int shellRadiusChunks) {
        CompletableFuture.runAsync(() -> marks(stack(seed, shellRadiusChunks), seed))
                .exceptionally(t -> {
                    LOGGER.error("[dawnmine] background cave planning failed", t);
                    return null;
                });
    }

    /** Rock variety for the floor and ceiling surfaces a player actually sees: layer 0 mixes in
     * cobblestone, a mossy ("moist") variant, andesite, granite and the odd dirt patch instead of
     * plain stone throughout; layer 3 (the nether layer) is netherrack with basalt patches instead
     * of overworld stone. Null means "leave the plain rock as-is". Deterministic per column so a
     * reset regenerates the same look. */
    private static BlockState wallPaletteState(int layer, long seed, int x, int z) {
        long h = mix(seed ^ (layer * 0x9E3779B97F4A7C15L) ^ ((long) x * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) z * 0x165667B19E3779F9L));
        int r = (int) (Long.remainderUnsigned(h, 100));
        if (layer == 0) {
            if (r < 45) return null; // plain stone majority
            if (r < 65) return COBBLESTONE;
            if (r < 75) return MOSSY_COBBLESTONE;
            if (r < 85) return ANDESITE;
            if (r < 93) return GRANITE;
            return DIRT;
        }
        if (layer == 3) {
            return r < 75 ? NETHERRACK : BASALT;
        }
        return null;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A real stair block for one tread of a staircase descent, facing the direction of travel. */
    private static BlockState stairState(CaveStack.StairTread tread) {
        Direction facing = tread.dx() > 0 ? Direction.EAST : tread.dx() < 0 ? Direction.WEST
                : tread.dz() > 0 ? Direction.SOUTH : Direction.NORTH;
        return Blocks.COBBLESTONE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, facing)
                .setValue(StairBlock.HALF, Half.BOTTOM);
    }

    /** Opens the planned cave cells inside one freshly filled chunk. */
    public static void carve(ChunkAccess chunk, CaveStack stack, long seed) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = minX + dx;
                int z = minZ + dz;
                for (int layer = 0; layer < stack.layers(); layer++) {
                    CaveSheet sheet = stack.sheet(layer);
                    if (!sheet.isOpen(x, z)) {
                        continue;
                    }
                    int floor = sheet.floorAt(x, z);
                    int top = floor + sheet.heightAt(x, z);
                    for (int y = floor + 1; y <= top; y++) {
                        chunk.setBlockState(pos.set(x, y, z), AIR, false);
                    }
                    BlockState wallPalette = wallPaletteState(layer, seed, x, z);
                    if (wallPalette != null) {
                        chunk.setBlockState(pos.set(x, floor, z), wallPalette, false);
                        chunk.setBlockState(pos.set(x, top + 1, z), wallPalette, false);
                    }
                }
                int[] spans = stack.tubeSpans(x, z);
                if (spans != null) {
                    for (int i = 0; i < spans.length; i += 2) {
                        for (int y = spans[i]; y <= spans[i + 1]; y++) {
                            chunk.setBlockState(pos.set(x, y, z), AIR, false);
                        }
                    }
                }
                Integer dropperFloor = stack.dropperLandingFloor(x, z);
                if (dropperFloor != null) {
                    chunk.setBlockState(pos.set(x, dropperFloor + 1, z), WATER, false);
                    chunk.setBlockState(pos.set(x, dropperFloor + 2, z), WATER, false);
                }
                for (CaveStack.StairTread tread : stack.stairTreadsAt(x, z)) {
                    chunk.setBlockState(pos.set(x, tread.floor(), z), stairState(tread), false);
                }
            }
        }
        Ores ores = ores(stack, seed);
        for (var column : ores.field().forChunk(chunk.getPos().x, chunk.getPos().z).byColumn().entrySet()) {
            int x = (int) (column.getKey() >> 32);
            int z = (int) (long) column.getKey();
            int[] ys = column.getValue();
            for (int i = 0; i < ys.length; i += 2) {
                chunk.setBlockState(pos.set(x, ys[i], z), ores.states()[ys[i + 1]], false);
            }
        }
        Marks marks = marks(stack, seed);
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = minX + dx;
                int z = minZ + dz;
                int[] edits = marks.plan().columns().get(CaveStack.key(x, z));
                if (edits != null) {
                    for (int i = 0; i < edits.length; i += 2) {
                        chunk.setBlockState(pos.set(x, edits[i], z), marks.states()[edits[i + 1]], false);
                    }
                }
                for (int layer = 0; layer < stack.layers(); layer++) {
                    com.dwurdy.dawnmine.cave.LootSites.Site loot = marks.loot().atColumn(layer, x, z);
                    if (loot != null) {
                        placeLootContainer(chunk, pos.set(x, loot.y(), z), loot);
                    }
                }
            }
        }
    }

    /** M12d: places a static loot container (never a spawned entity) - "crate" is a chest,
     * "locker" is a barrel. The loot table is deliberately *not* attached here: setting it
     * against a block entity reached through a raw {@code ChunkAccess} during generation does
     * not persist (confirmed live - the saved container carried no {@code LootTable} tag at
     * all), the same reason {@code MineGenerator.finishSegment}'s teleporter sign text is set
     * post-generation against a real {@code ServerLevel} rather than here. See
     * {@link com.dwurdy.dawnmine.encounter.MineLootManager#resolveNearby}, which attaches each
     * site's loot table once a player is near enough for the chunk to be genuinely loaded. */
    private static void placeLootContainer(ChunkAccess chunk, BlockPos pos,
            com.dwurdy.dawnmine.cave.LootSites.Site site) {
        BlockState block = "locker".equals(site.kind())
                ? Blocks.BARREL.defaultBlockState() : Blocks.CHEST.defaultBlockState();
        chunk.setBlockState(pos, block, false);
    }

    /** A solid terrain column with the planned cave cells opened (reset baseline). */
    public static NoiseColumn carveColumn(NoiseColumn solid, int x, int z, int minY, int height,
                                          CaveStack stack, long seed) {
        Ores ores = ores(stack, seed);
        int[] oreYs = ores.field().forChunk(x >> 4, z >> 4).byColumn().get(CaveStack.key(x, z));
        Marks marks = marks(stack, seed);
        int[] markYs = marks.plan().columns().get(CaveStack.key(x, z));
        Integer dropperFloor = stack.dropperLandingFloor(x, z);
        List<CaveStack.StairTread> treads = stack.stairTreadsAt(x, z);
        boolean any = stack.tubeSpans(x, z) != null || oreYs != null || markYs != null
                || dropperFloor != null || !treads.isEmpty();
        for (int layer = 0; layer < stack.layers() && !any; layer++) {
            any = stack.sheet(layer).isOpen(x, z);
        }
        if (!any) {
            return solid;
        }
        BlockState[] states = new BlockState[height];
        for (int i = 0; i < height; i++) {
            states[i] = solid.getBlock(minY + i);
        }
        for (int layer = 0; layer < stack.layers(); layer++) {
            CaveSheet sheet = stack.sheet(layer);
            if (sheet.isOpen(x, z)) {
                int floor = sheet.floorAt(x, z);
                int top = floor + sheet.heightAt(x, z);
                open(states, minY, floor + 1, top);
                BlockState wallPalette = wallPaletteState(layer, seed, x, z);
                if (wallPalette != null) {
                    if (floor >= minY && floor < minY + height) {
                        states[floor - minY] = wallPalette;
                    }
                    if (top + 1 >= minY && top + 1 < minY + height) {
                        states[top + 1 - minY] = wallPalette;
                    }
                }
            }
        }
        int[] spans = stack.tubeSpans(x, z);
        if (spans != null) {
            for (int i = 0; i < spans.length; i += 2) {
                open(states, minY, spans[i], spans[i + 1]);
            }
        }
        if (dropperFloor != null) {
            for (int y = dropperFloor + 1; y <= dropperFloor + 2; y++) {
                if (y >= minY && y < minY + height) {
                    states[y - minY] = WATER;
                }
            }
        }
        for (CaveStack.StairTread tread : treads) {
            if (tread.floor() >= minY && tread.floor() < minY + height) {
                states[tread.floor() - minY] = stairState(tread);
            }
        }
        if (oreYs != null) {
            for (int i = 0; i < oreYs.length; i += 2) {
                if (oreYs[i] >= minY && oreYs[i] < minY + height) {
                    states[oreYs[i] - minY] = ores.states()[oreYs[i + 1]];
                }
            }
        }
        if (markYs != null) {
            for (int i = 0; i < markYs.length; i += 2) {
                if (markYs[i] >= minY && markYs[i] < minY + height) {
                    states[markYs[i] - minY] = marks.states()[markYs[i + 1]];
                }
            }
        }
        return new NoiseColumn(minY, states);
    }

    private static void open(BlockState[] states, int minY, int lo, int hi) {
        for (int y = Math.max(minY, lo); y <= Math.min(minY + states.length - 1, hi); y++) {
            states[y - minY] = AIR;
        }
    }
}
