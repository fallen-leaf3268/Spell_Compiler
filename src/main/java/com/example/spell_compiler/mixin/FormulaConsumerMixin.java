package com.example.spell_compiler.mixin;

import com.example.spell_compiler.formula.FormulaConsumerCarrier;
import com.example.spell_compiler.formula.FormulaConsumerHooks;
import com.example.spell_compiler.formula.FormulaConsumerNetwork;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

public final class FormulaConsumerMixin {
    private FormulaConsumerMixin() {}

    @Mixin(MobEffectInstance.class)
    public static class EffectData implements FormulaConsumerCarrier {
        @Unique private Map<String, Float> apoth$formulaValues = Map.of();

        @Override
        public Map<String, Float> apoth$getFormulaValues() { return apoth$formulaValues; }

        @Override
        public void apoth$setFormulaValues(Map<String, Float> values) {
            apoth$formulaValues = FormulaConsumerHooks.validValues(((MobEffectInstance) (Object) this).getEffect(), values);
        }

        @Inject(method = "setDetailsFrom", at = @At("TAIL"))
        private void apoth_copyFormulaValues(MobEffectInstance incoming, CallbackInfo ci) {
            apoth$setFormulaValues(((FormulaConsumerCarrier) incoming).apoth$getFormulaValues());
        }

        @Inject(method = "update", require = 2, allow = 2, at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
                target = "Lnet/minecraft/world/effect/MobEffectInstance;duration:I", shift = At.Shift.AFTER))
        private void apoth_adoptFormulaValues(MobEffectInstance incoming, CallbackInfoReturnable<Boolean> cir) {
            apoth$setFormulaValues(((FormulaConsumerCarrier) incoming).apoth$getFormulaValues());
        }

        @Inject(method = "save", at = @At("RETURN"))
        private void apoth_saveFormulaValues(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
            if (apoth$formulaValues.isEmpty()) cir.getReturnValue().remove(FormulaConsumerHooks.DATA_KEY);
            else cir.getReturnValue().put(FormulaConsumerHooks.DATA_KEY, FormulaConsumerHooks.writeValues(apoth$formulaValues));
        }

        @Inject(method = "loadSpecifiedEffect", at = @At("RETURN"))
        private static void apoth_loadFormulaValues(MobEffect effect, CompoundTag tag, CallbackInfoReturnable<MobEffectInstance> cir) {
            if (cir.getReturnValue() != null)
                ((FormulaConsumerCarrier) cir.getReturnValue()).apoth$setFormulaValues(
                        FormulaConsumerHooks.readValues(effect, tag.getCompound(FormulaConsumerHooks.DATA_KEY)));
        }
    }

    @Mixin(MobEffect.class)
    public static class Attributes {
        @WrapOperation(method = "addAttributeModifiers", at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/effect/MobEffect;getAttributeModifierValue(ILnet/minecraft/world/entity/ai/attributes/AttributeModifier;)D"))
        private double apoth_formulaAttribute(MobEffect effect, int amplifier, AttributeModifier modifier,
                                             Operation<Double> original, @Local(argsOnly = true) LivingEntity entity) {
            return FormulaConsumerHooks.attributeAmount(effect, entity, modifier, original.call(effect, amplifier, modifier));
        }
    }

    @Mixin(LivingEntity.class)
    public static class Synchronization {
        @Inject(method = "onEffectAdded", at = @At("TAIL"))
        private void apoth_addFormulaValues(MobEffectInstance effect, Entity source, CallbackInfo ci) {
            FormulaConsumerNetwork.synchronize((LivingEntity) (Object) this, effect);
        }

        @Inject(method = "onEffectUpdated", at = @At("TAIL"))
        private void apoth_updateFormulaValues(MobEffectInstance effect, boolean refresh, Entity source, CallbackInfo ci) {
            FormulaConsumerNetwork.synchronize((LivingEntity) (Object) this, effect);
        }

        @Inject(method = "onEffectRemoved", at = @At("TAIL"))
        private void apoth_removeFormulaValues(MobEffectInstance effect, CallbackInfo ci) {
            FormulaConsumerNetwork.removed((LivingEntity) (Object) this, effect);
        }
    }
}
