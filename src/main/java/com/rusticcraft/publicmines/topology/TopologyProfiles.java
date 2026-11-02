package com.rusticcraft.publicmines.topology;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/**
 * Server resource listener that loads topology profiles from
 * {@code data/publicmines/topology_profiles/*.json}. Region layout is
 * data-driven: a datapack can tune region counts and sizes, and a malformed
 * file is logged and skipped rather than silently degrading the mine.
 *
 * <p>The active profile for planning is {@link #activeProfileId()}
 * ({@code "standard"} by default). If nothing valid loaded - which should not
 * happen while the mod's own bundled file parses - the compiled-in
 * {@link TopologyProfile#builtin()} copy of that file is used as a
 * last-resort guard and a warning is logged.
 */
public class TopologyProfiles extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String DIRECTORY = "topology_profiles";
    private static final String DEFAULT_PROFILE = "standard";

    private static volatile Map<String, TopologyProfile> profiles = Map.of();

    public TopologyProfiles() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects,
                         ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, TopologyProfile> loaded = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            try {
                TopologyProfile profile = TopologyProfile.fromJson(entry.getValue().getAsJsonObject());
                loaded.put(profile.id(), profile);
            } catch (RuntimeException e) {
                LOGGER.error("[publicmines] Skipping invalid topology profile {}: {}",
                        entry.getKey(), e.getMessage());
            }
        }
        profiles = Map.copyOf(loaded);
        if (profiles.isEmpty()) {
            LOGGER.warn("[publicmines] No valid topology profiles loaded; using the built-in "
                    + DEFAULT_PROFILE + " profile");
        } else {
            LOGGER.info("[publicmines] Loaded {} topology profile(s): {}",
                    profiles.size(), String.join(", ", profiles.keySet()));
        }
    }

    /** The profile with this id, or null when it was not loaded. */
    public static TopologyProfile profile(String id) {
        return profiles.get(id);
    }

    public static String activeProfileId() {
        return selectActiveProfileId(profiles.keySet());
    }

    static String selectActiveProfileId(Set<String> profileIds) {
        if (profileIds.isEmpty() || profileIds.contains(DEFAULT_PROFILE)) {
            return DEFAULT_PROFILE;
        }
        return profileIds.stream().sorted().findFirst().orElse(DEFAULT_PROFILE);
    }

    /** The profile used for new run planning: the loaded {@code standard}
     * profile, or the built-in copy only when nothing valid loaded. */
    public static TopologyProfile active() {
        TopologyProfile loaded = profiles.get(activeProfileId());
        return loaded != null ? loaded : TopologyProfile.builtin();
    }
}
