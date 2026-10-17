package com.example.spell_compiler.formula;

import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.entity.spells.AoeEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public final class FormulaSiteHooks {
    private static final Object[] EMPTY = new Object[0];

    private FormulaSiteHooks() {}

    public static int overrideInt(int original, String key, Object actor) {
        Number value = evaluate(key, actor, EMPTY);
        return value == null ? original : value.intValue();
    }

    public static float overrideFloat(float original, String key, Object actor) {
        Number value = evaluate(key, actor, EMPTY);
        return value == null ? original : value.floatValue();
    }

    public static double overrideDouble(double original, String key, Object actor) {
        Number value = evaluate(key, actor, EMPTY);
        return value == null ? original : value.doubleValue();
    }

    public static double overrideIntAsDouble(double original, String key, Object actor) {
        Number value = evaluate(key, actor, EMPTY);
        return value == null ? original : value.intValue();
    }

    public static int overrideSquaredInt(int original, String key, Object actor) {
        Number value = evaluate(key, actor, EMPTY);
        if (value == null) return original;
        int radius = value.intValue();
        return radius * radius;
    }

    public static int overrideSummonDuration(int original, String key, Object actor) {
        var invoker = FormulaRuntimeDispatcher.resolve(key, actor);
        if (invoker == null) return original;
        Number value = FormulaRuntimeDispatcher.invoke(invoker, null, new Object[]{actor});
        return value == null ? original : value.intValue();
    }

    public static float overrideFloat(float original, String key, Object actor, float input) {
        var invoker = FormulaRuntimeDispatcher.resolve(key, actor);
        if (invoker == null) return original;
        Number value = FormulaRuntimeDispatcher.invoke(invoker, null, new Object[]{input});
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
            powerMultiplier = SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.POWER_MULTIPLIER).floatValue();
            spellpower = (float) entity.getAttributeValue(AttributeRegistry.SPELL_POWER.get());
            schoolSpellpower = spell.getSchoolType().getPowerFor(entity);
        }
        Number value = FormulaRuntimeDispatcher.invoke(invoker, null,
                new Object[]{amplifier, spellpower, schoolSpellpower, powerMultiplier, spell, caster});
        return value == null ? original : value.floatValue();
    }

    public static void initializeHealingInterval(Object created, String key, Object caster) {
        var invoker = FormulaRuntimeDispatcher.resolve(key, caster);
        if (invoker == null) return;
        Number value = FormulaRuntimeDispatcher.invoke(invoker, null, EMPTY);
        if (value != null) ((AoeEntity) created).setReapplicationDelay(value.intValue());
    }

    public static Number validateValue(String helperKey, Number value, Object[] arguments) {
        FormulaSites.validateValue(helperKey, value);
        if (helperKey.equals("summonDuration(Ljava/lang/Object;)I")) {
            if (arguments.length != 1) throw new IllegalArgumentException("Summon duration requires its native caster");
            if (arguments[0] instanceof Entity entity && entity.level() instanceof ServerLevel level)
                Math.addExact(level.getServer().getTickCount(), value.intValue());
        }
        return value;
    }

    private static Number evaluate(String key, Object actor, Object[] arguments) {
        var invoker = FormulaRuntimeDispatcher.resolve(key, actor);
        return invoker == null ? null : FormulaRuntimeDispatcher.invoke(invoker, null, arguments);
    }
}
