package com.dwurdy.dawnmine.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Registry;
import org.slf4j.Logger;

/**
 * Data-driven inventory of mine resources backed by native registered blocks.
 * The catalog is reloadable; its fingerprint participates in the run's
 * generation fingerprint so a catalog change cannot rewrite a partial run.
 */
public final class OreCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<TagKey<Block>> DISCOVERED_ORE_TAGS = List.of(
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores")),
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores/silver")),
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("fungidelight", "truffle_ore")));
    private static volatile OreCatalogSnapshot active = OreCatalogSnapshot.empty();

    public enum Tier {
        UPPER,
        MIDDLE,
        CHAOS;

        static Tier parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "upper" -> UPPER;
                case "middle" -> MIDDLE;
                case "chaos" -> CHAOS;
                default -> throw new IllegalArgumentException("unknown ore depth tier: " + value);
            };
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Kind {
        RESOURCE,
        ENCOUNTER;

        static Kind parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "resource" -> RESOURCE;
                case "encounter" -> ENCOUNTER;
                default -> throw new IllegalArgumentException("unknown ore catalog kind: " + value);
            };
        }
    }

    public record Entry(ResourceLocation blockId, Tier tier, int weight, int maxVeinSize,
                        Kind kind, boolean requiresCorrectTool, ResourceLocation minimumTestedTool,
                        List<ResourceLocation> nativeDropItems, ResourceLocation encounterEntity) {
        public Entry {
            if (weight <= 0 || weight > 1000 || maxVeinSize < 1 || maxVeinSize > 4) {
                throw new IllegalArgumentException("ore weight must be within 1..1000 and vein size within 1..4");
            }
            if (kind == null) {
                throw new IllegalArgumentException("ore catalog kind is required");
            }
            if (minimumTestedTool == null) {
                throw new IllegalArgumentException("minimum tested tool is required");
            }
            nativeDropItems = nativeDropItems == null ? List.of() : nativeDropItems.stream().distinct().sorted()
                    .toList();
            if (kind == Kind.RESOURCE && (nativeDropItems.isEmpty() || encounterEntity != null)) {
                throw new IllegalArgumentException("resource entry requires native drops and cannot define an encounter");
            }
            if (kind == Kind.ENCOUNTER && (!nativeDropItems.isEmpty() || encounterEntity == null)) {
                throw new IllegalArgumentException("encounter entry requires an entity and cannot declare native drops");
            }
        }
    }

    public record Completeness(int catalogEntries, int registeredEntries,
                               List<String> missingOreTagEntries,
                               List<String> unavailableCatalogEntries,
                               List<String> unavailableCatalogDropItems,
                               List<String> unavailableEncounterEntities,
                               List<String> unassignedTiers, List<String> catalogErrors) {
        public boolean complete() {
            return missingOreTagEntries.isEmpty() && unavailableCatalogEntries.isEmpty()
                    && unavailableCatalogDropItems.isEmpty()
                    && unavailableEncounterEntities.isEmpty()
                    && unassignedTiers.isEmpty() && catalogErrors.isEmpty();
        }
    }

    private OreCatalog() {
        super(new com.google.gson.Gson(), "ore_catalog");
    }

    public static OreCatalog createReloadListener() {
        return new OreCatalog();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        active = parseSnapshot(objects);
        for (String error : active.errors) {
            LOGGER.error("[dawnmine] Ore catalog: {}", error);
        }
        LOGGER.info("[dawnmine] Loaded {} mine ore catalog entries (fingerprint {}).",
                active.entries().size(), active.fingerprint().substring(0, 12));
    }

    static OreCatalogSnapshot parseSnapshot(Map<ResourceLocation, JsonElement> objects) {
        Map<ResourceLocation, Entry> entries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> object : objects.entrySet()) {
            try {
                JsonObject root = object.getValue().getAsJsonObject();
                if (root.get("schema").getAsInt() != 1) {
                    throw new IllegalArgumentException("unsupported ore catalog schema");
                }
                JsonArray ores = root.getAsJsonArray("ores");
                for (JsonElement value : ores) {
                    JsonObject json = value.getAsJsonObject();
                    ResourceLocation id = ResourceLocation.parse(json.get("block").getAsString());
                    Kind kind = Kind.parse(json.has("kind") ? json.get("kind").getAsString() : "resource");
                    ResourceLocation encounterEntity = json.has("encounter_entity")
                            ? ResourceLocation.parse(json.get("encounter_entity").getAsString()) : null;
                    Entry entry = new Entry(id, Tier.parse(json.get("tier").getAsString()),
                            json.get("weight").getAsInt(), json.get("max_vein_size").getAsInt(),
                            kind,
                            json.get("requires_correct_tool").getAsBoolean(),
                            ResourceLocation.parse(json.get("minimum_tested_tool").getAsString()),
                            json.getAsJsonArray("native_drop_items").asList().stream()
                                    .map(JsonElement::getAsString).map(ResourceLocation::parse).toList(),
                            encounterEntity);
                    if (entries.putIfAbsent(id, entry) != null) {
                        errors.add("duplicate catalog entry " + id);
                    }
                }
            } catch (RuntimeException e) {
                errors.add(object.getKey() + ": " + e.getMessage());
            }
        }
        return new OreCatalogSnapshot(entries, errors);
    }

    public static OreCatalogSnapshot active() {
        return active;
    }

    public static final class OreCatalogSnapshot {
        private final Map<ResourceLocation, Entry> entries;
        private final Map<Tier, List<Entry>> byTier;
        private final String fingerprint;

        private final List<String> errors;

        private OreCatalogSnapshot(Map<ResourceLocation, Entry> entries, List<String> errors) {
            this.entries = Map.copyOf(entries);
            this.errors = List.copyOf(errors);
            Map<Tier, List<Entry>> grouped = new LinkedHashMap<>();
            for (Tier tier : Tier.values()) {
                grouped.put(tier, entries.values().stream()
                        .filter(entry -> entry.tier() == tier)
                        .sorted(Comparator.comparing(entry -> entry.blockId().toString()))
                        .toList());
            }
            this.byTier = Map.copyOf(grouped);
            this.fingerprint = fingerprint(entries);
        }

        private static OreCatalogSnapshot empty() {
            return new OreCatalogSnapshot(Map.of(), List.of("no catalog resource loaded"));
        }

        static OreCatalogSnapshot fromEntries(List<Entry> entries) {
            Map<ResourceLocation, Entry> byId = new LinkedHashMap<>();
            for (Entry entry : entries) {
                if (byId.putIfAbsent(entry.blockId(), entry) != null) {
                    throw new IllegalArgumentException("duplicate ore entry " + entry.blockId());
                }
            }
            return new OreCatalogSnapshot(byId, List.of());
        }

        public Map<ResourceLocation, Entry> entries() {
            return entries;
        }

        public String fingerprint() {
            return fingerprint;
        }

        public List<Entry> entries(Tier tier) {
            return byTier.getOrDefault(tier, List.of());
        }

        public List<Entry> resourceEntries(Tier tier) {
            return entries(tier).stream().filter(entry -> entry.kind() == Kind.RESOURCE).toList();
        }

        public Optional<Entry> entry(ResourceLocation id) {
            return Optional.ofNullable(entries.get(id));
        }

        public Entry choose(Tier tier, RandomSource random) {
            List<Entry> candidates = resourceEntries(tier);
            int totalWeight = candidates.stream().mapToInt(Entry::weight).sum();
            if (totalWeight <= 0) {
                return null;
            }
            int selected = random.nextInt(totalWeight);
            for (Entry entry : candidates) {
                selected -= entry.weight();
                if (selected < 0) {
                    return entry;
                }
            }
            throw new IllegalStateException("weighted ore selection escaped its candidate range");
        }

        public Completeness completeness(ServerLevel level) {
            Registry<Block> registry = level.registryAccess().registryOrThrow(Registries.BLOCK);
            Set<ResourceLocation> taggedOreIds = new TreeSet<>();
            for (TagKey<Block> oreTag : DISCOVERED_ORE_TAGS) {
                registry.getTagOrEmpty(oreTag).forEach(holder -> {
                    ResourceLocation id = registry.getKey(holder.value());
                    if (id != null) taggedOreIds.add(id);
                });
            }
            Set<ResourceLocation> registeredIds = entries.keySet().stream()
                    .filter(id -> registry.getOptional(id).isPresent())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            Registry<net.minecraft.world.entity.EntityType<?>> entityRegistry =
                    level.registryAccess().registryOrThrow(Registries.ENTITY_TYPE);
            Set<ResourceLocation> registeredEncounterIds = entries.values().stream()
                    .filter(entry -> entry.kind() == Kind.ENCOUNTER)
                    .map(Entry::encounterEntity).distinct()
                    .filter(id -> entityRegistry.getOptional(id).isPresent())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            Registry<net.minecraft.world.item.Item> itemRegistry =
                    level.registryAccess().registryOrThrow(Registries.ITEM);
            Set<ResourceLocation> registeredDropItemIds = entries.values().stream()
                    .filter(entry -> entry.kind() == Kind.RESOURCE)
                    .flatMap(entry -> entry.nativeDropItems().stream()).distinct()
                    .filter(id -> itemRegistry.getOptional(id).isPresent())
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            return completeness(taggedOreIds, registeredIds, registeredEncounterIds, registeredDropItemIds);
        }

        Completeness completeness(Set<ResourceLocation> taggedOreIds,
                                  Set<ResourceLocation> registeredIds) {
            return completeness(taggedOreIds, registeredIds, Set.of());
        }

        Completeness completeness(Set<ResourceLocation> taggedOreIds,
                                  Set<ResourceLocation> registeredIds,
                                  Set<ResourceLocation> registeredEncounterIds) {
            return completeness(taggedOreIds, registeredIds, registeredEncounterIds, Set.of());
        }

        Completeness completeness(Set<ResourceLocation> taggedOreIds,
                                  Set<ResourceLocation> registeredIds,
                                  Set<ResourceLocation> registeredEncounterIds,
                                  Set<ResourceLocation> registeredDropItemIds) {
            TreeSet<String> missing = new TreeSet<>();
            for (ResourceLocation id : taggedOreIds) {
                if (!entries.containsKey(id)) missing.add(id.toString());
            }
            TreeSet<String> unavailable = new TreeSet<>();
            for (ResourceLocation id : entries.keySet()) {
                if (!registeredIds.contains(id)) unavailable.add(id.toString());
            }
            TreeSet<String> unavailableDropItems = new TreeSet<>();
            entries.values().stream().filter(entry -> entry.kind() == Kind.RESOURCE)
                    .flatMap(entry -> entry.nativeDropItems().stream()).distinct()
                    .filter(id -> !registeredDropItemIds.contains(id))
                    .forEach(id -> unavailableDropItems.add(id.toString()));
            TreeSet<String> unavailableEntities = new TreeSet<>();
            entries.values().stream().filter(entry -> entry.kind() == Kind.ENCOUNTER)
                    .map(Entry::encounterEntity).distinct()
                    .filter(id -> !registeredEncounterIds.contains(id))
                    .forEach(id -> unavailableEntities.add(id.toString()));
            List<String> emptyTiers = java.util.Arrays.stream(Tier.values())
                    .filter(tier -> resourceEntries(tier).isEmpty()).map(Tier::id).toList();
            return new Completeness(entries.size(), registeredIds.size(), List.copyOf(missing),
                    List.copyOf(unavailable), List.copyOf(unavailableDropItems),
                    List.copyOf(unavailableEntities), emptyTiers, errors);
        }

        public BlockState blockState(Entry entry) {
            Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getOptional(entry.blockId())
                    .orElseThrow(() -> new IllegalStateException("catalog block is not registered: " + entry.blockId()));
            return block.defaultBlockState();
        }

        private static String fingerprint(Map<ResourceLocation, Entry> entries) {
            String canonical = entries.values().stream()
                    .sorted(Comparator.comparing(entry -> entry.blockId().toString()))
                    .map(entry -> entry.blockId() + "|" + entry.tier().id() + "|"
                            + entry.weight() + "|" + entry.maxVeinSize() + "|" + entry.kind() + "|"
                            + entry.requiresCorrectTool() + "|" + entry.minimumTestedTool() + "|"
                            + entry.nativeDropItems().stream().map(ResourceLocation::toString)
                                    .collect(java.util.stream.Collectors.joining(",")) + "|"
                            + entry.encounterEntity())
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(canonical.getBytes(StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder(hash.length * 2);
                for (byte value : hash) {
                    result.append(Character.forDigit((value >> 4) & 0xf, 16));
                    result.append(Character.forDigit(value & 0xf, 16));
                }
                return result.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }
    }

    public static String describe(Completeness completeness) {
        return "registered " + completeness.registeredEntries() + "/" + completeness.catalogEntries()
                + ", missing ore-tag entries=" + completeness.missingOreTagEntries()
                + ", unavailable=" + completeness.unavailableCatalogEntries()
                + ", unavailable native drop items=" + completeness.unavailableCatalogDropItems()
                + ", unavailable encounter entities=" + completeness.unavailableEncounterEntities()
                + ", empty tiers=" + completeness.unassignedTiers()
                + ", catalog errors=" + completeness.catalogErrors();
    }
}
