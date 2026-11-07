package com.example.spell_compiler.formula;

import com.mojang.serialization.DynamicOps;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaEntityContext {
    static final String DATA_KEY = "spell_compiler:source_context";
    private static final Logger LOGGER = LogManager.getLogger(FormulaEntityContext.class);
    private static final Set<String> FAILURES = ConcurrentHashMap.newKeySet();

    private FormulaEntityContext() {}

    public static void captureScoped(Object created, String registryOwner, String registryField) {
        var frame = FormulaCastContext.peek();
        if (frame != null && frame.spell() != null
                && FormulaEntityFlow.hasVerifiedEffectSource(frame.spell().getClass().getName(), registryOwner, registryField))
            capture(created, frame.spell(), frame.level(), frame.caster());
    }

    public static void capture(Object created, Object spell, int level, Object caster) {
        if (level <= 0 || !(spell instanceof AbstractSpell source) || !(caster instanceof LivingEntity living)
                || living.level().isClientSide
                || !(created instanceof Entity) && !(created instanceof MobEffectInstance && created instanceof FormulaSourceCarrier)
                || created instanceof Entity entity && entity.level().isClientSide) return;
        ResourceLocation id = SpellRegistry.REGISTRY.getKey(source);
        if (id == null) return;
        try {
            if (created instanceof MobEffectInstance) FormulaConsumerHooks.captureAttributes(created, spell, level, caster);
            double power = living.getAttributeValue(AttributeRegistry.SPELL_POWER);
            double school = source.getSchoolType().getPowerFor(living);
            double multiplier = FormulaSpellConfig.number(source, "power_multiplier");
            CompoundTag data = snapshot(id.toString(), source.getClass().getName(), level, power, school, multiplier);
            data.putUUID("caster", living.getUUID());
            data.putString("dimension", living.level().dimension().location().toString());
            if (created instanceof Entity entity) entity.getPersistentData().put(DATA_KEY, data);
            else ((FormulaSourceCarrier) created).apoth$setFormulaSource(data);
        } catch (RuntimeException | LinkageError failure) {
            if (FAILURES.add(source.getClass().getName()))
                LOGGER.error("[SpellFormula] {} 无法保存实体施法来源，保留下游原生数值", id, failure);
        }
    }

    public static void inherit(Object child, Object parent) {
        if (!(child instanceof Entity entity) || entity.level().isClientSide
                || parent instanceof Entity source && source.level().isClientSide) return;
        if (parent instanceof Entity source) {
            CompoundTag context = source.getPersistentData().getCompound(DATA_KEY);
            if (valid(context)) entity.getPersistentData().put(DATA_KEY, context.copy());
        } else if (parent instanceof MobEffectInstance && parent instanceof FormulaSourceCarrier source) {
            source.apoth$copyFormulaSourceTo(entity.getPersistentData(), DATA_KEY);
        }
    }

    public static CompoundTag copySource(CompoundTag source) {
        return source != null && valid(source) ? source.copy() : new CompoundTag();
    }

    static <T> CompoundTag decodeSource(DynamicOps<T> ops, T encoded) {
        Number version = number(ops, encoded, "version");
        Number level = number(ops, encoded, "level");
        Number power = number(ops, encoded, "power");
        Number school = number(ops, encoded, "school");
        Number multiplier = number(ops, encoded, "multiplier");
        String spell = ops.get(encoded, "spell").flatMap(ops::getStringValue).result().orElse("");
        String owner = ops.get(encoded, "class").flatMap(ops::getStringValue).result().orElse("");
        if (version == null || version.doubleValue() != 1 || level == null || level.doubleValue() <= 0
                || level.doubleValue() > Integer.MAX_VALUE || level.doubleValue() != level.intValue()
                || power == null || school == null || multiplier == null) return new CompoundTag();
        try {
            CompoundTag result = snapshot(spell, owner, level.intValue(), power.doubleValue(), school.doubleValue(), multiplier.doubleValue());
            var casterValue = ops.get(encoded, "caster").result();
            if (casterValue.isPresent()) {
                var caster = UUIDUtil.CODEC.parse(ops, casterValue.get()).result();
                if (caster.isEmpty()) return new CompoundTag();
                result.putUUID("caster", caster.get());
            }
            var dimensionValue = ops.get(encoded, "dimension").result();
            if (dimensionValue.isPresent()) {
                String dimension = ops.getStringValue(dimensionValue.get()).result().orElse("");
                if (ResourceLocation.tryParse(dimension) == null) return new CompoundTag();
                result.putString("dimension", dimension);
            }
            return result;
        } catch (IllegalArgumentException invalid) {
            return new CompoundTag();
        }
    }

    private static <T> Number number(DynamicOps<T> ops, T encoded, String name) {
        return ops.get(encoded, name).flatMap(ops::getNumberValue).result().orElse(null);
    }

    static CompoundTag snapshot(String id, String owner, int level, double power, double school, double multiplier) {
        CompoundTag data = new CompoundTag();
        data.putInt("version", 1);
        data.putString("spell", id);
        data.putString("class", owner);
        data.putInt("level", level);
        data.putDouble("power", power);
        data.putDouble("school", school);
        data.putDouble("multiplier", multiplier);
        if (!valid(data)) throw new IllegalArgumentException("实体施法来源包含无效数值");
        return data;
    }

    static boolean valid(CompoundTag data) {
        return valid(data, ResourceLocation.tryParse(data.getString("spell")));
    }

    private static boolean valid(CompoundTag data, ResourceLocation spellId) {
        return data.contains("version", Tag.TAG_INT) && data.getInt("version") == 1
                && data.contains("spell", Tag.TAG_STRING) && spellId != null
                && data.contains("class", Tag.TAG_STRING) && !data.getString("class").isBlank()
                && data.contains("level", Tag.TAG_INT) && data.getInt("level") > 0
                && finite(data, "power") && finite(data, "school") && finite(data, "multiplier");
    }

    private static boolean finite(CompoundTag data, String key) {
        return data.contains(key, Tag.TAG_DOUBLE) && Double.isFinite(data.getDouble(key));
    }

    public static int overrideInt(int original, String key, Object carrier) {
        Number value = evaluate(original, key, carrier);
        return value == null ? original : value.intValue();
    }

    public static float overrideFloat(float original, String key, Object carrier) {
        Number value = evaluate(original, key, carrier);
        return value == null ? original : value.floatValue();
    }

    public static double overrideDouble(double original, String key, Object carrier) {
        Number value = evaluate(original, key, carrier);
        return value == null ? original : value.doubleValue();
    }

    private static Number evaluate(Number original, String key, Object carrier) {
        return evaluate(original, key, carrier, null);
    }

    public static int overrideInt(int original, Object[] inputs, String key, Object carrier) {
        Number value = evaluate(original, key, carrier, inputs);
        return value == null ? original : value.intValue();
    }

    public static float overrideFloat(float original, Object[] inputs, String key, Object carrier) {
        Number value = evaluate(original, key, carrier, inputs);
        return value == null ? original : value.floatValue();
    }

    public static double overrideDouble(double original, Object[] inputs, String key, Object carrier) {
        Number value = evaluate(original, key, carrier, inputs);
        return value == null ? original : value.doubleValue();
    }

    private static Number evaluate(Number original, String key, Object carrier, Object[] inputs) {
        if (!(carrier instanceof Entity entity) || !(entity.level() instanceof ServerLevel level)) return null;
        CompoundTag context = entity.getPersistentData().getCompound(DATA_KEY);
        ResourceLocation spellId = ResourceLocation.tryParse(context.getString("spell"));
        if (!valid(context, spellId)) return null;
        var anchor = FormulaAutomaticSites.find(key);
        if (anchor == null) return null;
        var site = FormulaAutomaticSites.findForSpell(context.getString("class"), anchor);
        if (site == null || !samePoint(anchor, site)) return null;
        var invoker = FormulaRuntimeDispatcher.resolveActive(site.definition().dispatchKey(), entity);
        if (invoker == null) return null;
        try {
            AbstractSpell spell = SpellRegistry.REGISTRY.get(spellId);
            if (spell == null || !spell.getClass().getName().equals(context.getString("class"))) return null;
            Entity caster = null;
            ResourceLocation dimension = ResourceLocation.tryParse(context.getString("dimension"));
            if (dimension != null && context.hasUUID("caster")) {
                ServerLevel sourceLevel = level.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
                if (sourceLevel != null) caster = sourceLevel.getEntity(context.getUUID("caster"));
            }
            Object[] arguments = inputs == null
                    ? new Object[]{original.doubleValue(), context.getInt("level"), context.getDouble("power"),
                    context.getDouble("school"), context.getDouble("multiplier"), spell, caster}
                    : new Object[]{original.doubleValue(), context.getInt("level"), context.getDouble("power"),
                    context.getDouble("school"), context.getDouble("multiplier"), spell, caster, inputs};
            return FormulaRuntimeDispatcher.invoke(invoker, null, arguments);
        } catch (RuntimeException | LinkageError failure) {
            if (FAILURES.add(site.definition().dispatchKey()))
                LOGGER.error("[SpellFormula] {} 无法读取实体施法来源，保留原生值", site.definition().dispatchKey(), failure);
            return null;
        }
    }

    static boolean samePoint(FormulaAutomaticSites.Site first, FormulaAutomaticSites.Site second) {
        return first.owner().equals(second.owner()) && first.methodName().equals(second.methodName())
                && first.methodDescriptor().equals(second.methodDescriptor()) && first.producerIndex() == second.producerIndex()
                && first.argumentIndex() == second.argumentIndex() && first.contextMode() == second.contextMode()
                && first.contextSlot() == second.contextSlot() && first.methodFingerprint().equals(second.methodFingerprint())
                && sameCaptureLayout(first, second)
                && first.kind() == second.kind() && first.definition().valueType() == second.definition().valueType();
    }

    private static boolean sameCaptureLayout(FormulaAutomaticSites.Site first, FormulaAutomaticSites.Site second) {
        if (first.inputs().size() != second.inputs().size()) return false;
        for (int i = 0; i < first.inputs().size(); i++) {
            var left = first.inputs().get(i);
            var right = second.inputs().get(i);
            if (!left.descriptor().equals(right.descriptor()) || left.producerIndex() != right.producerIndex()
                    || left.localSlot() != right.localSlot()) return false;
        }
        return true;
    }
}
