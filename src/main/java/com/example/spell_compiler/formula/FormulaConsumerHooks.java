package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaConsumerHooks {
    public static final String DATA_KEY = "spell_compiler:formula_values";
    private static final Set<String> ATTRIBUTE_FAILURES = ConcurrentHashMap.newKeySet();

    public interface PersistentEffect extends FormulaConsumerCarrier, FormulaSourceCarrier {
        MobEffectInstance apoth$getHiddenEffect();
    }

    private FormulaConsumerHooks() {}

    public static void capture(Object created, Object spell, int level, Object caster) {
        if (spell == null) return;
        if (created instanceof MobEffectInstance effect && caster instanceof LivingEntity entity && !entity.level().isClientSide)
            FormulaAttributeRuntime.capture(effect, spell, level, caster);
        capture(created, spell, level, caster, FormulaConsumerSites.definitions(spell.getClass().getName()));
    }

    public static void captureAttributes(Object created, Object spell, int level, Object caster) {
        if (created instanceof MobEffectInstance effect && spell != null && caster instanceof LivingEntity living && !living.level().isClientSide)
            FormulaAttributeRuntime.capture(effect, spell, level, caster);
        if (spell == null) return;
        capture(created, spell, level, caster, FormulaConsumerSites.attributeDefinitions(spell.getClass().getName()));
        if (!(created instanceof MobEffectInstance effect) || !(caster instanceof LivingEntity entity) || entity.level().isClientSide) return;
        var definitions = FormulaEffectAttributeControls.definitions(spell.getClass().getName());
        if (definitions.isEmpty()) return;
        var carrier = (FormulaConsumerCarrier) effect;
        Map<String, Float> values = new LinkedHashMap<>(carrier.apoth$getFormulaValues());
        var configured = FormulaNativeValues.override(spell, caster);
        Set<String> configuredBindings = configured == null ? Set.of() : new java.util.HashSet<>();
        if (configured != null) for (var component : configured.spell().attributeComponents())
            if (configured.attributes().containsKey(component.key())) configuredBindings.add(component.binding());
        effect.getEffect().value().createModifiers(effect.getAmplifier(), (attribute, modifier) -> {
            var definition = FormulaEffectAttributeControls.match(effect, attribute, modifier);
            if (definition == null || !definitions.contains(definition)) return;
            if (configuredBindings.contains(definition.numeric().key())) return;
            var value = FormulaNumericHooks.evaluate(definition.numeric(), FormulaAttributes.Operation.fromNative(definition.operation()).fromRaw(modifier.amount()), spell, level, caster);
            if (value != null && Float.isFinite(value.floatValue())) values.put(definition.numeric().alias(), value.floatValue());
        });
        carrier.apoth$setFormulaValues(values);
    }

    public static void captureMana(Object created, Object spell, int level, Object caster) {
        if (spell == null || !FormulaManaRatioBridge.enabled(spell.getClass().getName())) return;
        capture(created, spell, level, caster, FormulaManaRatioBridge.definitions(spell.getClass().getName()));
    }

    private static void capture(Object created, Object spell, int level, Object caster,
                                List<FormulaConsumerSites.Definition> definitions) {
        if (!(created instanceof MobEffectInstance effect) || !(caster instanceof LivingEntity entity)
                || entity.level().isClientSide) return;
        if (definitions.isEmpty()) return;
        var effectId = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
        if (effectId == null) return;
        String effectKey = effectId.toString();
        Map<Holder<Attribute>, String> aliases = null;
        var configured = FormulaNativeValues.override(spell, caster);
        Set<String> configuredAliases = configured == null ? Set.of() : new java.util.HashSet<>();
        if (configured != null) for (var component : configured.spell().attributeComponents())
            if (configured.attributes().containsKey(component.key())) configuredAliases.add(component.alias());
        var invocations = FormulaConsumerSites.invocations();
        Map<String, Float> values = new LinkedHashMap<>(((FormulaConsumerCarrier) effect).apoth$getFormulaValues());
        for (var definition : definitions) {
            if (!definition.effectId().equals(effectKey)) continue;
            values.remove(definition.alias());
            if (configuredAliases.contains(definition.alias())) continue;
            var invocation = invocations.get(definition);
            if (invocation == null) invocation = FormulaConsumerSites.invocation(definition);
            var invoker = FormulaRuntimeDispatcher.resolveActive(invocation.dispatchKey(), caster);
            if (invoker == null) continue;
            if (invocation.attribute()) {
                if (aliases == null) aliases = attributeAliases(effect.getEffect().value());
                if (!aliases.containsValue(definition.alias())) {
                    if (ATTRIBUTE_FAILURES.add(effectId + "#" + definition.alias()))
                        SpellCompiler.LOGGER.warn("[SpellFormula] {} 没有唯一匹配的原生属性模板，保留该属性原值：{}", effectId, definition.alias());
                    continue;
                }
            }
            Object[] arguments = invocation.singleArgument()
                    ? new Object[]{level} : new Object[]{level, caster};
            var value = FormulaRuntimeDispatcher.invoke(invoker, spell, arguments);
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
                effect.getDescriptionId(), alias,
                new ArithmeticException("Non-finite effect product: input=" + input + " value=" + value + " result=" + result));
        if (holder instanceof LivingEntity entity && entity.getEffect(effect.getEffect()) == effect)
            FormulaConsumerNetwork.synchronize(entity, effect);
        return original;
    }

    public static float overrideProductForHolder(float original, Object holder, String effectId, String alias,
                                                 float divisor, float input, int operation, Object source) {
        if (!(holder instanceof LivingEntity entity)) return original;
        var effect = BuiltInRegistries.MOB_EFFECT.getHolder(net.minecraft.resources.ResourceLocation.parse(effectId));
        return effect.isEmpty() ? original : overrideProduct(original, entity.getEffect(effect.get()), alias, divisor, input, operation, source);
    }

    public static double attributeAmount(MobEffectInstance instance, Holder<Attribute> attribute, double original) {
        if (instance == null) return original;
        if (!(instance instanceof FormulaConsumerCarrier carrier) || carrier.apoth$getFormulaValues().isEmpty()) return original;
        String alias = attributeAliases(instance.getEffect().value()).get(attribute);
        if (alias == null) alias = FormulaEffectAttributeControls.alias(instance, attribute);
        var value = alias == null ? null : carrier.apoth$getFormulaValues().get(alias);
        if (value == null) return original;
        String selected = alias;
        var effectId = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value());
        var definition = effectId == null ? null : FormulaEffectAttributeControls.forEffect(effectId.toString()).stream()
                .filter(candidate -> candidate.numeric().alias().equals(selected)).findFirst().orElse(null);
        return definition == null ? value.doubleValue() / 100
                : FormulaAttributes.Operation.fromNative(definition.operation()).toRaw(value.doubleValue());
    }

    private static Map<Holder<Attribute>, String> attributeAliases(MobEffect effect) {
        var effectId = BuiltInRegistries.MOB_EFFECT.getKey(effect);
        if (effectId == null) return Map.of();
        var allowed = attributeControlAliases(effectId.toString());
        if (allowed.isEmpty()) return Map.of();
        Map<Holder<Attribute>, String> candidates = new LinkedHashMap<>();
        effect.createModifiers(0, (attribute, modifier) -> {
            if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) return;
            var id = BuiltInRegistries.ATTRIBUTE.getKey(attribute.value());
            String alias = attribute.value() == Attributes.ATTACK_DAMAGE.value() ? "percent_attack_damage"
                    : attribute.value() == Attributes.MOVEMENT_SPEED.value() ? "percent_speed"
                    : id != null && id.getNamespace().equals("irons_spellbooks")
                    && (id.getPath().equals("spell_power") || id.getPath().endsWith("_spell_power")) ? "percent_spell_power" : null;
            if (alias != null && allowed.contains(alias)) candidates.put(attribute, alias);
        });
        int damage = 0, speed = 0, power = 0;
        for (String alias : candidates.values()) switch (alias) {
            case "percent_attack_damage" -> damage++;
            case "percent_speed" -> speed++;
            case "percent_spell_power" -> power++;
        }
        if (damage > 1) candidates.values().removeIf("percent_attack_damage"::equals);
        if (speed > 1) candidates.values().removeIf("percent_speed"::equals);
        if (power > 1) candidates.values().removeIf("percent_spell_power"::equals);
        return candidates;
    }

    static Set<String> attributeControlAliases(String effectId) {
        return FormulaConsumerSites.attributeControlAliases(effectId);
    }

    public static AttributeModifier attributeModifier(MobEffectInstance instance, Holder<Attribute> attribute,
                                                       AttributeModifier original) {
        var redirected = FormulaAttributeRuntime.resolve(instance, attribute, original);
        if (redirected.modifier() != original) return redirected.modifier();
        String synthetic = FormulaEffectAttributeControls.alias(instance, attribute);
        if (synthetic != null && FormulaEffectAttributeControls.match(instance, attribute, original) == null) return original;
        double amount = attributeAmount(instance, attribute, original.amount());
        return Double.compare(amount, original.amount()) == 0 ? original
                : new AttributeModifier(original.id(), amount, original.operation());
    }

    public static void applyAttributeValues(MobEffectInstance instance, AttributeMap attributes) {
        FormulaAttributeRuntime.apply(instance, attributes);
        if (((FormulaConsumerCarrier) instance).apoth$getFormulaValues().isEmpty()) return;
        instance.getEffect().value().createModifiers(instance.getAmplifier(), (attribute, original) -> {
            if (FormulaAttributeRuntime.resolve(instance, attribute, original).modifier() != original) return;
            var replacement = attributeModifier(instance, attribute, original);
            if (replacement == original) return;
            var target = attributes.getInstance(attribute);
            if (target != null) {
                target.removeModifier(original.id());
                target.addPermanentModifier(replacement);
            }
        });
    }

    public static Map<String, Float> validValues(Holder<MobEffect> effect, Map<String, Float> values) {
        var id = BuiltInRegistries.MOB_EFFECT.getKey(effect.value());
        if (id == null || values == null || values.isEmpty()) return Map.of();
        var aliases = FormulaConsumerSites.aliasesForEffect(id.toString());
        Map<String, Float> valid = new LinkedHashMap<>();
        for (String alias : aliases) {
            var value = values.get(alias);
            if (value != null && Float.isFinite(value)) valid.put(alias, value);
        }
        return Map.copyOf(valid);
    }

    public static void applyClientValues(LivingEntity entity, MobEffectInstance effect, Map<String, Float> values) {
        applyClientValues(entity, effect, values, Map.of());
    }

    public static void applyClientValues(LivingEntity entity, MobEffectInstance effect, Map<String, Float> values, Map<String, FormulaAttributes.Value> attributes) {
        FormulaAttributeRuntime.clear(effect, entity.getAttributes());
        ((FormulaConsumerCarrier) effect).apoth$setAttributeValues(attributes);
        ((FormulaConsumerCarrier) effect).apoth$setFormulaValues(values);
        if (!attributeAliases(effect.getEffect().value()).isEmpty()
                || !FormulaEffectAttributeControls.forEffect(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString()).isEmpty()) {
            effect.getEffect().value().removeAttributeModifiers(entity.getAttributes());
            effect.getEffect().value().addAttributeModifiers(entity.getAttributes(), effect.getAmplifier());
            applyAttributeValues(effect, entity.getAttributes());
        }
    }

    public static CompoundTag writeValues(Map<String, Float> values) {
        var result = new CompoundTag();
        values.forEach(result::putFloat);
        return result;
    }

    public static Map<String, Float> readValues(Holder<MobEffect> effect, CompoundTag tag) {
        var id = BuiltInRegistries.MOB_EFFECT.getKey(effect.value());
        if (id == null) return Map.of();
        Map<String, Float> values = new LinkedHashMap<>();
        for (String alias : FormulaConsumerSites.aliasesForEffect(id.toString())) {
            if (tag.contains(alias, Tag.TAG_ANY_NUMERIC)) values.put(alias, tag.getFloat(alias));
        }
        return validValues(effect, values);
    }

    public static Codec<MobEffectInstance> withFormulaValues(Codec<MobEffectInstance> original) {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<MobEffectInstance, T>> decode(DynamicOps<T> ops, T input) {
                return original.decode(ops, input).map(result -> {
                    readEffectValues(result.getFirst(), ops, input);
                    return result;
                });
            }

            @Override
            public <T> DataResult<T> encode(MobEffectInstance input, DynamicOps<T> ops, T prefix) {
                return original.encode(input, ops, prefix).flatMap(encoded -> writeEffectValues(input, ops, encoded));
            }
        };
    }

    public static StreamCodec<RegistryFriendlyByteBuf, MobEffectInstance> formulaStreamCodec(
            StreamCodec<RegistryFriendlyByteBuf, MobEffectInstance> original) {
        return FormulaEffectStreamCodec.create(original);
    }

    private static <T> DataResult<T> writeEffectValues(MobEffectInstance instance, DynamicOps<T> ops, T encoded) {
        var carrier = (PersistentEffect) instance;
        T cleared = ops.remove(ops.remove(ops.remove(encoded, DATA_KEY), FormulaEntityContext.DATA_KEY), FormulaAttributeRuntime.DATA_KEY);
        var values = carrier.apoth$getFormulaValues();
        DataResult<T> result = values.isEmpty() ? DataResult.success(cleared)
                : ops.mergeToMap(cleared, ops.createString(DATA_KEY), ops.createMap(values.entrySet().stream()
                        .map(entry -> Pair.of(ops.createString(entry.getKey()), ops.createFloat(entry.getValue())))));
        if (!carrier.apoth$getAttributeValues().isEmpty()) result = result.flatMap(current -> ops.mergeToMap(current, ops.createString(FormulaAttributeRuntime.DATA_KEY),
                FormulaAttributeRuntime.encode(carrier.apoth$getAttributeValues(), ops)));
        if (carrier.apoth$hasFormulaSource()) {
            var source = carrier.apoth$getFormulaSource();
            if (FormulaEntityContext.valid(source)) {
                result = result.flatMap(current -> ops.mergeToMap(current, ops.createString(FormulaEntityContext.DATA_KEY),
                        NbtOps.INSTANCE.convertTo(ops, source)));
            }
        }
        var hidden = carrier.apoth$getHiddenEffect();
        if (hidden != null) {
            result = result.flatMap(current -> ops.get(current, "hidden_effect")
                    .flatMap(child -> writeEffectValues(hidden, ops, child))
                    .flatMap(child -> ops.mergeToMap(current, ops.createString("hidden_effect"), child)));
        }
        return result;
    }

    private static <T> void readEffectValues(MobEffectInstance instance, DynamicOps<T> ops, T encoded) {
        var carrier = (PersistentEffect) instance;
        Map<String, Float> values = new LinkedHashMap<>();
        var data = ops.get(encoded, DATA_KEY).flatMap(ops::getMap).result().orElse(null);
        var id = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value());
        if (data != null && id != null) {
            for (String alias : FormulaConsumerSites.aliasesForEffect(id.toString())) {
                var entry = data.get(alias);
                if (entry != null) ops.getNumberValue(entry).result()
                        .ifPresent(value -> values.put(alias, value.floatValue()));
            }
        }
        carrier.apoth$setFormulaValues(values);
        carrier.apoth$setAttributeValues(ops.get(encoded, FormulaAttributeRuntime.DATA_KEY).result()
                .map(value -> FormulaAttributeRuntime.read((CompoundTag) ops.convertTo(NbtOps.INSTANCE, value))).orElseGet(Map::of));
        var source = ops.get(encoded, FormulaEntityContext.DATA_KEY).result()
                .map(value -> FormulaEntityContext.decodeSource(ops, value)).orElseGet(CompoundTag::new);
        carrier.apoth$setFormulaSource(source);
        var hidden = carrier.apoth$getHiddenEffect();
        if (hidden != null) ops.get(encoded, "hidden_effect").result()
                .ifPresent(child -> readEffectValues(hidden, ops, child));
    }
}
