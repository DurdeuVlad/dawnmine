package com.dwurdy.dawnmine.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/**
 * M14b: admin tuning that layers on top of the shipped catalog/data files without ever touching
 * them. The shipped {@code ore_catalog}/{@code encounters}/loot-table JSON stay the untouched,
 * reviewable source of truth; everything an admin sets through {@code /dawnmine config} lives
 * here instead, in its own file under the world's {@code serverconfig} folder (the same place
 * {@link DawnmineConfig}'s TOML lives), so a mod update can never silently clobber it and a
 * "reset" of one entry is just removing it from this store and falling back to the shipped value.
 *
 * <p>Every category here is a plain in-memory map, kept in sync with the on-disk file by
 * {@link #save(MinecraftServer)}/{@link #load(MinecraftServer)}. Nothing here decides when a
 * change takes effect in the world - that is each consuming system's own concern (see the
 * {@code /dawnmine config} command's per-category timing messages).
 */
public final class DawnmineOverrides {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "dawnmine_overrides.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A per-block override: either field may be null (unset, falls back to the shipped value). */
    public record OreOverride(Integer weight, Integer veinMin, Integer veinMax) { }

    /** A per-band difficulty override, as a replacement fractional bonus (not a multiplier on
     * the shipped value, so the effective amount is always exactly what an admin set). */
    public record DifficultyOverride(Double health, Double damage, Double speed) { }

    /** One admin-added loot entry: a plain item, no functions - enough for "add this to the
     * table", not a full loot-function editor. */
    public record LootEntry(String item, int weight, int min, int max) { }

    private static final Map<String, OreOverride> ORE = new ConcurrentHashMap<>();
    private static final Map<String, Double> DENSITY = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> MOB_ADD = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> MOB_REMOVE = new ConcurrentHashMap<>();
    private static final Map<String, DifficultyOverride> DIFFICULTY = new ConcurrentHashMap<>();
    private static final Map<String, List<LootEntry>> LOOT_ADD = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> LOOT_REMOVE = new ConcurrentHashMap<>();

    private DawnmineOverrides() { }

    private static String oreKey(String layer, String blockId) { return layer + ":" + blockId; }

    // ---- ore ----

    public static Optional<OreOverride> ore(String layer, String blockId) {
        return Optional.ofNullable(ORE.get(oreKey(layer, blockId)));
    }

    public static void setOreWeight(String layer, String blockId, int weight) {
        OreOverride prev = ORE.get(oreKey(layer, blockId));
        ORE.put(oreKey(layer, blockId), new OreOverride(weight,
                prev == null ? null : prev.veinMin(), prev == null ? null : prev.veinMax()));
    }

    public static void setOreVeinSize(String layer, String blockId, int min, int max) {
        OreOverride prev = ORE.get(oreKey(layer, blockId));
        ORE.put(oreKey(layer, blockId), new OreOverride(prev == null ? null : prev.weight(), min, max));
    }

    public static void resetOre(String layer, String blockId) {
        ORE.remove(oreKey(layer, blockId));
    }

    public static Map<String, OreOverride> oreOverridesForLayer(String layer) {
        Map<String, OreOverride> out = new LinkedHashMap<>();
        String prefix = layer + ":";
        for (var e : ORE.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }

    // ---- density ----

    public static Optional<Double> density(String layer) {
        return Optional.ofNullable(DENSITY.get(layer));
    }

    public static void setDensity(String layer, double veinsPerCell) {
        DENSITY.put(layer, veinsPerCell);
    }

    public static void resetDensity(String layer) {
        DENSITY.remove(layer);
    }

    // ---- mob roster ----

    public static Set<String> mobAdds(String layer) {
        return MOB_ADD.getOrDefault(layer, Set.of());
    }

    public static Set<String> mobRemovals(String layer) {
        return MOB_REMOVE.getOrDefault(layer, Set.of());
    }

    public static void addMob(String layer, String entityId) {
        // getOrDefault's Set.of() fallback is immutable - only ever mutate a set this store
        // itself created (computeIfAbsent), never the shared empty default.
        Set<String> removed = MOB_REMOVE.get(layer);
        if (removed != null) removed.remove(entityId);
        MOB_ADD.computeIfAbsent(layer, k -> new LinkedHashSet<>()).add(entityId);
    }

    public static void removeMob(String layer, String entityId) {
        Set<String> added = MOB_ADD.get(layer);
        if (added != null) added.remove(entityId);
        MOB_REMOVE.computeIfAbsent(layer, k -> new LinkedHashSet<>()).add(entityId);
    }

    public static void resetMobs(String layer) {
        MOB_ADD.remove(layer);
        MOB_REMOVE.remove(layer);
    }

    // ---- difficulty bands ----

    public static Optional<DifficultyOverride> difficulty(String band) {
        return Optional.ofNullable(DIFFICULTY.get(band));
    }

    public static void setDifficulty(String band, Double health, Double damage, Double speed) {
        DifficultyOverride prev = DIFFICULTY.get(band);
        DIFFICULTY.put(band, new DifficultyOverride(
                health != null ? health : prev == null ? null : prev.health(),
                damage != null ? damage : prev == null ? null : prev.damage(),
                speed != null ? speed : prev == null ? null : prev.speed()));
    }

    public static void resetDifficulty(String band) {
        DIFFICULTY.remove(band);
    }

    // ---- loot ----

    public static List<LootEntry> lootAdds(String layer) {
        return LOOT_ADD.getOrDefault(layer, List.of());
    }

    public static Set<String> lootRemovals(String layer) {
        return LOOT_REMOVE.getOrDefault(layer, Set.of());
    }

    public static void addLoot(String layer, String item, int weight, int min, int max) {
        Set<String> removed = LOOT_REMOVE.get(layer);
        if (removed != null) removed.remove(item);
        LOOT_ADD.computeIfAbsent(layer, k -> new java.util.ArrayList<>()).add(new LootEntry(item, weight, min, max));
    }

    public static void removeLoot(String layer, String item) {
        List<LootEntry> added = LOOT_ADD.get(layer);
        if (added != null) added.removeIf(e -> e.item().equals(item));
        LOOT_REMOVE.computeIfAbsent(layer, k -> new LinkedHashSet<>()).add(item);
    }

    public static void resetLoot(String layer) {
        LOOT_ADD.remove(layer);
        LOOT_REMOVE.remove(layer);
    }

    // ---- whole-store ops ----

    public static boolean isEmpty() {
        return ORE.isEmpty() && DENSITY.isEmpty() && MOB_ADD.isEmpty() && MOB_REMOVE.isEmpty()
                && DIFFICULTY.isEmpty() && LOOT_ADD.isEmpty() && LOOT_REMOVE.isEmpty();
    }

    /** Every override as plain lines, for {@code /dawnmine config show}. */
    public static List<String> describe() {
        List<String> lines = new java.util.ArrayList<>();
        if (isEmpty()) {
            lines.add("  (no overrides set - every value is the shipped default)");
            return lines;
        }
        ORE.forEach((k, v) -> lines.add("  ore " + k + ": weight=" + v.weight()
                + " veinsize=" + v.veinMin() + ".." + v.veinMax()));
        DENSITY.forEach((k, v) -> lines.add("  density " + k + ": " + v));
        MOB_ADD.forEach((k, v) -> v.forEach(id -> lines.add("  mobs " + k + ": +" + id)));
        MOB_REMOVE.forEach((k, v) -> v.forEach(id -> lines.add("  mobs " + k + ": -" + id)));
        DIFFICULTY.forEach((k, v) -> lines.add("  difficulty " + k + ": health=" + v.health()
                + " damage=" + v.damage() + " speed=" + v.speed()));
        LOOT_ADD.forEach((k, v) -> v.forEach(e -> lines.add("  loot " + k + ": +" + e.item()
                + " weight=" + e.weight() + " count=" + e.min() + ".." + e.max())));
        LOOT_REMOVE.forEach((k, v) -> v.forEach(id -> lines.add("  loot " + k + ": -" + id)));
        return lines;
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve(FILE_NAME);
    }

    public static synchronized void load(MinecraftServer server) {
        ORE.clear();
        DENSITY.clear();
        MOB_ADD.clear();
        MOB_REMOVE.clear();
        DIFFICULTY.clear();
        LOOT_ADD.clear();
        LOOT_REMOVE.clear();
        Path path = file(server);
        if (!Files.exists(path)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("ore")) {
                for (var e : root.getAsJsonObject("ore").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    ORE.put(e.getKey(), new OreOverride(
                            o.has("weight") ? o.get("weight").getAsInt() : null,
                            o.has("veinMin") ? o.get("veinMin").getAsInt() : null,
                            o.has("veinMax") ? o.get("veinMax").getAsInt() : null));
                }
            }
            if (root.has("density")) {
                for (var e : root.getAsJsonObject("density").entrySet()) {
                    DENSITY.put(e.getKey(), e.getValue().getAsDouble());
                }
            }
            if (root.has("mobAdd")) {
                for (var e : root.getAsJsonObject("mobAdd").entrySet()) {
                    MOB_ADD.put(e.getKey(), toStringSet(e.getValue().getAsJsonArray()));
                }
            }
            if (root.has("mobRemove")) {
                for (var e : root.getAsJsonObject("mobRemove").entrySet()) {
                    MOB_REMOVE.put(e.getKey(), toStringSet(e.getValue().getAsJsonArray()));
                }
            }
            if (root.has("difficulty")) {
                for (var e : root.getAsJsonObject("difficulty").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    DIFFICULTY.put(e.getKey(), new DifficultyOverride(
                            o.has("health") ? o.get("health").getAsDouble() : null,
                            o.has("damage") ? o.get("damage").getAsDouble() : null,
                            o.has("speed") ? o.get("speed").getAsDouble() : null));
                }
            }
            if (root.has("lootAdd")) {
                for (var e : root.getAsJsonObject("lootAdd").entrySet()) {
                    List<LootEntry> entries = new java.util.ArrayList<>();
                    for (var el : e.getValue().getAsJsonArray()) {
                        JsonObject o = el.getAsJsonObject();
                        entries.add(new LootEntry(o.get("item").getAsString(), o.get("weight").getAsInt(),
                                o.get("min").getAsInt(), o.get("max").getAsInt()));
                    }
                    LOOT_ADD.put(e.getKey(), entries);
                }
            }
            if (root.has("lootRemove")) {
                for (var e : root.getAsJsonObject("lootRemove").entrySet()) {
                    LOOT_REMOVE.put(e.getKey(), toStringSet(e.getValue().getAsJsonArray()));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[dawnmine] could not load overrides from {}: {}", path, e.toString());
        }
    }

    private static Set<String> toStringSet(JsonArray array) {
        Set<String> out = new LinkedHashSet<>();
        for (var e : array) {
            out.add(e.getAsString());
        }
        return out;
    }

    public static synchronized void save(MinecraftServer server) {
        JsonObject root = new JsonObject();
        JsonObject ore = new JsonObject();
        ORE.forEach((k, v) -> {
            JsonObject o = new JsonObject();
            if (v.weight() != null) o.addProperty("weight", v.weight());
            if (v.veinMin() != null) o.addProperty("veinMin", v.veinMin());
            if (v.veinMax() != null) o.addProperty("veinMax", v.veinMax());
            ore.add(k, o);
        });
        root.add("ore", ore);
        JsonObject density = new JsonObject();
        DENSITY.forEach(density::addProperty);
        root.add("density", density);
        root.add("mobAdd", toJson(MOB_ADD));
        root.add("mobRemove", toJson(MOB_REMOVE));
        JsonObject difficulty = new JsonObject();
        DIFFICULTY.forEach((k, v) -> {
            JsonObject o = new JsonObject();
            if (v.health() != null) o.addProperty("health", v.health());
            if (v.damage() != null) o.addProperty("damage", v.damage());
            if (v.speed() != null) o.addProperty("speed", v.speed());
            difficulty.add(k, o);
        });
        root.add("difficulty", difficulty);
        JsonObject lootAdd = new JsonObject();
        LOOT_ADD.forEach((k, v) -> {
            JsonArray arr = new JsonArray();
            for (LootEntry e : v) {
                JsonObject o = new JsonObject();
                o.addProperty("item", e.item());
                o.addProperty("weight", e.weight());
                o.addProperty("min", e.min());
                o.addProperty("max", e.max());
                arr.add(o);
            }
            lootAdd.add(k, arr);
        });
        root.add("lootAdd", lootAdd);
        root.add("lootRemove", toJson(LOOT_REMOVE));
        Path path = file(server);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[dawnmine] could not save overrides to {}: {}", path, e.toString());
        }
    }

    private static JsonObject toJson(Map<String, Set<String>> map) {
        JsonObject out = new JsonObject();
        map.forEach((k, v) -> {
            JsonArray arr = new JsonArray();
            v.forEach(arr::add);
            out.add(k, arr);
        });
        return out;
    }
}
