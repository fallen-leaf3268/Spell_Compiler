package com.example.spell_compiler.formula;

import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaNumericHooks {
    private static final Logger LOGGER = LogManager.getLogger(FormulaNumericHooks.class);
    private static final Set<String> FAILURES = ConcurrentHashMap.newKeySet();

    private FormulaNumericHooks() {}

    public static int overrideInt(int original, String key, Object spell, int level, Object caster) {
        Number value = evaluate(runtimeDefinition(key, spell), original, spell, level, caster);
        return value == null ? original : value.intValue();
    }

    public static float overrideFloat(float original, String key, Object spell, int level, Object caster) {
        Number value = evaluate(runtimeDefinition(key, spell), original, spell, level, caster);
        return value == null ? original : value.floatValue();
    }

    public static double overrideDouble(double original, String key, Object spell, int level, Object caster) {
        Number value = evaluate(runtimeDefinition(key, spell), original, spell, level, caster);
        return value == null ? original : value.doubleValue();
    }

    public static int overrideInt(int original, Object[] inputs, String key, Object spell, int level, Object caster) {
        Number value = evaluate(runtimeDefinition(key, spell), original, spell, level, caster, inputs);
        return value == null ? original : value.intValue();
    }

    public static float overrideFloat(float original, Object[] inputs, String key, Object spell, int level, Object caster) {
        Number value = evaluate(runtimeDefinition(key, spell), original, spell, level, caster, inputs);
        return value == null ? original : value.floatValue();
    }

    public static double overrideDouble(double original, Object[] inputs, String key, Object spell, int level, Object caster) {
        Number value = evaluate(runtimeDefinition(key, spell), original, spell, level, caster, inputs);
        return value == null ? original : value.doubleValue();
    }

    public static FormulaNumericSites.Definition definition(String dispatchKey) {
        var definition = FormulaNumericSites.find(dispatchKey);
        if (definition == null) definition = FormulaEntitySites.find(dispatchKey);
        if (definition == null) definition = FormulaAddonNumericSites.find(dispatchKey);
        if (definition == null) definition = FormulaSourceControls.find(dispatchKey);
        var automatic = definition == null ? FormulaAutomaticSites.find(dispatchKey) : null;
        return automatic == null ? definition : automatic.definition();
    }

    static FormulaNumericSites.Definition runtimeDefinition(String key, Object spell) {
        var automatic = FormulaAutomaticSites.find(key);
        if (automatic == null || spell == null) return definition(key);
        var selected = FormulaAutomaticSites.findForSpell(spell.getClass().getName(), automatic);
        if (selected == null || !selected.owner().equals(automatic.owner())
                || !selected.methodName().equals(automatic.methodName())
                || !selected.methodDescriptor().equals(automatic.methodDescriptor())
                || selected.producerIndex() != automatic.producerIndex()
                || selected.argumentIndex() != automatic.argumentIndex()
                || selected.contextMode() != automatic.contextMode()
                || selected.contextSlot() != automatic.contextSlot()
                || selected.casterSlot() != automatic.casterSlot()
                || selected.levelSlot() != automatic.levelSlot()
                || selected.spellSlot() != automatic.spellSlot()
                || !selected.methodFingerprint().equals(automatic.methodFingerprint())
                || selected.kind() != automatic.kind()) return null;
        return selected.definition();
    }

    public static Number evaluate(FormulaNumericSites.Definition definition, Number original,
                                  Object spell, int level, Object caster) {
        return evaluate(definition, original, spell, level, caster, null);
    }

    static Number evaluate(FormulaNumericSites.Definition definition, Number original,
                           Object spell, int level, Object caster, Object[] inputs) {
        if (definition == null) return null;
        var invoker = FormulaRuntimeDispatcher.resolveActive(definition.dispatchKey(), caster);
        if (invoker == null) return null;
        try {
            double power = 1;
            double school = 1;
            double multiplier = 1;
            if (spell instanceof AbstractSpell source) {
                multiplier = FormulaSpellConfig.number(source, "power_multiplier");
                if (caster instanceof LivingEntity entity) {
                    power = entity.getAttributeValue(AttributeRegistry.SPELL_POWER);
                    school = source.getSchoolType().getPowerFor(entity);
                }
            }
            Object[] arguments = inputs == null
                    ? new Object[]{original.doubleValue(), level, power, school, multiplier, spell, caster}
                    : new Object[]{original.doubleValue(), level, power, school, multiplier, spell, caster, inputs};
            return FormulaRuntimeDispatcher.invoke(invoker, null, arguments);
        } catch (RuntimeException | LinkageError failure) {
            if (FAILURES.add(definition.dispatchKey()))
                LOGGER.error("[SpellFormula] {} 无法读取消费数值上下文，保留原生值", definition.dispatchKey(), failure);
            return null;
        }
    }

    static void clearFailures() { FAILURES.clear(); }

    public static Number validate(FormulaNumericSites.Definition definition, Number value) {
        double numeric = value.doubleValue();
        if (!Double.isFinite(numeric)) throw new ArithmeticException("消费数值公式必须返回有限值：" + definition.alias());
        Number converted = switch (definition.valueType()) {
            case INT -> {
                if (numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE)
                    throw new ArithmeticException("消费数值公式超出整数范围：" + definition.alias());
                yield value.intValue();
            }
            case FLOAT -> {
                float result = value.floatValue();
                if (!Float.isFinite(result)) throw new ArithmeticException("消费数值公式超出浮点范围：" + definition.alias());
                yield result;
            }
            case DOUBLE -> numeric;
        };
        if (converted.doubleValue() < definition.minimum() || converted.doubleValue() > definition.maximum())
            throw new ArithmeticException("消费数值公式超出允许范围：" + definition.alias() + '=' + converted
                    + " allowed=" + definition.minimum() + ".." + definition.maximum());
        return converted;
    }

    public static Number validate(FormulaNumericSites.Definition definition, Number value, Object[] arguments) {
        Number result = validate(definition, value);
        if (definition.alias().equals("summon_duration_ticks") && arguments.length >= 7
                && arguments[6] instanceof net.minecraft.world.entity.Entity entity
                && entity.level() instanceof net.minecraft.server.level.ServerLevel level)
            Math.addExact(level.getServer().getTickCount(), result.intValue());
        return result;
    }
}
