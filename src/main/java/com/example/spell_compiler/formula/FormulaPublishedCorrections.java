package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class FormulaPublishedCorrections {
    private record Selected(MethodNode method, AbstractInsnNode point, int projectileSlot) {}

    private static final String SPELL = "io/redspace/ironsspellbooks/spells/nature/AcidOrbSpell";
    private static final String ORB = "io/redspace/ironsspellbooks/entity/spells/acid_orb/AcidOrb";
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaPublishedCorrectionsHooks";
    private static final String ON_CAST = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/spells/CastSource;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V";
    private static final String ON_HIT = "(Lnet/minecraft/world/phys/HitResult;)V";
    private static final String VALUE_ARGUMENTS = "(ILnet/minecraft/world/entity/LivingEntity;)";

    private FormulaPublishedCorrections() {}

    public static Set<String> targetClasses() {
        return Set.of(SPELL.replace('/', '.'), ORB.replace('/', '.'));
    }

    public static void validate(FormulaClassRepository repository) {
        for (String owner : targetClasses()) {
            var source = repository.classNode(owner.replace('.', '/'));
            if (source == null) throw mismatch(owner, "owner is unavailable");
            select(source);
        }
    }

    public static void apply(String owner, ClassNode source) {
        if (!targetClasses().contains(owner.replace('/', '.'))) return;
        if (!source.name.equals(owner.replace('.', '/'))) throw mismatch(owner, "class name differs");
        List<MethodNode> copies = new ArrayList<>();
        for (var original : source.methods) {
            var copy = new MethodNode(Opcodes.ASM9, original.access, original.name, original.desc, original.signature,
                    original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
            original.accept(copy);
            copies.add(copy);
        }
        var candidate = new ClassNode();
        candidate.name = source.name;
        candidate.methods = copies;
        var selected = select(candidate);
        if (source.name.equals(SPELL)) capture(selected);
        else radius(selected);
        source.methods = copies;
    }

    private static Selected select(ClassNode source) {
        boolean spell = source.name.equals(SPELL);
        var matches = source.methods.stream().filter(method -> spell
                ? method.name.equals("onCast") && method.desc.equals(ON_CAST)
                : Set.of("onHit", "m_6532_").contains(method.name) && method.desc.equals(ON_HIT)).toList();
        if (matches.size() != 1) throw mismatch(source.name, "method count=" + matches.size());
        var method = matches.get(0);
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0)
            throw mismatch(source.name, "method has no instance body");
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOKS))
                throw mismatch(source.name, "consumer was already modified");
        return spell ? castPoint(source.name, method) : hitPoint(source.name, method);
    }

    private static Selected castPoint(String owner, MethodNode method) {
        List<MethodInsnNode> points = new ArrayList<>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(ORB) && call.name.equals("setExplosionRadius") && call.desc.equals("(F)V"))
                points.add(call);
        if (points.size() != 1) throw mismatch(owner, "radius setter count=" + points.size());
        var point = points.get(0);
        var getter = previous(point);
        var caster = previous(getter);
        var level = previous(caster);
        var self = previous(level);
        var projectile = previous(self);
        if (!(getter instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !call.owner.equals(SPELL) || !call.name.equals("getRadius") || !call.desc.equals(VALUE_ARGUMENTS + 'F')
                || !load(caster, Opcodes.ALOAD, 3) || !load(level, Opcodes.ILOAD, 2) || !load(self, Opcodes.ALOAD, 0)
                || !(projectile instanceof VarInsnNode value) || value.getOpcode() != Opcodes.ALOAD || value.var < 6)
            throw mismatch(owner, "radius getter/setter input differs");
        return new Selected(method, point, ((VarInsnNode) projectile).var);
    }

    private static Selected hitPoint(String owner, MethodNode method) {
        List<AbstractInsnNode> points = new ArrayList<>();
        for (var instruction : method.instructions)
            if (instruction instanceof LdcInsnNode constant && constant.cst.equals(3.5f)
                    && next(instruction) instanceof VarInsnNode store && store.getOpcode() == Opcodes.FSTORE)
                points.add(instruction);
        if (points.size() != 1) throw mismatch(owner, "native radius count=" + points.size());
        int radiusSlot = ((VarInsnNode) next(points.get(0))).var;
        int reads = 0;
        int bounds = 0;
        int duration = 0;
        int amplifier = 0;
        for (var instruction : method.instructions) {
            if (load(instruction, Opcodes.FLOAD, radiusSlot)) reads++;
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/world/phys/AABB") && Set.of("inflate", "m_82400_").contains(call.name)
                        && call.desc.equals("(D)Lnet/minecraft/world/phys/AABB;")) bounds++;
                if (call.owner.equals(ORB) && call.name.equals("getRendDuration") && call.desc.equals("()I")) duration++;
                if (call.owner.equals(ORB) && call.name.equals("getRendLevel") && call.desc.equals("()I")) amplifier++;
            }
        }
        if (reads != 2 || bounds != 1 || duration != 1 || amplifier != 1)
            throw mismatch(owner, "radius consumers differ: reads=" + reads + " bounds=" + bounds
                    + " duration=" + duration + " amplifier=" + amplifier);
        return new Selected(method, points.get(0), -1);
    }

    private static void capture(Selected selected) {
        var code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, selected.projectileSlot()));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "captureAcidRadius",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
        selected.method().instructions.insert(selected.point(), code);
        selected.method().maxStack += 3;
    }

    private static void radius(Selected selected) {
        var code = new InsnList();
        var original = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "hasAcidRadius", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, original));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ORB, "getExplosionRadius", "()F", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "validAcidRadius", "(F)F", false));
        code.add(original);
        selected.method().instructions.insert(selected.point(), code);
        selected.method().maxStack += 1;
    }

    private static boolean load(AbstractInsnNode instruction, int opcode, int slot) {
        return instruction instanceof VarInsnNode load && load.getOpcode() == opcode && load.var == slot;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        var previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        var next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static IllegalArgumentException mismatch(String owner, String reason) {
        return new IllegalArgumentException("Native published formula consumer differs: " + owner + ' ' + reason);
    }
}
