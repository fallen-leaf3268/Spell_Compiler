package com.example.spell_compiler.formula;

import java.math.BigDecimal;

public final class FormulaNumberFormatting {
    private FormulaNumberFormatting() {}

    public static String wideInteger(double value) {
        if (!Double.isFinite(value) || Math.floor(value) != value
                || value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) return null;
        return BigDecimal.valueOf(value).toBigInteger().toString();
    }
}
