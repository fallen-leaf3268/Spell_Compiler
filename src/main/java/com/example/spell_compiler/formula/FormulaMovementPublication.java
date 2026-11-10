package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class FormulaMovementPublication {
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String VECTOR = "net/minecraft/world/phys/Vec3";
    private static final String DATA = "io/redspace/ironsspellbooks/api/magic/MagicData";
    private static final String IMPULSE = "io/redspace/ironsspellbooks/capabilities/magic/ImpulseCastData";
    private static final String CAST_DATA = "io/redspace/ironsspellbooks/api/spells/ICastData";
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaMovementPublicationHooks";
    private static final String ON_CAST = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/spells/CastSource;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V";
    private static final String ON_CLIENT = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/spells/ICastData;)V";

    private record Client(MethodInsnNode getter) {}
    record Plan(BooleanSupplier action) { boolean apply() { return action.getAsBoolean(); } }
    private static final Plan UNAVAILABLE = new Plan(() -> false);

    private FormulaMovementPublication() {}

    static Plan prepare(FormulaClassRepository repository, String owner, List<MethodNode> methods, List<FormulaAutomaticSites.Site> sites) {
        var clients = methods.stream().filter(m -> m.name.equals("onClientCast") && m.desc.equals(ON_CLIENT)).toList();
        var servers = methods.stream().filter(m -> m.name.equals("onCast") && m.desc.equals(ON_CAST)).toList();
        if (clients.size() != 1 || servers.size() != 1) return UNAVAILABLE;
        Client client = additiveClient(clients.get(0));
        if (client == null) return UNAVAILABLE;
        MethodNode server = servers.get(0);
        List<String> keys = sites.stream().filter(site -> site.owner().equals(owner.replace('/', '.'))
                && site.methodName().equals(server.name) && site.methodDescriptor().equals(server.desc)
                && site.contextMode() == FormulaAutomaticSites.ContextMode.DIRECT
                && site.casterSlot() == 3 && site.spellSlot() == 0 && site.kind().name().startsWith("MOVEMENT_"))
                .map(site -> site.definition().dispatchKey()).distinct().sorted().toList();
        if (keys.isEmpty()) return UNAVAILABLE;
        var code = FormulaAutomaticInjector.executable(server);
        List<MethodInsnNode> setters = code.stream().filter(i -> i instanceof MethodInsnNode call
                && call.owner.equals(LIVING) && Set.of("setDeltaMovement", "m_20256_").contains(call.name)
                && call.desc.equals("(L" + VECTOR + ";)V")).map(MethodInsnNode.class::cast).toList();
        List<MethodInsnNode> publications = code.stream().filter(i -> i instanceof MethodInsnNode call
                && call.owner.equals(DATA) && call.name.equals("setAdditionalCastData") && call.desc.equals("(L" + CAST_DATA + ";)V"))
                .map(MethodInsnNode.class::cast).toList();
        if (setters.size() != 1 || publications.size() != 1 || code.indexOf(publications.get(0)) >= code.indexOf(setters.get(0))
                || code.stream().anyMatch(i -> code.indexOf(i) > code.indexOf(publications.get(0))
                        && i instanceof MethodInsnNode call && call.owner.equals(DATA) && call.name.equals("getAdditionalCastData"))
                || code.stream().filter(i -> i instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(IMPULSE)).count() != 1) return UNAVAILABLE;
        String[] axes = publicationFields(code, publications.get(0));
        if (axes == null) return UNAVAILABLE;
        var setter = setters.get(0);
        try {
            var frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, server);
            var frame = frames[server.instructions.indexOf(setter)];
            if (frame == null || frame.getStackSize() < 2
                    || !local(single(frame.getStack(frame.getStackSize() - 2)), Opcodes.ALOAD, 3)
                    || !noLaterDataEscape(repository, server, publications.get(0), frames)) return UNAVAILABLE;
        } catch (AnalyzerException unsupported) { return UNAVAILABLE; }
        String group = owner.replace('/', '.') + '#' + server.name + server.desc;
        return new Plan(() -> {
            if (server.instructions.indexOf(setter) < 0) return false;
            inject(server, setter, client.getter(), group, axes);
            FormulaMovementPublicationHooks.register(group, keys);
            return true;
        });
    }

    private static boolean noLaterDataEscape(FormulaClassRepository repository, MethodNode method, MethodInsnNode publication, Frame<SourceValue>[] frames) {
        int first = method.instructions.indexOf(publication) + 1;
        for (int i = first; i < method.instructions.size(); i++) {
            var instruction = method.instructions.get(i);
            var frame = frames[i];
            if (frame == null) continue;
            if (instruction instanceof MethodInsnNode call) {
                Type[] types = Type.getArgumentTypes(call.desc);
                int start = frame.getStackSize() - types.length;
                boolean receiver = call.getOpcode() != Opcodes.INVOKESTATIC
                        && dataOrigin(frame.getStack(start - 1), method, frames, new HashSet<>());
                if (receiver && !syncedGetter(repository, call)) return false;
                for (int argument = 0; argument < types.length; argument++) {
                    if (!types[argument].getDescriptor().equals('L' + DATA + ';')
                            && !dataOrigin(frame.getStack(start + argument), method, frames, new HashSet<>())) continue;
                    var resolved = repository.resolveMethod(call.owner, call.name, call.desc);
                    if (resolved == null || !ignoresParameter(resolved.method(), argument)) return false;
                }
            } else if (instruction instanceof InvokeDynamicInsnNode call) {
                int arguments = Type.getArgumentTypes(call.desc).length;
                for (int argument = 0; argument < arguments; argument++)
                    if (dataOrigin(frame.getStack(frame.getStackSize() - arguments + argument), method, frames, new HashSet<>())) return false;
            } else if (instruction.getOpcode() == Opcodes.PUTFIELD || instruction.getOpcode() == Opcodes.PUTSTATIC
                    || instruction.getOpcode() == Opcodes.AASTORE || instruction.getOpcode() == Opcodes.ARETURN) {
                if (dataOrigin(frame.getStack(frame.getStackSize() - 1), method, frames, new HashSet<>())) return false;
            }
        }
        return true;
    }

    private static boolean ignoresParameter(MethodNode method, int argument) {
        Type[] types = Type.getArgumentTypes(method.desc);
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < argument; i++) slot += types[i].getSize();
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions.size() == 0) return false;
        for (var instruction : method.instructions) {
            if (instruction instanceof VarInsnNode variable && variable.var == slot) return false;
            if (instruction instanceof FieldInsnNode field && field.desc.equals('L' + DATA + ';')) return false;
            if (instruction instanceof MethodInsnNode call && (call.owner.equals(DATA) || call.desc.contains('L' + DATA + ';'))) return false;
            if (instruction instanceof InvokeDynamicInsnNode call && call.desc.contains('L' + DATA + ';')) return false;
        }
        return true;
    }

    private static boolean syncedGetter(FormulaClassRepository repository, MethodInsnNode call) {
        if (!call.owner.equals(DATA) || !call.name.equals("getSyncedData")
                || !call.desc.equals("()Lio/redspace/ironsspellbooks/capabilities/magic/SyncedSpellData;")) return false;
        var source = repository.resolveMethod(call.owner, call.name, call.desc);
        if (source == null) return false;
        var code = FormulaAutomaticInjector.executable(source.method());
        if (code.size() == 3 && local(code.get(0), Opcodes.ALOAD, 0)
                && code.get(1) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && field.owner.equals(DATA) && field.name.equals("syncedSpellData")
                && field.desc.equals("Lio/redspace/ironsspellbooks/capabilities/magic/SyncedSpellData;")
                && code.get(2).getOpcode() == Opcodes.ARETURN) return true;
        int[] lazy = {25,180,199,25,187,89,25,180,183,181,25,180,176};
        if (code.size() != lazy.length) return false;
        for (int i = 0; i < lazy.length; i++) if (code.get(i).getOpcode() != lazy[i]) return false;
        for (int i : new int[]{0,3,6,10}) if (!local(code.get(i), Opcodes.ALOAD, 0)) return false;
        String synced = "io/redspace/ironsspellbooks/capabilities/magic/SyncedSpellData";
        for (int i : new int[]{1,9,11})
            if (!(code.get(i) instanceof FieldInsnNode field) || !field.owner.equals(DATA)
                    || !field.name.equals("syncedSpellData") || !field.desc.equals('L' + synced + ';')) return false;
        if (!(code.get(2) instanceof JumpInsnNode guard) || next(guard.label) != code.get(10)
                || !(code.get(4) instanceof TypeInsnNode allocation) || !allocation.desc.equals(synced)
                || !(code.get(7) instanceof FieldInsnNode player) || !player.owner.equals(DATA)
                || !player.name.equals("serverPlayer") || !player.desc.equals("Lnet/minecraft/server/level/ServerPlayer;")
                || !(code.get(8) instanceof MethodInsnNode constructor) || !constructor.owner.equals(synced)
                || !constructor.name.equals("<init>") || !constructor.desc.equals("(Lnet/minecraft/world/entity/LivingEntity;)V")) return false;
        var initialization = repository.classNode(synced);
        if (initialization == null) return false;
        for (var method : initialization.methods) if (method.name.equals("<init>"))
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode nested && (nested.owner.equals(DATA) || nested.desc.contains('L' + DATA + ';'))) return false;
                if (instruction instanceof FieldInsnNode field && field.desc.equals('L' + DATA + ';')) return false;
                if (instruction instanceof InvokeDynamicInsnNode dynamic && dynamic.desc.contains('L' + DATA + ';')) return false;
            }
        return true;
    }

    private static boolean dataOrigin(SourceValue value, MethodNode method, Frame<SourceValue>[] frames, Set<AbstractInsnNode> seen) {
        if (value == null) return false;
        for (var instruction : value.insns) {
            if (!seen.add(instruction)) continue;
            var frame = frames[method.instructions.indexOf(instruction)];
            if (instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD) {
                if (variable.var == 5 || frame != null && dataOrigin(frame.getLocal(variable.var), method, frames, seen)) return true;
            } else if (frame != null && (instruction.getOpcode() == Opcodes.ASTORE
                    || instruction.getOpcode() == Opcodes.CHECKCAST || instruction.getOpcode() == Opcodes.DUP)) {
                if (dataOrigin(frame.getStack(frame.getStackSize() - 1), method, frames, seen)) return true;
            } else if (instruction instanceof MethodInsnNode call && Type.getReturnType(call.desc).getDescriptor().equals('L' + DATA + ';')) return true;
        }
        return false;
    }

    private static Client additiveClient(MethodNode method) {
        var code = FormulaAutomaticInjector.executable(method);
        int[] opcodes = {25,193,153,25,192,58,25,25,180,181,25,25,182,25,180,141,25,180,141,25,180,141,182,182,25,25,21,25,25,183,177};
        if (code.size() != opcodes.length) return null;
        for (int i = 0; i < code.size(); i++) if (code.get(i).getOpcode() != opcodes[i]) return null;
        int[][] locals = {{0,4},{3,4},{5,5},{6,3},{7,5},{10,3},{11,3},{13,5},{16,5},{19,5},{24,0},{25,1},{26,2},{27,3},{28,4}};
        for (int[] expected : locals) if (!(code.get(expected[0]) instanceof VarInsnNode v) || v.var != expected[1]) return null;
        if (!(code.get(1) instanceof TypeInsnNode test) || !test.desc.equals(IMPULSE)
                || !(code.get(4) instanceof TypeInsnNode cast) || !cast.desc.equals(IMPULSE)
                || !(code.get(2) instanceof JumpInsnNode branch) || next(branch.label) != code.get(24)
                || !field(code.get(8), IMPULSE, Set.of("hasImpulse"), "Z")
                || !(code.get(9) instanceof FieldInsnNode impulseFlag) || !impulseFlag.owner.equals(LIVING)
                || !Set.of("hasImpulse", "f_19812_").contains(impulseFlag.name) || !impulseFlag.desc.equals("Z")
                || !field(code.get(14), IMPULSE, Set.of("x"), "F")
                || !field(code.get(17), IMPULSE, Set.of("y"), "F")
                || !field(code.get(20), IMPULSE, Set.of("z"), "F")
                || !(code.get(12) instanceof MethodInsnNode getter) || !getter.owner.equals(LIVING)
                || !Set.of("getDeltaMovement", "m_20184_").contains(getter.name) || !getter.desc.equals("()L" + VECTOR + ';')
                || !(code.get(22) instanceof MethodInsnNode add) || !add.owner.equals(VECTOR)
                || !Set.of("add", "m_82520_").contains(add.name) || !add.desc.equals("(DDD)L" + VECTOR + ';')
                || !(code.get(23) instanceof MethodInsnNode setter) || !setter.owner.equals(LIVING)
                || !Set.of("setDeltaMovement", "m_20256_").contains(setter.name) || !setter.desc.equals("(L" + VECTOR + ";)V")
                || !(code.get(29) instanceof MethodInsnNode parent) || !parent.owner.equals("io/redspace/ironsspellbooks/api/spells/AbstractSpell")
                || !parent.name.equals("onClientCast") || !parent.desc.equals(ON_CLIENT)) return null;
        return new Client(getter);
    }

    private static String[] publicationFields(List<AbstractInsnNode> code, MethodInsnNode publication) {
        int end = code.indexOf(publication);
        if (end < 14) return null;
        var p = code.subList(end - 14, end + 1);
        int[] opcodes = {25,187,89,25,180,144,25,180,144,25,180,144,4,183,182};
        for (int i = 0; i < p.size(); i++) if (p.get(i).getOpcode() != opcodes[i]) return null;
        if (!local(p.get(0), Opcodes.ALOAD, 5) || !(p.get(1) instanceof TypeInsnNode type) || !type.desc.equals(IMPULSE)
                || !(p.get(3) instanceof VarInsnNode vector) || vector.var < 6
                || !local(p.get(6), Opcodes.ALOAD, vector.var) || !local(p.get(9), Opcodes.ALOAD, vector.var)
                || !field(p.get(4), VECTOR, Set.of("x", "f_82479_"), "D")
                || !field(p.get(7), VECTOR, Set.of("y", "f_82480_"), "D")
                || !field(p.get(10), VECTOR, Set.of("z", "f_82481_"), "D")
                || !(p.get(13) instanceof MethodInsnNode constructor) || !constructor.owner.equals(IMPULSE)
                || !constructor.name.equals("<init>") || !constructor.desc.equals("(FFFZ)V")) return null;
        return new String[]{((FieldInsnNode) p.get(4)).name, ((FieldInsnNode) p.get(7)).name, ((FieldInsnNode) p.get(10)).name};
    }

    private static void inject(MethodNode method, MethodInsnNode setter, MethodInsnNode getter, String group, String[] axes) {
        int actor = method.maxLocals;
        int desired = actor + 1;
        int before = desired + 1;
        int active = before + 1;
        int after = active + 1;
        int data = after + 1;
        int delta = data + 1;
        method.maxLocals = delta + 6;
        var head = new InsnList();
        head.add(new VarInsnNode(Opcodes.ASTORE, desired));
        head.add(new VarInsnNode(Opcodes.ASTORE, actor));
        head.add(new InsnNode(Opcodes.ACONST_NULL));
        head.add(new VarInsnNode(Opcodes.ASTORE, before));
        head.add(new VarInsnNode(Opcodes.ALOAD, 0));
        head.add(new VarInsnNode(Opcodes.ALOAD, 3));
        head.add(new LdcInsnNode(group));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "active", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Z", false));
        head.add(new VarInsnNode(Opcodes.ISTORE, active));
        var nativeCall = new LabelNode();
        head.add(new VarInsnNode(Opcodes.ILOAD, active));
        head.add(new JumpInsnNode(Opcodes.IFEQ, nativeCall));
        head.add(new VarInsnNode(Opcodes.ALOAD, actor));
        head.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, getter.owner, getter.name, getter.desc, false));
        head.add(new VarInsnNode(Opcodes.ASTORE, before));
        head.add(nativeCall);
        head.add(new VarInsnNode(Opcodes.ALOAD, actor));
        head.add(new VarInsnNode(Opcodes.ALOAD, desired));
        method.instructions.insertBefore(setter, head);
        var tail = new InsnList();
        var done = new LabelNode();
        tail.add(new VarInsnNode(Opcodes.ILOAD, active));
        tail.add(new JumpInsnNode(Opcodes.IFEQ, done));
        tail.add(new VarInsnNode(Opcodes.ALOAD, 5));
        tail.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DATA, "getAdditionalCastData", "()L" + CAST_DATA + ';', false));
        tail.add(new VarInsnNode(Opcodes.ASTORE, data));
        tail.add(new VarInsnNode(Opcodes.ALOAD, data));
        tail.add(new TypeInsnNode(Opcodes.INSTANCEOF, IMPULSE));
        tail.add(new JumpInsnNode(Opcodes.IFEQ, done));
        tail.add(new VarInsnNode(Opcodes.ALOAD, data));
        tail.add(new TypeInsnNode(Opcodes.CHECKCAST, IMPULSE));
        tail.add(new VarInsnNode(Opcodes.ASTORE, data));
        tail.add(new VarInsnNode(Opcodes.ALOAD, actor));
        tail.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, getter.owner, getter.name, getter.desc, false));
        tail.add(new VarInsnNode(Opcodes.ASTORE, after));
        for (int i = 0; i < 3; i++) {
            tail.add(new VarInsnNode(Opcodes.ALOAD, after));
            tail.add(new FieldInsnNode(Opcodes.GETFIELD, VECTOR, axes[i], "D"));
            tail.add(new VarInsnNode(Opcodes.ALOAD, before));
            tail.add(new FieldInsnNode(Opcodes.GETFIELD, VECTOR, axes[i], "D"));
            tail.add(new InsnNode(Opcodes.DSUB));
            tail.add(new VarInsnNode(Opcodes.DSTORE, delta + i * 2));
        }
        for (int i = 0; i < 3; i++) tail.add(new VarInsnNode(Opcodes.DLOAD, delta + i * 2));
        tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "representable", "(DDD)Z", false));
        var valid = new LabelNode();
        tail.add(new JumpInsnNode(Opcodes.IFNE, valid));
        tail.add(new LdcInsnNode(group));
        tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "invalid", "(Ljava/lang/String;)V", false));
        tail.add(new JumpInsnNode(Opcodes.GOTO, done));
        tail.add(valid);
        String[] components = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            tail.add(new VarInsnNode(Opcodes.ALOAD, data));
            tail.add(new VarInsnNode(Opcodes.DLOAD, delta + i * 2));
            tail.add(new InsnNode(Opcodes.D2F));
            tail.add(new FieldInsnNode(Opcodes.PUTFIELD, IMPULSE, components[i], "F"));
        }
        tail.add(done);
        method.instructions.insert(setter, tail);
        method.maxStack += 8;
    }

    private static boolean local(AbstractInsnNode i, int opcode, int slot) { return i instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == slot; }
    private static boolean field(AbstractInsnNode i, String owner, Set<String> names, String descriptor) { return i instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && f.owner.equals(owner) && names.contains(f.name) && f.desc.equals(descriptor); }
    private static AbstractInsnNode single(SourceValue v) { return v.insns.size() == 1 ? v.insns.iterator().next() : null; }
    private static AbstractInsnNode next(AbstractInsnNode i) { do { i = i.getNext(); } while (i != null && i.getOpcode() < 0); return i; }
}
