package com.example.spell_compiler.mixin;

import com.example.spell_compiler.formula.FormulaConsumerCarrier;
import com.example.spell_compiler.formula.FormulaAttributes;
import com.example.spell_compiler.formula.FormulaAttributeRuntime;
import com.example.spell_compiler.formula.FormulaConsumerHooks;
import com.example.spell_compiler.formula.FormulaConsumerNetwork;
import com.example.spell_compiler.formula.FormulaEntityContext;
import com.example.spell_compiler.formula.FormulaSourceCarrier;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

public final class FormulaConsumerMixin {
    private FormulaConsumerMixin() {}

    @Mixin(MobEffectInstance.class)
    public static class EffectData implements FormulaConsumerHooks.PersistentEffect {
        @Unique private Map<String, Float> apoth$formulaValues = Map.of();
        @Unique private Map<String, FormulaAttributes.Value> apoth$attributeValues = Map.of();
        @Override public Map<String, FormulaAttributes.Value> apoth$getAttributeValues() { return apoth$attributeValues; }
        @Override public void apoth$setAttributeValues(Map<String, FormulaAttributes.Value> values) { apoth$attributeValues = FormulaAttributeRuntime.valid(BuiltInRegistries.MOB_EFFECT.getKey(((MobEffectInstance) (Object) this).getEffect()).toString(), values); }
        @Unique private CompoundTag apoth$formulaSource = new CompoundTag();
        @Shadow private MobEffectInstance hiddenEffect;

        @Override
        public Map<String, Float> apoth$getFormulaValues() { return apoth$formulaValues; }

        @Override
        public void apoth$setFormulaValues(Map<String, Float> values) {
            apoth$formulaValues = FormulaConsumerHooks.validValues(((MobEffectInstance) (Object) this).getEffect(), values);
        }

        @Override
        public MobEffectInstance apoth$getHiddenEffect() { return hiddenEffect; }

        @Override
        public CompoundTag apoth$getFormulaSource() { return apoth$formulaSource.copy(); }

        @Override
        public boolean apoth$hasFormulaSource() { return !apoth$formulaSource.isEmpty(); }

        @Override
        public void apoth$copyFormulaSourceTo(CompoundTag target, String key) {
            CompoundTag source = FormulaEntityContext.copySource(apoth$formulaSource);
            if (!source.isEmpty()) target.put(key, source);
        }

        @Override
        public void apoth$setFormulaSource(CompoundTag source) {
            apoth$formulaSource = FormulaEntityContext.copySource(source);
        }

        @Inject(method = "setDetailsFrom", at = @At("TAIL"), require = 1)
        private void apoth_copyFormulaValues(MobEffectInstance incoming, CallbackInfo ci) {
            apoth$setFormulaValues(((FormulaConsumerCarrier) incoming).apoth$getFormulaValues());
            apoth$setAttributeValues(((FormulaConsumerCarrier) incoming).apoth$getAttributeValues());
            apoth$setFormulaSource(((FormulaSourceCarrier) incoming).apoth$getFormulaSource());
        }

        @Inject(method = "update", require = 2, allow = 2, at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                target = "Lnet/minecraft/world/effect/MobEffectInstance;duration:I", shift = At.Shift.AFTER))
        private void apoth_adoptFormulaValues(MobEffectInstance incoming, CallbackInfoReturnable<Boolean> cir) {
            apoth$setFormulaValues(((FormulaConsumerCarrier) incoming).apoth$getFormulaValues());
            apoth$setAttributeValues(((FormulaConsumerCarrier) incoming).apoth$getAttributeValues());
            apoth$setFormulaSource(((FormulaSourceCarrier) incoming).apoth$getFormulaSource());
        }

        @Inject(method = "save", at = @At("RETURN"), require = 1)
        private void apoth_saveFormulaValues(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
            FormulaConsumerHooks.writeEffectValues((MobEffectInstance) (Object) this, cir.getReturnValue());
        }

        @Inject(method = "loadSpecifiedEffect", at = @At("RETURN"), require = 1)
        private static void apoth_loadFormulaValues(MobEffect effect, CompoundTag tag, CallbackInfoReturnable<MobEffectInstance> cir) {
            if (cir.getReturnValue() != null) FormulaConsumerHooks.readEffectValues(cir.getReturnValue(), tag);
        }
    }

    @Mixin(LivingEntity.class)
    public static class Attributes {
        @WrapOperation(method = {"onEffectAdded", "onEffectUpdated"}, require = 1, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/effect/MobEffect;addAttributeModifiers(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/ai/attributes/AttributeMap;I)V"))
        private void apoth_formulaAttribute(MobEffect effect, LivingEntity entity, AttributeMap attributes, int amplifier,
                                            Operation<Void> original, @Local(argsOnly = true) MobEffectInstance instance) {
            FormulaAttributeRuntime.clear(instance, attributes);
            original.call(effect, entity, attributes, amplifier);
            FormulaConsumerHooks.applyAttributeValues(instance, attributes);
        }
    }

    @Mixin(PotionUtils.class)
    public static class Tooltips {
        @WrapOperation(method = "addPotionTooltip(Ljava/util/List;Ljava/util/List;F)V", require = 1, allow = 1, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/effect/MobEffect;getAttributeModifierValue(ILnet/minecraft/world/entity/ai/attributes/AttributeModifier;)D"))
        private static double apoth_formulaTooltip(MobEffect effect, int amplifier, AttributeModifier modifier,
                                                   Operation<Double> original, @Local MobEffectInstance instance,
                                                   @Local Map.Entry<Attribute, AttributeModifier> entry) {
            var scaled = new AttributeModifier(modifier.getId(), modifier.getName(), original.call(effect, amplifier, modifier), modifier.getOperation());
            return FormulaConsumerHooks.attributeModifier(instance, entry.getKey(), scaled).getAmount();
        }

        @WrapOperation(method = "addPotionTooltip(Ljava/util/List;Ljava/util/List;F)V", require = 1, allow = 1, at = @At(value = "INVOKE",
                target = "Ljava/util/Map$Entry;getKey()Ljava/lang/Object;"))
        private static Object apoth_formulaTooltipTarget(Map.Entry<Attribute, AttributeModifier> entry,
                                                         Operation<Object> original, @Local MobEffectInstance instance) {
            Attribute attribute = (Attribute) original.call(entry);
            return FormulaAttributeRuntime.resolve(instance, attribute, entry.getValue()).attribute();
        }

        @WrapOperation(method = "addPotionTooltip(Ljava/util/List;Ljava/util/List;F)V", require = 1, allow = 1, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/entity/ai/attributes/AttributeModifier;getOperation()Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;", ordinal = 0))
        private static AttributeModifier.Operation apoth_formulaTooltipOperation(AttributeModifier modifier,
                Operation<AttributeModifier.Operation> original, @Local MobEffectInstance instance,
                @Local Map.Entry<Attribute, AttributeModifier> entry) {
            var resolved = FormulaAttributeRuntime.resolve(instance, entry.getKey(), modifier);
            return resolved.modifier() == modifier ? original.call(modifier) : resolved.modifier().getOperation();
        }
    }

    @Mixin(LivingEntity.class)
    public static class Synchronization {
        @Inject(method = "onEffectAdded", at = @At("TAIL"), require = 1)
        private void apoth_addFormulaValues(MobEffectInstance effect, Entity source, CallbackInfo ci) {
            FormulaConsumerNetwork.synchronize((LivingEntity) (Object) this, effect);
        }

        @Inject(method = "onEffectUpdated", at = @At("TAIL"), require = 1)
        private void apoth_updateFormulaValues(MobEffectInstance effect, boolean refresh, Entity source, CallbackInfo ci) {
            FormulaConsumerNetwork.synchronize((LivingEntity) (Object) this, effect);
        }

        @Inject(method = "onEffectRemoved", at = @At("HEAD"), require = 1)
        private void apoth_clearAttributeValues(MobEffectInstance effect, CallbackInfo ci) {
            FormulaAttributeRuntime.clear(effect, ((LivingEntity) (Object) this).getAttributes());
        }

        @Inject(method = "onEffectRemoved", at = @At("TAIL"), require = 1)
        private void apoth_removeFormulaValues(MobEffectInstance effect, CallbackInfo ci) {
            FormulaConsumerNetwork.removed((LivingEntity) (Object) this, effect);
        }
    }
}
