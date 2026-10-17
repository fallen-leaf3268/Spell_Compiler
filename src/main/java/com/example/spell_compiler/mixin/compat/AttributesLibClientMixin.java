package com.example.spell_compiler.mixin.compat;

import com.example.spell_compiler.formula.FormulaConsumerHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "dev.shadowsoffire.attributeslib.client.AttributesLibClient", remap = false)
public abstract class AttributesLibClientMixin {
    @WrapOperation(method = "effectGuiTooltips(Ldev/shadowsoffire/attributeslib/api/client/GatherEffectScreenTooltipsEvent;)V",
            remap = false, require = 1, allow = 1, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/effect/MobEffect;getAttributeModifierValue(ILnet/minecraft/world/entity/ai/attributes/AttributeModifier;)D"))
    private double compiler$effectAttribute(MobEffect effect, int amplifier, AttributeModifier modifier,
                                           Operation<Double> original, @Local MobEffectInstance instance) {
        return FormulaConsumerHooks.attributeAmount(instance, modifier, original.call(effect, amplifier, modifier));
    }
}
