package com.example.spell_compiler.formula;

import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.entity.spells.AoeEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public final class FormulaSiteHooks {
    private static final Object[] NO_ARGUMENTS = new Object[0];

    private FormulaSiteHooks() {}

    public static int overrideInt(int original, String key, Object actor) {
        return overrideInt(original, key, actor, -1);
    }

    public static int overrideInt(int original, String key, Object actor, int level) {
        Number value = evaluate(key, actor, level);
        return value == null ? original : value.intValue();
    }

    public static float overrideFloat(float original, String key, Object actor) {
        return overrideFloat(original, key, actor, -1);
    }

    public static float overrideFloat(float original, String key, Object actor, int level) {
        Number value = evaluate(key, actor, level);
        return value == null ? original : value.floatValue();
    }

    public static double overrideDouble(double original, String key, Object actor) {
        return overrideDouble(original, key, actor, -1);
    }

    public static double overrideDouble(double original, String key, Object actor, int level) {
        Number value = evaluate(key, actor, level);
        return value == null ? original : value.doubleValue();
    }

    public static double overrideIntAsDouble(double original, String key, Object actor) {
        return overrideIntAsDouble(original, key, actor, -1);
    }

    public static double overrideIntAsDouble(double original, String key, Object actor, int level) {
        Number value = evaluate(key, actor, level);
        return value == null ? original : value.intValue();
    }

    public static int overrideSquaredInt(int original, String key, Object actor) {
        return overrideSquaredInt(original, key, actor, -1);
    }

    public static int overrideSquaredInt(int original, String key, Object actor, int level) {
        Number value = evaluate(key, actor, level);
        if (value == null) return original;
        int radius = value.intValue();
        return radius * radius;
    }

    public static int overrideSummonDuration(int original, String key, Object actor) {
        return overrideSummonDuration(original, key, actor, -1);
    }

    public static int overrideSummonDuration(int original, String key, Object actor, int level) {
        var invoker = FormulaRuntimeDispatcher.resolve(key, actor);
        if (invoker == null) return original;
        Number value = FormulaRuntimeDispatcher.invoke(invoker, null, key.endsWith("(Ljava/lang/Object;)I")
                ? new Object[]{actor} : new Object[]{actor, level(key, actor, level)});
        return value == null ? original : value.intValue();
    }

    public static float overrideFloat(float original, String key, Object actor, float input) {
        return overrideFloat(original, key, actor, input, -1);
    }

    public static float overrideFloat(float original, String key, Object actor, float input, int level) {
        var invoker = FormulaRuntimeDispatcher.resolve(key, actor);
        if (invoker == null) return original;
        Number value = FormulaRuntimeDispatcher.invoke(invoker, null, key.endsWith("(F)F")
                ? new Object[]{input} : new Object[]{input, level(key, actor, level)});
        return value == null ? original : value.floatValue();
    }

    public static float overrideThunderstormDamage(float original, String key, int amplifier, Object caster) {
        var invoker = FormulaRuntimeDispatcher.resolve(key, caster);
        if (invoker == null) return original;
        var spell = SpellRegistry.THUNDERSTORM_SPELL.get();
        float powerMultiplier = 1;
        float spellpower = 1;
        double schoolSpellpower = 1;
        if (caster instanceof LivingEntity entity) {
            powerMultiplier = (float) FormulaSpellConfig.number(spell, "power_multiplier");
            spellpower = (float) entity.getAttributeValue(AttributeRegistry.SPELL_POWER);
            schoolSpellpower = spell.getSchoolType().getPowerFor(entity);
        }
        Number value = FormulaRuntimeDispatcher.invoke(invoker, null,
                new Object[]{amplifier, spellpower, schoolSpellpower, powerMultiplier, spell, caster});
        return value == null ? original : value.floatValue();
    }

    public static void initializeHealingInterval(Object created, String key, Object caster) {
        initializeHealingInterval(created, key, caster, -1);
    }

    public static void initializeHealingInterval(Object created, String key, Object caster, int level) {
        Number value = evaluate(key, caster, level);
        if (value != null) FormulaSpellConfig.aoeInterval(created, value.intValue());
    }

    public static Number validateValue(String helperKey, Number value, Object[] arguments) {
        FormulaSites.validateValue(helperKey, value);
        if (helperKey.equals("summonDuration(Ljava/lang/Object;)I") || helperKey.equals("summonDuration(Ljava/lang/Object;I)I")) {
            int expected = helperKey.equals("summonDuration(Ljava/lang/Object;)I") ? 1 : 2;
            if (arguments.length != expected) throw new IllegalArgumentException("Summon duration requires its native caster and source level");
            if (arguments[0] instanceof Entity entity && entity.level() instanceof ServerLevel level)
                Math.addExact(level.getServer().getTickCount(), value.intValue());
        }
        return value;
    }

    private static Number evaluate(String key, Object actor, int sourceLevel) {
        var invoker = FormulaRuntimeDispatcher.resolve(key, actor);
        return invoker == null ? null : FormulaRuntimeDispatcher.invoke(invoker, null,
                key.endsWith("()I") || key.endsWith("()F") || key.endsWith("()D")
                        ? NO_ARGUMENTS : new Object[]{level(key, actor, sourceLevel)});
    }

    private static int level(String key, Object actor, int original) {
        if (original > 0) return original;
        int separator = key.indexOf('#');
        return separator < 0 ? -1 : FormulaCastContext.sourceLevel(key.substring(0, separator), actor);
    }
}
