package com.example.spell_compiler.formula;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import java.util.*;

public final class FormulaAttributeRuntime {
    public static final String DATA_KEY = "spell_compiler:attribute_values";
    private static final Map<AttributeMap, Map<String, List<FormulaAttributes.Value>>> INSTALLED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> MISSING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private record Binding(FormulaAttributes.Component component, String modifierId, String dispatch, int arguments) {}
    private record Index(SpellFormulaCatalog catalog, Map<String, Map<String, Binding>> spells, Map<String, List<Binding>> effects) {}
    private static volatile Index index;
    public record Applied(Attribute attribute, AttributeModifier modifier) {}
    private FormulaAttributeRuntime() {}

    private static Index index() {
        var catalog = SpellFormulaState.catalog();
        var current = index;
        if (current != null && current.catalog() == catalog) return current;
        synchronized (FormulaAttributeRuntime.class) {
            current = index;
            if (current != null && current.catalog() == catalog) return current;
            Map<String, Map<String, Binding>> spells = new HashMap<>();
            Map<String, List<Binding>> effects = new HashMap<>();
            for (var spell : catalog.spells()) {
                Map<String, Binding> bindings = new HashMap<>();
                for (var component : spell.attributeComponents()) {
                    String id = UUID.nameUUIDFromBytes(
                            (spell.id() + "/" + component.key()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    var binding = new Binding(component, id, FormulaRuntimeDispatcher.key(spell.className(), component.binding()),
                            org.objectweb.asm.Type.getArgumentTypes(component.binding().substring(component.binding().indexOf('('))).length);
                    bindings.put(component.key(), binding);
                    effects.computeIfAbsent(component.effectId(), ignored -> new ArrayList<>()).add(binding);
                }
                spells.put(spell.className(), Map.copyOf(bindings));
            }
            effects.replaceAll((key, value) -> List.copyOf(value));
            current = new Index(catalog, Map.copyOf(spells), Map.copyOf(effects));
            index = current;
            return current;
        }
    }

    public static Map<String, FormulaAttributes.Value> valid(String effect, Map<String, FormulaAttributes.Value> values) {
        if (values.isEmpty()) return Map.of();
        if (values.size() > 32) throw new IllegalArgumentException("Too many attribute values");
        Map<String, FormulaAttributes.Value> valid = new LinkedHashMap<>();
        var candidates = index().effects().getOrDefault(effect, List.of());
        for (var entry : values.entrySet()) {
            var value = entry.getValue();
            boolean known = false;
            boolean accepted = false;
            for (var binding : candidates) {
                var component = binding.component();
                if (!component.key().equals(entry.getKey()) || !effect.equals(component.effectId())) continue;
                known = true;
                if (value != null && component.sourceAttribute().equals(value.sourceAttribute())
                        && component.modifierId().equals(value.sourceModifierId()) && binding.modifierId().equals(value.modifierId())
                        && value.operation() != null && Double.isFinite(value.amount())
                        && BuiltInRegistries.ATTRIBUTE.containsKey(new ResourceLocation(value.targetAttribute()))) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) throw new IllegalArgumentException(known
                    ? "Invalid attribute snapshot: " + entry.getKey() : "Unknown attribute snapshot component");
            valid.put(entry.getKey(), value);
        }
        return Map.copyOf(valid);
    }

    public static Number evaluate(FormulaAttributes.Component component, FormulaAttributes.Config config,
                                  Object spell, int level, Object caster, double nativeRawAmount) {
        var binding = index().spells().getOrDefault(spell.getClass().getName(), Map.of()).get(component.key());
        String dispatch = binding != null && binding.component().equals(component) ? binding.dispatch()
                : FormulaRuntimeDispatcher.key(spell.getClass().getName(), component.binding());
        var numeric = FormulaNumericHooks.definition(dispatch);
        if (numeric != null) return FormulaNumericHooks.evaluate(numeric, config.operation().fromRaw(nativeRawAmount), spell, level, caster);
        var invoker = FormulaRuntimeDispatcher.resolveActive(dispatch, caster);
        int arguments = binding != null && binding.component().equals(component) ? binding.arguments()
                : org.objectweb.asm.Type.getArgumentTypes(component.binding().substring(component.binding().indexOf('('))).length;
        return invoker == null ? null : FormulaRuntimeDispatcher.invoke(invoker, spell, arguments == 1 ? new Object[]{level} : new Object[]{level, caster});
    }

    public static void capture(MobEffectInstance effect, Object spell, int level, Object caster) {
        var override = FormulaNativeValues.override(spell, caster);
        if (override == null || override.attributes().isEmpty()) return;
        Map<String, FormulaAttributes.Value> values = new LinkedHashMap<>();
        var effectId = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect()).toString();
        var bindings = index().spells().getOrDefault(override.spell().className(), Map.of());
        FormulaConsumerHooks.createModifiers(effect.getEffect(), effect.getAmplifier(), (attribute, original) -> {
            String source = BuiltInRegistries.ATTRIBUTE.getKey(attribute).toString();
            for (var component : override.spell().attributeComponents()) {
                var config = override.attributes().get(component.key());
                var binding = bindings.get(component.key());
                if (config == null || binding == null || !component.effectId().equals(effectId)) continue;
                if (!source.equals(component.sourceAttribute())
                        || !original.getId().toString().equals(component.modifierId()) || original.getOperation() != component.operation().nativeOperation()) continue;
                Number result = evaluate(component, config, spell, level, caster, original.getAmount());
                if (result == null || !Double.isFinite(result.doubleValue())) continue;
                values.put(component.key(), new FormulaAttributes.Value(component.sourceAttribute(), component.modifierId(),
                        config.targetAttribute(), binding.modifierId(), config.operation(), config.operation().toRaw(result.doubleValue())));
            }
        });
        ((FormulaConsumerCarrier) effect).apoth$setAttributeValues(values);
    }

    public static Applied resolve(MobEffectInstance effect, Attribute attribute, AttributeModifier original) {
        var values = ((FormulaConsumerCarrier) effect).apoth$getAttributeValues();
        if (values.isEmpty()) return new Applied(attribute, original);
        String source = BuiltInRegistries.ATTRIBUTE.getKey(attribute).toString();
        for (var value : values.values()) {
            if (!source.equals(value.sourceAttribute()) || !original.getId().toString().equals(value.sourceModifierId())) continue;
            var target = BuiltInRegistries.ATTRIBUTE.getOptional(new ResourceLocation(value.targetAttribute())).orElse(null);
            if (target != null) return new Applied(target, new AttributeModifier(UUID.fromString(value.modifierId()), "spell_compiler", value.amount(), value.operation().nativeOperation()));
        }
        return new Applied(attribute, original);
    }

    public static void clear(MobEffectInstance effect, AttributeMap attributes) {
        var effects = INSTALLED.get(attributes);
        if (effects == null && ((FormulaConsumerCarrier) effect).apoth$getAttributeValues().isEmpty()) return;
        Collection<FormulaAttributes.Value> previous = effects == null ? null : effects.remove(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect()).toString());
        if (previous == null) previous = ((FormulaConsumerCarrier) effect).apoth$getAttributeValues().values();
        if (previous != null) for (var value : previous) {
            var holder = BuiltInRegistries.ATTRIBUTE.getOptional(new ResourceLocation(value.targetAttribute())).orElse(null);
            var instance = holder == null ? null : attributes.getInstance(holder);
            if (instance != null) instance.removeModifier(UUID.fromString(value.modifierId()));
        }
        if (effects != null && effects.isEmpty()) INSTALLED.remove(attributes);
    }

    public static void apply(MobEffectInstance effect, AttributeMap attributes) {
        clear(effect, attributes);
        if (((FormulaConsumerCarrier) effect).apoth$getAttributeValues().isEmpty()) return;
        List<FormulaAttributes.Value> installed = new ArrayList<>();
        FormulaConsumerHooks.createModifiers(effect.getEffect(), effect.getAmplifier(), (attribute, original) -> {
            var replacement = resolve(effect, attribute, original);
            if (replacement.modifier() == original) return;
            var target = attributes.getInstance(replacement.attribute());
            if (target == null) {
                var carrier = (FormulaConsumerCarrier) effect;
                var remaining = new LinkedHashMap<>(carrier.apoth$getAttributeValues());
                remaining.entrySet().removeIf(entry -> entry.getValue().sourceAttribute().equals(BuiltInRegistries.ATTRIBUTE.getKey(attribute).toString())
                        && entry.getValue().sourceModifierId().equals(original.getId().toString()));
                carrier.apoth$setAttributeValues(remaining);
                String id = BuiltInRegistries.ATTRIBUTE.getKey(replacement.attribute()).toString();
                if (MISSING.add(id)) com.example.spell_compiler.SpellCompiler.LOGGER.warn("[SpellFormula] Target attribute unavailable on recipient; retaining native modifier: {}", id);
                return;
            }
            var source = attributes.getInstance(attribute);
            if (source != null) source.removeModifier(original.getId());
            target.removeModifier(replacement.modifier().getId());
            target.addPermanentModifier(replacement.modifier());
            installed.add(new FormulaAttributes.Value(BuiltInRegistries.ATTRIBUTE.getKey(attribute).toString(), original.getId().toString(),
                    BuiltInRegistries.ATTRIBUTE.getKey(replacement.attribute()).toString(), replacement.modifier().getId().toString(),
                    FormulaAttributes.Operation.fromNative(replacement.modifier().getOperation().name()), replacement.modifier().getAmount()));
        });
        if (!installed.isEmpty()) INSTALLED.computeIfAbsent(attributes, ignored -> new HashMap<>())
                .put(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect()).toString(), List.copyOf(installed));
    }

    public static CompoundTag write(Map<String, FormulaAttributes.Value> values) {
        var tag = new CompoundTag();
        values.forEach((key, value) -> {
            var entry = new CompoundTag();
            entry.putString("source", value.sourceAttribute()); entry.putString("source_id", value.sourceModifierId());
            entry.putString("target", value.targetAttribute()); entry.putString("id", value.modifierId());
            entry.putString("operation", value.operation().id()); entry.putDouble("amount", value.amount()); tag.put(key, entry);
        });
        return tag;
    }

    static <T> T encode(Map<String, FormulaAttributes.Value> values, com.mojang.serialization.DynamicOps<T> ops) {
        return ops.createMap(values.entrySet().stream().map(entry -> {
            var value = entry.getValue();
            return com.mojang.datafixers.util.Pair.of(ops.createString(entry.getKey()), ops.createMap(java.util.stream.Stream.of(
                    com.mojang.datafixers.util.Pair.of(ops.createString("source"), ops.createString(value.sourceAttribute())),
                    com.mojang.datafixers.util.Pair.of(ops.createString("source_id"), ops.createString(value.sourceModifierId())),
                    com.mojang.datafixers.util.Pair.of(ops.createString("target"), ops.createString(value.targetAttribute())),
                    com.mojang.datafixers.util.Pair.of(ops.createString("id"), ops.createString(value.modifierId())),
                    com.mojang.datafixers.util.Pair.of(ops.createString("operation"), ops.createString(value.operation().id())),
                    com.mojang.datafixers.util.Pair.of(ops.createString("amount"), ops.createDouble(value.amount())))));
        }));
    }

    public static Map<String, FormulaAttributes.Value> read(CompoundTag tag) {
        if (tag.getAllKeys().size() > 32) throw new IllegalArgumentException("Too many attribute values");
        Map<String, FormulaAttributes.Value> values = new LinkedHashMap<>();
        for (String key : tag.getAllKeys()) {
            var e = tag.getCompound(key);
            for (String id : List.of("source", "target")) new ResourceLocation(e.getString(id));
            UUID.fromString(e.getString("source_id")); UUID.fromString(e.getString("id"));
            double amount = e.getDouble("amount");
            if (!Double.isFinite(amount)) throw new IllegalArgumentException("Non-finite attribute value");
            values.put(key, new FormulaAttributes.Value(e.getString("source"), e.getString("source_id"), e.getString("target"),
                    e.getString("id"), FormulaAttributes.Operation.parse(e.getString("operation")), amount));
        }
        return Map.copyOf(values);
    }
}
