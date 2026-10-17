package com.example.spell_compiler.formula;

import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.util.thread.EffectiveSide;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaRuntimeHooks {
    private static final String ABSTRACT_SPELL = "io.redspace.ironsspellbooks.api.spells.AbstractSpell";
    private static final String SPELL_POWER = "getSpellPower(ILnet/minecraft/world/entity/Entity;)F";
    private static final String MANA_COST = "getManaCost(I)I";
    private static final String COOLDOWN = "getSpellCooldown()I";
    private static final String CAST_TIME = "getCastTime(I)I";
    private static final String EFFECTIVE_CAST_TIME = "getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I";
    private static final Set<String> INHERITED_METHODS = Set.of(SPELL_POWER, MANA_COST, COOLDOWN, CAST_TIME, EFFECTIVE_CAST_TIME);
    private static volatile Map<String, Map<String, String>> inheritedMethods = Map.of();
    private static final Logger LOGGER = LogManager.getLogger(FormulaRuntimeHooks.class);
    private static final Set<String> PARAMETER_FAILURES = ConcurrentHashMap.newKeySet();

    private FormulaRuntimeHooks() {}

    public static void initialize(SpellFormulaCatalog catalog) {
        FormulaNativeValues.initialize(catalog);
        Map<String, Map<String, String>> bindings = new LinkedHashMap<>();
        for (var spell : catalog.spells()) {
            Map<String, String> methods = new LinkedHashMap<>();
            for (var method : spell.formulas()) {
                if (INHERITED_METHODS.contains(method.key()) && method.declaringClass().equals(ABSTRACT_SPELL)
                        && !method.declaringClass().equals(spell.className()))
                    methods.put(method.key(), FormulaRuntimeDispatcher.key(spell.className(), method.key()));
            }
            if (!methods.isEmpty()) bindings.put(spell.className(), Map.copyOf(methods));
        }
        inheritedMethods = Map.copyOf(bindings);
    }

    public static float overrideSpellPower(float original, Object spell, int level, Object caster) {
        if (FormulaNativeValues.isEvaluatingOriginal()) return FormulaNativeValues.originalPower(spell, level, caster);
        original = safeParameterPower(original, spell, level, caster);
        var invoker = resolve(SPELL_POWER, spell, caster);
        if (invoker == null) return original;
        Number value = FormulaRuntimeDispatcher.invoke(invoker, spell, new Object[]{level, caster});
        return value == null ? original : value.floatValue();
    }

    public static int overrideManaCost(int original, Object spell, int level) {
        if (FormulaNativeValues.isEvaluatingOriginal()) return originalInteger(spell, MANA_COST, new Object[]{level}, null);
        original = safeParameterMana(original, spell, level);
        var invoker = resolve(MANA_COST, spell, null);
        if (invoker == null) return original;
        Number value = FormulaRuntimeDispatcher.invoke(invoker, spell, new Object[]{level});
        return value == null ? original : value.intValue();
    }

    public static int overrideCooldown(int original, Object spell) {
        var invoker = resolve(COOLDOWN, spell, null);
        if (invoker == null) return original;
        Number value = FormulaRuntimeDispatcher.invoke(invoker, spell, new Object[0]);
        return value == null ? original : value.intValue();
    }

    public static int overrideCastTime(int original, Object spell, int level) {
        original = FormulaCastSafety.guardCastTime(original, spell, level);
        var invoker = resolve(CAST_TIME, spell, null);
        if (invoker == null) return original;
        Number value = FormulaRuntimeDispatcher.invoke(invoker, spell, new Object[]{level});
        if (value != null && spell instanceof AbstractSpell source && source.getCastType() == CastType.INSTANT) return 0;
        return value == null ? original : value.intValue();
    }

    public static int overrideEffectiveCastTime(int original, Object spell, int level, Object caster) {
        original = FormulaCastSafety.guardEffectiveCastTime(original, spell, level, caster);
        var invoker = resolve(EFFECTIVE_CAST_TIME, spell, caster);
        if (invoker == null) return original;
        Number value = FormulaRuntimeDispatcher.invoke(invoker, spell, new Object[]{level, caster});
        if (value != null && spell instanceof AbstractSpell source && source.getCastType() == CastType.INSTANT) return 0;
        return value == null ? original : value.intValue();
    }

    static void clearParameterFailures() {
        PARAMETER_FAILURES.clear();
        FormulaNativeValues.clearFailures();
    }

    private static SpellFormulaFiles.SpellOverride parameterOverride(Object spell, Object caster) {
        boolean clientSide = caster instanceof Entity entity ? entity.level().isClientSide : EffectiveSide.get().isClient();
        return (clientSide ? SpellFormulaState.clientSnapshot() : SpellFormulaState.serverSnapshot())
                .byClass().get(spell.getClass().getName());
    }

    private static int safeParameterMana(int original, Object spell, int level) {
        var override = parameterOverride(spell, null);
        if (!(spell instanceof AbstractSpell source) || override == null
                || !FormulaParameterLimits.related(override.parameters(), override.advanced().containsKey("max_level"),
                "baseManaCost", "manaCostPerLevel", "mana_cost_multiplier")) return original;
        try {
            FormulaParameterLimits.manaCost(parameter(source, override, "baseManaCost"),
                    parameter(source, override, "manaCostPerLevel"), level,
                    (Double) SpellConfigManager.getSpellConfigValue(source, SpellConfigParameter.MANA_MULTIPLIER));
            return original;
        } catch (ArithmeticException failure) {
            reportParameterFailure(override, MANA_COST, failure);
            try {
                return FormulaNativeValues.evaluateOriginal(source, MANA_COST, new Object[]{level}).intValue();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable fallbackFailure) {
                FormulaNativeValues.report(source, MANA_COST + ":fallback", null, fallbackFailure);
                return Integer.MAX_VALUE;
            }
        }
    }

    private static float safeParameterPower(float original, Object spell, int level, Object caster) {
        var override = parameterOverride(spell, caster);
        if (!(spell instanceof AbstractSpell source) || override == null
                || !FormulaParameterLimits.related(override.parameters(), override.advanced().containsKey("max_level"),
                "baseSpellPower", "spellPowerPerLevel", "power_multiplier")) return original;
        try {
            FormulaParameterLimits.linearPower(powerParameter(source, override, "baseSpellPower"),
                    powerParameter(source, override, "spellPowerPerLevel"), level);
            return FormulaFunctions.requireFinite(FormulaNativeValues.evaluateNative(source, SPELL_POWER,
                    new Object[]{level, caster}).floatValue());
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (RuntimeException | LinkageError failure) {
            reportParameterFailure(override, SPELL_POWER, failure);
            return FormulaNativeValues.originalPower(source, level, caster);
        } catch (Throwable failure) {
            throw new IllegalStateException("扩展法术强度计算失败", failure);
        }
    }

    private static int originalParameter(AbstractSpell source, SpellFormulaFiles.SpellOverride override, String name) {
        return SpellFormulaRuntime.originalParameter(source, name, override.spell().parameters().get(name).getAsInt());
    }

    private static long powerParameter(AbstractSpell source, SpellFormulaFiles.SpellOverride override, String name) {
        var value = override.parameters().get(name);
        return value == null ? originalParameter(source, override, name) : value.getAsLong();
    }

    private static int parameter(AbstractSpell source, SpellFormulaFiles.SpellOverride override, String name) {
        var value = override.parameters().get(name);
        return value == null ? originalParameter(source, override, name) : value.getAsInt();
    }

    private static void reportParameterFailure(SpellFormulaFiles.SpellOverride override, String method, Throwable failure) {
        if (PARAMETER_FAILURES.add(override.spell().id() + '#' + method))
            LOGGER.error("[SpellFormula] {}#{} 参数计算超出原生范围，本次调用使用原始参数的安全结果",
                    override.spell().id(), method, failure);
    }

    private static FormulaRuntimeDispatcher.Invoker resolve(String method, Object spell, Object caster) {
        var methods = inheritedMethods.get(spell.getClass().getName());
        if (methods == null) return null;
        String key = methods.get(method);
        if (key == null) return null;
        return FormulaRuntimeDispatcher.resolve(key, caster);
    }

    public static float applyPower(float value, Object spell, Object caster) {
        Integer level = FormulaNativeValues.nativeLevel(spell);
        if (level != null) value = overrideSpellPower(value, spell, level, caster);
        return finishPower(value, spell, caster);
    }

    public static float applyEntityPower(float value, Object spell, Object caster) {
        float multiplier = FormulaIntegration.damageMultiplier(spell, caster);
        float result = value * multiplier;
        if (Float.isFinite(result) || !FormulaNativeValues.isModified(spell, caster)) return result;
        FormulaNativeValues.report(spell, "final_entity_power", caster,
                new ArithmeticException("词条乘算后的实体强度倍率超出有限范围"));
        float fallback = FormulaNativeValues.originalEntityPower(spell, caster) * multiplier;
        return Float.isFinite(fallback) ? fallback : 0;
    }

    public static float finishPower(float value, Object spell, Object caster) {
        Integer nativeLevel = FormulaNativeValues.currentLevel(spell);
        int level = nativeLevel != null ? nativeLevel : FormulaIntegration.contextLevel(spell, caster);
        return finishPower(value, spell, level, caster);
    }

    public static float finishPower(float value, Object spell, int level, Object caster) {
        float multiplier = FormulaIntegration.damageMultiplier(spell, caster);
        float result = value * multiplier;
        if (Float.isFinite(result) || !FormulaNativeValues.isModified(spell, caster)) return result;
        FormulaNativeValues.report(spell, "final_spell_power", caster, new ArithmeticException("词条乘算后的法术强度超出有限范围"));
        float fallback = FormulaNativeValues.originalPower(spell, level, caster) * multiplier;
        return Float.isFinite(fallback) ? fallback : 0;
    }

    private static int originalInteger(Object spell, String key, Object[] arguments, Object caster) {
        try {
            return FormulaNativeValues.evaluateOriginal(spell, key, arguments).intValue();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            FormulaNativeValues.report(spell, key, caster, failure);
            return Integer.MAX_VALUE;
        }
    }

    public static float finiteFloat(float value, Object spell, String key, Object[] arguments) {
        if (Float.isFinite(value) || !FormulaNativeValues.isModified(spell, actor(arguments))) return value;
        return recoverFinite(spell, key, arguments).floatValue();
    }

    public static double finiteDouble(double value, Object spell, String key, Object[] arguments) {
        if (Double.isFinite(value) || !FormulaNativeValues.isModified(spell, actor(arguments))) return value;
        return recoverFinite(spell, key, arguments).doubleValue();
    }

    private static Number recoverFinite(Object spell, String key, Object[] arguments) {
        Object caster = actor(arguments);
        FormulaNativeValues.report(spell, key, caster, new ArithmeticException("法术最终结果超出有限范围"));
        try {
            Number result = FormulaNativeValues.evaluateOriginal(spell, key, arguments);
            if (Double.isFinite(result.doubleValue()) && (!(result instanceof Float) || Float.isFinite(result.floatValue()))) return result;
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            FormulaNativeValues.report(spell, key + ":fallback", caster, failure);
        }
        return 0;
    }

    private static Object actor(Object[] arguments) {
        for (Object value : arguments) if (value instanceof Entity) return value;
        return null;
    }
}
