package com.example.spell_compiler.mixin;

import com.example.spell_compiler.formula.FormulaNumberFormatting;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "io.redspace.ironsspellbooks.util.TooltipsUtils", remap = false)
public abstract class FormulaTooltipTimeMixin {
    @WrapOperation(method = {"formatActiveSpellTooltip", "formatScrollTooltip"}, require = 1,
            at = @At(value = "INVOKE", target = "Lio/redspace/ironsspellbooks/api/util/Utils;timeFromTicks(FI)Ljava/lang/String;"))
    private static String compiler$tooltipTime(float ticks, int precision, Operation<String> original) {
        return FormulaNumberFormatting.timeFromTicks(ticks, precision);
    }
}
