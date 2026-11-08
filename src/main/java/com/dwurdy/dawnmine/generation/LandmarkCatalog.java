package com.dwurdy.dawnmine.generation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.dwurdy.dawnmine.topology.TopoPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Data-driven registry of fixed landmark definitions under
 * {@code data/<namespace>/landmarks/*.json}. Each definition is a literal
 * per-layer block grid placed unchanged at its anchored node - the same
 * "code reads a hand-authored grid" pattern the expedition hub uses, never
 * an NBT structure template. Parsing is fail-closed per file: invalid
 * definitions are logged and skipped, so a bad file can never silently
 * substitute a different room.
 */
public final class LandmarkCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "landmarks";

    private static volatile Map<String, Definition> definitions = Map.of();

    /** One declared connector cell on the footprint boundary an edge may
     * terminate at, plus the direction it opens toward. */
    public record Connector(int x, int y, int z, String facing) {
        public TopoPos offset() {
            return new TopoPos(x, y, z);
        }
    }

    /** A literal, final landmark room: per-layer single-char slot codes
     * resolved through {@link #slots} to block-state strings. */
    public record Definition(String id, int halfX, int halfZ, int height,
                             int reserveMargin, List<Connector> connectors,
                             Map<Character, String> slots, List<List<String>> layers) {
        public Definition {
            connectors = List.copyOf(connectors);
            slots = Map.copyOf(slots);
            layers = layers.stream().map(List::copyOf).toList();
        }
    }

    public LandmarkCatalog() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, Definition> loaded = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            try {
                Definition definition = parse(entry.getValue().getAsJsonObject());
                if (loaded.putIfAbsent(definition.id(), definition) != null) {
                    throw new JsonParseException("duplicate landmark id '" + definition.id() + "'");
                }
            } catch (RuntimeException e) {
                LOGGER.error("[dawnmine] Skipping invalid landmark definition {}: {}",
                        entry.getKey(), e.getMessage());
            }
        }
        definitions = Map.copyOf(loaded);
        LOGGER.info("[dawnmine] Loaded {} landmark definition(s): {}", definitions.size(),
                definitions.isEmpty() ? "none" : String.join(", ", definitions.keySet()));
    }

    /** The live definition set for the current reload; empty before the
     * first resource load. */
    public static Map<String, Definition> definitions() {
        return definitions;
    }

    /** A canonical hash of every loaded definition: placed grids are
     * persisted geometry, so a catalog edit between restarts must
     * invalidate the run's saved generation plan rather than silently
     * rebuild a different room mid-run. */
    public static String fingerprint() {
        StringBuilder canonical = new StringBuilder();
        definitions.values().stream()
                .sorted(java.util.Comparator.comparing(Definition::id))
                .forEach(def -> canonical.append(def.id()).append('|')
                        .append(def.halfX()).append(',').append(def.halfZ()).append(',')
                        .append(def.height()).append(',').append(def.reserveMargin()).append('|')
                        .append(def.connectors().stream()
                                .map(c -> c.x() + "," + c.y() + "," + c.z() + ":" + c.facing())
                                .collect(java.util.stream.Collectors.joining(";")))
                        .append('|')
                        .append(def.slots().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .collect(java.util.stream.Collectors.joining(";")))
                        .append('|')
                        .append(def.layers().stream()
                                .map(layer -> String.join("/", layer))
                                .collect(java.util.stream.Collectors.joining("~")))
                        .append('\n'));
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static Definition definition(String id) {
        return definitions.get(id);
    }

    /** Test seam: swap the loaded set without a resource reload. */
    static void installForTest(Map<String, Definition> loaded) {
        definitions = Map.copyOf(loaded);
    }

    static Definition parse(JsonObject json) {
        String id = requiredString(json, "id");
        if (!id.matches("[a-z0-9_-]+")) {
            throw new JsonParseException("landmark id '" + id + "' must match [a-z0-9_-]+");
        }
        JsonObject footprint = requiredObject(json, "footprint");
        int halfX = integer(footprint, "halfX", 1, 16);
        int halfZ = integer(footprint, "halfZ", 1, 16);
        int height = integer(footprint, "height", 1, 32);
        int reserveMargin = integer(footprint, "reserveMargin", 0, 8);

        JsonArray connectorArray = requiredArray(json, "connectors");
        if (connectorArray.isEmpty() || connectorArray.size() > 8) {
            throw new JsonParseException("landmark '" + id + "' must declare 1-8 connectors");
        }
        List<Connector> connectors = new ArrayList<>();
        for (JsonElement element : connectorArray) {
            JsonObject c = element.getAsJsonObject();
            int cx = integer(c, "x", -halfX, halfX);
            int cy = integer(c, "y", 0, height - 1);
            int cz = integer(c, "z", -halfZ, halfZ);
            String facing = requiredString(c, "facing");
            if (!facing.matches("north|south|east|west")) {
                throw new JsonParseException("landmark '" + id
                        + "' connector has invalid facing '" + facing + "'");
            }
            if (Math.abs(cx) != halfX && Math.abs(cz) != halfZ) {
                throw new JsonParseException("landmark '" + id
                        + "' connector (" + cx + "," + cy + "," + cz
                        + ") is not on the footprint boundary");
            }
            connectors.add(new Connector(cx, cy, cz, facing));
        }

        JsonObject slotsJson = requiredObject(json, "slots");
        Map<Character, String> slots = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> slot : slotsJson.entrySet()) {
            if (slot.getKey().length() != 1) {
                throw new JsonParseException("landmark '" + id
                        + "' slot key '" + slot.getKey() + "' must be a single character");
            }
            String value = slot.getValue().getAsString().trim();
            String blockId = value.contains("[") ? value.substring(0, value.indexOf('[')) : value;
            if (ResourceLocation.tryParse(blockId) == null
                    || (value.contains("[") && !value.endsWith("]"))) {
                throw new JsonParseException("landmark '" + id
                        + "' slot '" + slot.getKey() + "' has invalid block state '" + value + "'");
            }
            slots.put(slot.getKey().charAt(0), value);
        }

        JsonArray layerArray = requiredArray(json, "layers");
        if (layerArray.size() != height) {
            throw new JsonParseException("landmark '" + id + "' declares " + layerArray.size()
                    + " layers but footprint height is " + height);
        }
        List<List<String>> layers = new ArrayList<>();
        int expectedRows = halfZ * 2 + 1;
        int expectedCols = halfX * 2 + 1;
        for (JsonElement layerElement : layerArray) {
            JsonArray rowArray = layerElement.getAsJsonArray();
            if (rowArray.size() != expectedRows) {
                throw new JsonParseException("landmark '" + id + "' layer has " + rowArray.size()
                        + " rows, expected " + expectedRows);
            }
            List<String> rows = new ArrayList<>();
            for (JsonElement rowElement : rowArray) {
                String row = rowElement.getAsString();
                if (row.length() != expectedCols) {
                    throw new JsonParseException("landmark '" + id + "' row '" + row
                            + "' is " + row.length() + " columns, expected " + expectedCols);
                }
                for (int i = 0; i < row.length(); i++) {
                    if (!slots.containsKey(row.charAt(i))) {
                        throw new JsonParseException("landmark '" + id + "' row '" + row
                                + "' uses undeclared slot '" + row.charAt(i) + "'");
                    }
                }
                rows.add(row);
            }
            layers.add(rows);
        }
        return new Definition(id, halfX, halfZ, height, reserveMargin,
                connectors, slots, layers);
    }

    private static String requiredString(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isString()) {
            throw new JsonParseException("missing or invalid string '" + key + "'");
        }
        String value = json.get(key).getAsString().trim();
        if (value.isEmpty() || value.length() > 256) {
            throw new JsonParseException("empty or overlong string '" + key + "'");
        }
        return value;
    }

    private static JsonObject requiredObject(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonObject()) {
            throw new JsonParseException("missing or invalid object '" + key + "'");
        }
        return json.getAsJsonObject(key);
    }

    private static JsonArray requiredArray(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            throw new JsonParseException("missing or invalid array '" + key + "'");
        }
        return json.getAsJsonArray(key);
    }

    private static int integer(JsonObject json, String key, int min, int max) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new JsonParseException("missing or invalid integer '" + key + "'");
        }
        int value = json.get(key).getAsInt();
        if (value < min || value > max) {
            throw new JsonParseException("integer '" + key + "' = " + value
                    + " outside [" + min + ", " + max + "]");
        }
        return value;
    }
}
