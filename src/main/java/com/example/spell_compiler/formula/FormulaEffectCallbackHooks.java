package com.example.spell_compiler.formula;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;

public final class FormulaEffectCallbackHooks {
    private FormulaEffectCallbackHooks() {}

    public static void inherit(Object created, Object host, Object holder) {
        if (!(host instanceof LivingEntity entity) || entity.level().isClientSide) return;
        Object resolved = holder instanceof java.util.function.Supplier<?> supplier ? supplier.get() : holder;
        if (!(resolved instanceof MobEffect effect)) return;
        var instance = entity.getEffect(effect);
        if (instance != null) FormulaEntityContext.inherit(created, instance);
    }
}
