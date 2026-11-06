package com.dwurdy.dawnmine.encounter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/** Validated, pack-overridable combat bonus rates and their progression purpose. */
public final class MineRewardCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "mine_rewards";
    private static volatile Map<String, Reward> rewards = Map.of();

    public record Reward(String tier, ResourceLocation itemId, int chanceBasisPoints,
                         int minCount, int maxCount, String purpose, String sourceAndSink) {
        public Reward {
            if (tier == null || tier.isBlank() || itemId == null || chanceBasisPoints < 0
                    || chanceBasisPoints > 10_000 || minCount < 1 || maxCount < minCount
                    || maxCount > 16 || purpose == null || purpose.isBlank()
                    || sourceAndSink == null || sourceAndSink.isBlank()) {
                throw new IllegalArgumentException("invalid mine reward");
            }
        }
        public double chancePercent() { return chanceBasisPoints / 100.0; }
    }

    public MineRewardCatalog() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, Reward> loaded = new LinkedHashMap<>();
        Set<String> conflictedTiers = new HashSet<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            try {
                Map<String, Reward> parsed = parse(entry.getValue().getAsJsonObject());
                for (Reward reward : parsed.values()) {
                    if (conflictedTiers.contains(reward.tier())) continue;
                    var item = BuiltInRegistries.ITEM.getOptional(reward.itemId()).orElse(null);
                    if (item == null || reward.maxCount() > item.getDefaultInstance().getMaxStackSize()) {
                        LOGGER.error("[dawnmine] Mine reward item {} for tier '{}' is unavailable or cannot hold the configured stack size; skipping it.",
                                reward.itemId(), reward.tier());
                        continue;
                    }
                    if (loaded.putIfAbsent(reward.tier(), reward) != null) {
                        loaded.remove(reward.tier());
                        conflictedTiers.add(reward.tier());
                        throw new JsonParseException("duplicate reward tier '" + reward.tier() + "'");
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.error("[dawnmine] Skipping invalid mine reward catalog {}: {}",
                        entry.getKey(), e.getMessage());
            }
        }
        rewards = Map.copyOf(loaded);
        LOGGER.info("[dawnmine] Loaded mine rewards: {}", adminSummary());
    }

    public static Reward forTier(String tier) { return rewards.get(tier); }

    public static Map<String, Reward> all() { return rewards; }

    public static String adminSummary() {
        if (rewards.isEmpty()) return "none (combat bonuses disabled)";
        return rewards.values().stream()
                .sorted(java.util.Comparator.comparing(Reward::tier))
                .map(reward -> reward.tier() + "=" + reward.itemId() + " x"
                        + reward.minCount() + "-" + reward.maxCount() + " @"
                        + reward.chancePercent() + "% [" + reward.purpose() + "; "
                        + reward.sourceAndSink() + "]")
                .collect(java.util.stream.Collectors.joining("; "));
    }

    static Map<String, Reward> parse(JsonObject json) {
        int schema = integer(json, "schema", 1, 1);
        if (schema != 1) throw new JsonParseException("unsupported reward schema " + schema);
        JsonArray entries = requiredArray(json, "rewards");
        if (entries.isEmpty() || entries.size() > 16) {
            throw new JsonParseException("catalog must have between 1 and 16 rewards");
        }
        Map<String, Reward> parsed = new LinkedHashMap<>();
        for (JsonElement element : entries) {
            Reward reward = parseReward(element.getAsJsonObject());
            if (parsed.putIfAbsent(reward.tier(), reward) != null) {
                throw new JsonParseException("duplicate reward tier '" + reward.tier() + "'");
            }
        }
        return Map.copyOf(parsed);
    }

    static Reward parseReward(JsonObject json) {
        String tier = requiredString(json, "tier");
        ResourceLocation itemId = ResourceLocation.tryParse(requiredString(json, "item"));
        if (itemId == null) throw new JsonParseException("invalid item id for tier " + tier);
        return new Reward(tier, itemId,
                integer(json, "chanceBasisPoints", 0, 10_000),
                integer(json, "minCount", 1, 16), integer(json, "maxCount", 1, 16),
                requiredString(json, "purpose"), requiredString(json, "sourceAndSink"));
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
}
