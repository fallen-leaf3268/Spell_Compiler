package com.example.spell_compiler.formula;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HexFormat;
import java.util.jar.JarFile;

public final class FormulaClassRepository {
    private FormulaExecutionGraph.Index executionGraph;
    private record CachedDataFlow(MethodNode method, String fingerprint, FormulaDataFlow.Analysis analysis) {}
    private final Map<FormulaDataFlow.MethodId, CachedDataFlow> dataFlows = new HashMap<>();
    private final Map<String, FormulaMethodResolver.Resolution> invocations = new HashMap<>();
    private final Map<String, List<ClassNode>> sourceSubtypes = new HashMap<>();
    public record ResolvedMethod(ClassNode owner, MethodNode method) {}
    public record ResolvedField(ClassNode owner, FieldNode field) {}

    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Map<String, ClassNode> sources = new LinkedHashMap<>();
    private final HashSet<String> missing = new HashSet<>();
    private final ClassLoader lookupLoader;
    private final HashSet<String> sourcePrefixes = new HashSet<>();
    private final Set<String> sourceFingerprints = new HashSet<>();
    private boolean baselineCoverage = true;

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

    public static FormulaClassRepository fromJar(Path path, ClassLoader lookupLoader) throws IOException {
        return fromJars(List.of(path), lookupLoader);
    }

    public static ClassLoader resourceLookup(List<Path> dependencies, ClassLoader parent) throws IOException {
        List<Path> paths = dependencies.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        for (Path path : paths) {
            if (!Files.isRegularFile(path)) throw new IOException("Missing analysis dependency JAR: " + path);
            try (var jar = new JarFile(path.toFile())) {
                if (jar.getJarEntry("net/minecraft/world/entity/LivingEntity.class") == null)
                    throw new IOException("Analysis dependency has no Minecraft LivingEntity: " + path);
            }
        }
        return new ClassLoader(parent) {
            @Override public InputStream getResourceAsStream(String name) {
                if (!name.startsWith("net/minecraft/") || !name.endsWith(".class"))
                    return super.getResourceAsStream(name);
                for (Path path : paths) {
                    try (var jar = new JarFile(path.toFile())) {
                        var entry = jar.getJarEntry(name);
                        if (entry == null) continue;
                        try (var stream = jar.getInputStream(entry)) {
                            return new ByteArrayInputStream(stream.readAllBytes());
                        }
                    } catch (IOException failure) {
                        throw new UncheckedIOException("Cannot read analysis dependency " + path + '#' + name, failure);
                    }
                }
                return null;
            }
        };
    }

    public static FormulaClassRepository fromJars(Collection<Path> paths) throws IOException {
        return fromJars(paths, FormulaClassRepository.class.getClassLoader());
    }

    public static FormulaClassRepository fromJars(Collection<Path> paths, ClassLoader lookupLoader) throws IOException {
        var repository = new FormulaClassRepository(lookupLoader);
        for (Path path : paths) {
            repository.recordSourceFingerprint(path);
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

    public synchronized void add(ClassNode owner) {
        executionGraph = null;
        dataFlows.clear();
        invocations.clear();
        sourceSubtypes.clear();
        classes.put(owner.name, owner);
        sources.put(owner.name, owner);
        missing.remove(owner.name);
    }

    public synchronized void ensureSource(ClassNode owner) {
        if (sources.get(owner.name) != owner || classes.get(owner.name) != owner) add(owner);
    }

    public List<ClassNode> sourceClasses() {
        return List.copyOf(sources.values());
    }

    List<ClassNode> sourceSubtypes(String owner) {
        return sourceSubtypes.computeIfAbsent(owner, key -> sources.values().stream()
                .filter(source -> isSubtype(source.name, key)).toList());
    }

    Set<String> sourceFingerprints() {
        return Set.copyOf(sourceFingerprints);
    }

    String recordSourceFingerprint(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            String fingerprint = HexFormat.of().formatHex(digest.digest());
            sourceFingerprints.add(fingerprint);
            return fingerprint;
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    public boolean baselineCoverage() {
        return baselineCoverage;
    }

    public synchronized void baselineCoverage(boolean enabled) {
        if (baselineCoverage == enabled) return;
        baselineCoverage = enabled;
        executionGraph = null;
    }

    public synchronized void sealSourcePrefix(String prefix) {
        sourcePrefixes.add(Objects.requireNonNull(prefix));
        classes.keySet().removeIf(name -> name.startsWith(prefix) && !sources.containsKey(name));
        executionGraph = null;
        dataFlows.clear();
        invocations.clear();
        sourceSubtypes.clear();
    }

    synchronized FormulaExecutionGraph.Index executionGraph() {
        if (executionGraph == null) executionGraph = FormulaExecutionGraph.createIndex(this);
        return executionGraph;
    }

    synchronized FormulaDataFlow.Analysis dataFlow(String owner, MethodNode method) {
        var id = new FormulaDataFlow.MethodId(owner, method.name, method.desc);
        String fingerprint = FormulaDataFlow.fingerprint(owner, method);
        CachedDataFlow cached = dataFlows.get(id);
        if (cached != null && cached.method() == method && cached.fingerprint().equals(fingerprint)) return cached.analysis();
        FormulaDataFlow.Analysis analysis = FormulaDataFlow.create(this, owner, method);
        dataFlows.put(id, new CachedDataFlow(method, fingerprint, analysis));
        return analysis;
    }

    public ClassNode classNode(String name) {
        ClassNode present = classes.get(name);
        if (present != null || missing.contains(name)) return present;
        if (sourcePrefixes.stream().anyMatch(name::startsWith)) {
            missing.add(name);
            return null;
        }
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
        return FormulaMethodResolver.symbolic(this, owner.replace('.', '/'), name, descriptor).target();
    }

    public ResolvedMethod resolveMinecraftMethod(String owner, String canonicalName, String descriptor) {
        String root = owner.replace('.', '/');
        var pending = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        pending.add(root);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!visited.add(name)) continue;
            ClassNode type = classNode(name);
            if (type == null) continue;
            for (MethodNode method : type.methods)
                if (FormulaMinecraftNames.method(method.name).equals(canonicalName) && method.desc.equals(descriptor))
                    return resolveMethod(root, method.name, descriptor);
            if (type.superName != null) pending.add(type.superName);
            pending.addAll(type.interfaces);
        }
        return null;
    }

    synchronized FormulaMethodResolver.Resolution resolveInvocation(String caller, MethodInsnNode call, String receiver) {
        String key = caller + ":" + call.getOpcode() + ':' + call.itf + ':' + call.owner + '#' + call.name + call.desc + ':' + receiver;
        return invocations.computeIfAbsent(key, ignored -> FormulaMethodResolver.invocation(this, caller, call, receiver));
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
