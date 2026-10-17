package com.example.spell_compiler.formula;

public final class FormulaSiteValues {
    private FormulaSiteValues() {}

    public static int healingInterval() { return 10; }

    public static int summonDuration(Object caster) { return 12000; }

    public static int fireTicks() { return 60; }

    public static float thunderstormDamage(@FormulaInput("effect_level") int amplifierForLevel,
                                           @FormulaInput("spell_power") float spellpower,
                                           @FormulaInput("school_spell_power") double schoolSpellpower,
                                           @FormulaInput("power_multiplier") float powerMultiplier,
                                           Object spell, Object caster) {
        return (amplifierForLevel - 7) * FormulaRuntimeHooks.applyEntityPower(
                (float) ((double) (powerMultiplier * spellpower) * schoolSpellpower), spell, caster) + 7;
    }

    public static int strikeInterval() { return 40; }

    public static int horizontalRange() { return 20; }

    public static double verticalRange() { return 12; }

    public static float hasteRadius() { return 3; }

    public static float sacrificeHealthBonus(@FormulaInput("summon_health") float summonHealth) {
        return summonHealth * 0.5f;
    }
}
