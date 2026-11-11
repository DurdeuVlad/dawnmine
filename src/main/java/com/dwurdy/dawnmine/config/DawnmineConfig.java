package com.dwurdy.dawnmine.config;

import com.dwurdy.dawnmine.Dawnmine;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Authoritative NeoForge SERVER configuration for Dawnmine.
 *
 * <p>Values are stored in {@code <world>/serverconfig/dawnmine-server.toml}
 * on the dedicated server. Every key is also writable in game through
 * {@code /dawnmine config set}, which applies the same bounds.
 */
public final class DawnmineConfig {
    public static final ModConfigSpec SPEC;

    /** Hard safety bounds for the optional Netherrack setting. See Decision.md. */
    public static final int NETHERRACK_INTERVAL_MIN = 256;
    public static final int NETHERRACK_INTERVAL_MAX = 65536;
    public static final int NETHERRACK_CLUSTER_MAX_BLOCKS = 3;

    /** Bounds for the active mine region, in 16x16 chunk columns. */
    public static final int REGION_RADIUS_MIN = 16;
    public static final int REGION_RADIUS_MAX = 96; // the cave layer planner is sized for up to 96 chunks

    /** Bounds for advance reset warnings, in minutes before the reset. */
    public static final int RESET_WARNING_MIN_MINUTES = 1;
    public static final int RESET_WARNING_MAX_MINUTES = 720;
    public static final int RESET_WARNING_MAX_COUNT = 8;

    private static final ModConfigSpec.ConfigValue<Integer> RESET_HOUR;
    private static final ModConfigSpec.ConfigValue<Integer> RESET_MINUTE;
    private static final ModConfigSpec.ConfigValue<String> RESET_TIME_ZONE;
    private static final ModConfigSpec.ConfigValue<List<? extends Integer>> RESET_WARNING_MINUTES;
    private static final ModConfigSpec.ConfigValue<Boolean> NETHERRACK_ENABLED;
    private static final ModConfigSpec.ConfigValue<Integer> NETHERRACK_COLUMN_INTERVAL;
    private static final ModConfigSpec.ConfigValue<Integer> MINE_REGION_RADIUS_CHUNKS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> STRUCTURE_SITES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                        "Dawnmine server settings.",
                        "These values can also be inspected and changed in game with",
                        "'/dawnmine config get' and '/dawnmine config set' (operator level 2+).")
                .push("lifecycle");

        RESET_HOUR = builder.comment(
                        "Local hour (0-23) when the shared mine resets each day.",
                        "Default 6 = 06:00. The reset scheduler itself arrives in M7;",
                        "this value is recorded now and shown to operators in '/dawnmine status'.")
                .defineInRange("resetHour", 6, 0, 23);

        RESET_MINUTE = builder.comment("Local minute (0-59) of the daily reset, combined with resetHour.")
                .defineInRange("resetMinute", 0, 0, 59);

        RESET_TIME_ZONE = builder.comment(
                        "IANA time zone used for the daily reset clock, for example 'Europe/Bucharest'.",
                        "The special value 'server' uses the host machine's default zone.")
                .define("resetTimeZone", "server", o -> o instanceof String s && isValidTimeZone(s));

        RESET_WARNING_MINUTES = builder.comment(
                        "Minutes before the daily reset when players inside the mine are warned.",
                        "Each entry must be between " + RESET_WARNING_MIN_MINUTES + " and "
                                + RESET_WARNING_MAX_MINUTES + "; at most " + RESET_WARNING_MAX_COUNT
                                + " entries are used. An empty list disables warnings.",
                        "Warnings only reach players inside the mine; staff see reset diagnostics",
                        "in '/dawnmine status' and the server log.")
                .defineList("resetWarningMinutes", List.of(15, 5, 1), () -> 5,
                        o -> o instanceof Integer i
                                && i >= RESET_WARNING_MIN_MINUTES && i <= RESET_WARNING_MAX_MINUTES);

        NETHERRACK_ENABLED = builder.comment(
                        "Optional Netherrack placement inside the mine. Default OFF.",
                        "When enabled, generation may place at most one attempt per",
                        "netherrackColumnInterval eligible 16x16 chunk columns in the deepest",
                        "configured region only, with at most one cluster of three blocks per",
                        "column. Netherrack placement is applied by M2b; no config value can make",
                        "Netherrack more common or shallower than the hard bounds.")
                .define("netherrackEnabled", false);

        NETHERRACK_COLUMN_INTERVAL = builder.comment(
                        "Eligible-column interval for optional Chaos-region Netherrack placement.",
                        "Hard range " + NETHERRACK_INTERVAL_MIN + "-" + NETHERRACK_INTERVAL_MAX
                                + "; larger values make Netherrack rarer.",
                        "Only meaningful when netherrackEnabled is true.")
                .defineInRange("netherrackColumnInterval", NETHERRACK_INTERVAL_MIN,
                        NETHERRACK_INTERVAL_MIN, NETHERRACK_INTERVAL_MAX);

        MINE_REGION_RADIUS_CHUNKS = builder.comment(
                        "Radius, in 16x16 chunk columns, of the active mine region centred on",
                        "the arrival point of the mine dimension. Range "
                                + REGION_RADIUS_MIN + "-" + REGION_RADIUS_MAX + ".",
                        "Must match shell_radius_chunks in data/dawnmine/dimension/mine.json.",
                        "A mismatch fails mine setup closed; changing the radius after chunks exist",
                        "requires a fresh mine dimension so an old shell cannot remain inside it.",
                        "Recorded as the cleanup ownership boundary for M2 generation.",
                        "Changes apply when the next run is initialized; they never resize",
                        "the currently active run.")
                .defineInRange("mineRegionRadiusChunks", 64, REGION_RADIUS_MIN, REGION_RADIUS_MAX);

        STRUCTURE_SITES = builder.comment(
                        "Optional structure integration: 'modid:structure_id=N' entries",
                        "placed deterministically at run-seeded sites after preparation",
                        "completes. Structures for mods that are not installed are",
                        "skipped with a log line, so pack owners can add entries for",
                        "any mod structure without code changes.",
                        "Every placement must fit inside the mine shell, stay below y64,",
                        "and must not overlap the hub reserve, node rooms, safe zones,",
                        "checkpoint stations, or another placed structure.")
                .defineList("structureSites",
                        List.of("minecraft:mineshaft=5",
                                "valhelsia_structures:deep_spawner_room=2",
                                "valhelsia_structures:spawner_room=1",
                                "bettermineshafts:mineshaft=2",
                                "betterdungeons:zombie_dungeon=1",
                                "betterdungeons:skeleton_dungeon=1",
                                "betterdungeons:spider_dungeon=1",
                                "betterdungeons:small_dungeon=1",
                                "repurposed_structures:bastion_underground=1"),
                        () -> "minecraft:mineshaft=1",
                        o -> o instanceof String s
                                && s.matches("[a-z0-9_.\\-]+:[a-z0-9_/\\-.]+=[0-9]+"));

        builder.pop();
        SPEC = builder.build();
    }

    private DawnmineConfig() {}

    public static boolean isValidTimeZone(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.equalsIgnoreCase("server")) {
            return true;
        }
        try {
            ZoneId.of(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static int resetHour() {
        return RESET_HOUR.get();
    }

    public static int resetMinute() {
        return RESET_MINUTE.get();
    }

    public static String resetTimeZoneName() {
        return RESET_TIME_ZONE.get();
    }

    public static ZoneId resetZoneId() {
        String name = RESET_TIME_ZONE.get();
        if (name.equalsIgnoreCase("server")) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(name);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    /** Validated warning offsets in configured order: in-range entries only,
     * deduplicated, capped at {@link #RESET_WARNING_MAX_COUNT}. An empty list
     * means the operator deliberately disabled warnings. */
    public static List<Integer> resetWarningMinutes() {
        List<Integer> warnings = new ArrayList<>();
        List<? extends Integer> stored = RESET_WARNING_MINUTES.get();
        if (stored == null) {
            return warnings;
        }
        for (Integer value : stored) {
            if (value != null && value >= RESET_WARNING_MIN_MINUTES
                    && value <= RESET_WARNING_MAX_MINUTES && !warnings.contains(value)) {
                warnings.add(value);
                if (warnings.size() >= RESET_WARNING_MAX_COUNT) {
                    break;
                }
            }
        }
        return warnings;
    }

    public static boolean netherrackEnabled() {
        return NETHERRACK_ENABLED.get();
    }

    public static int netherrackColumnInterval() {
        return NETHERRACK_COLUMN_INTERVAL.get();
    }

    public static int mineRegionRadiusChunks() {
        return MINE_REGION_RADIUS_CHUNKS.get();
    }

    public static ModConfigSpec.ConfigValue<Integer> resetHourValue() {
        return RESET_HOUR;
    }

    public static ModConfigSpec.ConfigValue<Integer> resetMinuteValue() {
        return RESET_MINUTE;
    }

    public static ModConfigSpec.ConfigValue<String> resetTimeZoneValue() {
        return RESET_TIME_ZONE;
    }

    public static ModConfigSpec.ConfigValue<List<? extends Integer>> resetWarningMinutesValue() {
        return RESET_WARNING_MINUTES;
    }

    public static ModConfigSpec.ConfigValue<Boolean> netherrackEnabledValue() {
        return NETHERRACK_ENABLED;
    }

    public static ModConfigSpec.ConfigValue<Integer> netherrackColumnIntervalValue() {
        return NETHERRACK_COLUMN_INTERVAL;
    }

    public static ModConfigSpec.ConfigValue<Integer> mineRegionRadiusChunksValue() {
        return MINE_REGION_RADIUS_CHUNKS;
    }

    /** Configured 'modid:structure_id=N' optional site specs. */
    public static List<String> structureSites() {
        return List.copyOf(STRUCTURE_SITES.get());
    }
}
