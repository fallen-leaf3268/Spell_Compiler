package com.example.spell_compiler.formula;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarFile;

public final class FormulaClassRepository {
    public record ResolvedMethod(ClassNode owner, MethodNode method) {}
    public record ResolvedField(ClassNode owner, FieldNode field) {}

    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, ClassNode> sources = new LinkedHashMap<>();
    private final HashSet<String> missing = new HashSet<>();
    private final ClassLoader lookupLoader;

    private FormulaClassRepository(ClassLoader lookupLoader) {
        this.lookupLoader = Objects.requireNonNull(lookupLoader);
    }

    public static FormulaClassRepository of(ClassNode owner) {
        return of(owner, FormulaClassRepository.class.getClassLoader());
    }

    public static FormulaClassRepository of(ClassNode first, ClassNode... others) {
        return of(first, FormulaClassRepository.class.getClassLoader(), others);
    }

    public static FormulaClassRepository of(ClassNode owner, ClassLoader lookupLoader) {
        return of(owner, lookupLoader, new ClassNode[0]);
    }

    public static FormulaClassRepository of(ClassNode first, ClassLoader lookupLoader, ClassNode... others) {
        var repository = new FormulaClassRepository(lookupLoader);
        repository.add(first);
        for (ClassNode owner : others) repository.add(owner);
        return repository;
    }

    public static FormulaClassRepository fromJar(Path path) throws IOException {
        return fromJars(List.of(path));
    }

    public static FormulaClassRepository fromJars(Collection<Path> paths) throws IOException {
        return fromJars(paths, FormulaClassRepository.class.getClassLoader());
    }

    public static FormulaClassRepository fromJars(Collection<Path> paths, ClassLoader lookupLoader) throws IOException {
        var repository = new FormulaClassRepository(lookupLoader);
        for (Path path : paths) {
            try (var jar = new JarFile(path.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                    try (InputStream stream = jar.getInputStream(entry)) {
                        ClassNode owner = new ClassNode();
                        new ClassReader(stream).accept(owner, ClassReader.SKIP_DEBUG);
                        repository.add(owner);
                    }
                }
            }
        }
        return repository;
    }

    public void add(ClassNode owner) {
        classes.put(owner.name, owner);
        sources.put(owner.name, owner);
        missing.remove(owner.name);
    }

    public List<ClassNode> sourceClasses() {
        return List.copyOf(sources.values());
    }

    public ClassNode classNode(String name) {
        ClassNode present = classes.get(name);
        if (present != null || missing.contains(name)) return present;
        String resource = name + ".class";
        try (InputStream stream = lookupLoader.getResourceAsStream(resource)) {
            if (stream == null) {
                missing.add(name);
                return null;
            }
            ClassNode owner = new ClassNode();
            new ClassReader(stream).accept(owner, ClassReader.SKIP_DEBUG);
            classes.put(owner.name, owner);
            return owner;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot read class " + name, failure);
        }
    }

    public ClassNode getClassNode(String name) {
        return classNode(name);
    }

    public ResolvedMethod resolveMethod(String owner, String name, String descriptor) {
        var pending = new ArrayDeque<String>();
        var seen = new HashSet<String>();
        pending.add(owner);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!seen.add(current)) continue;
            ClassNode node = classNode(current);
            if (node == null) continue;
            for (MethodNode method : node.methods) {
                if (method.name.equals(name) && method.desc.equals(descriptor)) return new ResolvedMethod(node, method);
            }
            if (node.superName != null) pending.addFirst(node.superName);
            pending.addAll(node.interfaces);
        }
        return null;
    }

    public ResolvedField resolveField(String owner, String name, String descriptor) {
        var seen = new HashSet<String>();
        while (owner != null && seen.add(owner)) {
            ClassNode node = classNode(owner);
            if (node == null) return null;
            for (FieldNode field : node.fields) {
                if (field.name.equals(name) && field.desc.equals(descriptor)) return new ResolvedField(node, field);
            }
            owner = node.superName;
        }
        return null;
    }

    public boolean isSubtype(String candidate, String parent) {
        if (candidate.equals(parent)) return true;
        if (parent.equals("java/lang/Object") && !candidate.isEmpty()) return true;
        var pending = new ArrayDeque<String>();
        var seen = new HashSet<String>();
        pending.add(candidate);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current.equals(parent)) return true;
            ClassNode node = classNode(current);
            if (node == null) continue;
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        return false;
    }

    public String commonSuperClass(String left, String right) {
        if (left.equals(right)) return left;
        if (left.equals("java/lang/Object") || right.equals("java/lang/Object")) return "java/lang/Object";
        if (left.startsWith("[") || right.startsWith("[")) return "java/lang/Object";
        if (classNode(left) == null || classNode(right) == null) {
            throw new IllegalArgumentException("Unknown class hierarchy: " + left + " and " + right);
        }
        if (isSubtype(left, right)) return right;
        if (isSubtype(right, left)) return left;
        ClassNode current = classNode(left);
        while (current != null && current.superName != null) {
            if (isSubtype(right, current.superName)) return current.superName;
            current = classNode(current.superName);
        }
        return "java/lang/Object";
    }
}
