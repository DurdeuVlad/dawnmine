package com.rusticcraft.publicmines.command;

import com.rusticcraft.publicmines.config.PublicMineConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Registry of config keys exposed through {@code /publicmine config}.
 *
 * <p>Each key declares its parser, hard bounds, documentation, and when a
 * change takes effect. Only listed keys are writable; unknown keys are
 * rejected with the valid list.
 */
public final class ConfigKeys {
    private ConfigKeys() {}

    /** When a config change takes effect. */
    public enum Timing {
        IMMEDIATE("immediately"),
        NEXT_RUN("when the next run is initialized"),
        NEXT_RESET("at the next scheduled reset"),
        NEXT_RESTART("after a server restart");

        public final String phrase;
        Timing(String phrase) { this.phrase = phrase; }
    }

    public abstract static class Key {
        public final String name;
        public final String doc;
        public final String validRange;
        public final String example;
        public final Timing timing;

        Key(String name, String doc, String validRange, String example, Timing timing) {
            this.name = name;
            this.doc = doc;
            this.validRange = validRange;
            this.example = example;
            this.timing = timing;
        }

        public abstract String get();
        /** Returns empty on success, or the friendly rejection reason. */
        public abstract Optional<String> set(String raw);
    }

    private static Key intKey(String name, String doc, int min, int max, Timing timing,
            ModConfigSpec.ConfigValue<Integer> backing, String example) {
        return new Key(name, doc, "an integer between " + min + " and " + max, example, timing) {
            @Override public String get() { return String.valueOf(backing.get()); }
            @Override public Optional<String> set(String raw) {
                final int value;
                try {
                    value = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    return Optional.of("'" + raw + "' is not a number");
                }
                if (value < min || value > max) {
                    return Optional.of(value + " is outside the allowed range " + min + "-" + max);
                }
                backing.set(value);
                backing.save();
                return Optional.empty();
            }
        };
    }

    private static Key boolKey(String name, String doc, Timing timing,
            ModConfigSpec.ConfigValue<Boolean> backing, String example) {
        return new Key(name, doc, "true or false", example, timing) {
            @Override public String get() { return String.valueOf(backing.get()); }
            @Override public Optional<String> set(String raw) {
                String v = raw.trim().toLowerCase();
                if (!v.equals("true") && !v.equals("false")) {
                    return Optional.of("'" + raw + "' must be true or false");
                }
                backing.set(Boolean.parseBoolean(v));
                backing.save();
                return Optional.empty();
            }
        };
    }

    private static Key timeZoneKey(String name, String doc, Timing timing,
            ModConfigSpec.ConfigValue<String> backing, String example) {
        return new Key(name, doc, "an IANA time zone like Europe/Bucharest, or 'server'", example, timing) {
            @Override public String get() { return backing.get(); }
            @Override public Optional<String> set(String raw) {
                String v = raw.trim();
                if (!PublicMineConfig.isValidTimeZone(v)) {
                    return Optional.of("'" + raw + "' is not a known time zone");
                }
                backing.set(v);
                backing.save();
                return Optional.empty();
            }
        };
    }

    public static final List<Key> ALL = List.of(
            intKey("resetHour", "local hour of the daily reset", 0, 23,
                    Timing.NEXT_RESET, PublicMineConfig.resetHourValue(), "6"),
            intKey("resetMinute", "local minute of the daily reset", 0, 59,
                    Timing.NEXT_RESET, PublicMineConfig.resetMinuteValue(), "0"),
            timeZoneKey("resetTimeZone", "time zone used for the reset clock",
                    Timing.NEXT_RESET, PublicMineConfig.resetTimeZoneValue(), "Europe/Bucharest"),
            boolKey("netherrackEnabled", "optional ultra-rare Netherrack placement (off by default)",
                    Timing.NEXT_RUN, PublicMineConfig.netherrackEnabledValue(), "false"),
            intKey("netherrackColumnInterval",
                    "one Netherrack attempt per N eligible 16x16 chunk columns (larger = rarer)",
                    PublicMineConfig.NETHERRACK_INTERVAL_MIN, PublicMineConfig.NETHERRACK_INTERVAL_MAX,
                    Timing.NEXT_RUN, PublicMineConfig.netherrackColumnIntervalValue(), "512"),
            intKey("mineRegionRadiusChunks", "radius of the active mine region in chunk columns",
                    PublicMineConfig.REGION_RADIUS_MIN, PublicMineConfig.REGION_RADIUS_MAX,
                    Timing.NEXT_RUN, PublicMineConfig.mineRegionRadiusChunksValue(), "64"));

    public static Optional<Key> find(String name) {
        for (Key key : ALL) {
            if (key.name.equalsIgnoreCase(name)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (Key key : ALL) {
            names.add(key.name);
        }
        return names;
    }
}
