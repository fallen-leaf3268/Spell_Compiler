package com.example.spell_compiler.formula;

public final class FormulaNumericValues {
    private FormulaNumericValues() {}

    public static double value(@FormulaInput("native_value") double nativeValue,
                               @FormulaInput("level") int level,
                               @FormulaInput("spell_power") double power,
                               @FormulaInput("school_spell_power") double school,
                               @FormulaInput("power_multiplier") double multiplier,
                               Object spell, Object caster) {
        return nativeValue;
    }
}
