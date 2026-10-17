package com.example.spell_compiler.formula;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FormulaUsage {
    record Usage(Set<String> methods, Set<String> fields, boolean uncertain) {
        Usage {
            methods = Set.copyOf(methods);
            fields = Set.copyOf(fields);
        }

        boolean usesMethod(String nameAndDescriptor) { return uncertain || methods.contains(nameAndDescriptor); }
        boolean usesField(String fieldName) { return uncertain || fields.contains(fieldName); }
    }

    private record MethodId(String owner, String name, String descriptor) {
        String key() { return name + descriptor; }
    }

    private record Call(String owner, String name, String descriptor, int opcode) {}
    private record FieldRead(String owner, String name) {}
    private record Body(List<Call> calls, List<FieldRead> fields, boolean uncertain) {}
    private record Visit(MethodId method, String receiver) {}

    private final FormulaClassRepository repository;
    private final Map<String, ClassNode> sources = new LinkedHashMap<>();
    private final Map<MethodId, Body> bodies = new LinkedHashMap<>();
    private final Map<String, Usage> cache = new HashMap<>();
    private final String domain;

    FormulaUsage(FormulaClassRepository repository, List<ClassNode> sources) {
        this.repository = repository;
        for (ClassNode source : sources) {
            this.sources.put(source.name, source);
            for (MethodNode method : source.methods)
                bodies.put(new MethodId(source.name, method.name, method.desc), body(method));
        }
        String prefix = sources.isEmpty() ? "" : sources.get(0).name.substring(0, sources.get(0).name.lastIndexOf('/') + 1);
        for (ClassNode source : sources)
            while (!source.name.startsWith(prefix)) prefix = prefix.substring(0, Math.max(0, prefix.lastIndexOf('/', prefix.length() - 2) + 1));
        domain = prefix;
    }

    Usage forSpell(String spellClassInternalName) {
        return cache.computeIfAbsent(spellClassInternalName, this::analyze);
    }

    Set<String> uncoveredFields(String spellClassInternalName, Set<String> coveredMethodIds) {
        Usage result = analyze(spellClassInternalName, Set.copyOf(coveredMethodIds));
        Set<String> fields = new LinkedHashSet<>(result.fields());
        if (result.uncertain()) fields.addAll(Set.of("baseSpellPower", "spellPowerPerLevel"));
        return Set.copyOf(fields);
    }

    private Usage analyze(String spell) {
        return analyze(spell, Set.of());
    }

    private Usage analyze(String spell, Set<String> coveredMethodIds) {
        Set<String> family = hierarchy(spell);
        Set<String> local = new HashSet<>(family);
        for (String name : sources.keySet()) if (name.startsWith(spell + '$')) local.add(name);
        Set<String> methods = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        Set<Visit> visited = new HashSet<>();
        boolean[] uncertain = {!sources.containsKey(spell)};
        for (String owner : family) uncertain[0] |= missingNativeParent(owner);
        String base = spell;
        while (sources.containsKey(base) && sources.containsKey(sources.get(base).superName))
            base = sources.get(base).superName;
        for (String owner : local) {
            ClassNode source = sources.get(owner);
            if (source == null) continue;
            for (MethodNode method : source.methods) {
                if (!initializer(method.name) && ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0
                        || numeric(method.desc))) continue;
                String receiver = family.contains(owner) ? spell : owner;
                var resolved = repository.resolveMethod(initializer(method.name) ? owner : receiver, method.name, method.desc);
                if (resolved == null) {
                    uncertain[0] = true;
                    continue;
                }
                pending.add(new Visit(new MethodId(resolved.owner().name, method.name, method.desc), receiver));
            }
        }
        for (var entry : bodies.entrySet()) {
            String caller = entry.getKey().owner();
            if (local.contains(caller)) continue;
            boolean otherSpell = spellClass(caller, base);
            for (Call call : entry.getValue().calls()) {
                if (!local.contains(call.owner()) || otherSpell && !call.owner().equals(spell)
                        && !call.owner().startsWith(spell + '$') && call.opcode() == Opcodes.INVOKESPECIAL) continue;
                enqueue(call, spell, spell, family, pending, uncertain);
            }
            for (FieldRead field : entry.getValue().fields())
                if (family.contains(field.owner())) fields.add(field.name());
        }
        while (!pending.isEmpty()) {
            Visit visit = pending.removeFirst();
            if (!visited.add(visit)) continue;
            if (coveredMethodIds.contains(visit.method().owner() + '#' + visit.method().key())) continue;
            Body body = bodies.get(visit.method());
            if (body == null) continue;
            if (family.contains(visit.method().owner()) && (visit.receiver() == null || spell.equals(visit.receiver())))
                methods.add(visit.method().key());
            uncertain[0] |= body.uncertain();
            if (visit.receiver() == null || spell.equals(visit.receiver()))
                for (FieldRead field : body.fields()) if (family.contains(field.owner())) fields.add(field.name());
            for (Call call : body.calls()) enqueue(call, visit.receiver(), spell, family, pending, uncertain);
        }
        return new Usage(methods, fields, uncertain[0]);
    }

    private void enqueue(Call call, String receiver, String spell, Set<String> family,
                         ArrayDeque<Visit> pending, boolean[] uncertain) {
        if (reflection(call)) uncertain[0] = true;
        if (!sources.containsKey(call.owner())) {
            if (nativeName(call.owner())) uncertain[0] = true;
            return;
        }
        boolean exact = call.opcode() == Opcodes.INVOKESTATIC || call.opcode() == Opcodes.INVOKESPECIAL;
        String dispatch = call.owner();
        String selectedReceiver = call.opcode() == Opcodes.INVOKESTATIC ? null : call.owner();
        if (family.contains(call.owner())) {
            selectedReceiver = receiver != null && repository.isSubtype(receiver, call.owner()) ? receiver : spell;
            if (!exact) dispatch = selectedReceiver;
        }
        var resolved = repository.resolveMethod(dispatch, call.name(), call.descriptor());
        if (resolved == null) {
            if (missingNativeParent(dispatch) || !externalParent(dispatch)) uncertain[0] = true;
            return;
        }
        if (!sources.containsKey(resolved.owner().name)) return;
        if ((resolved.method().access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                && family.contains(call.owner())) uncertain[0] = true;
        pending.add(new Visit(new MethodId(resolved.owner().name, call.name(), call.descriptor()), selectedReceiver));
    }

    private Set<String> hierarchy(String owner) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(owner);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!sources.containsKey(name) || !seen.add(name)) continue;
            ClassNode source = sources.get(name);
            if (source.superName != null) pending.add(source.superName);
            pending.addAll(source.interfaces);
        }
        return seen;
    }

    private boolean spellClass(String owner, String base) {
        if (repository.isSubtype(owner, base)) return true;
        int separator = owner.indexOf('$');
        return separator >= 0 && repository.isSubtype(owner.substring(0, separator), base);
    }

    private boolean externalParent(String owner) {
        for (String name : hierarchy(owner)) {
            ClassNode source = sources.get(name);
            if (source.superName != null && !source.superName.equals("java/lang/Object")
                    && !sources.containsKey(source.superName)) return true;
            for (String parent : source.interfaces) if (!sources.containsKey(parent)) return true;
        }
        return false;
    }

    private boolean missingNativeParent(String owner) {
        for (String name : hierarchy(owner)) {
            ClassNode source = sources.get(name);
            if (source.superName != null && nativeName(source.superName) && !sources.containsKey(source.superName)) return true;
            for (String parent : source.interfaces) if (nativeName(parent) && !sources.containsKey(parent)) return true;
        }
        return false;
    }

    private boolean nativeName(String owner) { return !domain.isEmpty() && owner.startsWith(domain); }

    private static Body body(MethodNode method) {
        List<Call> calls = new ArrayList<>();
        List<FieldRead> fields = new ArrayList<>();
        boolean[] uncertain = {false};
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call)
                calls.add(new Call(call.owner, call.name, call.desc, call.getOpcode()));
            else if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD)
                fields.add(new FieldRead(field.owner, field.name));
            else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                if (!standardBootstrap(dynamic.bsm)) uncertain[0] = true;
                for (Object argument : dynamic.bsmArgs) constant(argument, calls, fields, uncertain);
            } else if (instruction instanceof LdcInsnNode constant)
                constant(constant.cst, calls, fields, uncertain);
        }
        return new Body(List.copyOf(calls), List.copyOf(fields), uncertain[0]);
    }

    private static void constant(Object value, List<Call> calls, List<FieldRead> fields, boolean[] uncertain) {
        if (value instanceof Handle handle) {
            int opcode = switch (handle.getTag()) {
                case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
                case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
                case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
                default -> -1;
            };
            if (opcode >= 0) calls.add(new Call(handle.getOwner(), handle.getName(), handle.getDesc(), opcode));
            else if (handle.getTag() == Opcodes.H_GETFIELD) fields.add(new FieldRead(handle.getOwner(), handle.getName()));
        } else if (value instanceof ConstantDynamic dynamic) {
            uncertain[0] = true;
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++)
                constant(dynamic.getBootstrapMethodArgument(index), calls, fields, uncertain);
        }
    }

    private static boolean standardBootstrap(Handle bootstrap) {
        return bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                && Set.of("metafactory", "altMetafactory").contains(bootstrap.getName())
                || bootstrap.getOwner().equals("java/lang/invoke/StringConcatFactory")
                && Set.of("makeConcat", "makeConcatWithConstants").contains(bootstrap.getName());
    }

    private static boolean reflection(Call call) {
        return call.owner().startsWith("java/lang/reflect/")
                || call.owner().equals("java/lang/Class") && Set.of("forName", "getMethod", "getDeclaredMethod",
                "getMethods", "getDeclaredMethods", "getField", "getDeclaredField", "getFields", "getDeclaredFields",
                "getConstructor", "getDeclaredConstructor", "newInstance").contains(call.name())
                || call.owner().equals("java/lang/invoke/MethodHandles$Lookup")
                || call.owner().equals("java/lang/invoke/MethodHandle")
                && Set.of("invoke", "invokeExact", "invokeWithArguments").contains(call.name());
    }

    private static boolean numeric(String descriptor) {
        int sort = Type.getReturnType(descriptor).getSort();
        return sort == Type.INT || sort == Type.LONG || sort == Type.FLOAT || sort == Type.DOUBLE;
    }

    private static boolean initializer(String name) { return name.equals("<init>") || name.equals("<clinit>"); }
}
