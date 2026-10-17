package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.registries.MobEffectRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FormulaConsumerHooks {
    public static final String DATA_KEY = "spell_compiler:formula_values";

    private FormulaConsumerHooks() {}

    public static void capture(Object created, Object spell, int level, Object caster) {
        if (!(created instanceof MobEffectInstance effect) || !(caster instanceof LivingEntity entity)
                || entity.level().isClientSide) return;
        var definitions = FormulaConsumerSites.definitions(spell.getClass().getName());
        if (definitions.isEmpty()) return;
        Map<String, Float> values = new LinkedHashMap<>();
        for (var definition : definitions) {
            var invoker = FormulaRuntimeDispatcher.resolve(definition.dispatchKey(), caster);
            if (invoker == null) continue;
            var value = FormulaRuntimeDispatcher.invoke(invoker, spell, new Object[]{level, caster});
            if (value != null) values.put(definition.alias(), value.floatValue());
        }
        ((FormulaConsumerCarrier) effect).apoth$setFormulaValues(values);
    }

    public static float overrideFloat(float original, Object instance, String alias, float divisor) {
        if (!(instance instanceof FormulaConsumerCarrier carrier)) return original;
        var value = carrier.apoth$getFormulaValues().get(alias);
        return value == null ? original : value / divisor;
    }

    public static float overrideProduct(float original, Object instance, String alias, float divisor,
                                        float input, int operation, Object holder) {
        if (!(instance instanceof MobEffectInstance effect) || !(instance instanceof FormulaConsumerCarrier carrier)) return original;
        var value = carrier.apoth$getFormulaValues().get(alias);
        if (value == null) return original;
        float ratio = value / divisor;
        float multiplier = switch (operation) {
            case 0 -> ratio;
            case 1 -> 1f - ratio;
            case 2 -> 1f + ratio;
            default -> throw new IllegalArgumentException("Unknown numeric effect product: " + operation);
        };
        float result = input * multiplier;
        if (Float.isFinite(result)) return result;
        var remaining = new LinkedHashMap<>(carrier.apoth$getFormulaValues());
        remaining.remove(alias);
        carrier.apoth$setFormulaValues(remaining);
        SpellCompiler.LOGGER.error("[SpellFormula] {}#{} 最终效果数值非有限，已禁用本次效果实例的该公式并回退到原生结果",
                effect.getEffect().getDescriptionId(), alias,
                new ArithmeticException("Non-finite effect product: input=" + input + " value=" + value + " result=" + result));
        if (holder instanceof LivingEntity entity && entity.getEffect(effect.getEffect()) == effect)
            FormulaConsumerNetwork.synchronize(entity, effect);
        return original;
    }

    public static float overrideProductForHolder(float original, Object holder, String effectId, String alias,
                                                 float divisor, float input, int operation, Object source) {
        if (!(holder instanceof LivingEntity entity)) return original;
        var effect = ForgeRegistries.MOB_EFFECTS.getValue(net.minecraft.resources.ResourceLocation.parse(effectId));
        return effect == null ? original : overrideProduct(original, entity.getEffect(effect), alias, divisor, input, operation, source);
    }

    public static double attributeAmount(MobEffect effect, LivingEntity entity, AttributeModifier modifier, double original) {
        return attributeAmount(entity.getEffect(effect), modifier, original);
    }

    public static double attributeAmount(MobEffectInstance instance, AttributeModifier modifier, double original) {
        if (instance == null || instance.getEffect() != MobEffectRegistry.CHARGED.get()) return original;
        var effect = instance.getEffect();
        if (!(instance instanceof FormulaConsumerCarrier carrier) || carrier.apoth$getFormulaValues().isEmpty()) return original;
        String alias = matches(effect, modifier, Attributes.ATTACK_DAMAGE) ? "percent_attack_damage"
                : matches(effect, modifier, Attributes.MOVEMENT_SPEED) ? "percent_speed"
                : matches(effect, modifier, AttributeRegistry.SPELL_POWER.get()) ? "percent_spell_power" : null;
        var value = alias == null ? null : carrier.apoth$getFormulaValues().get(alias);
        return value == null ? original : value.doubleValue() / 100;
    }

    public static Map<String, Float> validValues(MobEffect effect, Map<String, Float> values) {
        var id = ForgeRegistries.MOB_EFFECTS.getKey(effect);
        if (id == null || values == null || values.isEmpty()) return Map.of();
        var definitions = FormulaConsumerSites.forEffect(id.toString());
        Map<String, Float> valid = new LinkedHashMap<>();
        for (var definition : definitions) {
            var value = values.get(definition.alias());
            if (value != null && Float.isFinite(value)) valid.put(definition.alias(), value);
        }
        return Map.copyOf(valid);
    }

    public static void applyClientValues(LivingEntity entity, MobEffectInstance effect, Map<String, Float> values) {
        ((FormulaConsumerCarrier) effect).apoth$setFormulaValues(values);
        if (effect.getEffect() == MobEffectRegistry.CHARGED.get()) {
            effect.getEffect().removeAttributeModifiers(entity, entity.getAttributes(), effect.getAmplifier());
            effect.getEffect().addAttributeModifiers(entity, entity.getAttributes(), effect.getAmplifier());
        }
    }

    public static CompoundTag writeValues(Map<String, Float> values) {
        var result = new CompoundTag();
        values.forEach(result::putFloat);
        return result;
    }

    public static Map<String, Float> readValues(MobEffect effect, CompoundTag tag) {
        var id = ForgeRegistries.MOB_EFFECTS.getKey(effect);
        if (id == null) return Map.of();
        Map<String, Float> values = new LinkedHashMap<>();
        for (var definition : FormulaConsumerSites.forEffect(id.toString())) {
            String alias = definition.alias();
            if (tag.contains(alias, Tag.TAG_ANY_NUMERIC)) values.put(alias, tag.getFloat(alias));
        }
        return validValues(effect, values);
    }

    private static boolean matches(MobEffect effect, AttributeModifier modifier, Attribute attribute) {
        var template = effect.getAttributeModifiers().get(attribute);
        return template == modifier;
    }
}
