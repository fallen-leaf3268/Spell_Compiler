package com.example.spell_compiler.formula;

import io.redspace.ironsspellbooks.api.spells.AbstractSpell;

public final class FormulaDirectPowerSafety {
    private FormulaDirectPowerSafety() {}

    public static float equipmentDenominator(float original, Object spell) {
        if (!(spell instanceof AbstractSpell source) || FormulaNativeValues.isEvaluatingOriginal()) return original;
        Object caster = FormulaNativeValues.currentActor();
        var override = FormulaNativeValues.override(spell, caster);
        if (override == null || !override.parameters().containsKey("spellPowerPerLevel")
                && !override.advanced().containsKey("max_level")) return original;
        var configured = override.parameters().get("spellPowerPerLevel");
        long step = configured == null ? SpellFormulaRuntime.originalParameter(source, "spellPowerPerLevel",
                override.spell().parameters().get("spellPowerPerLevel").getAsInt()) : configured.getAsLong();
        try {
            return calculateEquipmentDenominator(source.getMaxLevel(), step);
        } catch (ArithmeticException failure) {
            FormulaNativeValues.report(spell, "equipment_denominator", caster, failure);
            return original;
        }
    }

    static float calculateEquipmentDenominator(int maximum, long step) {
        return Math.subtractExact(Math.addExact(Math.multiplyExact((long) maximum, step), 15L), 12L);
    }
}
