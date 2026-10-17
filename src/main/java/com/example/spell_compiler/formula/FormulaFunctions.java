package com.example.spell_compiler.formula;

public final class FormulaFunctions {
    private FormulaFunctions() {}

    public static float requireFinite(float value) {
        if (!Float.isFinite(value)) throw new ArithmeticException("Non-finite formula value");
        return value;
    }

    public static double requireFinite(double value) {
        if (!Double.isFinite(value)) throw new ArithmeticException("Non-finite formula value");
        return value;
    }

    public static int divideExact(int dividend, int divisor) {
        if (dividend == Integer.MIN_VALUE && divisor == -1) throw new ArithmeticException("integer overflow");
        return dividend / divisor;
    }

    public static long divideExact(long dividend, long divisor) {
        if (dividend == Long.MIN_VALUE && divisor == -1) throw new ArithmeticException("long overflow");
        return dividend / divisor;
    }

    public static int toIntExact(float value) {
        return toIntExact((double) value);
    }

    public static int toIntExact(double value) {
        requireFinite(value);
        if (value >= 2147483648.0 || value <= -2147483649.0) throw new ArithmeticException("integer overflow");
        return (int) value;
    }

    public static long toLongExact(float value) {
        return toLongExact((double) value);
    }

    public static long toLongExact(double value) {
        requireFinite(value);
        if (value >= 0x1.0p63 || value < -0x1.0p63) throw new ArithmeticException("long overflow");
        return (long) value;
    }

    public static int roundExact(float value) {
        toIntExact(Math.floor((double) requireFinite(value) + 0.5));
        return Math.round(value);
    }

    public static long roundExact(double value) {
        toLongExact(value);
        return Math.round(value);
    }

    public static int floorToIntExact(float value) {
        return floorToIntExact((double) value);
    }

    public static int floorToIntExact(double value) {
        return toIntExact(Math.floor(requireFinite(value)));
    }

    public static int ceilToIntExact(float value) {
        return ceilToIntExact((double) value);
    }

    public static int ceilToIntExact(double value) {
        return toIntExact(Math.ceil(requireFinite(value)));
    }

    public static int clamp(int value, int minimum, int maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }

    public static long clamp(long value, long minimum, long maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }

    public static float clamp(float value, float minimum, float maximum) {
        return value < minimum ? minimum : Math.min(value, maximum);
    }

    public static double clamp(double value, double minimum, double maximum) {
        return value < minimum ? minimum : Math.min(value, maximum);
    }

    public static int mth_floor(float value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    public static int mth_floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    public static int mth_ceil(float value) {
        int truncated = (int) value;
        return value > truncated ? truncated + 1 : truncated;
    }

    public static int mth_ceil(double value) {
        int truncated = (int) value;
        return value > truncated ? truncated + 1 : truncated;
    }
}
