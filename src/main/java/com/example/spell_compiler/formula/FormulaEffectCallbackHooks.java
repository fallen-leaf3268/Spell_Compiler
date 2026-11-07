package com.example.spell_compiler.formula;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;

public final class FormulaEffectCallbackHooks {
    private FormulaEffectCallbackHooks() {}

    @SuppressWarnings("unchecked")
    public static void inherit(Object created, Object host, Object holder) {
        if (!(host instanceof LivingEntity entity) || entity.level().isClientSide
                || !(holder instanceof Holder<?> effect) || !(effect.value() instanceof MobEffect)) return;
        var instance = entity.getEffect((Holder<MobEffect>) effect);
        if (instance != null) FormulaEntityContext.inherit(created, instance);
    }
}
