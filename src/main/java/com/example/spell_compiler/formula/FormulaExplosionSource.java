package com.example.spell_compiler.formula;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;

public interface FormulaExplosionSource {
    String apoth$getExplosionFormulaKey();
    Entity apoth$getExplosionFormulaSource();
    DamageSource apoth$getExplosionDamageSource();
    Level apoth$getExplosionLevel();
    double apoth$getExplosionX();
    double apoth$getExplosionY();
    double apoth$getExplosionZ();
    boolean apoth$isAnonymousExplosionSource();
    void apoth$setExplosionFormulaSource(String key, Entity source, boolean anonymous);
}
