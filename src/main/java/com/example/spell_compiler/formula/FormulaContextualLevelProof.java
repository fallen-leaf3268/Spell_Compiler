package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaContextualLevelProof {
    private static final String CONTEXT = "com/example/spell_compiler/formula/FormulaCastContext";
    private static final Map<FormulaCastContext.Root, List<Call>> PLANS = new ConcurrentHashMap<>();
    private record Call(int index, int opcode, String owner, String name, String descriptor, int ordinal) {
        String key() { return name + descriptor; }
        boolean matches(MethodInsnNode call) { return call.getOpcode() == opcode && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor); }
    }

    private FormulaContextualLevelProof() {}

    public static void registerBindings(FormulaClassRepository repository, String spellClass,
                                         Collection<FormulaDataFlow.MethodId> bindings) {
        String spell = spellClass.replace('.', '/');
        for (var binding : bindings) {
            var resolved = repository.resolveMethod(binding.owner(), binding.name(), binding.descriptor());
            if (resolved != null && repository.isSubtype(spell, resolved.owner().name))
                owningRoot(repository, resolved.owner(), resolved.method(), spell);
        }
    }

    static boolean owningRoot(FormulaClassRepository repository, ClassNode owner, MethodNode getter, String concreteReceiver) {
        String receiver = concreteReceiver == null ? owner.name : concreteReceiver;
        Type[] arguments = Type.getArgumentTypes(getter.desc);
        if ((getter.access & Opcodes.ACC_STATIC) != 0 || arguments.length != 1 || arguments[0].getSort() != Type.OBJECT
                || !repository.isSubtype(arguments[0].getInternalName(), "net/minecraft/world/entity/LivingEntity")) return false;
        boolean found = false;
        for (var root : FormulaCastContext.contextualRoots(repository, receiver)) {
            var resolved = repository.resolveMethod(root.owner(), root.methodName(), root.methodDescriptor());
            if (resolved == null) continue;
            MethodNode method = resolved.method();
            var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, method);
            if (!flow.successful()) continue;
            var code = FormulaAutomaticInjector.executable(method);
            int ordinal = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !matches(repository, call, owner, getter)) continue;
                int current = ordinal++;
                if (!flow.reachable(call) || !flow.parameter(flow.receiver(call), root.spellSlot()) || flow.arguments(call).size() != 1) continue;
                var argument = flow.arguments(call).get(0);
                if (flow.origin(argument) == null || flow.parameter(argument, root.casterSlot()) || caught(method, call)) continue;
                Call plan = new Call(code.indexOf(call), call.getOpcode(), call.owner, call.name, call.desc, current);
                PLANS.compute(root, (ignored, previous) -> {
                    var values = new ArrayList<>(previous == null ? List.<Call>of() : previous);
                    if (!values.contains(plan)) values.add(plan);
                    return List.copyOf(values);
                });
                found = true;
            }
        }
        return found;
    }

    static Runnable prepare(MethodNode method, FormulaCastContext.Root root) {
        List<Call> plans = PLANS.getOrDefault(root, List.of());
        if (plans.isEmpty()) return () -> {};
        var code = FormulaAutomaticInjector.executable(method);
        Map<MethodInsnNode, Call> selected = new LinkedHashMap<>();
        for (Call plan : plans) {
            if (plan.index() >= code.size() || !(code.get(plan.index()) instanceof MethodInsnNode call)
                    || !plan.matches(call) || caught(method, call)) throw new IllegalArgumentException("Getter source projection changed: " + root);
            selected.put(call, plan);
        }
        return () -> {
            int token = method.maxLocals;
            for (var entry : selected.entrySet()) {
                MethodInsnNode call = entry.getKey();
                if (method.instructions.indexOf(call) < 0) throw new IllegalArgumentException("Getter source projection consumer disappeared");
                InsnList before = new InsnList();
                before.add(new InsnNode(Opcodes.DUP2));
                before.add(new LdcInsnNode(entry.getValue().key()));
                before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "enterGetterProjection", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
                before.add(new VarInsnNode(Opcodes.ASTORE, token));
                method.instructions.insertBefore(call, before);
                InsnList after = new InsnList();
                after.add(new VarInsnNode(Opcodes.ALOAD, token));
                after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "exitGetterProjection", "(Ljava/lang/Object;)V", false));
                method.instructions.insert(call, after);
            }
            method.maxLocals = token + 1;
            method.maxStack = Math.max(5, method.maxStack + 3);
        };
    }

    static boolean retained(MethodNode method, FormulaCastContext.Root root) {
        var code = FormulaAutomaticInjector.executable(method);
        for (Call plan : PLANS.getOrDefault(root, List.of())) {
            var matches = code.stream().filter(instruction -> instruction instanceof MethodInsnNode call && plan.matches(call))
                    .map(instruction -> (MethodInsnNode) instruction).toList();
            if (matches.size() <= plan.ordinal()) return false;
            int index = code.indexOf(matches.get(plan.ordinal()));
            if (index < 4 || index + 2 >= code.size() || code.get(index - 4).getOpcode() != Opcodes.DUP2
                    || !(code.get(index - 3) instanceof LdcInsnNode key) || !plan.key().equals(key.cst)
                    || !hook(code.get(index - 2), "enterGetterProjection", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;")
                    || !(code.get(index - 1) instanceof VarInsnNode stored) || stored.getOpcode() != Opcodes.ASTORE
                    || !(code.get(index + 1) instanceof VarInsnNode loaded) || loaded.getOpcode() != Opcodes.ALOAD || loaded.var != stored.var
                    || !hook(code.get(index + 2), "exitGetterProjection", "(Ljava/lang/Object;)V")) return false;
        }
        return true;
    }

    private static boolean hook(AbstractInsnNode instruction, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals(CONTEXT) && call.name.equals(name) && call.desc.equals(descriptor);
    }

    private static boolean caught(MethodNode method, AbstractInsnNode call) {
        int index = method.instructions.indexOf(call);
        return method.tryCatchBlocks.stream().anyMatch(block -> method.instructions.indexOf(block.start) <= index
                && index < method.instructions.indexOf(block.end));
    }

    private static boolean matches(FormulaClassRepository repository, MethodInsnNode call, ClassNode owner, MethodNode getter) {
        if (!call.name.equals(getter.name) || !call.desc.equals(getter.desc)) return false;
        var resolved = repository.resolveMethod(call.owner, call.name, call.desc);
        return resolved != null && resolved.owner().name.equals(owner.name) && resolved.method().name.equals(getter.name)
                && resolved.method().desc.equals(getter.desc);
    }
}
