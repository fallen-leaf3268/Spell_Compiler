package com.example.spell_compiler.formula;

import java.util.Objects;

public final class FormulaIntegration {
    public interface Modifiers {
        default float damageMultiplier(Object spell, Object caster) { return 1; }
        default float durationMultiplier(Object spell, Object caster) { return 1; }
        default float radiusMultiplier(Object spell, Object caster) { return 1; }
        default int contextLevel(Object spell, Object caster) { return 1; }
        default int finishManaCost(int value, Object spell, int level) { return value; }
        default int finishCastTime(int value, Object spell, int level, Object caster) { return value; }
    }

    private static volatile Modifiers modifiers = new Modifiers() {};

    private FormulaIntegration() {}

    public static void register(Modifiers value) {
        modifiers = Objects.requireNonNull(value);
    }

    public static float damageMultiplier(Object spell, Object caster) {
        return modifiers.damageMultiplier(spell, caster);
    }

    public static float durationMultiplier(Object spell, Object caster) {
        return modifiers.durationMultiplier(spell, caster);
    }

    public static float radiusMultiplier(Object spell, Object caster) {
        return modifiers.radiusMultiplier(spell, caster);
    }

    public static int contextLevel(Object spell, Object caster) {
        return modifiers.contextLevel(spell, caster);
    }

    public static int finishManaCost(int value, Object spell, int level) {
        return modifiers.finishManaCost(value, spell, level);
    }

    public static int finishCastTime(int value, Object spell, int level, Object caster) {
        return modifiers.finishCastTime(value, spell, level, caster);
    }
}
