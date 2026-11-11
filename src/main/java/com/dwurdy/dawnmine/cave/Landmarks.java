package com.dwurdy.dawnmine.cave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Landmarks (points of interest) for a planned cave stack, a pure function of (stack, run seed).
 *
 * <p>Footprint contract (what M11-8's authored overrides must also honour): every landmark is a
 * side chamber cut into solid rock next to an open cave column (its anchor). A 3-wide, 4-high
 * doorway of 3 blocks leads from the anchor to a rectangular chamber whose floor is level with the
 * anchor's floor, so entering never needs a climb. The whole footprint plus a one-block rock shell
 * must be solid rock: landmarks never touch the hub, other caves, tunnels, ore-free volumes or one
 * another, and never change what is walkable outside themselves, so routes cannot be blocked.
 * Each landmark lists hooks (encounter, loot) that later systems fill in, and carries an
 * {@code authoredOverride} id that stays empty until a builder supplies a structure.
 * The fossil is the one exception: a decoration set into the cave wall with no chamber.
 */
public final class Landmarks {
    public enum Type {
        SPAWNER_DUNGEON("spawner_dungeon", 3, 7, 7, 4),
        GEODE("geode", 2, 7, 7, 5),
        FOSSIL("fossil", 3, 0, 0, 0),
        BURIED_RUIN("buried_ruin", 2, 9, 7, 5),
        FLOODED_POOL("flooded_pool", 2, 9, 9, 4),
        MINESHAFT_STUB("mineshaft_stub", 3, 3, 13, 4),
        COLLAPSED_SHAFT("collapsed_shaft", 2, 3, 9, 4),
        FOREMANS_OFFICE("foremans_office", 1, 7, 5, 4),
        MINER_CAMP("miner_camp", 2, 7, 7, 4);

        public final String id;
        public final int weight;
        final int width;
        final int depth;
        final int height;

        Type(String id, int weight, int width, int depth, int height) {
            this.id = id;
            this.weight = weight;
            this.width = width;
            this.depth = depth;
            this.height = height;
        }
    }

    /** A place later systems fill: kind is "encounter" or "loot". */
    public record Hook(String kind, int x, int y, int z) { }

    /** One placed landmark. {@code safe} marks the Foreman's Office safe room; {@code authoredOverride} stays null until M11-8. */
    public record Placement(Type type, int layer, int x, int y, int z, int minX, int minZ, int maxX, int maxZ,
                            List<Hook> hooks, boolean safe, String authoredOverride) { }

    public static final int CELL = 48;
    public static final double CELL_CHANCE = 0.9;
    private static final int DOOR = 3;
    private static final int DOOR_HEIGHT = 4;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final List<Placement> placements = new ArrayList<>();
    private final List<String> palette = new ArrayList<>(List.of("minecraft:air"));
    private final Map<Long, int[]> columns = new HashMap<>();

    public List<Placement> placements() {
        return placements;
    }

    /** Block ids (with optional state) indexed by the values in {@link #columns()}; index 0 is air. */
    public List<String> palette() {
        return palette;
    }

    /** Column key (see {@link CaveStack#key}) to {y, paletteIndex, ...} edits, applied after caves and ore. */
    public Map<Long, int[]> columns() {
        return columns;
    }

    public static Landmarks plan(CaveStack stack, long seed) {
        Landmarks out = new Landmarks();
        Rock rock = new Rock(stack);
        Map<Long, List<Placement>> byCell = new HashMap<>();
        for (int layer = 0; layer < stack.layers(); layer++) {
            CaveSheet sheet = stack.sheet(layer);
            for (int cz = Math.floorDiv(sheet.minZ(), CELL); cz <= Math.floorDiv(sheet.maxZ(), CELL); cz++) {
                for (int cx = Math.floorDiv(sheet.minX(), CELL); cx <= Math.floorDiv(sheet.maxX(), CELL); cx++) {
                    Rng rng = new Rng(seed ^ 0x2545F4914F6CDD1DL ^ (layer * 0x9E3779B97F4A7C15L)
                            ^ (cx * 0xC2B2AE3D27D4EB4FL) ^ (cz * 0x165667B19E3779F9L));
                    if (rng.nextDouble() >= CELL_CHANCE) {
                        continue;
                    }
                    Type type = pickType(rng);
                    for (int attempt = 0; attempt < 300; attempt++) {
                        int x = cx * CELL + rng.nextInt(CELL);
                        int z = cz * CELL + rng.nextInt(CELL);
                        if (!sheet.isOpen(x, z) || Rock.protectedColumn(x, z)) {
                            continue;
                        }
                        int[] d = DIRS[rng.nextInt(4)];
                        Build build = out.tryBuild(rock, sheet, rng, type, layer, x, z, d[0], d[1], byCell, cx, cz);
                        if (build != null) {
                            out.commit(build, byCell, cx, cz);
                            break;
                        }
                    }
                }
            }
        }
        out.finish();
        return out;
    }

    private static Type pickType(Rng rng) {
        int total = 0;
        for (Type t : Type.values()) {
            total += t.weight;
        }
        int roll = rng.nextInt(total);
        for (Type t : Type.values()) {
            roll -= t.weight;
            if (roll < 0) {
                return t;
            }
        }
        throw new IllegalStateException("weighted pick escaped");
    }

    // ---------- building one landmark ----------

    private record Edit(int x, int y, int z, String block) { }

    private record Build(Placement placement, List<Edit> edits) { }

    /** Local frame: t runs from the anchor along the doorway, s is sideways, dy is height above the floor block. */
    private static final class Frame {
        final int ox;
        final int oz;
        final int dx;
        final int dz;
        final int floor;
        final List<Edit> edits = new ArrayList<>();
        final List<Hook> hooks = new ArrayList<>();

        Frame(int ox, int oz, int dx, int dz, int floor) {
            this.ox = ox;
            this.oz = oz;
            this.dx = dx;
            this.dz = dz;
            this.floor = floor;
        }

        int wx(int t, int s) {
            return ox + dx * t - dz * s;
        }

        int wz(int t, int s) {
            return oz + dz * t + dx * s;
        }

        void set(int t, int s, int dy, String block) {
            edits.add(new Edit(wx(t, s), floor + dy, wz(t, s), block));
        }
    }

    private Build tryBuild(Rock rock, CaveSheet sheet, Rng rng, Type type, int layer, int x, int z, int dx, int dz,
                           Map<Long, List<Placement>> byCell, int cx, int cz) {
        int floor = sheet.floorAt(x, z);
        if (type == Type.FOSSIL) {
            return buildFossil(rock, rng, layer, x, z, dx, dz, floor, byCell, cx, cz);
        }
        int hw = type.width / 2;
        int lastT = DOOR + type.depth;
        Frame f = new Frame(x, z, dx, dz, floor);
        // the footprint and a one-block rock shell must be solid rock (the anchor row t = 0 is the cave itself)
        for (int t = 1; t <= lastT + 1; t++) {
            int half = (t <= DOOR ? 1 : hw) + 1;
            for (int s = -half; s <= half; s++) {
                for (int y = floor - 1; y <= floor + type.height + 1; y++) {
                    if (!rock.solid(f.wx(t, s), y, f.wz(t, s))) {
                        return null;
                    }
                }
            }
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] corner : new int[][] {{0, -hw - 1}, {0, hw + 1}, {lastT + 1, -hw - 1}, {lastT + 1, hw + 1}}) {
            minX = Math.min(minX, f.wx(corner[0], corner[1]));
            maxX = Math.max(maxX, f.wx(corner[0], corner[1]));
            minZ = Math.min(minZ, f.wz(corner[0], corner[1]));
            maxZ = Math.max(maxZ, f.wz(corner[0], corner[1]));
        }
        if (overlaps(byCell, layer, cx, cz, minX, minZ, maxX, maxZ)) {
            return null;
        }
        // doorway and chamber air
        for (int t = 1; t <= DOOR; t++) {
            for (int s = -1; s <= 1; s++) {
                for (int dy = 1; dy <= DOOR_HEIGHT; dy++) {
                    f.set(t, s, dy, "minecraft:air");
                }
            }
        }
        for (int t = DOOR + 1; t <= lastT; t++) {
            for (int s = -hw; s <= hw; s++) {
                for (int dy = 1; dy <= type.height; dy++) {
                    f.set(t, s, dy, "minecraft:air");
                }
            }
        }
        decorate(type, f, rng, hw, lastT);
        Placement p = new Placement(type, layer, x, floor + 1, z, minX, minZ, maxX, maxZ,
                List.copyOf(f.hooks), type == Type.FOREMANS_OFFICE, null);
        return new Build(p, f.edits);
    }

    private static void decorate(Type type, Frame f, Rng rng, int hw, int lastT) {
        int h = type.height;
        int midT = DOOR + 1 + type.depth / 2;
        switch (type) {
            case SPAWNER_DUNGEON -> {
                for (int t = DOOR + 1; t <= lastT; t++) {
                    for (int s = -hw; s <= hw; s++) {
                        f.set(t, s, 0, rng.nextInt(2) == 0 ? "minecraft:mossy_cobblestone" : "minecraft:cobblestone");
                    }
                }
                f.hooks.add(new Hook("encounter", f.wx(midT, 0), f.floor + 1, f.wz(midT, 0)));
            }
            case GEODE -> {
                for (int t = DOOR + 1; t <= lastT; t++) {
                    for (int s = -hw; s <= hw; s++) {
                        f.set(t, s, 0, "minecraft:calcite");
                        f.set(t, s, h + 1, rng.nextInt(6) == 0 ? "minecraft:budding_amethyst" : "minecraft:amethyst_block");
                    }
                    for (int dy = 1; dy <= h; dy++) {
                        f.set(t, -hw - 1, dy, geodeWall(rng));
                        f.set(t, hw + 1, dy, geodeWall(rng));
                    }
                }
                for (int s = -hw; s <= hw; s++) {
                    for (int dy = 1; dy <= h; dy++) {
                        f.set(lastT + 1, s, dy, geodeWall(rng));
                    }
                }
                f.hooks.add(new Hook("loot", f.wx(lastT, 0), f.floor + 1, f.wz(lastT, 0)));
            }
            case BURIED_RUIN -> {
                for (int t = DOOR + 1; t <= lastT; t++) {
                    for (int s = -hw; s <= hw; s++) {
                        int r = rng.nextInt(3);
                        f.set(t, s, 0, r == 0 ? "minecraft:cracked_stone_bricks"
                                : r == 1 ? "minecraft:mossy_stone_bricks" : "minecraft:stone_bricks");
                    }
                }
                for (int dy = 1; dy <= h; dy++) {
                    for (int t : new int[] {DOOR + 1, lastT}) {
                        f.set(t, -hw, dy, "minecraft:stone_bricks");
                        f.set(t, hw, dy, "minecraft:stone_bricks");
                    }
                }
                f.set(midT, 0, 0, "minecraft:chiseled_stone_bricks");
                f.hooks.add(new Hook("loot", f.wx(midT, 0), f.floor + 1, f.wz(midT, 0)));
            }
            case FLOODED_POOL -> {
                for (int t = midT - 1; t <= midT + 1; t++) {
                    for (int s = -1; s <= 1; s++) {
                        f.set(t, s, 0, "minecraft:water");
                    }
                }
            }
            case MINESHAFT_STUB, COLLAPSED_SHAFT -> {
                for (int t = 1; t <= lastT; t++) {
                    f.set(t, 0, 1, "minecraft:rail");
                }
                for (int t = DOOR + 2; t <= lastT; t += 4) {
                    for (int s = -hw; s <= hw; s++) {
                        f.set(t, s, h, "minecraft:spruce_planks");
                    }
                    f.set(t, -hw, 1, "minecraft:spruce_fence");
                    f.set(t, hw, 1, "minecraft:spruce_fence");
                    f.set(t, -hw, 2, "minecraft:spruce_fence");
                    f.set(t, hw, 2, "minecraft:spruce_fence");
                }
                if (type == Type.COLLAPSED_SHAFT) {
                    for (int t = lastT - 2; t <= lastT; t++) {
                        for (int s = -hw; s <= hw; s++) {
                            for (int dy = 1; dy <= 3; dy++) {
                                f.set(t, s, dy, rng.nextInt(3) == 0 ? "minecraft:cobblestone" : "minecraft:gravel");
                            }
                        }
                    }
                } else {
                    f.hooks.add(new Hook("loot", f.wx(lastT, 0), f.floor + 1, f.wz(lastT, 0)));
                }
            }
            case FOREMANS_OFFICE -> {
                for (int t = DOOR + 1; t <= lastT; t++) {
                    for (int s = -hw; s <= hw; s++) {
                        f.set(t, s, 0, "minecraft:oak_planks");
                    }
                }
                f.set(DOOR + 1, -hw, 1, "minecraft:crafting_table");
                f.set(DOOR + 1, hw, 1, "minecraft:furnace");
                f.set(lastT, -hw, 1, "minecraft:barrel");
                f.set(lastT, hw, 1, "minecraft:barrel");
                f.set(DOOR + 1, 0, h, "minecraft:lantern[hanging=true]");
                f.set(lastT, 0, h, "minecraft:lantern[hanging=true]");
            }
            case MINER_CAMP -> {
                f.set(midT, 0, 1, "minecraft:campfire");
                f.set(DOOR + 1, -hw, 1, "minecraft:hay_block");
                f.set(DOOR + 1, -hw + 1, 1, "minecraft:hay_block");
                f.set(lastT, hw, 1, "minecraft:barrel");
                f.set(lastT, hw - 1, 1, "minecraft:barrel");
                f.set(midT, -hw, h, "minecraft:lantern[hanging=true]");
                f.set(midT, hw, h, "minecraft:lantern[hanging=true]");
                f.hooks.add(new Hook("loot", f.wx(lastT, hw), f.floor + 1, f.wz(lastT, hw)));
            }
            default -> throw new IllegalStateException("no decoration for " + type);
        }
    }

    private static String geodeWall(Rng rng) {
        return rng.nextInt(6) == 0 ? "minecraft:budding_amethyst" : "minecraft:amethyst_block";
    }

    /** Bones set into the cave wall: a spine along the wall with ribs, no chamber. */
    private Build buildFossil(Rock rock, Rng rng, int layer, int x, int z, int dx, int dz, int floor,
                              Map<Long, List<Placement>> byCell, int cx, int cz) {
        Frame f = new Frame(x, z, dx, dz, floor);
        for (int s = -5; s <= 5; s++) {
            for (int t = 1; t <= 2; t++) {
                for (int dy = 1; dy <= 3; dy++) {
                    if (!rock.solid(f.wx(t, s), floor + dy, f.wz(t, s))) {
                        return null;
                    }
                }
            }
        }
        int minX = Math.min(f.wx(0, -5), f.wx(2, 5));
        int maxX = Math.max(f.wx(0, -5), f.wx(2, 5));
        int minZ = Math.min(f.wz(0, -5), f.wz(2, 5));
        int maxZ = Math.max(f.wz(0, -5), f.wz(2, 5));
        if (overlaps(byCell, layer, cx, cz, minX, minZ, maxX, maxZ)) {
            return null;
        }
        for (int s = -4; s <= 4; s++) {
            f.set(1, s, 2, "minecraft:bone_block");
        }
        for (int s = -3; s <= 3; s += 2) {
            f.set(1, s, 1, "minecraft:bone_block");
            f.set(1, s, 3, "minecraft:bone_block");
            f.set(2, s, 2, "minecraft:bone_block");
        }
        Placement p = new Placement(Type.FOSSIL, layer, x, floor + 1, z, minX, minZ, maxX, maxZ,
                List.of(), false, null);
        return new Build(p, f.edits);
    }

    // ---------- bookkeeping ----------

    private static boolean overlaps(Map<Long, List<Placement>> byCell, int layer, int cx, int cz,
                                    int minX, int minZ, int maxX, int maxZ) {
        for (int gz = cz - 1; gz <= cz + 1; gz++) {
            for (int gx = cx - 1; gx <= cx + 1; gx++) {
                for (Placement o : byCell.getOrDefault(cellKey(layer, gx, gz), List.of())) {
                    if (minX <= o.maxX() + 2 && maxX >= o.minX() - 2 && minZ <= o.maxZ() + 2 && maxZ >= o.minZ() - 2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static long cellKey(int layer, int gx, int gz) {
        return (((long) layer) << 56) ^ (((long) gx & 0xFFFFFFFL) << 28) ^ ((long) gz & 0xFFFFFFFL);
    }

    private final Map<Long, TreeMap<Integer, Integer>> working = new HashMap<>();

    private void commit(Build build, Map<Long, List<Placement>> byCell, int cx, int cz) {
        placements.add(build.placement());
        byCell.computeIfAbsent(cellKey(build.placement().layer(), cx, cz), k -> new ArrayList<>())
                .add(build.placement());
        for (Edit e : build.edits()) {
            int idx = palette.indexOf(e.block());
            if (idx < 0) {
                idx = palette.size();
                palette.add(e.block());
            }
            working.computeIfAbsent(CaveStack.key(e.x(), e.z()), k -> new TreeMap<>()).put(e.y(), idx);
        }
    }

    private void finish() {
        for (var e : working.entrySet()) {
            int[] flat = new int[e.getValue().size() * 2];
            int i = 0;
            for (var cell : e.getValue().entrySet()) {
                flat[i++] = cell.getKey();
                flat[i++] = cell.getValue();
            }
            columns.put(e.getKey(), flat);
        }
        working.clear();
    }
}
