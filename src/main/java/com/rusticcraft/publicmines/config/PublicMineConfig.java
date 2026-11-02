package com.rusticcraft.publicmines.config;

import com.rusticcraft.publicmines.PublicMines;
import java.time.ZoneId;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Authoritative NeoForge SERVER configuration for Public Mines.
 *
 * <p>Values are stored in {@code <world>/serverconfig/publicmines-server.toml}
 * on the dedicated server. Every key is also writable in game through
 * {@code /publicmine config set}, which applies the same bounds.
 */
public final class PublicMineConfig {
    public static final ModConfigSpec SPEC;

    /** Hard safety bounds for the optional Netherrack setting. See Decision.md. */
    public static final int NETHERRACK_INTERVAL_MIN = 256;
    public static final int NETHERRACK_INTERVAL_MAX = 65536;
    public static final int NETHERRACK_CLUSTER_MAX_BLOCKS = 3;

    /** Bounds for the active mine region, in 16x16 chunk columns. */
    public static final int REGION_RADIUS_MIN = 16;
    public static final int REGION_RADIUS_MAX = 512;

    private static final ModConfigSpec.ConfigValue<Integer> RESET_HOUR;
    private static final ModConfigSpec.ConfigValue<Integer> RESET_MINUTE;
    private static final ModConfigSpec.ConfigValue<String> RESET_TIME_ZONE;
    private static final ModConfigSpec.ConfigValue<Boolean> NETHERRACK_ENABLED;
    private static final ModConfigSpec.ConfigValue<Integer> NETHERRACK_COLUMN_INTERVAL;
    private static final ModConfigSpec.ConfigValue<Integer> MINE_REGION_RADIUS_CHUNKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                        "Rustic Craft Public Mines server settings.",
                        "These values can also be inspected and changed in game with",
                        "'/publicmine config get' and '/publicmine config set' (operator level 2+).")
                .push("lifecycle");

        RESET_HOUR = builder.comment(
                        "Local hour (0-23) when the shared mine resets each day.",
                        "Default 6 = 06:00. The reset scheduler itself arrives in M7;",
                        "this value is recorded now and shown to operators in '/publicmine status'.")
                .defineInRange("resetHour", 6, 0, 23);

        RESET_MINUTE = builder.comment("Local minute (0-59) of the daily reset, combined with resetHour.")
                .defineInRange("resetMinute", 0, 0, 59);

        RESET_TIME_ZONE = builder.comment(
                        "IANA time zone used for the daily reset clock, for example 'Europe/Bucharest'.",
                        "The special value 'server' uses the host machine's default zone.")
                .define("resetTimeZone", "server", o -> o instanceof String s && isValidTimeZone(s));

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
                        "the arrival point of the public_mine dimension. Range "
                                + REGION_RADIUS_MIN + "-" + REGION_RADIUS_MAX + ".",
                        "Recorded as the cleanup ownership boundary for M2 generation.",
                        "Changes apply when the next run is initialized; they never resize",
                        "the currently active run.")
                .defineInRange("mineRegionRadiusChunks", 64, REGION_RADIUS_MIN, REGION_RADIUS_MAX);

        builder.pop();
        SPEC = builder.build();
    }

    private PublicMineConfig() {}

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

    public static ModConfigSpec.ConfigValue<Boolean> netherrackEnabledValue() {
        return NETHERRACK_ENABLED;
    }

    public static ModConfigSpec.ConfigValue<Integer> netherrackColumnIntervalValue() {
        return NETHERRACK_COLUMN_INTERVAL;
    }

    public static ModConfigSpec.ConfigValue<Integer> mineRegionRadiusChunksValue() {
        return MINE_REGION_RADIUS_CHUNKS;
    }
}
