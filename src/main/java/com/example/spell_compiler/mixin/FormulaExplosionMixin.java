package com.example.spell_compiler.mixin;

import com.example.spell_compiler.formula.FormulaExplosionHooks;
import com.example.spell_compiler.formula.FormulaExplosionSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Explosion.class)
public abstract class FormulaExplosionMixin implements FormulaExplosionSource {
    @Shadow @Final private DamageSource damageSource;
    @Shadow @Final private Level level;
    @Shadow @Final private double x;
    @Shadow @Final private double y;
    @Shadow @Final private double z;
    @Unique private String apoth$explosionFormulaKey;
    @Unique private Entity apoth$explosionFormulaSource;
    @Unique private boolean apoth$anonymousExplosionSource;

    @Override public String apoth$getExplosionFormulaKey() { return apoth$explosionFormulaKey; }
    @Override public Entity apoth$getExplosionFormulaSource() { return apoth$explosionFormulaSource; }
    @Override public DamageSource apoth$getExplosionDamageSource() { return damageSource; }
    @Override public Level apoth$getExplosionLevel() { return level; }
    @Override public double apoth$getExplosionX() { return x; }
    @Override public double apoth$getExplosionY() { return y; }
    @Override public double apoth$getExplosionZ() { return z; }
    @Override public boolean apoth$isAnonymousExplosionSource() { return apoth$anonymousExplosionSource; }
    @Override public void apoth$setExplosionFormulaSource(String key, Entity source, boolean anonymous) {
        if (apoth$explosionFormulaKey == null) {
            apoth$explosionFormulaKey = key;
            apoth$explosionFormulaSource = source;
            apoth$anonymousExplosionSource = anonymous;
        }
    }

    @Inject(method = "<init>*", at = @At("TAIL"))
    private void apoth$bindFormulaSource(CallbackInfo callback) { FormulaExplosionHooks.attachPending(this); }

    @ModifyArg(method = "explode", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"), index = 1)
    private float apoth$modifyFormulaDamage(float original) { return FormulaExplosionHooks.modify(this, original); }
}
