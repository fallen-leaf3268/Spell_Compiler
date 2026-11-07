package com.example.spell_compiler.formula;

import net.minecraft.nbt.CompoundTag;

public interface FormulaSourceCarrier {
    CompoundTag apoth$getFormulaSource();
    void apoth$setFormulaSource(CompoundTag source);

    default boolean apoth$hasFormulaSource() {
        return !apoth$getFormulaSource().isEmpty();
    }

    default void apoth$copyFormulaSourceTo(CompoundTag target, String key) {
        CompoundTag source = FormulaEntityContext.copySource(apoth$getFormulaSource());
        if (!source.isEmpty()) target.put(key, source);
    }
}
