package com.example.spell_compiler.mixin;

import io.redspace.ironsspellbooks.api.config.ModifyDefaultConfigValuesEvent;
import io.redspace.ironsspellbooks.api.config.SpellConfigHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ModifyDefaultConfigValuesEvent.class, remap = false)
public interface ModifySpellConfigAccessor {
    @Accessor("config")
    SpellConfigHolder getSpellCompilerConfig();
}
