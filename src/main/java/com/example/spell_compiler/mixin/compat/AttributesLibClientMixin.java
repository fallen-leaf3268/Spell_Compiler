package com.example.spell_compiler.mixin.compat;

import com.example.spell_compiler.formula.FormulaConsumerHooks;
import com.example.spell_compiler.formula.FormulaAttributeRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

@Pseudo
@Mixin(targets = "dev.shadowsoffire.attributeslib.client.AttributesLibClient", remap = false)
public abstract class AttributesLibClientMixin {
    @WrapOperation(method = "effectGuiTooltips(Ldev/shadowsoffire/attributeslib/api/client/GatherEffectScreenTooltipsEvent;)V",
            remap = false, require = 1, allow = 1, at = @At(value = "INVOKE", remap = false,
            target = "Ljava/util/Map$Entry;getKey()Ljava/lang/Object;"))
    private Object compiler$effectTarget(Map.Entry<Attribute, AttributeModifier> entry,
                                         Operation<Object> original, @Local MobEffectInstance instance) {
        var attribute = (Attribute) original.call(entry);
        return FormulaAttributeRuntime.resolve(instance, attribute, entry.getValue()).attribute();
    }

    @WrapOperation(method = "effectGuiTooltips(Ldev/shadowsoffire/attributeslib/api/client/GatherEffectScreenTooltipsEvent;)V",
            remap = false, require = 1, allow = 1, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/effect/MobEffect;getAttributeModifierValue(ILnet/minecraft/world/entity/ai/attributes/AttributeModifier;)D"))
    private double compiler$effectAttribute(MobEffect effect, int amplifier, AttributeModifier modifier,
                                           Operation<Double> original, @Local MobEffectInstance instance,
                                           @Local Map.Entry<Attribute, AttributeModifier> entry) {
        var scaled = new AttributeModifier(modifier.getId(), modifier.getName(), original.call(effect, amplifier, modifier), modifier.getOperation());
        return FormulaConsumerHooks.attributeModifier(instance, entry.getKey(), scaled).getAmount();
    }

    @WrapOperation(method = "effectGuiTooltips(Ldev/shadowsoffire/attributeslib/api/client/GatherEffectScreenTooltipsEvent;)V",
            remap = false, require = 1, allow = 1, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/entity/ai/attributes/AttributeModifier;getOperation()Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;", ordinal = 0))
    private AttributeModifier.Operation compiler$effectOperation(AttributeModifier modifier,
            Operation<AttributeModifier.Operation> original, @Local MobEffectInstance instance,
            @Local Map.Entry<Attribute, AttributeModifier> entry) {
        var resolved = FormulaAttributeRuntime.resolve(instance, entry.getKey(), modifier);
        return resolved.modifier() == modifier ? original.call(modifier) : resolved.modifier().getOperation();
    }
}
