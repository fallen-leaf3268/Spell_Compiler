package com.example.spell_compiler.mixin;

import com.example.spell_compiler.formula.FormulaConsumerCarrier;
import com.example.spell_compiler.formula.FormulaAttributes;
import com.example.spell_compiler.formula.FormulaAttributeRuntime;
import com.example.spell_compiler.formula.FormulaConsumerHooks;
import com.example.spell_compiler.formula.FormulaConsumerNetwork;
import com.example.spell_compiler.formula.FormulaEntityContext;
import com.example.spell_compiler.formula.FormulaSourceCarrier;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Codec;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.alchemy.PotionContents;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.BiConsumer;

public final class FormulaConsumerMixin {
    private FormulaConsumerMixin() {}

    @Mixin(MobEffectInstance.class)
    public static class EffectData implements FormulaConsumerHooks.PersistentEffect {
        @Unique private Map<String, Float> apoth$formulaValues = Map.of();
        @Unique private Map<String, FormulaAttributes.Value> apoth$attributeValues = Map.of();
        @Override public Map<String, FormulaAttributes.Value> apoth$getAttributeValues() { return apoth$attributeValues; }
        @Override public void apoth$setAttributeValues(Map<String, FormulaAttributes.Value> values) { apoth$attributeValues = FormulaAttributeRuntime.valid(((MobEffectInstance) (Object) this).getEffect().unwrapKey().orElseThrow().location().toString(), values); }
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

        @ModifyExpressionValue(method = "<clinit>", require = 1, allow = 1, at = @At(value = "INVOKE",
                target = "Lcom/mojang/serialization/codecs/RecordCodecBuilder;create(Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;"))
        private static Codec<MobEffectInstance> apoth_persistFormulaValues(Codec<MobEffectInstance> original) {
            return FormulaConsumerHooks.withFormulaValues(original);
        }

        @ModifyExpressionValue(method = "<clinit>", require = 1, allow = 1, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/network/codec/StreamCodec;composite(Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Lnet/minecraft/network/codec/StreamCodec;Ljava/util/function/Function;Ljava/util/function/BiFunction;)Lnet/minecraft/network/codec/StreamCodec;"))
        private static StreamCodec<RegistryFriendlyByteBuf, MobEffectInstance> apoth_syncFormulaValues(
                StreamCodec<RegistryFriendlyByteBuf, MobEffectInstance> original) {
            return FormulaConsumerHooks.formulaStreamCodec(original);
        }
    }

    @Mixin(LivingEntity.class)
    public static class Attributes {
        @WrapOperation(method = {"onEffectAdded", "onEffectUpdated"}, require = 1, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/effect/MobEffect;addAttributeModifiers(Lnet/minecraft/world/entity/ai/attributes/AttributeMap;I)V"))
        private void apoth_formulaAttribute(MobEffect effect, AttributeMap attributes, int amplifier,
                                            Operation<Void> original, @Local(argsOnly = true) MobEffectInstance instance) {
            FormulaAttributeRuntime.clear(instance, attributes);
            original.call(effect, attributes, amplifier);
            FormulaConsumerHooks.applyAttributeValues(instance, attributes);
        }
    }

    @Mixin(PotionContents.class)
    public static class Tooltips {
        @WrapOperation(method = "addPotionTooltip(Ljava/lang/Iterable;Ljava/util/function/Consumer;FF)V", require = 1, at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/effect/MobEffect;createModifiers(ILjava/util/function/BiConsumer;)V"))
        private static void apoth_formulaTooltip(MobEffect effect, int amplifier,
                                                 BiConsumer<Holder<Attribute>, AttributeModifier> consumer,
                                                 Operation<Void> original, @Local MobEffectInstance instance) {
            original.call(effect, amplifier, (BiConsumer<Holder<Attribute>, AttributeModifier>) (attribute, modifier) ->
                    {
                        var resolved = FormulaAttributeRuntime.resolve(instance, attribute, modifier);
                        consumer.accept(resolved.attribute(), FormulaConsumerHooks.attributeModifier(instance, attribute, resolved.modifier()));
                    });
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
