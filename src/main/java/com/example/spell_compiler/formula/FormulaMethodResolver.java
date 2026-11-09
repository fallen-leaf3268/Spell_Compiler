package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;

final class FormulaMethodResolver {
    record Resolution(FormulaClassRepository.ResolvedMethod target,
                      List<FormulaClassRepository.ResolvedMethod> candidates, boolean complete, String reason) {
        Resolution { candidates = List.copyOf(candidates); }
    }

    private FormulaMethodResolver() {}

    static FormulaClassRepository.ResolvedMethod declared(FormulaClassRepository repository, String owner,
                                                          String name, String descriptor) {
        ClassNode source = repository.classNode(owner);
        if (source == null) return null;
        for (MethodNode method : source.methods)
            if (method.name.equals(name) && method.desc.equals(descriptor))
                return new FormulaClassRepository.ResolvedMethod(source, method);
        return null;
    }

    static Resolution symbolic(FormulaClassRepository repository, String owner, String name, String descriptor) {
        ClassNode source = repository.classNode(owner);
        if (source == null) return unresolved(List.of(), "MISSING_OWNER");
        if (name.startsWith("<")) return exact(declared(repository, owner, name, descriptor), "DECLARED_INITIALIZER");
        Set<String> interfaces = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        if ((source.access & Opcodes.ACC_INTERFACE) != 0) {
            var own = declared(repository, owner, name, descriptor);
            if (own != null) return exact(own, "INTERFACE_DECLARATION");
            var object = declared(repository, "java/lang/Object", name, descriptor);
            if (object != null && (object.method().access & Opcodes.ACC_PUBLIC) != 0
                    && (object.method().access & Opcodes.ACC_STATIC) == 0) return exact(object, "OBJECT_METHOD");
            interfaces.addAll(source.interfaces);
        } else {
            for (String current = owner; current != null && seen.add(current); ) {
                ClassNode type = repository.classNode(current);
                if (type == null) return unresolved(List.of(), "MISSING_SUPERCLASS");
                var own = declared(repository, current, name, descriptor);
                if (own != null) return exact(own, "CLASS_DECLARATION");
                interfaces.addAll(type.interfaces);
                current = type.superName;
            }
        }
        List<FormulaClassRepository.ResolvedMethod> candidates = new ArrayList<>();
        ArrayDeque<String> pending = new ArrayDeque<>(interfaces);
        seen.clear();
        boolean complete = true;
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!seen.add(current)) continue;
            ClassNode type = repository.classNode(current);
            if (type == null) { complete = false; continue; }
            var own = declared(repository, current, name, descriptor);
            if (own != null && (own.method().access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0)
                candidates.add(own);
            pending.addAll(type.interfaces);
        }
        List<FormulaClassRepository.ResolvedMethod> maximal = candidates.stream().filter(candidate -> candidates.stream()
                .noneMatch(other -> other != candidate && repository.isSubtype(other.owner().name, candidate.owner().name)))
                .sorted(Comparator.comparing(value -> value.owner().name)).toList();
        if (!complete) return unresolved(maximal, "MISSING_SUPERINTERFACE");
        List<FormulaClassRepository.ResolvedMethod> defaults = maximal.stream()
                .filter(value -> (value.method().access & Opcodes.ACC_ABSTRACT) == 0).toList();
        if (defaults.size() == 1) return exact(defaults.get(0), "MAXIMALLY_SPECIFIC_DEFAULT");
        if (defaults.isEmpty() && maximal.size() == 1) return exact(maximal.get(0), "MAXIMALLY_SPECIFIC_ABSTRACT");
        return unresolved(maximal, maximal.isEmpty() ? "MISSING_METHOD" : "AMBIGUOUS_INTERFACE_METHOD");
    }

    static Resolution invocation(FormulaClassRepository repository, String caller, MethodInsnNode call, String receiver) {
        Resolution symbolic = symbolic(repository, call.owner, call.name, call.desc);
        var declaration = symbolic.target();
        if (declaration == null) return symbolic;
        int access = declaration.method().access;
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        if (call.getOpcode() == Opcodes.INVOKESTATIC) {
            if (!isStatic || (declaration.owner().access & Opcodes.ACC_INTERFACE) != 0
                    && !call.owner.equals(declaration.owner().name)) return unresolved(symbolic.candidates(), "STATIC_CONTRACT_MISMATCH");
            return exact(declaration, "STATIC_CALL");
        }
        if (isStatic) return unresolved(symbolic.candidates(), "INSTANCE_CONTRACT_MISMATCH");
        if (call.getOpcode() == Opcodes.INVOKESPECIAL) {
            if (call.name.equals("<init>") || (access & Opcodes.ACC_PRIVATE) != 0)
                return exact(declaration, "SPECIAL_DECLARATION");
            if (caller == null) return unresolved(symbolic.candidates(), "SPECIAL_CALLER_UNAVAILABLE");
            ClassNode calling = repository.classNode(caller);
            ClassNode owner = repository.classNode(call.owner);
            if (calling == null || owner == null) return unresolved(symbolic.candidates(), "SPECIAL_HIERARCHY_UNAVAILABLE");
            if ((owner.access & Opcodes.ACC_INTERFACE) != 0) return exact(declaration, "SPECIAL_INTERFACE_DEFAULT");
            if (!caller.equals(call.owner) && repository.isSubtype(caller, call.owner)
                    && (calling.access & Opcodes.ACC_SUPER) != 0) {
                if (calling.superName == null) return unresolved(symbolic.candidates(), "SPECIAL_SUPERCLASS_UNAVAILABLE");
                return symbolic(repository, calling.superName, call.name, call.desc);
            }
            return exact(declaration, "SPECIAL_CALL");
        }
        if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0
                || (declaration.owner().access & Opcodes.ACC_FINAL) != 0) return exact(declaration, "BOUND_INSTANCE_CALL");
        if (receiver != null) {
            var selected = virtual(repository, receiver.replace('.', '/'), declaration);
            return selected == null ? unresolved(symbolic.candidates(), "RECEIVER_SELECTION_UNAVAILABLE")
                    : exact(selected, "EXACT_RECEIVER");
        }
        Map<String, FormulaClassRepository.ResolvedMethod> candidates = new TreeMap<>();
        if ((declaration.method().access & Opcodes.ACC_ABSTRACT) == 0) candidates.put(declaration.owner().name, declaration);
        if (call.owner.startsWith("java/") || call.owner.startsWith("net/minecraft/")
                || call.owner.startsWith("net/minecraftforge/"))
            return unresolved(new ArrayList<>(candidates.values()), "EXTERNAL_DISPATCH_BOUNDARY");
        for (ClassNode source : repository.sourceSubtypes(call.owner)) {
            if ((source.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) continue;
            var selected = virtual(repository, source.name, declaration);
            if (selected != null && (selected.method().access & Opcodes.ACC_ABSTRACT) == 0) candidates.put(selected.owner().name, selected);
        }
        return unresolved(new ArrayList<>(candidates.values()), "RECEIVER_NOT_PROVEN");
    }

    static FormulaClassRepository.ResolvedMethod virtual(FormulaClassRepository repository, String receiver,
                                                         FormulaClassRepository.ResolvedMethod declaration) {
        if (!repository.isSubtype(receiver, declaration.owner().name)) return null;
        if ((declaration.method().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC)) != 0)
            return declaration;
        boolean interfaceDeclaration = (declaration.owner().access & Opcodes.ACC_INTERFACE) != 0;
        List<ClassNode> lineage = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = receiver;
        while (current != null && !current.equals(declaration.owner().name) && seen.add(current)) {
            ClassNode source = repository.classNode(current);
            if (source == null) return null;
            lineage.add(source);
            if (interfaceDeclaration) {
                var own = declared(repository, current, declaration.method().name, declaration.method().desc);
                if (own != null && (own.method().access & Opcodes.ACC_PUBLIC) != 0
                        && (own.method().access & Opcodes.ACC_STATIC) == 0) return own;
            }
            current = source.superName;
        }
        if (interfaceDeclaration) return symbolic(repository, receiver, declaration.method().name, declaration.method().desc).target();
        if (current == null) return null;
        var selected = declaration;
        for (int index = lineage.size() - 1; index >= 0; index--) {
            ClassNode source = lineage.get(index);
            var own = declared(repository, source.name, declaration.method().name, declaration.method().desc);
            if (own == null || (own.method().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) continue;
            if ((selected.method().access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                    || samePackage(selected.owner().name, source.name)) selected = own;
        }
        return selected;
    }

    private static boolean samePackage(String first, String second) {
        int a = first.lastIndexOf('/'), b = second.lastIndexOf('/');
        return a == b && first.regionMatches(0, second, 0, a + 1);
    }

    private static Resolution exact(FormulaClassRepository.ResolvedMethod method, String reason) {
        return method == null ? unresolved(List.of(), "MISSING_METHOD") : new Resolution(method, List.of(method), true, reason);
    }

    private static Resolution unresolved(List<FormulaClassRepository.ResolvedMethod> candidates, String reason) {
        return new Resolution(null, candidates, false, reason);
    }
}
