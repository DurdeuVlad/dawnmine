package com.dwurdy.dawnmine.encounter;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Bounded, data-independent level presentation and health scaling for leveled
 * mine encounters (M9a). Levels 1-2 keep unmodified native stats so the
 * easiest fight is native; deeper bands add a capped MAX_HEALTH bonus and
 * nothing else — never damage, never speed. The native infected-ore summon
 * path never passes through {@link #apply}, so it receives no level tag,
 * custom name, or attribute modification.
 */
public final class MobLevelScaling {
    public static final String LEVEL_TAG = "dawnmineLevel";
    public static final ResourceLocation HEALTH_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("dawnmine", "level_health");
    public static final double BONUS_BAND_3_TO_5 = 0.15;
    public static final double BONUS_BAND_6_TO_8 = 0.35;
    public static final double BONUS_BAND_9_PLUS = 0.60;

    private MobLevelScaling() { }

    /**
     * Writes the {@code dawnmineLevel} tag, shows a {@code "Lv. <N> <Name>"}
     * nametag, and applies the bounded health bonus for the composition level.
     * No-scaling levels get the nametag but no attribute write.
     */
    public static void apply(Mob mob, int level) {
        mob.getPersistentData().putInt(LEVEL_TAG, level);
        mob.setCustomName(Component.literal("Lv. " + level + " "
                + mob.getType().getDescription().getString()));
        mob.setCustomNameVisible(true);
        double bonus = healthBonusMultiplier(level);
        if (bonus <= 0.0) return;
        AttributeInstance maxHealth = mob.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null || maxHealth.hasModifier(HEALTH_MODIFIER_ID)) return;
        maxHealth.addPermanentModifier(new AttributeModifier(HEALTH_MODIFIER_ID, bonus,
                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        mob.setHealth(mob.getMaxHealth());
    }

    /**
     * Fractional MAX_HEALTH bonus for a composition level, in [0, 0.60].
     * Levels outside the parsed range are clamped into the extreme band so a
     * hand-edited datapack can never produce an out-of-table multiplier.
     */
    public static double healthBonusMultiplier(int level) {
        if (level <= 2) return 0.0;
        if (level <= 5) return BONUS_BAND_3_TO_5;
        if (level <= 8) return BONUS_BAND_6_TO_8;
        return BONUS_BAND_9_PLUS;
    }
}
