package com.example.spell_compiler.formula;

import io.redspace.ironsspellbooks.entity.spells.AoeEntity;

public final class FormulaDurationSafety {
    private FormulaDurationSafety() {}

    public static int overrideInt(int original, Object spell, String method, int level, Object caster) {
        if (!modified(spell, caster)) return original;
        try {
            Number value = FormulaNativeValues.evaluateNative(spell, method, new Object[]{level, caster});
            return checkedDuration(value, spell, caster).intValue();
        } catch (Throwable failure) {
            return originalDuration(spell, method, level, caster, failure).intValue();
        }
    }

    public static float overrideFloat(float original, Object spell, String method, int level, Object caster) {
        if (!modified(spell, caster)) return original;
        try {
            Number value = FormulaNativeValues.evaluateNative(spell, method, new Object[]{level, caster});
            return checkedDuration(value, spell, caster).floatValue();
        } catch (Throwable failure) {
            return originalDuration(spell, method, level, caster, failure).floatValue();
        }
    }

    public static int validateOverrideInt(int value, Object spell, String method, int level, Object caster) {
        if (!modified(spell, caster)) return value;
        try {
            return checkedDuration(value, spell, caster).intValue();
        } catch (Throwable failure) {
            return originalDuration(spell, method, level, caster, failure).intValue();
        }
    }

    public static float validateOverrideFloat(float value, Object spell, String method, int level, Object caster) {
        if (!modified(spell, caster)) return value;
        try {
            return checkedDuration(value, spell, caster).floatValue();
        } catch (Throwable failure) {
            return originalDuration(spell, method, level, caster, failure).floatValue();
        }
    }

    public static int overridePowerDuration(int original, float power, Object spell, int level, Object caster,
                                            float scale, boolean integralFirst) {
        if (!modified(spell, caster)) return original;
        try {
            return checkedDuration(calculatePowerDuration(power, scale, integralFirst), spell, caster).intValue();
        } catch (Throwable failure) {
            return originalPowerDuration(spell, level, caster, scale, integralFirst, failure);
        }
    }

    public static float overridePowerPreview(float original, float power, Object spell, int level, Object caster,
                                             float scale, boolean integralFirst, String durationMethod) {
        if (!modified(spell, caster)) return original;
        if (durationMethod != null) {
            var invoker = FormulaRuntimeDispatcher.resolve(FormulaRuntimeDispatcher.key(spell.getClass().getName(), durationMethod), caster);
            if (invoker != null) {
                Number configured = FormulaRuntimeDispatcher.invoke(invoker, spell, new Object[]{level, caster});
                if (configured != null) {
                    int duration = validateOverrideInt(configured.intValue(), spell, durationMethod, level, caster);
                    float multiplier = durationMultiplier(spell, caster);
                    return multiplier == 1 ? duration : Math.max(0, Math.round(duration * multiplier));
                }
            }
        }
        try {
            checkedDuration(calculatePowerDuration(power, scale, integralFirst), spell, caster);
            return FormulaFunctions.requireFinite(original);
        } catch (Throwable failure) {
            return originalPowerDuration(spell, level, caster, scale, integralFirst, failure);
        }
    }

    public static int guardAoeDuration(Object created, int duration, Object spell, String method, int level, Object caster) {
        if (!modified(spell, caster) || !(created instanceof AoeEntity area)) return duration;
        try {
            Math.addExact(duration, area.getDelay());
            return duration;
        } catch (ArithmeticException failure) {
            int fallback = originalDuration(spell, method, level, caster, failure).intValue();
            float multiplier = durationMultiplier(spell, caster);
            try {
                int scaled = multiplier == 1 ? fallback : FormulaFunctions.roundExact(fallback * multiplier);
                Math.addExact(scaled, area.getDelay());
                return scaled;
            } catch (ArithmeticException fallbackFailure) {
                FormulaNativeValues.report(spell, method + ":aoe_expiration", caster, fallbackFailure);
                return 0;
            }
        }
    }

    static int calculatePowerDuration(float power, float scale, boolean integralFirst) {
        int duration = integralFirst
                ? Math.multiplyExact(FormulaFunctions.toIntExact(power), FormulaFunctions.toIntExact(scale))
                : FormulaFunctions.toIntExact(FormulaFunctions.requireFinite(power * scale));
        if (duration < 0) throw new ArithmeticException("法术持续时间不能为负数：" + duration);
        return duration;
    }

    private static boolean modified(Object spell, Object caster) {
        return !FormulaNativeValues.isEvaluatingOriginal() && FormulaNativeValues.isModified(spell, caster);
    }

    private static Number checkedDuration(Number value, Object spell, Object caster) {
        if (value == null) throw new ArithmeticException("法术持续时间返回空值");
        double duration = FormulaFunctions.requireFinite(value.doubleValue());
        if (duration < 0) throw new ArithmeticException("法术持续时间不能为负数：" + value);
        FormulaFunctions.toIntExact(duration);
        float multiplier = durationMultiplier(spell, caster);
        if (multiplier != 1) {
            int scaled = FormulaFunctions.roundExact(value.floatValue() * multiplier);
            if (scaled < 0) throw new ArithmeticException("法术持续时间词条计算不能为负数：" + scaled);
        }
        return value;
    }

    private static float durationMultiplier(Object spell, Object caster) {
        return FormulaIntegration.durationMultiplier(spell, caster);
    }

    private static Number originalDuration(Object spell, String method, int level, Object caster, Throwable failure) {
        rethrowFatal(failure);
        FormulaNativeValues.report(spell, method, caster, failure);
        try {
            return checkedDuration(FormulaNativeValues.evaluateOriginal(spell, method, new Object[]{level, caster}), spell, caster);
        } catch (Throwable fallbackFailure) {
            rethrowFatal(fallbackFailure);
            FormulaNativeValues.report(spell, method + ":fallback", caster, fallbackFailure);
            return 0;
        }
    }

    private static int originalPowerDuration(Object spell, int level, Object caster, float scale,
                                             boolean integralFirst, Throwable failure) {
        rethrowFatal(failure);
        FormulaNativeValues.report(spell, "inline_effect_duration", caster, failure);
        try {
            float original = FormulaRuntimeHooks.applyPower(FormulaNativeValues.originalPower(spell, level, caster), spell, caster);
            return checkedDuration(calculatePowerDuration(original, scale, integralFirst), spell, caster).intValue();
        } catch (Throwable fallbackFailure) {
            rethrowFatal(fallbackFailure);
            FormulaNativeValues.report(spell, "inline_effect_duration:fallback", caster, fallbackFailure);
            return 0;
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }
}
