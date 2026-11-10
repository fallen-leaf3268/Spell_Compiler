package com.example.spell_compiler.mixin;

import com.example.spell_compiler.formula.FormulaLifestealControls;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "io.redspace.ironsspellbooks.damage.SpellDamageSource", remap = false)
public abstract class FormulaLifestealMixin {
    @ModifyReturnValue(method = "getLifestealPercent()F", at = @At("RETURN"), require = 1, allow = 1)
    private float apoth_formulaLifestealRatio(float original) {
        return FormulaLifestealControls.lifestealRatio(original, this);
    }

    @Mixin(targets = "io.redspace.ironsspellbooks.damage.DamageSources", remap = false)
    public abstract static class Consumer {}
}
