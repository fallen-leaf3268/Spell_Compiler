package com.example.spell_compiler.formula;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class FormulaNumberFormatting {
    private FormulaNumberFormatting() {}

    public static String format(double value) {
        if (!Double.isFinite(value)) return Double.toString(value);
        return decimal(BigDecimal.valueOf(value));
    }

    public static String format(float value) {
        if (!Float.isFinite(value)) return Float.toString(value);
        if (value == Math.rint(value)) return decimal(BigDecimal.valueOf((double) value));
        return decimal(new BigDecimal(Float.toString(value)));
    }

    public static String format(Number value) {
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte
                || value instanceof java.math.BigInteger) return value.toString();
        if (value instanceof BigDecimal decimal) return decimal(decimal);
        return value instanceof Float ? format(value.floatValue()) : format(value.doubleValue());
    }

    public static String tooltip(double value, int ignoredPrecision) {
        return Double.isFinite(value) && value != Math.rint(value) && (double) (float) value == value
                ? format((float) value) : format(value);
    }

    public static String timeFromTicks(float ticks, int ignoredPrecision) {
        double seconds = ticks / 20d;
        return seconds > 60 ? format(seconds / 60) + "m" : format(seconds) + "s";
    }

    public static String magnitude(Number value) {
        String formatted = format(value);
        return formatted.startsWith("-") ? formatted.substring(1) : formatted;
    }

    private static String decimal(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public static String wideInteger(double value) {
        if (!Double.isFinite(value) || Math.floor(value) != value
                || value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) return null;
        return BigDecimal.valueOf(value).toBigInteger().toString();
    }
}
