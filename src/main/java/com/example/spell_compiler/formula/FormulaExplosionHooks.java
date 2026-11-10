package com.example.spell_compiler.formula;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.Deque;

public final class FormulaExplosionHooks {
    private static final ThreadLocal<Deque<Pending>> PENDING = new ThreadLocal<>();

    private static final class Pending {
        private final Entity source;
        private final String point;
        private boolean consumed;

        private Pending(Entity source, String point) { this.source = source; this.point = point; }
    }

    private FormulaExplosionHooks() {}

    public static void bind(Object candidate, Object source, String point) {
        if (!(candidate instanceof Explosion explosion) || !(candidate instanceof FormulaExplosionSource carrier)
                || !(source instanceof Entity entity) || !(entity.level() instanceof ServerLevel)
                || carrier.apoth$getExplosionFormulaKey() != null) return;
        if (!entity.getPersistentData().contains(FormulaEntityContext.DATA_KEY, Tag.TAG_COMPOUND)) return;
        String spell = entity.getPersistentData().getCompound(FormulaEntityContext.DATA_KEY).getString("class");
        FormulaNumericSites.Definition selected = null;
        boolean anonymous = false;
        for (var binding : FormulaExplosionBindings.bindings(point)) {
            if (!binding.sourceEntityClass().equals(entity.getClass().getName())
                    || !binding.definition().spellClassName().equals(spell)
                    || !FormulaExplosionBindings.available(binding.definition().dispatchKey())) continue;
            boolean candidateAnonymous = binding.sourceProof() == FormulaExplosionBindings.SourceProof.ORIGIN_POSITION;
            if (candidateAnonymous ? !anonymousOrigin(explosion, carrier, entity) : !sourceMatches(explosion, entity)) continue;
            if (selected != null && !selected.equals(binding.definition())) return;
            selected = binding.definition();
            anonymous = candidateAnonymous;
        }
        if (selected != null) carrier.apoth$setExplosionFormulaSource(selected.dispatchKey(), entity, anonymous);
    }

    public static void enter(Object source, String point) {
        Deque<Pending> stack = PENDING.get();
        if (stack == null) { stack = new ArrayDeque<>(); PENDING.set(stack); }
        stack.push(new Pending(source instanceof Entity entity ? entity : null, point));
    }

    public static void exit() {
        Deque<Pending> stack = PENDING.get();
        if (stack == null) return;
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) PENDING.remove();
    }

    public static void attachPending(Object explosion) {
        Deque<Pending> stack = PENDING.get();
        if (stack == null || stack.isEmpty()) return;
        Pending pending = stack.peek();
        if (pending.consumed) return;
        pending.consumed = true;
        bind(explosion, pending.source, pending.point);
    }

    public static float modify(Object explosion, float original) {
        if (!(explosion instanceof FormulaExplosionSource carrier)) return original;
        String key = carrier.apoth$getExplosionFormulaKey();
        if (key == null || !FormulaExplosionBindings.available(key)) return original;
        var definition = FormulaExplosionBindings.find(key);
        Entity source = carrier.apoth$getExplosionFormulaSource();
        if (definition == null || source == null || !(explosion instanceof Explosion nativeExplosion)
                || (carrier.apoth$isAnonymousExplosionSource() ? !anonymousSourcesRemain(nativeExplosion, carrier)
                : !sourceMatches(nativeExplosion, source))) return original;
        Number value = FormulaEntityContext.evaluateSource(definition, original, source);
        return value == null ? original : value.floatValue();
    }

    private static boolean sourceMatches(Explosion explosion, Entity source) {
        return explosion.getDirectSourceEntity() == source || explosion instanceof FormulaExplosionSource carrier
                && carrier.apoth$getExplosionDamageSource() != null && carrier.apoth$getExplosionDamageSource().getDirectEntity() == source;
    }

    private static boolean anonymousOrigin(Explosion explosion, FormulaExplosionSource carrier, Entity source) {
        return anonymousSourcesRemain(explosion, carrier) && carrier.apoth$getExplosionLevel() == source.level()
                && carrier.apoth$getExplosionX() == source.getX() && carrier.apoth$getExplosionY() == source.getY()
                && carrier.apoth$getExplosionZ() == source.getZ();
    }

    private static boolean anonymousSourcesRemain(Explosion explosion, FormulaExplosionSource carrier) {
        return explosion.getDirectSourceEntity() == null && carrier.apoth$getExplosionDamageSource() != null
                && carrier.apoth$getExplosionDamageSource().getDirectEntity() == null;
    }
}
