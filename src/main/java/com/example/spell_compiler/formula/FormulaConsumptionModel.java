package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;
import java.util.Set;

public final class FormulaConsumptionModel {
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String EFFECT = "net/minecraft/world/effect/MobEffectInstance";
    private static final String HOLDER = "Lnet/minecraft/world/effect/MobEffect;";
    private static final Set<String> EFFECT_CONSTRUCTORS = Set.of('(' + HOLDER + "I)V", '(' + HOLDER + "II)V",
            '(' + HOLDER + "IIZZ)V", '(' + HOLDER + "IIZZZ)V", '(' + HOLDER + "IIZZZL" + EFFECT + ";Ljava/util/Optional;)V");

    public enum Proof { TERMINAL, EFFECT_PUBLICATION, EFFECT_EVENT }
    public record Consumption(FormulaAutomaticSites.Kind kind, int argument, Proof proof) {}

    private FormulaConsumptionModel() {}

    public static List<Consumption> classify(FormulaClassRepository repository, MethodInsnNode call) {
        return classify(repository, call, repository.resolveMethod(call.owner, call.name, call.desc));
    }

    public static List<Consumption> classify(FormulaClassRepository repository, MethodInsnNode call,
                                             FormulaClassRepository.ResolvedMethod implementation) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC && Set.of("hurt", "m_6469_").contains(FormulaMinecraftNames.method(call.name))
                && call.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;F)Z")
                && repository.isSubtype(call.owner, ENTITY) && platformImplementation(call, implementation, "net/minecraft/"))
            return List.of(new Consumption(FormulaAutomaticSites.Kind.DAMAGE, 1, Proof.TERMINAL));
        if (call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals("io/redspace/ironsspellbooks/damage/DamageSources") && call.name.equals("applyDamage")
                && call.desc.equals("(L" + ENTITY + ";FLnet/minecraft/world/damagesource/DamageSource;)Z"))
            return List.of(new Consumption(FormulaAutomaticSites.Kind.DAMAGE, 1, Proof.TERMINAL));
        if (call.getOpcode() != Opcodes.INVOKESTATIC && Set.of("heal", "m_5634_").contains(FormulaMinecraftNames.method(call.name))
                && call.desc.equals("(F)V") && repository.isSubtype(call.owner, LIVING)
                && platformImplementation(call, implementation, "net/minecraft/"))
            return List.of(new Consumption(FormulaAutomaticSites.Kind.HEALING, 0, Proof.TERMINAL));
        if (call.getOpcode() != Opcodes.INVOKESTATIC && Set.of("setDeltaMovement", "m_20334_", "push").contains(FormulaMinecraftNames.method(call.name))
                && call.desc.equals("(DDD)V") && repository.isSubtype(call.owner, ENTITY)
                && platformImplementation(call, implementation, "net/minecraft/"))
            return List.of(new Consumption(FormulaAutomaticSites.Kind.MOVEMENT_X, 0, Proof.TERMINAL),
                    new Consumption(FormulaAutomaticSites.Kind.MOVEMENT_Y, 1, Proof.TERMINAL),
                    new Consumption(FormulaAutomaticSites.Kind.MOVEMENT_Z, 2, Proof.TERMINAL));
        if (call.getOpcode() != Opcodes.INVOKESTATIC && FormulaMinecraftNames.method(call.name).equals("knockback") && call.desc.equals("(DDD)V")
                && repository.isSubtype(call.owner, LIVING) && platformImplementation(call, implementation, "net/minecraft/"))
            return List.of(new Consumption(FormulaAutomaticSites.Kind.MOVEMENT_KNOCKBACK_STRENGTH, 0, Proof.TERMINAL),
                    new Consumption(FormulaAutomaticSites.Kind.MOVEMENT_KNOCKBACK_X_DIRECTION, 1, Proof.TERMINAL),
                    new Consumption(FormulaAutomaticSites.Kind.MOVEMENT_KNOCKBACK_Z_DIRECTION, 2, Proof.TERMINAL));
        if (call.getOpcode() != Opcodes.INVOKESTATIC && call.name.equals("setAmount") && call.desc.equals("(F)V")
                && platformImplementation(call, implementation, "net/minecraftforge/")) {
            String event = implementation.owner().name;
            if (event.equals("net/minecraftforge/event/entity/living/LivingHealEvent"))
                return List.of(new Consumption(FormulaAutomaticSites.Kind.EVENT_HEALING, 0, Proof.EFFECT_EVENT));
            if (Set.of("net/minecraftforge/event/entity/living/LivingHurtEvent",
                    "net/minecraftforge/event/entity/living/LivingDamageEvent").contains(event))
                return List.of(new Consumption(FormulaAutomaticSites.Kind.EVENT_DAMAGE, 0, Proof.EFFECT_EVENT));
        }
        if (call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(EFFECT) && call.name.equals("<init>")
                && EFFECT_CONSTRUCTORS.contains(call.desc)) {
            var duration = new Consumption(FormulaAutomaticSites.Kind.DURATION, 1, Proof.EFFECT_PUBLICATION);
            return Type.getArgumentTypes(call.desc).length == 2 ? List.of(duration)
                    : List.of(duration, new Consumption(FormulaAutomaticSites.Kind.EFFECT_LEVEL, 2, Proof.EFFECT_PUBLICATION));
        }
        return List.of();
    }

    public static boolean vectorMovementTerminal(FormulaClassRepository repository, MethodInsnNode call) {
        return vectorMovementTerminal(repository, call, repository.resolveMethod(call.owner, call.name, call.desc));
    }

    public static boolean vectorMovementTerminal(FormulaClassRepository repository, MethodInsnNode call,
            FormulaClassRepository.ResolvedMethod implementation) {
        return call.getOpcode() != Opcodes.INVOKESTATIC && Set.of("setDeltaMovement", "m_20256_").contains(FormulaMinecraftNames.method(call.name))
                && call.desc.equals("(Lnet/minecraft/world/phys/Vec3;)V") && repository.isSubtype(call.owner, ENTITY)
                && platformImplementation(call, implementation, "net/minecraft/");
    }

    private static boolean platformImplementation(MethodInsnNode call, FormulaClassRepository.ResolvedMethod implementation,
                                                   String namespace) {
        return implementation != null && implementation.owner().name.startsWith(namespace)
                && implementation.method().name.equals(call.name) && implementation.method().desc.equals(call.desc)
                && (implementation.method().access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                && implementation.method().instructions.size() != 0;
    }
}
