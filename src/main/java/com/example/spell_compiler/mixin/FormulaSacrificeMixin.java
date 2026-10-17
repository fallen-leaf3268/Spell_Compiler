package com.example.spell_compiler.mixin;

import com.example.spell_compiler.formula.FormulaIntegration;
import com.example.spell_compiler.formula.FormulaRuntimeDispatcher;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.redspace.ironsspellbooks.spells.blood.SacrificeSpell;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SacrificeSpell.class, remap = false, priority = 1100)
public abstract class FormulaSacrificeMixin {
    @WrapOperation(method = "getUniqueInfo", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"), require = 1)
    private MutableComponent compiler$radiusPreview(String key, Object[] arguments, Operation<MutableComponent> original,
                                                     @Local(argsOnly = true) LivingEntity caster) {
        if (key.equals("ui.irons_spellbooks.radius")) {
            Number configured = null;
            if (caster != null) {
                var invoker = FormulaRuntimeDispatcher.resolve(
                        "io.redspace.ironsspellbooks.spells.blood.SacrificeSpell#getRadius(Lnet/minecraft/world/entity/LivingEntity;)F",
                        caster);
                if (invoker != null) configured = FormulaRuntimeDispatcher.invoke(invoker, this, new Object[]{caster});
            }
            float multiplier = FormulaIntegration.radiusMultiplier(this, caster);
            if (configured != null || multiplier != 1) {
                arguments = java.util.Arrays.copyOf(arguments, arguments.length);
                arguments[0] = (configured == null ? ((Number) arguments[0]).floatValue() : configured.floatValue()) * multiplier;
            }
        }
        return original.call(key, arguments);
    }
}
