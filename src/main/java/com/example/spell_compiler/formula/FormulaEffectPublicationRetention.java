package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

final class FormulaEffectPublicationRetention {
    private static final String POISON = "net/minecraft/world/effect/MobEffect";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String DAMAGE = "net/minecraft/world/damagesource/DamageSource";
    private static final String HOOK = "com/example/spell_compiler/formula/FormulaEffectPublicationControls";

    private FormulaEffectPublicationRetention() {}

    static boolean retainedPoison(ClassNode target) {
        if (target == null || !target.name.equals(POISON)) return false;
        target = FormulaRetentionBytecode.analysisCopy(target);
        if (target == null) return false;
        var roots = target.methods.stream().filter(method -> Set.of("applyEffectTick", "m_6742_").contains(method.name)
                && Set.of("(L" + LIVING + ";I)Z", "(L" + LIVING + ";I)V").contains(method.desc)).toList();
        if (roots.size() != 1) return false;
        var repository = FormulaClassRepository.of(target);
        MethodNode root = roots.get(0);
        var flow = FormulaDataFlow.analyze(repository, target.name, root);
        if (!flow.successful()) return false;
        int connected = 0;
        for (var instruction : root.instructions) {
            if (!(instruction instanceof MethodInsnNode hurt) || !hurt.owner.equals(LIVING)
                    || !Set.of("hurt", "m_6469_").contains(hurt.name) || !hurt.desc.equals("(L" + DAMAGE + ";F)Z")
                    || !flow.reachable(hurt) || !flow.parameter(flow.receiver(hurt), 1) || !poisonBranch(root, hurt)
                    || !guarded(root, hurt, flow)) continue;
            var amount = flow.origin(flow.arguments(hurt).get(1));
            if (amount == null || !(amount.instruction() instanceof MethodInsnNode hook) || hook.getOpcode() != Opcodes.INVOKESTATIC
                    || !hook.owner.equals(HOOK) || !hook.name.equals("poisonDamage") || !hook.desc.equals("(FL" + LIVING + ";)F")
                    || !constant(flow.origin(flow.arguments(hook).get(0)), 1)
                    || !flow.parameter(flow.arguments(hook).get(1), 1) || !flow.resultDominates(hook, hurt)) continue;
            connected++;
        }
        return connected == 1;
    }

    static boolean poisonBranch(MethodNode method, AbstractInsnNode consumer) {
        var instructions = FormulaAutomaticInjector.executable(method);
        int at = method.instructions.indexOf(consumer);
        for (int i = 0; i + 2 < instructions.size(); i++) {
            if (!(instructions.get(i) instanceof VarInsnNode receiver) || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0
                    || !(instructions.get(i + 1) instanceof FieldInsnNode effect) || effect.getOpcode() != Opcodes.GETSTATIC
                    || !effect.owner.equals("net/minecraft/world/effect/MobEffects") || !Set.of("POISON", "f_19614_").contains(effect.name)
                    || !effect.desc.equals("Lnet/minecraft/world/effect/MobEffect;")
                    || !(instructions.get(i + 2) instanceof JumpInsnNode branch) || branch.getOpcode() != Opcodes.IF_ACMPNE) continue;
            if (method.instructions.indexOf(branch) < at && method.instructions.indexOf(branch.label) > at) return true;
        }
        return false;
    }

    private static boolean guarded(MethodNode method, AbstractInsnNode call, FormulaDataFlow.Analysis flow) {
        var instructions = FormulaAutomaticInjector.executable(method);
        int limit = instructions.indexOf(call);
        for (int i = 0; i + 3 < limit; i++) {
            if (!(instructions.get(i) instanceof MethodInsnNode health) || !health.owner.equals(LIVING)
                    || !Set.of("getHealth", "m_21223_").contains(health.name) || !health.desc.equals("()F")
                    || !flow.parameter(flow.receiver(health), 1) || instructions.get(i + 1).getOpcode() != Opcodes.FCONST_1
                    || instructions.get(i + 2).getOpcode() != Opcodes.FCMPL
                    || !(instructions.get(i + 3) instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFLE) continue;
            if (method.instructions.indexOf(jump.label) > method.instructions.indexOf(call)
                    && flow.resultDominates(health, call)) return true;
        }
        return false;
    }

    private static boolean constant(FormulaDataFlow.Node node, int value) {
        if (node == null || node.instruction() == null) return false;
        if (node.instruction() instanceof LdcInsnNode literal && literal.cst instanceof Number number) return number.doubleValue() == value;
        if (node.instruction() instanceof IntInsnNode literal
                && (literal.getOpcode() == Opcodes.BIPUSH || literal.getOpcode() == Opcodes.SIPUSH)) return literal.operand == value;
        int opcode = node.instruction().getOpcode();
        return opcode == Opcodes.FCONST_1 && value == 1 || opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5 && opcode - Opcodes.ICONST_0 == value;
    }

}
