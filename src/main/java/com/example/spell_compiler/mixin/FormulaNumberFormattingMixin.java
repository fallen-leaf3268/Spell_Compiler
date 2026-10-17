package com.example.spell_compiler.mixin;

import com.example.spell_compiler.formula.FormulaNumberFormatting;
import io.redspace.ironsspellbooks.api.util.Utils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Utils.class, remap = false)
public abstract class FormulaNumberFormattingMixin {
    @Inject(method = "stringTruncation(DI)Ljava/lang/String;", at = @At("HEAD"), cancellable = true, require = 1)
    private static void apoth_wideInteger(double value, int decimalPlaces, CallbackInfoReturnable<String> cir) {
        String replacement = FormulaNumberFormatting.wideInteger(value);
        if (replacement != null) cir.setReturnValue(replacement);
    }
}
