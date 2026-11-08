package com.dwurdy.dawnmine.generation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Data-driven parametric motif rule sets under
 * {@code data/dawnmine/motifs/}. A motif is not a fixed grid: it declares
 * where it may appear ({@code appliesTo}, {@code regions}), how often
 * ({@code spacing}, {@code chance}), and a list of elements whose counts
 * and positions {@link MotifPlanner} re-rolls per segment seed, so every
 * placed instance differs while staying recognizably the same family.
 *
 * <p>Block references are abstract {@link BlockSlot}s, never concrete
 * block ids; {@code ore_slot} resolves through the active ore catalog at
 * plan time so motifs adapt to whatever ore mods the pack carries.
 */
public final class MotifCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DIRECTORY = "motifs";

    private static volatile Map<String, Motif> motifs = Map.of();

    /** Inclusive integer range used for spacing and element counts. */
    public record Range(int min, int max) {
        public Range {
            if (min < 1 || max < min) {
                throw new IllegalArgumentException("range must satisfy 1 <= min <= max");
            }
        }
    }

    /** Where an element draws its candidate cells from. */
    public enum OffsetRule {
        /** Solid rock cells adjacent to reachable open air (wall band). */
        WALL,
        /** Floor-adjacent open air cells beside the walk column. */
        FLOOR,
        /** The segment's own spine cells (walk-through kinds only). */
        PATH;

        static OffsetRule parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "wall" -> WALL;
                case "floor" -> FLOOR;
                case "path" -> PATH;
                default -> throw new JsonParseException("unknown motif offsetRule: " + value);
            };
        }
    }

    /** The arrangement a rolled element takes inside its offset pool. */
    public enum ElementType {
        /** Vertical stack rising from one floor cell; count = height. */
        PILLAR,
        /** Count cells sampled within the element's radius. */
        SCATTER,
        /** Count cells clustered from one wall start (random walk). */
        VEIN,
        /** Count consecutive spine cells (walk-through decoration only). */
        RAIL_STUB,
        /** Exactly one light cell; count is fixed at one. */
        LAMP;

        static ElementType parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "pillar" -> PILLAR;
                case "scatter" -> SCATTER;
                case "vein" -> VEIN;
                case "rail_stub" -> RAIL_STUB;
                case "lamp" -> LAMP;
                default -> throw new JsonParseException("unknown motif element type: " + value);
            };
        }
    }

    /** Abstract material intent; resolved to a BlockKind or catalog ore. */
    public enum BlockSlot {
        SUPPORT, RUBBLE, PLANKS, FENCE, RAIL, LIGHT, ORE_SLOT;

        static BlockSlot parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "support" -> SUPPORT;
                case "rubble" -> RUBBLE;
                case "planks" -> PLANKS;
                case "fence" -> FENCE;
                case "rail" -> RAIL;
                case "light" -> LIGHT;
                case "ore_slot" -> ORE_SLOT;
                default -> throw new JsonParseException("unknown motif blockSlot: " + value);
            };
        }
    }

    /** One parametric element inside a motif rule set. */
    public record Element(ElementType type, BlockSlot slot, Range count,
                          OffsetRule offset, int radius, double chance) { }

    /** Which segment tags a motif applies to; empty means every segment. */
    public record Motif(String id, Set<String> appliesTo, Set<String> regions,
                        Range spacing, double chance, List<Element> elements) {
        public Motif {
            appliesTo = Set.copyOf(appliesTo);
            regions = Set.copyOf(regions);
            elements = List.copyOf(elements);
        }

        public boolean applies(Set<String> segmentTags) {
            if (appliesTo.isEmpty()) {
                return true;
            }
            for (String tag : segmentTags) {
                if (appliesTo.contains(tag)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Set<String> APPLIES_TOKENS = Set.of(
            "edge", "node", "connector", "mineshaft", "natural",
            "main", "branch", "spur", "loop",
            "entrance", "route", "cavern", "cave", "pocket");
    private static final Set<String> REGION_TOKENS = Set.of("upper", "middle", "chaos");
    private static final int MAX_ELEMENTS = 12;
    private static final int MAX_MOTIFS = 64;

    public MotifCatalog() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, Motif> loaded = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            try {
                Motif motif = parse(entry.getValue().getAsJsonObject());
                if (loaded.containsKey(motif.id())) {
                    throw new JsonParseException("duplicate motif id '" + motif.id() + "'");
                }
                if (loaded.size() >= MAX_MOTIFS) {
                    throw new JsonParseException("more than " + MAX_MOTIFS + " motifs");
                }
                loaded.put(motif.id(), motif);
            } catch (RuntimeException e) {
                LOGGER.error("[dawnmine] Skipping invalid motif {}: {}",
                        entry.getKey(), e.getMessage());
            }
        }
        motifs = Map.copyOf(loaded);
        LOGGER.info("[dawnmine] Loaded {} motif rule set(s): {}", motifs.size(),
                motifs.isEmpty() ? "none" : String.join(", ", motifs.keySet()));
    }

    /** Active snapshot: every loaded motif keyed by id, never null. */
    public static Map<String, Motif> active() {
        return motifs;
    }

    /** A canonical hash of every loaded rule set: motif ops are persisted
     * geometry, so a catalog edit between restarts must invalidate the
     * run's saved generation plan rather than rebuild a different motif
     * mid-run. */
    public static String fingerprint() {
        StringBuilder canonical = new StringBuilder();
        motifs.values().stream()
                .sorted(java.util.Comparator.comparing(Motif::id))
                .forEach(motif -> canonical.append(motif.id()).append('|')
                        .append(String.join(",", motif.appliesTo().stream().sorted().toList()))
                        .append('|')
                        .append(String.join(",", motif.regions().stream().sorted().toList()))
                        .append('|').append(motif.spacing().min()).append('-')
                        .append(motif.spacing().max()).append('|').append(motif.chance())
                        .append('|').append(motif.elements().stream()
                                .map(e -> e.type() + ":" + e.slot() + ":" + e.offset() + ":"
                                        + e.count().min() + "-" + e.count().max() + ":"
                                        + e.radius() + ":" + e.chance())
                                .collect(java.util.stream.Collectors.joining(";")))
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

    static Motif parse(JsonObject json) {
        String id = requiredString(json, "motif");
        if (!id.matches("[a-z0-9_-]+")) {
            throw new JsonParseException("motif id '" + id + "' must match [a-z0-9_-]+");
        }
        Set<String> appliesTo = new LinkedHashSet<>();
        for (JsonElement token : requiredArray(json, "appliesTo")) {
            String value = token.getAsString().toLowerCase(Locale.ROOT).trim();
            if (!APPLIES_TOKENS.contains(value)) {
                throw new JsonParseException("motif '" + id + "' has unknown appliesTo token '" + value + "'");
            }
            appliesTo.add(value);
        }
        Set<String> regions = new LinkedHashSet<>();
        if (json.has("regions")) {
            for (JsonElement token : json.getAsJsonArray("regions")) {
                String value = token.getAsString().toLowerCase(Locale.ROOT).trim();
                if (!REGION_TOKENS.contains(value)) {
                    throw new JsonParseException("motif '" + id + "' has unknown region '" + value + "'");
                }
                regions.add(value);
            }
        } else {
            regions.addAll(REGION_TOKENS);
        }
        Range spacing = range(json, "spacing", 4, 512);
        double chance = fraction(json, "chance");
        JsonArray elementArray = requiredArray(json, "elements");
        if (elementArray.isEmpty()) {
            throw new JsonParseException("motif '" + id + "' has no elements");
        }
        if (elementArray.size() > MAX_ELEMENTS) {
            throw new JsonParseException("motif '" + id + "' has more than " + MAX_ELEMENTS + " elements");
        }
        List<Element> elements = new ArrayList<>();
        for (JsonElement elementJson : elementArray) {
            JsonObject element = elementJson.getAsJsonObject();
            ElementType type = ElementType.parse(requiredString(element, "type"));
            BlockSlot slot = BlockSlot.parse(requiredString(element, "blockSlot"));
            OffsetRule offset = element.has("offsetRule")
                    ? OffsetRule.parse(element.get("offsetRule").getAsString())
                    : defaultOffset(type);
            Range count = element.has("countRange")
                    ? range(element, "countRange", 1, 24)
                    : new Range(1, 1);
            int radius = element.has("radius")
                    ? integer(element, "radius", 1, 10) : 3;
            double elementChance = element.has("chance")
                    ? fraction(element, "chance") : 1.0;
            validateCombination(id, type, slot, offset, count);
            elements.add(new Element(type, slot, count, offset, radius, elementChance));
        }
        return new Motif(id, appliesTo, regions, spacing, chance, elements);
    }

    private static OffsetRule defaultOffset(ElementType type) {
        return switch (type) {
            case VEIN -> OffsetRule.WALL;
            case RAIL_STUB -> OffsetRule.PATH;
            default -> OffsetRule.FLOOR;
        };
    }

    private static void validateCombination(String id, ElementType type, BlockSlot slot,
                                            OffsetRule offset, Range count) {
        if (type == ElementType.RAIL_STUB && slot != BlockSlot.RAIL) {
            throw new JsonParseException("motif '" + id
                    + "' rail_stub elements must use the rail blockSlot");
        }
        if (type == ElementType.LAMP && slot != BlockSlot.LIGHT) {
            throw new JsonParseException("motif '" + id
                    + "' lamp elements must use the light blockSlot");
        }
        if (type == ElementType.LAMP && count.min() != 1) {
            throw new JsonParseException("motif '" + id
                    + "' lamp elements place exactly one cell");
        }
        if (type == ElementType.LAMP && offset == OffsetRule.PATH) {
            throw new JsonParseException("motif '" + id
                    + "' lamp elements cannot anchor on the path spine");
        }
        if (slot == BlockSlot.ORE_SLOT && offset != OffsetRule.WALL) {
            throw new JsonParseException("motif '" + id
                    + "' ore_slot elements must use the wall offsetRule");
        }
        if (offset == OffsetRule.PATH && slot != BlockSlot.RAIL && slot != BlockSlot.LIGHT) {
            throw new JsonParseException("motif '" + id
                    + "' path-offset elements must be walk-through (rail or light)");
        }
        if (offset == OffsetRule.WALL && slot == BlockSlot.RAIL) {
            throw new JsonParseException("motif '" + id
                    + "' rail elements cannot embed in walls");
        }
        if (type == ElementType.VEIN && offset != OffsetRule.WALL) {
            throw new JsonParseException("motif '" + id
                    + "' vein elements must use the wall offsetRule");
        }
        if (type == ElementType.PILLAR && offset != OffsetRule.FLOOR) {
            throw new JsonParseException("motif '" + id
                    + "' pillar elements rise from the floor pool");
        }
    }

    private static Range range(JsonObject json, String key, int minBound, int maxBound) {
        JsonArray pair = requiredArray(json, key);
        if (pair.size() != 2) {
            throw new JsonParseException("'" + key + "' must be a [min,max] pair");
        }
        int min = pair.get(0).getAsInt();
        int max = pair.get(1).getAsInt();
        if (min < minBound || max > maxBound || min > max) {
            throw new JsonParseException("'" + key + "' out of bounds ["
                    + minBound + "," + maxBound + "]");
        }
        return new Range(min, max);
    }

    private static double fraction(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isNumber()) {
            throw new JsonParseException("missing or invalid number '" + key + "'");
        }
        double value = json.get(key).getAsDouble();
        if (value < 0.0 || value > 1.0) {
            throw new JsonParseException("'" + key + "' must be within 0..1");
        }
        return value;
    }

    private static int integer(JsonObject json, String key, int min, int max) {
        try {
            int value = json.get(key).getAsInt();
            if (value < min || value > max) {
                throw new JsonParseException("'" + key + "' is out of bounds");
            }
            return value;
        } catch (RuntimeException e) {
            throw new JsonParseException("missing or invalid integer '" + key + "'", e);
        }
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

    private static JsonArray requiredArray(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            throw new JsonParseException("missing or invalid array '" + key + "'");
        }
        return json.getAsJsonArray(key);
    }
}
