package com.rusticcraft.publicmines.progression;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.rusticcraft.publicmines.encounter.MineRewardCatalog;
import com.rusticcraft.publicmines.generation.OreCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.slf4j.Logger;

/**
 * Versioned, evidence-backed progression graph for the shared mine.
 *
 * <p>Every material node declares a repeatable bounded source (mining,
 * combat reward, or exploration), at least one sink, a documented yield
 * bound, and the audit evidence behind it. Every upgrade node names the
 * earlier-depth problem it solves and the different deeper problem it does
 * not solve. The graph is reloadable datapack data; a SHA-256 fingerprint
 * versions it so a graph change is visible in logs and receipts.
 *
 * <p>Nodes that reference items absent from the live registry are kept but
 * flagged unavailable — the graph degrades to a partial map rather than
 * dropping claims silently. Recipe references are verified against the
 * server's {@link RecipeManager} by {@link #verifyRecipes}, which the exact-
 * pack runtime audit exercises; a recipe that cannot be resolved is a hard
 * completeness failure, never silently assumed.
 */
public final class ProgressionGraph extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIRECTORY = "progression";
    private static final int MAX_MATERIALS = 64;
    private static final int MAX_UPGRADES = 32;
    private static final int MAX_BLOCKED = 16;
    private static volatile ProgressionSnapshot active = ProgressionSnapshot.empty();

    public enum SourceKind {
        MINING,
        PVE,
        EXPLORATION;

        static SourceKind parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "mining" -> MINING;
                case "pve" -> PVE;
                case "exploration" -> EXPLORATION;
                default -> throw new JsonParseException("unknown progression source kind: " + value);
            };
        }
    }

    public enum SinkKind {
        RECIPE,
        MECHANIC;

        static SinkKind parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "recipe" -> RECIPE;
                case "mechanic" -> MECHANIC;
                default -> throw new JsonParseException("unknown progression sink kind: " + value);
            };
        }
    }

    /** A repeatable bounded acquisition route. Mining refs are
     * {@code block:<ore-catalog-id>}; combat refs are {@code reward:<tier>} for
     * configured mine rewards or {@code entity:<entity-id>} for named mob
     * drops; exploration refs are free text describing the verified source. */
    public record Source(SourceKind kind, String ref) {
        public Source {
            if (kind == null || ref == null || ref.isBlank() || ref.length() > 256) {
                throw new JsonParseException("progression source requires a kind and bounded ref");
            }
            switch (kind) {
                case MINING -> {
                    if (!ref.startsWith("block:") || location(ref.substring(6)) == null) {
                        throw new JsonParseException("mining source must be block:<item-id>, got '" + ref + "'");
                    }
                }
                case PVE -> {
                    boolean reward = ref.startsWith("reward:") && !ref.substring(7).isBlank();
                    boolean entity = ref.startsWith("entity:") && location(ref.substring(7)) != null;
                    if (!reward && !entity) {
                        throw new JsonParseException("pve source must be reward:<tier> or entity:<id>, got '" + ref + "'");
                    }
                }
                case EXPLORATION -> { /* free descriptive ref; bounded above */ }
                default -> throw new JsonParseException("unhandled source kind " + kind);
            }
        }
    }

    /** Where the material goes. Recipe sinks name a pack recipe id and the
     * verified output item; mechanic sinks describe a non-recipe use
     * (fuel, enchanting, trading, tech input). */
    public record Sink(SinkKind kind, String ref, ResourceLocation output) {
        public Sink {
            if (kind == null || ref == null || ref.isBlank() || ref.length() > 256) {
                throw new JsonParseException("progression sink requires a kind and bounded ref");
            }
            if (kind == SinkKind.RECIPE) {
                if (location(ref) == null || output == null) {
                    throw new JsonParseException("recipe sink needs a recipe id ref and an output item id");
                }
            } else if (output != null) {
                throw new JsonParseException("mechanic sink cannot declare a recipe output");
            }
        }

        /** The pack recipe id (only for {@link SinkKind#RECIPE} sinks). */
        public ResourceLocation recipeId() {
            return location(ref);
        }
    }

    public record Material(ResourceLocation item, String tier, boolean outsideMine,
                           List<ResourceLocation> coDrops, List<Source> sources, List<Sink> sinks,
                           String boundedYield, List<String> evidence) {
        public Material {
            if (item == null || !OreTier.isValid(tier) || boundedYield == null || boundedYield.isBlank()
                    || boundedYield.length() > 512 || sources == null || sources.isEmpty()
                    || sources.size() > 8 || sinks == null || sinks.isEmpty() || sinks.size() > 8
                    || evidence == null || evidence.isEmpty() || evidence.size() > 12) {
                throw new JsonParseException("invalid progression material for " + item);
            }
            coDrops = coDrops == null ? List.of() : List.copyOf(coDrops);
            sources = List.copyOf(sources);
            sinks = List.copyOf(sinks);
            evidence = copyBoundedStrings(evidence, 256, "evidence");
        }

        public List<ResourceLocation> allItems() {
            List<ResourceLocation> all = new ArrayList<>(1 + coDrops.size());
            all.add(item);
            all.addAll(coDrops);
            return all;
        }
    }

    public record Upgrade(String id, String tier, List<String> activities, ResourceLocation output,
                          ResourceLocation recipe, List<String> inputs, String solves,
                          String doesNotSolve, String verification, List<String> evidence) {
        public static final Set<String> ACTIVITIES = Set.of("mining", "pve", "exploration");

        public Upgrade {
            if (id == null || id.isBlank() || id.length() > 64 || !OreTier.isValid(tier)
                    || output == null || recipe == null
                    || activities == null || activities.isEmpty() || activities.size() > 3
                    || !ACTIVITIES.containsAll(activities)
                    || inputs == null || inputs.isEmpty() || inputs.size() > 12
                    || solves == null || solves.isBlank() || solves.length() > 768
                    || doesNotSolve == null || doesNotSolve.isBlank() || doesNotSolve.length() > 768
                    || solves.equals(doesNotSolve)
                    || verification == null
                    || !(verification.equals("verified") || verification.equals("candidate"))
                    || evidence == null || evidence.isEmpty() || evidence.size() > 12) {
                throw new JsonParseException("invalid progression upgrade " + id);
            }
            activities = List.copyOf(activities);
            inputs = copyBoundedStrings(inputs, 256, "inputs");
            solves = solves.trim();
            doesNotSolve = doesNotSolve.trim();
            evidence = copyBoundedStrings(evidence, 256, "evidence");
        }
    }

    /** A verified pack gap: a real pack item/recipe whose configured-mine
     * source is missing. Recorded, never silently invented around. */
    public record BlockedNode(ResourceLocation item, String neededFor, String reason,
                              List<String> evidence) {
        public BlockedNode {
            if (item == null || neededFor == null || neededFor.isBlank()
                    || reason == null || reason.isBlank() || reason.length() > 768
                    || evidence == null || evidence.isEmpty() || evidence.size() > 12) {
                throw new JsonParseException("invalid blocked progression node " + item);
            }
            neededFor = neededFor.trim();
            reason = reason.trim();
            evidence = copyBoundedStrings(evidence, 256, "evidence");
        }
    }

    /** Registry/cross-catalog integrity report. Recipe ids are resolved by
     * {@link #verifyRecipes} at runtime; everything else is checked here. */
    public record Completeness(int materials, int upgrades, int blocked,
                               List<String> unavailableItems,
                               List<String> unknownMiningBlocks,
                               List<String> miningDropMismatches,
                               List<String> unknownRewardTiers,
                               List<String> rewardItemMismatches,
                               List<String> unavailableSourceEntities,
                               List<String> errors) {
        public boolean complete() {
            return unavailableItems.isEmpty() && unknownMiningBlocks.isEmpty()
                    && miningDropMismatches.isEmpty() && unknownRewardTiers.isEmpty()
                    && rewardItemMismatches.isEmpty() && unavailableSourceEntities.isEmpty()
                    && errors.isEmpty();
        }
    }

    private ProgressionGraph() {
        super(new Gson(), DIRECTORY);
    }

    public static ProgressionGraph createReloadListener() {
        return new ProgressionGraph();
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, Material> materials = new LinkedHashMap<>();
        List<Upgrade> upgrades = new ArrayList<>();
        List<BlockedNode> blocked = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> object : objects.entrySet()) {
            try {
                parseInto(object.getValue().getAsJsonObject(), materials, upgrades, blocked);
            } catch (RuntimeException e) {
                errors.add(object.getKey() + ": " + e.getMessage());
            }
        }
        if (materials.isEmpty() && errors.isEmpty()) {
            errors.add("no progression graph resource loaded");
        }
        active = new ProgressionSnapshot(materials, upgrades, blocked, errors);
        for (String error : errors) {
            LOGGER.error("[publicmines] Progression graph: {}", error);
        }
        Completeness completeness = active.completeness();
        LOGGER.info("[publicmines] Loaded progression graph: {} materials, {} upgrades, {} blocked "
                        + "(fingerprint {}, unavailable items: {}).",
                materials.size(), upgrades.size(), blocked.size(),
                active.fingerprint().substring(0, 12), completeness.unavailableItems().size());
        for (String line : describeFailures(completeness)) {
            LOGGER.error("[publicmines] Progression graph integrity: {}", line);
        }
    }

    public static ProgressionSnapshot active() {
        return active;
    }

    /** Runtime recipe-resolution check. Called by the exact-pack audit and by
     * server validation: every recipe sink and every upgrade recipe must
     * resolve, and recipe sinks must produce their declared output item. */
    public static List<String> verifyRecipes(RecipeManager recipes,
                                             net.minecraft.core.HolderLookup.Provider registries) {
        List<String> failures = new ArrayList<>();
        for (Material material : active.materials().values()) {
            for (Sink sink : material.sinks()) {
                if (sink.kind() != SinkKind.RECIPE) continue;
                Optional<RecipeHolder<?>> holder = recipes.byKey(sink.recipeId());
                if (holder.isEmpty()) {
                    failures.add("material " + material.item() + " sink recipe " + sink.recipeId()
                            + " is not registered" + alternativesFor(recipes, registries, sink.output()));
                    continue;
                }
                net.minecraft.world.item.Item result =
                        holder.get().value().getResultItem(registries).getItem();
                ResourceLocation resultId = BuiltInRegistries.ITEM.getKey(result);
                if (!sink.output().equals(resultId)) {
                    failures.add("material " + material.item() + " sink recipe " + sink.recipeId()
                            + " produces " + resultId + ", expected " + sink.output());
                }
            }
        }
        for (Upgrade upgrade : active.upgrades()) {
            Optional<RecipeHolder<?>> holder = recipes.byKey(upgrade.recipe());
            if (holder.isEmpty()) {
                failures.add("upgrade " + upgrade.id() + " recipe " + upgrade.recipe()
                        + " is not registered" + alternativesFor(recipes, registries, upgrade.output()));
                continue;
            }
            net.minecraft.world.item.Item result =
                    holder.get().value().getResultItem(registries).getItem();
            ResourceLocation resultId = BuiltInRegistries.ITEM.getKey(result);
            if (!upgrade.output().equals(resultId)) {
                failures.add("upgrade " + upgrade.id() + " recipe " + upgrade.recipe()
                        + " produces " + resultId + ", expected " + upgrade.output());
            }
        }
        return failures;
    }

    /** Diagnostic for the audit log: when a declared recipe id does not
     * resolve, lists every registered recipe that produces the expected
     * output so the graph can be corrected to the pack's real recipe ids
     * instead of guessing. */
    private static String alternativesFor(RecipeManager recipes,
                                          net.minecraft.core.HolderLookup.Provider registries,
                                          ResourceLocation output) {
        TreeSet<String> producers = new TreeSet<>();
        try {
            for (RecipeHolder<?> holder : recipes.getRecipes()) {
                try {
                    net.minecraft.world.item.Item result =
                            holder.value().getResultItem(registries).getItem();
                    if (output.equals(BuiltInRegistries.ITEM.getKey(result))) {
                        producers.add(holder.id() + " (" + holder.value().getType() + ")");
                    }
                } catch (RuntimeException ignored) {
                    // Some recipe types cannot answer getResultItem generically; skip them.
                }
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return producers.isEmpty() ? " (no registered recipe produces " + output + ")"
                : " (registered producers: " + producers + ")";
    }

    static void parseInto(JsonObject root, Map<ResourceLocation, Material> materials,
                          List<Upgrade> upgrades, List<BlockedNode> blocked) {
        int schema = integer(root, "schema", 1, 1);
        if (schema != 1) throw new JsonParseException("unsupported progression schema " + schema);
        JsonArray mats = requiredArray(root, "materials");
        JsonArray ups = requiredArray(root, "upgrades");
        JsonArray blockedArray = root.has("blocked") ? root.getAsJsonArray("blocked") : new JsonArray();
        if (mats.isEmpty() || mats.size() > MAX_MATERIALS || ups.size() > MAX_UPGRADES
                || blockedArray.size() > MAX_BLOCKED) {
            throw new JsonParseException("progression graph exceeds bounds (materials 1.."
                    + MAX_MATERIALS + ", upgrades 0.." + MAX_UPGRADES
                    + ", blocked 0.." + MAX_BLOCKED + ")");
        }
        for (JsonElement element : mats) {
            Material material = parseMaterial(element.getAsJsonObject());
            if (materials.putIfAbsent(material.item(), material) != null) {
                throw new JsonParseException("duplicate progression material " + material.item());
            }
        }
        Set<String> upgradeIds = new LinkedHashSet<>();
        for (JsonElement element : ups) {
            Upgrade upgrade = parseUpgrade(element.getAsJsonObject());
            if (!upgradeIds.add(upgrade.id())) {
                throw new JsonParseException("duplicate progression upgrade " + upgrade.id());
            }
            upgrades.add(upgrade);
        }
        for (JsonElement element : blockedArray) {
            blocked.add(parseBlocked(element.getAsJsonObject()));
        }
    }

    private static Material parseMaterial(JsonObject json) {
        ResourceLocation item = location(requiredString(json, "item"));
        if (item == null) throw new JsonParseException("invalid material item id");
        List<ResourceLocation> coDrops = new ArrayList<>();
        if (json.has("coDrops")) {
            for (JsonElement element : json.getAsJsonArray("coDrops")) {
                ResourceLocation drop = location(element.getAsString());
                if (drop == null) throw new JsonParseException("invalid coDrops item id");
                coDrops.add(drop);
            }
        }
        List<Source> sources = new ArrayList<>();
        for (JsonElement element : requiredArray(json, "sources")) {
            JsonObject source = element.getAsJsonObject();
            sources.add(new Source(SourceKind.parse(requiredString(source, "kind")),
                    requiredString(source, "ref")));
        }
        List<Sink> sinks = new ArrayList<>();
        for (JsonElement element : requiredArray(json, "sinks")) {
            JsonObject sink = element.getAsJsonObject();
            sinks.add(new Sink(SinkKind.parse(requiredString(sink, "kind")),
                    requiredString(sink, "ref"),
                    sink.has("output") ? location(sink.get("output").getAsString()) : null));
        }
        return new Material(item, requiredString(json, "tier"),
                json.has("outsideMine") && json.get("outsideMine").getAsBoolean(),
                coDrops, sources, sinks, requiredString(json, "boundedYield"),
                stringList(json, "evidence"));
    }

    private static Upgrade parseUpgrade(JsonObject json) {
        ResourceLocation output = location(requiredString(json, "output"));
        ResourceLocation recipe = location(requiredString(json, "recipe"));
        if (output == null || recipe == null) {
            throw new JsonParseException("upgrade requires item ids for output and recipe");
        }
        return new Upgrade(requiredString(json, "id"), requiredString(json, "tier"),
                stringList(json, "activities"), output, recipe, stringList(json, "inputs"),
                requiredString(json, "solves"), requiredString(json, "doesNotSolve"),
                requiredString(json, "verification"), stringList(json, "evidence"));
    }

    private static BlockedNode parseBlocked(JsonObject json) {
        ResourceLocation item = location(requiredString(json, "item"));
        if (item == null) throw new JsonParseException("invalid blocked item id");
        return new BlockedNode(item, requiredString(json, "neededFor"),
                requiredString(json, "reason"), stringList(json, "evidence"));
    }

    private static ResourceLocation location(String id) {
        return id == null ? null : ResourceLocation.tryParse(id);
    }

    private static String requiredString(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive()
                || !json.getAsJsonPrimitive(key).isString()) {
            throw new JsonParseException("missing or invalid string '" + key + "'");
        }
        String value = json.get(key).getAsString().trim();
        if (value.isEmpty() || value.length() > 1024) {
            throw new JsonParseException("empty or overlong string '" + key + "'");
        }
        return value;
    }

    private static int integer(JsonObject json, String key, int min, int max) {
        try {
            int value = json.get(key).getAsInt();
            if (value < min || value > max) throw new JsonParseException("'" + key + "' is out of bounds");
            return value;
        } catch (RuntimeException e) {
            throw new JsonParseException("missing or invalid integer '" + key + "'", e);
        }
    }

    private static JsonArray requiredArray(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            throw new JsonParseException("missing or invalid array '" + key + "'");
        }
        return json.getAsJsonArray(key);
    }

    private static List<String> stringList(JsonObject json, String key) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : requiredArray(json, key)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new JsonParseException("non-string entry in '" + key + "'");
            }
            values.add(element.getAsString());
        }
        return values;
    }

    private static List<String> copyBoundedStrings(List<String> values, int max, String key) {
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > max) {
                throw new JsonParseException("empty or overlong '" + key + "' entry");
            }
            copy.add(value.trim());
        }
        return List.copyOf(copy);
    }

    /** Depth tiers reuse the ore catalog's region ids. */
    private static final class OreTier {
        private static final Set<String> IDS = Set.of("upper", "middle", "chaos");
        static boolean isValid(String tier) {
            return tier != null && IDS.contains(tier);
        }
    }

    private static List<String> describeFailures(Completeness completeness) {
        List<String> lines = new ArrayList<>();
        if (!completeness.unavailableItems().isEmpty()) {
            lines.add("unavailable items: " + completeness.unavailableItems());
        }
        if (!completeness.unknownMiningBlocks().isEmpty()) {
            lines.add("mining sources not in the ore catalog: " + completeness.unknownMiningBlocks());
        }
        if (!completeness.miningDropMismatches().isEmpty()) {
            lines.add("mining sources whose ore drops do not contain the material: "
                    + completeness.miningDropMismatches());
        }
        if (!completeness.unknownRewardTiers().isEmpty()) {
            lines.add("reward sources without a configured tier: " + completeness.unknownRewardTiers());
        }
        if (!completeness.rewardItemMismatches().isEmpty()) {
            lines.add("reward sources whose tier yields a different item: "
                    + completeness.rewardItemMismatches());
        }
        if (!completeness.unavailableSourceEntities().isEmpty()) {
            lines.add("pve sources with unregistered entities: "
                    + completeness.unavailableSourceEntities());
        }
        return lines;
    }

    public static final class ProgressionSnapshot {
        private final Map<ResourceLocation, Material> materials;
        private final List<Upgrade> upgrades;
        private final List<BlockedNode> blocked;
        private final List<String> errors;
        private final String fingerprint;

        ProgressionSnapshot(Map<ResourceLocation, Material> materials, List<Upgrade> upgrades,
                            List<BlockedNode> blocked, List<String> errors) {
            this.materials = Map.copyOf(materials);
            this.upgrades = List.copyOf(upgrades);
            this.blocked = List.copyOf(blocked);
            this.errors = List.copyOf(errors);
            this.fingerprint = fingerprint(materials, upgrades, blocked);
        }

        private static ProgressionSnapshot empty() {
            return new ProgressionSnapshot(Map.of(), List.of(), List.of(),
                    List.of("no progression graph resource loaded"));
        }

        public Map<ResourceLocation, Material> materials() { return materials; }
        public List<Upgrade> upgrades() { return upgrades; }
        public List<BlockedNode> blocked() { return blocked; }
        public List<String> errors() { return errors; }
        public String fingerprint() { return fingerprint; }

        public Optional<Material> material(ResourceLocation item) {
            return Optional.ofNullable(materials.get(item));
        }

        public boolean itemAvailable(ResourceLocation item) {
            return item != null && BuiltInRegistries.ITEM.getOptional(item).isPresent();
        }

        /** Upgrades serving a tier whose output item exists in this runtime. */
        public List<Upgrade> reachableUpgrades(String tier) {
            return upgrades.stream()
                    .filter(upgrade -> upgrade.tier().equals(tier) && itemAvailable(upgrade.output()))
                    .toList();
        }

        /** Cross-checks the graph against registries, the ore catalog, and
         * the mine reward table. Recipe resolution is verified separately by
         * {@link #verifyRecipes} at runtime. */
        public Completeness completeness() {
            return completeness(
                    item -> BuiltInRegistries.ITEM.getOptional(item).isPresent(),
                    OreCatalog.active().entries(),
                    MineRewardCatalog::forTier,
                    entity -> BuiltInRegistries.ENTITY_TYPE.getOptional(entity).isPresent());
        }

        /** Injectable variant used by tests and the audit harness. */
        Completeness completeness(Predicate<ResourceLocation> itemAvailable,
                                  Map<ResourceLocation, OreCatalog.Entry> oreEntries,
                                  java.util.function.Function<String, MineRewardCatalog.Reward> rewardForTier,
                                  Predicate<ResourceLocation> entityAvailable) {
            TreeSet<String> unavailableItems = new TreeSet<>();
            TreeSet<String> unknownMiningBlocks = new TreeSet<>();
            TreeSet<String> miningDropMismatches = new TreeSet<>();
            TreeSet<String> unknownRewardTiers = new TreeSet<>();
            TreeSet<String> rewardItemMismatches = new TreeSet<>();
            TreeSet<String> unavailableSourceEntities = new TreeSet<>();
            for (Material material : materials.values()) {
                if (material.allItems().stream().noneMatch(itemAvailable)) {
                    unavailableItems.add(material.item().toString());
                }
                for (Source source : material.sources()) {
                    switch (source.kind()) {
                        case MINING -> {
                            ResourceLocation blockId = location(source.ref().substring(6));
                            OreCatalog.Entry entry = blockId == null ? null
                                    : oreEntries.get(blockId);
                            if (entry == null) {
                                unknownMiningBlocks.add(blockId + " for " + material.item());
                            } else if (material.allItems().stream()
                                    .noneMatch(drop -> entry.nativeDropItems().contains(drop))) {
                                miningDropMismatches.add(blockId + " does not drop " + material.item());
                            }
                        }
                        case PVE -> {
                            if (source.ref().startsWith("reward:")) {
                                String tier = source.ref().substring(7);
                                MineRewardCatalog.Reward reward = rewardForTier.apply(tier);
                                if (reward == null) {
                                    // A null tier means either the tier is not configured at all or
                                    // the reward was skipped at load because its item is unregistered
                                    // in this runtime. Only flag it unexplained when the material
                                    // itself resolves; a missing item is already reported in
                                    // unavailableItems and explains the absent reward.
                                    if (itemAvailable.test(material.item())) {
                                        unknownRewardTiers.add(tier + " for " + material.item());
                                    }
                                } else if (!reward.itemId().equals(material.item())) {
                                    rewardItemMismatches.add(tier + " yields " + reward.itemId()
                                            + ", not " + material.item());
                                }
                            } else if (!entityAvailable.test(location(source.ref().substring(7)))) {
                                unavailableSourceEntities.add(source.ref().substring(7));
                            }
                        }
                        case EXPLORATION -> { /* descriptive; evidence is audited in docs */ }
                        default -> { }
                    }
                }
            }
            for (Upgrade upgrade : upgrades) {
                if (!itemAvailable.test(upgrade.output())) {
                    unavailableItems.add(upgrade.output() + " (upgrade " + upgrade.id() + ")");
                }
            }
            return new Completeness(materials.size(), upgrades.size(), blocked.size(),
                    List.copyOf(unavailableItems), List.copyOf(unknownMiningBlocks),
                    List.copyOf(miningDropMismatches), List.copyOf(unknownRewardTiers),
                    List.copyOf(rewardItemMismatches), List.copyOf(unavailableSourceEntities),
                    errors);
        }

        public String adminSummary() {
            return "materials=" + materials.size() + ", upgrades=" + upgrades.size()
                    + ", blocked=" + blocked.size() + ", fingerprint=" + fingerprint.substring(0, 12);
        }

        private static String fingerprint(Map<ResourceLocation, Material> materials,
                                          List<Upgrade> upgrades, List<BlockedNode> blocked) {
            StringBuilder canonical = new StringBuilder();
            materials.values().stream()
                    .sorted(Comparator.comparing(material -> material.item().toString()))
                    .forEach(material -> canonical.append(material.item()).append('|')
                            .append(material.tier()).append('|').append(material.boundedYield())
                            .append('|').append(material.sources()).append('|')
                            .append(material.sinks()).append('\n'));
            upgrades.stream().sorted(Comparator.comparing(Upgrade::id))
                    .forEach(upgrade -> canonical.append(upgrade.id()).append('|')
                            .append(upgrade.output()).append('|').append(upgrade.recipe())
                            .append('|').append(upgrade.solves()).append('|')
                            .append(upgrade.doesNotSolve()).append('\n'));
            blocked.stream().sorted(Comparator.comparing(node -> node.item().toString()))
                    .forEach(node -> canonical.append(node.item()).append('|')
                            .append(node.neededFor()).append('|').append(node.reason()).append('\n'));
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
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
}
