package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class FormulaLegacyContinuousCompatibility {
    private record Selected(MethodNode method, AbstractInsnNode gate, LabelNode finish, int dataSlot) {}

    private static final String MANAGER = "io/redspace/ironsspellbooks/capabilities/magic/MagicManager";
    private static final String DATA = "io/redspace/ironsspellbooks/api/magic/MagicData";
    private static final String SPELL = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String TYPE = "io/redspace/ironsspellbooks/api/spells/CastType";
    private static final String SOURCE = "io/redspace/ironsspellbooks/api/spells/CastSource";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String PLAYER = "net/minecraft/server/level/ServerPlayer";

    private FormulaLegacyContinuousCompatibility() {}

    public static Set<String> targetClasses() { return Set.of(MANAGER.replace('/', '.')); }

    public static boolean compatible(FormulaClassRepository repository) {
        ClassNode owner = repository.classNode(MANAGER);
        if (owner == null) return false;
        try { select(owner); return true; }
        catch (IllegalArgumentException unsupported) { return false; }
    }

    public static void validate(FormulaClassRepository repository) {
        ClassNode owner = repository.classNode(MANAGER);
        if (owner == null) throw mismatch("owner unavailable");
        select(owner);
    }

    public static boolean applyIfCompatible(String owner, ClassNode source) {
        if (!MANAGER.equals(owner.replace('.', '/')) || !MANAGER.equals(source.name)) return false;
        ClassNode candidate = new ClassNode();
        source.accept(candidate);
        Selected selected;
        try { selected = select(candidate); }
        catch (IllegalArgumentException unsupported) { return false; }
        var guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, selected.dataSlot()));
        guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DATA, "getCastDurationRemaining", "()I", false));
        guard.add(new JumpInsnNode(Opcodes.IFLE, selected.finish()));
        selected.method().instructions.insertBefore(selected.gate(), guard);
        selected.method().maxStack += 1;
        source.methods = candidate.methods;
        return true;
    }

    private static Selected select(ClassNode source) {
        if (!source.name.equals(MANAGER)) throw mismatch("owner differs");
        List<Selected> matches = new ArrayList<>();
        for (MethodNode method : source.methods) {
            if (!method.name.equals("lambda$tick$0") || !method.desc.equals("(ZLnet/minecraft/world/entity/player/Player;)V")
                    || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            List<AbstractInsnNode> code = new ArrayList<>();
            for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
            for (int i = 0; i + 14 < code.size(); i++) {
                if (!field(code.get(i + 2), TYPE, "CONTINUOUS", 'L' + TYPE + ';')) continue;
                if (!load(code.get(i), Opcodes.ALOAD, 5)
                        || !call(code.get(i + 1), SPELL, "getCastType", "()L" + TYPE + ';')
                        || !(code.get(i + 3) instanceof JumpInsnNode typeGuard) || typeGuard.getOpcode() != Opcodes.IF_ACMPNE
                        || !load(code.get(i + 4), Opcodes.ALOAD, 4)
                        || !call(code.get(i + 5), DATA, "getCastDurationRemaining", "()I")
                        || code.get(i + 6).getOpcode() != Opcodes.ICONST_1 || code.get(i + 7).getOpcode() != Opcodes.IADD
                        || !integer(code.get(i + 8), 10) || code.get(i + 9).getOpcode() != Opcodes.IREM
                        || !(code.get(i + 10) instanceof JumpInsnNode periodic) || periodic.getOpcode() != Opcodes.IFNE
                        || periodic.label != typeGuard.label
                        || !load(code.get(i + 11), Opcodes.ALOAD, 4)
                        || !call(code.get(i + 12), DATA, "getCastDurationRemaining", "()I")
                        || !integer(code.get(i + 13), 10)
                        || !(code.get(i + 14) instanceof JumpInsnNode finalPulse) || finalPulse.getOpcode() != Opcodes.IF_ICMPLT)
                    throw mismatch("continuous duration gate differs");
                validateFinish(finalPulse.label, typeGuard.label);
                AbstractInsnNode beforeType = code.get(i).getPrevious();
                boolean decrementBefore = false;
                for (var prior = method.instructions.getFirst(); prior != null && prior != beforeType; prior = prior.getNext())
                    if (call(prior, DATA, "handleCastDuration", "()V")) decrementBefore = true;
                if (!decrementBefore) throw mismatch("duration is not decremented before the gate");
                matches.add(new Selected(method, code.get(i + 4), finalPulse.label, 4));
            }
        }
        if (matches.size() != 1) throw mismatch("matching gate count=" + matches.size());
        return matches.get(0);
    }

    private static void validateFinish(LabelNode finish, LabelNode done) {
        List<AbstractInsnNode> code = new ArrayList<>();
        for (var instruction = finish.getNext(); instruction != null && instruction != done; instruction = instruction.getNext()) {
            if (instruction.getOpcode() >= 0) code.add(instruction);
            if (instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO && jump.label == done) break;
        }
        int[] opcodes = {25,25,180,25,182,25,25,182,4,182,25,182,178,166,25,184,25,25,180,25,182,25,25,3,182,167};
        if (code.size() != opcodes.length) throw mismatch("completion branch length differs");
        for (int i = 0; i < opcodes.length; i++) if (code.get(i).getOpcode() != opcodes[i])
            throw mismatch("completion branch instructions differ");
        for (int i : new int[]{0,16}) if (!load(code.get(i), Opcodes.ALOAD, 5)) throw mismatch("completion spell differs");
        for (int i : new int[]{1,5,14,17,21}) if (!load(code.get(i), Opcodes.ALOAD, 3)) throw mismatch("completion player differs");
        for (int i : new int[]{3,6,10,19,22}) if (!load(code.get(i), Opcodes.ALOAD, 4)) throw mismatch("completion data differs");
        for (int i : new int[]{2,18})
            if (!(code.get(i) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(PLAYER) || !field.name.equals("level") || !field.desc.equals('L' + LEVEL + ';'))
                throw mismatch("completion level source differs");
        for (int i : new int[]{4,20}) if (!call(code.get(i), DATA, "getCastingSpellLevel", "()I")) throw mismatch("completion level differs");
        for (int i : new int[]{7,11}) if (!call(code.get(i), DATA, "getCastSource", "()L" + SOURCE + ';')) throw mismatch("completion cast source differs");
        if (!call(code.get(9), SPELL, "castSpell", "(L" + LEVEL + ";IL" + PLAYER + ";L" + SOURCE + ";Z)V")
                || !field(code.get(12), SOURCE, "SCROLL", 'L' + SOURCE + ';')
                || !call(code.get(15), Opcodes.INVOKESTATIC, "io/redspace/ironsspellbooks/item/Scroll", "attemptRemoveScrollAfterCast", "(L" + PLAYER + ";)V")
                || !call(code.get(24), SPELL, "onServerCastComplete", "(L" + LEVEL + ";IL" + LIVING + ";L" + DATA + ";Z)V")
                || !(code.get(13) instanceof JumpInsnNode scrollGuard) || next(scrollGuard.label) != code.get(16)
                || !(code.get(25) instanceof JumpInsnNode finalJump) || finalJump.label != done)
            throw mismatch("native finish, scroll or completion route differs");
    }

    private static boolean load(AbstractInsnNode instruction, int opcode, int slot) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode && variable.var == slot;
    }
    private static boolean integer(AbstractInsnNode instruction, int value) {
        return instruction instanceof IntInsnNode integer && integer.getOpcode() == Opcodes.BIPUSH && integer.operand == value;
    }
    private static boolean field(AbstractInsnNode instruction, String owner, String name, String descriptor) {
        return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(owner) && field.name.equals(name) && field.desc.equals(descriptor);
    }
    private static boolean call(AbstractInsnNode instruction, String owner, String name, String descriptor) {
        return call(instruction, Opcodes.INVOKEVIRTUAL, owner, name, descriptor);
    }
    private static boolean call(AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals(descriptor) && !call.itf;
    }
    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static IllegalArgumentException mismatch(String reason) {
        return new IllegalArgumentException("Legacy continuous-cast lifecycle differs: " + MANAGER + ' ' + reason);
    }
}
