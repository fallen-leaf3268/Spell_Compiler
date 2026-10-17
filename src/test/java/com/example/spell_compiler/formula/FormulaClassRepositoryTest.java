package com.example.spell_compiler.formula;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaClassRepositoryTest {
    private static final String PARENT = "addon/repository/DependencyParent";
    private static final String HELPER = "dependency/repository/NumericHelper";
    private static final String LEAF = "addon/repository/RegisteredLeaf";

    @Test
    void multipleJarSourcesResolveInheritedAndCrossJarHelpersAndProduceRealDelegates() throws Throwable {
        var jars = jars();
        try (var loader = new URLClassLoader(jars.stream().map(path -> {
            try { return path.toUri().toURL(); }
            catch (java.net.MalformedURLException failure) { throw new IllegalArgumentException(failure); }
        }).toArray(java.net.URL[]::new), getClass().getClassLoader())) {
            var repository = FormulaClassRepository.fromJars(jars, loader);
            assertEquals(Set.of(PARENT, HELPER, LEAF), sourceNames(repository));
            assertTrue(repository.isSubtype(LEAF, PARENT));
            assertEquals(PARENT, repository.resolveMethod(LEAF, "inherited", "(I)I").owner().name);
            var owner = repository.classNode(LEAF);
            var method = owner.methods.stream().filter(value -> value.name.equals("numeric")).findFirst().orElseThrow();
            var analysis = AutomaticFormulaExtractor.analyzeRuntime(owner, method, repository);
            assertTrue(analysis.supported(), analysis.rejection());
            assertTrue(analysis.formula().variables().containsKey("base"));
            Class<?> type = loader.loadClass(LEAF.replace('/', '.'));
            Object receiver = type.getConstructor().newInstance();
            var delegate = FormulaDelegateFactory.compile(type,
                    Map.of("numeric(I)I", '(' + analysis.formula().readableExpression() + ") + 5")).get("numeric(I)I");
            assertNotNull(delegate);
            for (int level : new int[]{-2, 0, 3, 9}) {
                assertEquals(12 + 4 * level, delegate.invoke(receiver, new Object[]{level}).intValue());
                assertEquals(7 + 4 * level, type.getMethod("numeric", int.class).invoke(receiver, level));
            }
            assertNotNull(repository.classNode("java/lang/Object"));
            assertEquals(Set.of(PARENT, HELPER, LEAF), sourceNames(repository));
        }
    }

    @Test
    void lazyExplicitLoaderLookupsDoNotBecomeSourcesAndExplicitAddsRecoverMissingClasses() throws Exception {
        var seed = node(leaf());
        var definitions = Map.of(PARENT, parent(), HELPER, helper());
        var loader = resourceLoader(definitions);
        var repository = FormulaClassRepository.of(seed, loader);
        var method = seed.methods.stream().filter(value -> value.name.equals("numeric")).findFirst().orElseThrow();
        var analysis = AutomaticFormulaExtractor.analyzeRuntime(seed, method, repository);
        assertTrue(analysis.supported(), analysis.rejection());
        assertEquals(Set.of(LEAF), sourceNames(repository));
        assertNotNull(repository.classNode(PARENT));
        assertNotNull(repository.classNode(HELPER));
        assertNotNull(repository.classNode("java/lang/Object"));
        assertEquals(Set.of(LEAF), sourceNames(repository));
        assertThrows(UnsupportedOperationException.class, () -> repository.sourceClasses().add(node(parent())));
        assertNull(repository.classNode("addon/repository/LateSource"));
        var late = new ClassNode();
        late.name = "addon/repository/LateSource";
        late.superName = "java/lang/Object";
        repository.add(late);
        assertSame(late, repository.classNode(late.name));
        assertEquals(Set.of(LEAF, late.name), sourceNames(repository));
        var explicitParent = node(parent());
        var seeded = FormulaClassRepository.of(seed, loader, explicitParent);
        assertEquals(Set.of(LEAF, PARENT), sourceNames(seeded));
        assertSame(explicitParent, seeded.classNode(PARENT));
    }

    @Test
    void sourceDiscoveryReadsMetadataWithoutRunningStaticInitializers() {
        String dangerous = "addon/repository/NeverInitializeDuringDiscovery";
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, dangerous, null, "java/lang/Object", null);
        var initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.visitCode();
        initializer.visitInsn(Opcodes.ACONST_NULL);
        initializer.visitInsn(Opcodes.ATHROW);
        initializer.visitMaxs(0, 0);
        initializer.visitEnd();
        writer.visitEnd();
        var repository = FormulaClassRepository.of(node(leaf()), resourceLoader(Map.of(dangerous, writer.toByteArray())));
        assertEquals(dangerous, repository.classNode(dangerous).name);
        assertEquals(Set.of(LEAF), sourceNames(repository));
    }

    @Test
    void originalFactoriesKeepSingleJarAndDefaultLoaderBehavior() throws Exception {
        var jars = jars();
        var single = FormulaClassRepository.fromJar(jars.get(0));
        assertEquals(Set.of(PARENT), sourceNames(single));
        assertNotNull(single.classNode("java/lang/Object"));
        assertEquals(Set.of(PARENT), sourceNames(single));
        assertEquals(Set.of(PARENT, HELPER, LEAF), sourceNames(FormulaClassRepository.fromJars(jars)));
        var seed = node(parent());
        var repository = FormulaClassRepository.of(seed, node(helper()));
        assertSame(seed, repository.classNode(PARENT));
        assertNotNull(repository.classNode("java/lang/Object"));
        assertEquals(Set.of(PARENT, HELPER), sourceNames(repository));
    }

    private static Set<String> sourceNames(FormulaClassRepository repository) {
        return repository.sourceClasses().stream().map(owner -> owner.name).collect(Collectors.toSet());
    }

    private static ClassLoader resourceLoader(Map<String, byte[]> definitions) {
        return new ClassLoader(FormulaClassRepositoryTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = definitions.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                byte[] bytes = name.endsWith(".class") ? definitions.get(name.substring(0, name.length() - 6)) : null;
                return bytes == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(bytes);
            }
        };
    }

    private static List<Path> jars() throws Exception {
        Path root = Path.of("build/formula-runtime-test").toAbsolutePath();
        Files.createDirectories(root);
        Path directory = Files.createTempDirectory(root, "repository-");
        return List.of(jar(directory.resolve("parent.jar"), PARENT, parent()),
                jar(directory.resolve("helper.jar"), HELPER, helper()),
                jar(directory.resolve("addon.jar"), LEAF, leaf()));
    }

    private static Path jar(Path path, String owner, byte[] bytes) throws Exception {
        try (var stream = new JarOutputStream(Files.newOutputStream(path))) {
            stream.putNextEntry(new JarEntry(owner + ".class"));
            stream.write(bytes);
            stream.closeEntry();
        }
        return path;
    }

    private static ClassNode node(byte[] bytes) {
        var owner = new ClassNode();
        new ClassReader(bytes).accept(owner, 0);
        return owner;
    }

    private static byte[] parent() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PARENT, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PROTECTED, "base", "I", null, null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitIntInsn(Opcodes.BIPUSH, 7);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, PARENT, "base", "I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "inherited", "(I)I", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, PARENT, "base", "I");
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitInsn(Opcodes.IADD);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] helper() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HELPER, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "scale", "(I)I", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitInsn(Opcodes.ICONST_3);
        method.visitInsn(Opcodes.IMUL);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] leaf() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, LEAF, null, PARENT, null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, PARENT, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "numeric", "(I)I", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PARENT, "inherited", "(I)I", false);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "scale", "(I)I", false);
        method.visitInsn(Opcodes.IADD);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
