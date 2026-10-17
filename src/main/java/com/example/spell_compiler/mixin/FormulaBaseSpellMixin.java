package com.example.spell_compiler.mixin;

import com.example.spell_compiler.formula.FormulaRuntimeHooks;
import com.example.spell_compiler.formula.FormulaIntegration;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = AbstractSpell.class, remap = false, priority = 1100)
public abstract class FormulaBaseSpellMixin {
    @ModifyReturnValue(method = "getManaCost", at = @At("RETURN"))
    private int compiler$manaCost(int value, int level) {
        return FormulaIntegration.finishManaCost(FormulaRuntimeHooks.overrideManaCost(value, this, level), this, level);
    }

    @ModifyReturnValue(method = "getSpellCooldown", at = @At("RETURN"))
    private int compiler$cooldown(int value) {
        return FormulaRuntimeHooks.overrideCooldown(value, this);
    }

    @ModifyReturnValue(method = "getCastTime", at = @At("RETURN"))
    private int compiler$castTime(int value, int level) {
        return FormulaRuntimeHooks.overrideCastTime(value, this, level);
    }

    @ModifyReturnValue(method = "getSpellPower", at = @At("RETURN"))
    private float compiler$spellPower(float value, int level, Entity caster) {
        return FormulaRuntimeHooks.finishPower(FormulaRuntimeHooks.overrideSpellPower(value, this, level, caster), this, level, caster);
    }

    @ModifyReturnValue(method = "getEntityPowerMultiplier", at = @At("RETURN"))
    private float compiler$entityPower(float value, LivingEntity caster) {
        return FormulaRuntimeHooks.applyEntityPower(value, this, caster);
    }

    @ModifyReturnValue(method = "getEffectiveCastTime", at = @At("RETURN"))
    private int compiler$effectiveCastTime(int value, int level, LivingEntity caster) {
        return FormulaIntegration.finishCastTime(FormulaRuntimeHooks.overrideEffectiveCastTime(value, this, level, caster),
                this, level, caster);
    }
}
