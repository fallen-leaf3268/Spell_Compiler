package com.example.spell_compiler.formula;

import com.example.spell_compiler.audit.formula.DelegateFactoryParent;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormulaDelegateFactoryTest {
    @Test
    void emptyAndReadableDefaultOverridesDoNotCreateDelegates() throws Exception {
        assertTrue(FormulaDelegateFactory.compile(Fixture.class, Map.of()).isEmpty());
        assertTrue(FormulaDelegateFactory.compile(Fixture.class,
                Map.of("integer(I)I", readable("integer(I)I"))).isEmpty());
        Fixture fixture = new Fixture();
        assertEquals(31, fixture.integer(3));
    }

    @Test
    void hiddenDelegatesReadInheritedProtectedAndPrivateNumericFieldsWithoutChangingOriginalMethods() throws Throwable {
        checkNumericDelegates(false);
    }

    @Test
    void namedFallbackReadsInheritedProtectedAndPrivateNumericFields() throws Throwable {
        checkNumericDelegates(true);
    }

    @Test
    void staticRootsKeepTheirOriginalLocalSlotsAndIgnoreTheReceiver() throws Throwable {
        try {
            Fixture.setStaticBase(7);
            var delegates = FormulaDelegateFactory.compile(Fixture.class, Map.of(
                    "staticRoot(I)I", '(' + readable("staticRoot(I)I") + ") + 7",
                    "multiple(JDI)D", '(' + readable("multiple(JDI)D") + ") + 0.5"), true);
            assertEquals(24, delegates.get("staticRoot(I)I").invoke(new Object(), new Object[]{5}).intValue());
            assertEquals(17, Fixture.staticRoot(5));
            Fixture.setStaticBase(21);
            assertEquals(38, delegates.get("staticRoot(I)I").invoke(null, new Object[]{5}).intValue());
            assertEquals(9.75, delegates.get("multiple(JDI)D").invoke(null, new Object[]{3L, 1.25, 5}).doubleValue());
        } finally {
            Fixture.setStaticBase(7);
        }
    }

    @Test
    void namedFallbackPreservesSpecialSuperDispatchAndVirtualOverrides() throws Throwable {
        String key = "specialAndVirtual(I)I";
        var delegates = FormulaDelegateFactory.compile(Fixture.class,
                Map.of(key, '(' + readable(key) + ") + 1"), true);
        assertEquals(41, delegates.get(key).invoke(new Fixture(), new Object[]{5}).intValue());
        assertEquals(133, delegates.get(key).invoke(new DerivedFixture(), new Object[]{5}).intValue());
    }

    @Test
    void identicalOverridesReuseDelegatesAndContinueReadingCurrentBaseFields() throws Throwable {
        String key = "integer(I)I";
        Map<String, String> changes = Map.of(key, '(' + readable(key) + ") + 9");
        var first = FormulaDelegateFactory.compile(Fixture.class, changes, true);
        var second = FormulaDelegateFactory.compile(Fixture.class, new LinkedHashMap<>(changes), true);
        assertSame(first, second);
        assertSame(first.get(key), second.get(key));
        Fixture fixture = new Fixture();
        assertEquals(50, first.get(key).invoke(fixture, new Object[]{5}).intValue());
        fixture.setBase(40);
        assertEquals(77, second.get(key).invoke(fixture, new Object[]{5}).intValue());
    }

    @Test
    void inheritedRootsCompileOnTheirDeclaringOwnersAndKeepTheConcreteReceiver() throws Throwable {
        for (boolean forceNamed : new boolean[]{false, true}) {
            String inherited = "protectedHelper(I)I";
            String expression = "base + level * 4 + 9";
            var changes = Map.of("integer(I)I", '(' + readable("integer(I)I") + ") + 7",
                    "floating(I)F", '(' + readable("floating(I)F") + ") + 7",
                    inherited, expression, "staticRoot(I)I", '(' + readable("staticRoot(I)I") + ") + 7");
            var delegates = FormulaDelegateFactory.compile(InheritingFixture.class, changes, forceNamed);
            assertEquals(changes.keySet(), delegates.keySet());
            assertSame(FormulaDelegateFactory.compile(DelegateFactoryParent.class, Map.of(inherited, expression), forceNamed)
                    .get(inherited), delegates.get(inherited));
            InheritingFixture receiver = new InheritingFixture();
            assertEquals(41, delegates.get("integer(I)I").invoke(receiver, new Object[]{5}).intValue());
            assertEquals(receiver.floating(5) + 7, delegates.get("floating(I)F").invoke(receiver, new Object[]{5}).floatValue());
            assertEquals(42, delegates.get(inherited).invoke(receiver, new Object[]{5}).intValue());
            assertEquals(24, delegates.get("staticRoot(I)I").invoke(receiver, new Object[]{5}).intValue());
            assertEquals(41, receiver.integer(5));
            receiver.setBase(37);
            assertEquals(65, delegates.get("integer(I)I").invoke(receiver, new Object[]{5}).intValue());
            assertEquals(66, delegates.get(inherited).invoke(receiver, new Object[]{5}).intValue());
            assertEquals(65, receiver.integer(5));
        }
    }

    @Test
    void inheritanceResolvesNearestOverrideAndRejectsAncestorPrivateRoots() throws Throwable {
        String key = "overridable(I)I";
        var delegates = FormulaDelegateFactory.compile(DerivedFixture.class, Map.of(key, "level + 1"), true);
        assertEquals(6, delegates.get(key).invoke(new DerivedFixture(), new Object[]{5}).intValue());
        assertEquals(100, new DerivedFixture().overridable(5));
        assertThrows(IllegalArgumentException.class, () -> FormulaDelegateFactory.compile(InheritingFixture.class,
                Map.of("privateHelper(I)I", "level + 1")));
    }

    @Test
    void invalidTargetsAndExpressionsFailBeforeReturningAnIncompleteDelegateMap() {
        assertThrows(IllegalArgumentException.class, () -> FormulaDelegateFactory.compile(Fixture.class,
                Map.of("missing(I)I", "level + 1")));
        assertThrows(IllegalArgumentException.class, () -> FormulaDelegateFactory.compile(Fixture.class,
                Map.of("integer(I)I", "missing + 1")));
    }

    @Test
    void exactNumericLookupDoesNotResolveUnrelatedClientOnlyMethodOrFieldTypes() throws Throwable {
        String owner = "com/example/spell_compiler/formula/IsolatedFormulaFixture";
        String forbidden = "formula/ClientOnlyNeverLoad";
        byte[] definition = isolated(owner, forbidden);
        int[] forbiddenLoads = {0};
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(forbidden.replace('/', '.'))) {
                    forbiddenLoads[0]++;
                    throw new ClassNotFoundException("Client-only signature must remain unresolved");
                }
                return super.loadClass(name, resolve);
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals(owner.replace('/', '.'))) throw new ClassNotFoundException(name);
                return defineClass(name, definition, 0, definition.length);
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return name.equals(owner + ".class") ? new ByteArrayInputStream(definition) : super.getResourceAsStream(name);
            }
        };
        Class<?> type = loader.loadClass(owner.replace('/', '.'));
        Object fixture = type.getConstructor().newInstance();
        var delegates = FormulaDelegateFactory.compile(type,
                Map.of("numeric(I)I", "base + level * 3 + 2 + 7"), true);
        assertEquals(35, delegates.get("numeric(I)I").invoke(fixture, new Object[]{5}).intValue());
        assertEquals(0, forbiddenLoads[0]);
    }

    private static byte[] isolated(String owner, String forbidden) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "base", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "clientOnlyField", 'L' + forbidden + ';', null, null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitLdcInsn(11);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, owner, "base", "I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        var helper = writer.visitMethod(Opcodes.ACC_PUBLIC, "safeHelper", "(I)I", null, null);
        helper.visitCode();
        helper.visitVarInsn(Opcodes.ALOAD, 0);
        helper.visitFieldInsn(Opcodes.GETFIELD, owner, "base", "I");
        helper.visitVarInsn(Opcodes.ILOAD, 1);
        helper.visitLdcInsn(3);
        helper.visitInsn(Opcodes.IMUL);
        helper.visitInsn(Opcodes.IADD);
        helper.visitInsn(Opcodes.IRETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        var numeric = writer.visitMethod(Opcodes.ACC_PUBLIC, "numeric", "(I)I", null, null);
        numeric.visitCode();
        numeric.visitVarInsn(Opcodes.ALOAD, 0);
        numeric.visitVarInsn(Opcodes.ILOAD, 1);
        numeric.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "safeHelper", "(I)I", false);
        numeric.visitInsn(Opcodes.ICONST_2);
        numeric.visitInsn(Opcodes.IADD);
        numeric.visitInsn(Opcodes.IRETURN);
        numeric.visitMaxs(0, 0);
        numeric.visitEnd();
        var unrelated = writer.visitMethod(Opcodes.ACC_PUBLIC, "clientOnlyMethod", "(L" + forbidden + ";)L" + forbidden + ';', null, null);
        unrelated.visitCode();
        unrelated.visitVarInsn(Opcodes.ALOAD, 1);
        unrelated.visitInsn(Opcodes.ARETURN);
        unrelated.visitMaxs(0, 0);
        unrelated.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void checkNumericDelegates(boolean forceNamed) throws Throwable {
        Map<String, String> changes = new LinkedHashMap<>();
        for (String key : new String[]{"integer(I)I", "floating(I)F", "decimal(I)D", "longValue(I)J"})
            changes.put(key, '(' + readable(key) + ") + 7");
        var delegates = FormulaDelegateFactory.compile(Fixture.class, changes, forceNamed);
        Fixture fixture = new Fixture();
        assertEquals(48, delegates.get("integer(I)I").invoke(fixture, new Object[]{5}).intValue());
        assertEquals(41, fixture.integer(5));
        assertEquals(Float.floatToRawIntBits(fixture.floating(5) + 7), Float.floatToRawIntBits(
                delegates.get("floating(I)F").invoke(fixture, new Object[]{5}).floatValue()));
        assertEquals(Double.doubleToRawLongBits(fixture.decimal(5) + 7), Double.doubleToRawLongBits(
                delegates.get("decimal(I)D").invoke(fixture, new Object[]{5}).doubleValue()));
        assertEquals(fixture.longValue(5) + 7, delegates.get("longValue(I)J").invoke(fixture, new Object[]{5}).longValue());
        fixture.setBase(37);
        assertEquals(fixture.integer(5) + 7, delegates.get("integer(I)I").invoke(fixture, new Object[]{5}).intValue());
    }

    private static String readable(String key) throws Exception {
        try (var stream = Fixture.class.getResourceAsStream('/' + Fixture.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);
            ClassNode owner = new ClassNode();
            new ClassReader(stream.readAllBytes()).accept(owner, 0);
            var method = owner.methods.stream().filter(value -> (value.name + value.desc).equals(key)).findFirst().orElseThrow();
            var analysis = AutomaticFormulaExtractor.analyzeRuntime(owner, method, FormulaClassRepository.of(owner));
            assertTrue(analysis.supported(), analysis.rejection());
            return analysis.formula().readableExpression();
        }
    }

    public static class Fixture extends DelegateFactoryParent {
        private int privatePart = 3;
        private float privateFloat = 0.25f;
        private double privateDouble = 0.5;
        private long privateLong = 11;
        private static int staticBase = 7;

        public int integer(int level) {
            return protectedHelper(level) + privateHelper(level);
        }

        private int privateHelper(int level) {
            return privatePart + level;
        }

        public float floating(int level) {
            return growth * level + privateFloat;
        }

        public double decimal(int level) {
            return bias * level + privateDouble;
        }

        public long longValue(int level) {
            return counter + privateLong * level;
        }

        public static int staticRoot(int level) {
            return staticBase + level * 2;
        }

        public static double multiple(long value, double fraction, int level) {
            return value + fraction + level;
        }

        public static void setStaticBase(int value) {
            staticBase = value;
        }

        public int overridable(int level) {
            return level + 2;
        }

        public int specialAndVirtual(int level) {
            return super.protectedHelper(level) + overridable(level);
        }
    }

    public static class DerivedFixture extends Fixture {
        @Override
        protected int protectedHelper(int level) {
            return 999;
        }

        @Override
        public int overridable(int level) {
            return 100;
        }
    }

    public static class InheritingFixture extends Fixture {}
}
