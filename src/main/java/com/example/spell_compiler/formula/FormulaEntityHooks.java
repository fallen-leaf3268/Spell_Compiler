package com.example.spell_compiler.formula;

import io.redspace.ironsspellbooks.entity.spells.AoeEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FormulaEntityHooks {
    public static final String DATA_KEY = "spell_compiler:entity_formula_values";
    private static final int VERSION = 1;

    private FormulaEntityHooks() {}

    public static void capture(Object created, Object spell, int level, Object caster) {
        if (!(created instanceof Entity entity) || entity.level().isClientSide || spell == null || level <= 0) return;
        CompoundTag snapshot = captureValues(spell.getClass().getName(), created, spell, level, caster);
        if (!snapshot.isEmpty()) entity.getPersistentData().put(DATA_KEY, snapshot);
    }

    static CompoundTag captureValues(String spellClassName, Object created, Object spell, int level, Object caster) {
        Map<String, Number> values = new LinkedHashMap<>();
        if (level <= 0) return new CompoundTag();
        for (var point : FormulaEntitySites.points(spellClassName)) {
            var definition = point.definition();
            Number original = definition.defaultValue();
            if (created instanceof AoeEntity aoe && created.getClass().getName().equals(point.entityClassName())
                    && definition.alias().equals("hit_interval_ticks")) original = FormulaSpellConfig.aoeInterval(aoe);
            Number value = FormulaNumericHooks.evaluate(definition, original, spell, level, caster);
            if (value != null) values.put(point.pointKey(), value);
        }
        return writeValues(values);
    }

    public static void inherit(Object child, Object parent) {
        if (!(child instanceof Entity entity) || !(parent instanceof Entity source)
                || entity.level().isClientSide || source.level().isClientSide) return;
        inheritValues(entity.getPersistentData(), source.getPersistentData());
    }

    static void inheritValues(CompoundTag destination, CompoundTag source) {
        CompoundTag values = writeValues(readValues(source.getCompound(DATA_KEY)));
        if (!values.isEmpty()) destination.put(DATA_KEY, values);
    }

    public static int readInt(int original, Object entity, String pointKey) {
        if (!(entity instanceof Entity source)) return original;
        return readInt(original, source.getPersistentData().getCompound(DATA_KEY), pointKey);
    }

    public static int readFieldInt(int original, Object entity, String alias) {
        return entity == null ? original : readInt(original, entity, entity.getClass().getName() + '#' + alias);
    }

    static int readInt(int original, CompoundTag snapshot, String pointKey) {
        Number value = readValue(snapshot, pointKey);
        return value == null ? original : value.intValue();
    }

    static CompoundTag writeValues(Map<String, ? extends Number> values) {
        CompoundTag result = new CompoundTag();
        CompoundTag numeric = new CompoundTag();
        if (values != null) for (var entry : values.entrySet()) {
            var definition = FormulaEntitySites.pointDefinition(entry.getKey());
            Number value = valid(definition, entry.getValue());
            if (value != null) numeric.putDouble(entry.getKey(), value.doubleValue());
        }
        if (!numeric.isEmpty()) {
            result.putInt("version", VERSION);
            result.put("values", numeric);
        }
        return result;
    }

    static Map<String, Number> readValues(CompoundTag snapshot) {
        Map<String, Number> values = new LinkedHashMap<>();
        for (String key : FormulaEntitySites.pointKeys()) {
            Number value = readValue(snapshot, key);
            if (value != null) values.put(key, value);
        }
        return Map.copyOf(values);
    }

    private static Number readValue(CompoundTag snapshot, String pointKey) {
        if (!snapshot.contains("version", Tag.TAG_INT) || snapshot.getInt("version") != VERSION
                || !snapshot.contains("values", Tag.TAG_COMPOUND)) return null;
        CompoundTag values = snapshot.getCompound("values");
        if (!values.contains(pointKey, Tag.TAG_DOUBLE)) return null;
        var definition = FormulaEntitySites.pointDefinition(pointKey);
        double value = values.getDouble(pointKey);
        if (definition != null && definition.valueType() == FormulaNumericSites.ValueType.INT
                && value != Math.rint(value)) return null;
        return valid(definition, value);
    }

    private static Number valid(FormulaNumericSites.Definition definition, Number value) {
        if (definition == null || value == null) return null;
        try {
            return FormulaNumericHooks.validate(definition, value);
        } catch (ArithmeticException invalid) {
            return null;
        }
    }
}
