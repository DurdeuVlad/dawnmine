package com.dwurdy.dawnmine.encounter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Bounded, data-independent level presentation and scaling for leveled mine
 * encounters (M9a, hardened by M12b). Levels 1-2 keep unmodified native
 * stats so the easiest fight is native; deeper bands add capped health,
 * attack damage, and movement speed bonuses, with a colored nametag per band
 * so the level reads at a glance. The native infected-ore summon path never
 * passes through {@link #apply}, so it receives no level tag, custom name,
 * or attribute modification.
 */
public final class MobLevelScaling {
    public static final String LEVEL_TAG = "dawnmineLevel";
    public static final ResourceLocation HEALTH_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("dawnmine", "level_health");
    public static final ResourceLocation DAMAGE_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("dawnmine", "level_damage");
    public static final ResourceLocation SPEED_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("dawnmine", "level_speed");

    /** M12b: a real difficulty ramp, not a soft cap - health/damage/speed all scale together. */
    public static final double HEALTH_BAND_3_TO_5 = 0.25;
    public static final double HEALTH_BAND_6_TO_8 = 0.60;
    public static final double HEALTH_BAND_9_PLUS = 1.20;
    public static final double DAMAGE_BAND_3_TO_5 = 0.20;
    public static final double DAMAGE_BAND_6_TO_8 = 0.50;
    public static final double DAMAGE_BAND_9_PLUS = 1.00;
    public static final double SPEED_BAND_3_TO_5 = 0.10;
    public static final double SPEED_BAND_6_TO_8 = 0.25;
    public static final double SPEED_BAND_9_PLUS = 0.40;

    /** M14b: band ids an admin can target with {@code /dawnmine config difficulty}, in the same
     * 1-2 / 3-5 / 6-8 / 9+ shape the hardcoded bonuses above always had - the override just
     * replaces the bonus for whichever band a level falls into. */
    public static final String[] BAND_IDS = {"1-2", "3-5", "6-8", "9+"};

    private MobLevelScaling() { }

    private static String bandIdFor(int level) {
        if (level <= 2) return BAND_IDS[0];
        if (level <= 5) return BAND_IDS[1];
        if (level <= 8) return BAND_IDS[2];
        return BAND_IDS[3];
    }

    /**
     * Writes the {@code dawnmineLevel} tag, shows a colored {@code "Lv. <N> <Name>"} nametag,
     * and applies the bounded health/damage/speed bonus for the composition level. No-scaling
     * levels (1-2) get the nametag but no attribute write. Idempotent: re-applying to an
     * already-leveled mob (e.g. on chunk reload) never stacks a modifier twice.
     */
    public static void apply(Mob mob, int level) {
        mob.getPersistentData().putInt(LEVEL_TAG, level);
        mob.setCustomName(Component.literal("Lv. " + level + " "
                        + mob.getType().getDescription().getString())
                .setStyle(Style.EMPTY.withColor(colorFor(level))));
        mob.setCustomNameVisible(true);
        addModifier(mob, Attributes.MAX_HEALTH, HEALTH_MODIFIER_ID, healthBonusMultiplier(level));
        addModifier(mob, Attributes.ATTACK_DAMAGE, DAMAGE_MODIFIER_ID, damageBonusMultiplier(level));
        addModifier(mob, Attributes.MOVEMENT_SPEED, SPEED_MODIFIER_ID, speedBonusMultiplier(level));
        mob.setHealth(mob.getMaxHealth());
    }

    private static void addModifier(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                     ResourceLocation id, double bonus) {
        if (bonus <= 0.0) return;
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null || instance.hasModifier(id)) return;
        instance.addPermanentModifier(new AttributeModifier(id, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    /** Nametag color per level band: gray (easy) -> yellow -> gold -> red (hardest). */
    public static ChatFormatting colorFor(int level) {
        if (level <= 2) return ChatFormatting.GRAY;
        if (level <= 5) return ChatFormatting.YELLOW;
        if (level <= 8) return ChatFormatting.GOLD;
        return ChatFormatting.RED;
    }

    /** Fractional MAX_HEALTH bonus for a composition level, in [0, 1.20] unless overridden. */
    public static double healthBonusMultiplier(int level) {
        var override = com.dwurdy.dawnmine.config.DawnmineOverrides.difficulty(bandIdFor(level));
        if (override.isPresent() && override.get().health() != null) {
            return override.get().health();
        }
        if (level <= 2) return 0.0;
        if (level <= 5) return HEALTH_BAND_3_TO_5;
        if (level <= 8) return HEALTH_BAND_6_TO_8;
        return HEALTH_BAND_9_PLUS;
    }

    /** Fractional ATTACK_DAMAGE bonus for a composition level, in [0, 1.00] unless overridden. */
    public static double damageBonusMultiplier(int level) {
        var override = com.dwurdy.dawnmine.config.DawnmineOverrides.difficulty(bandIdFor(level));
        if (override.isPresent() && override.get().damage() != null) {
            return override.get().damage();
        }
        if (level <= 2) return 0.0;
        if (level <= 5) return DAMAGE_BAND_3_TO_5;
        if (level <= 8) return DAMAGE_BAND_6_TO_8;
        return DAMAGE_BAND_9_PLUS;
    }

    /** Fractional MOVEMENT_SPEED bonus for a composition level, in [0, 0.40] unless overridden. */
    public static double speedBonusMultiplier(int level) {
        var override = com.dwurdy.dawnmine.config.DawnmineOverrides.difficulty(bandIdFor(level));
        if (override.isPresent() && override.get().speed() != null) {
            return override.get().speed();
        }
        if (level <= 2) return 0.0;
        if (level <= 5) return SPEED_BAND_3_TO_5;
        if (level <= 8) return SPEED_BAND_6_TO_8;
        return SPEED_BAND_9_PLUS;
    }
}
