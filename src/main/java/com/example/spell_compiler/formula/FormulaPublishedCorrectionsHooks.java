package com.example.spell_compiler.formula;

import net.minecraft.world.entity.Entity;

import java.util.Set;

public final class FormulaPublishedCorrectionsHooks {
    private static final String RADIUS_DATA = "spell_compiler:acid_orb_radius";
    private static final String RADIUS = "getRadius(ILnet/minecraft/world/entity/LivingEntity;)F";
    private static final String POWER = "getSpellPower(ILnet/minecraft/world/entity/Entity;)F";
    private static final Set<String> POWER_PARAMETERS = Set.of("baseSpellPower", "spellPowerPerLevel", "power_multiplier");

    private FormulaPublishedCorrectionsHooks() {}

    public static void captureAcidRadius(Object spell, Object caster, Object projectile) {
        if (!(projectile instanceof Entity entity)) return;
        if (configuredRadius(spell, caster)) entity.getPersistentData().putBoolean(RADIUS_DATA, true);
        else entity.getPersistentData().remove(RADIUS_DATA);
    }

    public static boolean hasAcidRadius(Object projectile) {
        return projectile instanceof Entity entity && entity.getPersistentData().getBoolean(RADIUS_DATA);
    }

    public static float validAcidRadius(float radius) {
        return Float.isFinite(radius) && radius >= 0 ? radius : 3.5f;
    }

    private static boolean configuredRadius(Object spell, Object caster) {
        if (spell == null || FormulaNativeValues.isEvaluatingOriginal()) return false;
        var override = FormulaNativeValues.override(spell, caster);
        if (override == null) return false;
        boolean radius = override.formulas().containsKey(RADIUS);
        boolean power = override.formulas().containsKey(POWER);
        String owner = spell.getClass().getName();
        if (radius) return FormulaRuntimeDispatcher.isActive(FormulaRuntimeDispatcher.key(owner, RADIUS), caster);
        if (power && !FormulaRuntimeDispatcher.isActive(FormulaRuntimeDispatcher.key(owner, POWER), caster)) return false;
        return power || override.parameters().keySet().stream().anyMatch(POWER_PARAMETERS::contains)
                || override.advanced().containsKey("school");
    }
}
