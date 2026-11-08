package com.dwurdy.dawnmine.encounter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/** Data-driven bounded encounter compositions grouped by topology depth region. */
public final class MineEncounterCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "encounters";
    private static volatile Map<String, Profile> profiles = Map.of();

    public record EntityCount(ResourceLocation entityId, int count) { }
    public record Composition(String id, int weight, String threat, String counterplay,
                              int level, List<EntityCount> entities) {
        public Composition {
            entities = List.copyOf(entities);
        }
        public int population() {
            return entities.stream().mapToInt(EntityCount::count).sum();
        }
    }
    public record Region(String id, int populationCap, List<Composition> compositions) {
        public Region {
            compositions = List.copyOf(compositions);
        }
    }
    public record Profile(String id, int totalPopulationCap, Map<String, Region> regions) {
        public Profile {
            regions = Map.copyOf(regions);
        }
        public Region region(String id) { return regions.get(id); }
    }

    public MineEncounterCatalog() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, Profile> loaded = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            try {
                Profile profile = parse(entry.getValue().getAsJsonObject());
                loaded.put(profile.id(), profile);
            } catch (RuntimeException e) {
                LOGGER.error("[dawnmine] Skipping invalid encounter profile {}: {}",
                        entry.getKey(), e.getMessage());
            }
        }
        profiles = Map.copyOf(loaded);
        LOGGER.info("[dawnmine] Loaded {} encounter profile(s): {}", profiles.size(),
                profiles.isEmpty() ? "none" : String.join(", ", profiles.keySet()));
    }

    public static Profile forProfile(String id) {
        return profiles.get(id);
    }

    static Profile parse(JsonObject json) {
        String id = requiredString(json, "id");
        int totalCap = integer(json, "totalPopulationCap", 1, 128);
        JsonArray regionArray = requiredArray(json, "regions");
        if (regionArray.size() > 32) throw new JsonParseException("profile has more than 32 regions");
        Map<String, Region> regions = new LinkedHashMap<>();
        for (JsonElement element : regionArray) {
            JsonObject regionJson = element.getAsJsonObject();
            String regionId = requiredString(regionJson, "id");
            int regionCap = integer(regionJson, "populationCap", 1, totalCap);
            JsonArray compositionArray = requiredArray(regionJson, "compositions");
            if (compositionArray.size() > 32) {
                throw new JsonParseException("region '" + regionId + "' has more than 32 compositions");
            }
            if (compositionArray.isEmpty()) {
                throw new JsonParseException("region '" + regionId + "' has no compositions");
            }
            List<Composition> compositions = new ArrayList<>();
            for (JsonElement compositionElement : compositionArray) {
                JsonObject compositionJson = compositionElement.getAsJsonObject();
                String compositionId = requiredString(compositionJson, "id");
                if (!compositionId.matches("[a-z0-9_-]+")) {
                    throw new JsonParseException("composition id '" + compositionId
                            + "' must match [a-z0-9_-]+; ':' would corrupt the encounter provenance format");
                }
                int weight = integer(compositionJson, "weight", 1, 1000);
                int level = integer(compositionJson, "level", 1, 16);
                String threat = requiredString(compositionJson, "threat");
                String counterplay = requiredString(compositionJson, "counterplay");
                JsonArray entityArray = requiredArray(compositionJson, "entities");
                if (entityArray.size() > 16) {
                    throw new JsonParseException("composition '" + compositionId + "' has more than 16 entity entries");
                }
                if (entityArray.isEmpty()) {
                    throw new JsonParseException("composition '" + compositionId + "' has no entities");
                }
                List<EntityCount> entities = new ArrayList<>();
                int population = 0;
                for (JsonElement entityElement : entityArray) {
                    JsonObject entityJson = entityElement.getAsJsonObject();
                    ResourceLocation entityId = ResourceLocation.tryParse(requiredString(entityJson, "id"));
                    if (entityId == null) {
                        throw new JsonParseException("composition '" + compositionId + "' has an invalid entity id");
                    }
                    int count = integer(entityJson, "count", 1, 8);
                    population += count;
                    entities.add(new EntityCount(entityId, count));
                }
                if (population > regionCap) {
                    throw new JsonParseException("composition '" + compositionId
                            + "' exceeds region population cap " + regionCap);
                }
                compositions.add(new Composition(compositionId, weight, threat, counterplay,
                        level, entities));
            }
            if (regions.putIfAbsent(regionId, new Region(regionId, regionCap, compositions)) != null) {
                throw new JsonParseException("duplicate region id '" + regionId + "'");
            }
        }
        for (String required : List.of("upper", "middle", "chaos")) {
            if (!regions.containsKey(required)) {
                throw new JsonParseException("profile is missing required region '" + required + "'");
            }
        }
        return new Profile(id, totalCap, regions);
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
