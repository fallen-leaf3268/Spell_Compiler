package com.example.spell_compiler.mixin.compat;

import com.example.spell_compiler.formula.FormulaConsumerHooks;
import com.example.spell_compiler.formula.FormulaAttributeRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

@Pseudo
@Mixin(targets = "dev.shadowsoffire.apothic_attributes.client.AttributesLibClient", remap = false)
public abstract class AttributesLibClientMixin {
    @WrapOperation(method = "effectGuiTooltips(Lnet/neoforged/neoforge/client/event/GatherEffectScreenTooltipsEvent;)V",
            remap = false, require = 1, allow = 1, at = @At(value = "INVOKE", remap = false,
            target = "Ljava/util/Map$Entry;getKey()Ljava/lang/Object;"))
    private Object compiler$effectTarget(Map.Entry<Holder<Attribute>, MobEffect.AttributeTemplate> entry,
                                         Operation<Object> original, @Local MobEffectInstance instance) {
        var attribute = (Holder<Attribute>) original.call(entry);
        return FormulaAttributeRuntime.resolve(instance, attribute, entry.getValue().create(instance.getAmplifier())).attribute();
    }

    @WrapOperation(method = "effectGuiTooltips(Lnet/neoforged/neoforge/client/event/GatherEffectScreenTooltipsEvent;)V",
            remap = false, require = 1, allow = 1, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/effect/MobEffect$AttributeTemplate;create(I)Lnet/minecraft/world/entity/ai/attributes/AttributeModifier;"))
    private AttributeModifier compiler$effectAttribute(MobEffect.AttributeTemplate template, int amplifier,
                                                        Operation<AttributeModifier> original,
                                                        @Local MobEffectInstance instance,
                                                        @Local Map.Entry<Holder<Attribute>, MobEffect.AttributeTemplate> entry) {
        return FormulaConsumerHooks.attributeModifier(instance, entry.getKey(), original.call(template, amplifier));
    }
}
