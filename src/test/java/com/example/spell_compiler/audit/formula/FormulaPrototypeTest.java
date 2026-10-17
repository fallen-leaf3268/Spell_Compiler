package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public final class FormulaPrototypeTest {
    @Test
    void automaticallyExtractsArgumentsFieldsAndReadableArithmetic() throws IOException {
        var mana = analyze("mana", "(I)I");
        assertTrue(mana.supported(), mana.rejection());
        assertNotNull(mana.formula());
        assertFalse(mana.formula().expression().isBlank());
        assertEquals(Set.of("level"), mana.formula().variables().keySet());
        assertTrue(mana.formula().expression().contains("level"));
        var damage = analyze("damage", "(I)F");
        assertTrue(damage.supported(), damage.rejection());
        assertEquals(Set.of("level", "baseDamage"), damage.formula().variables().keySet());
        assertTrue(damage.formula().expression().contains("baseDamage"));
        assertTrue(damage.formula().expression().contains("level"));
        for (var variable : damage.formula().variables().values()) {
            assertFalse(variable.name().isBlank());
            assertNotNull(variable.type());
        }
    }

    @Test
    void recompilingAutomaticallyExtractedDefaultsPreservesOriginalResults() throws Exception {
        var signatures = Map.of("mana", "(I)I", "damage", "(I)F", "integerDivision", "(I)I",
                "floatAfterIntegerDivision", "(I)F", "doubleArithmetic", "(I)D", "untouched", "(I)I");
        var defaults = new LinkedHashMap<String, String>();
        for (var entry : signatures.entrySet()) {
            var analysis = analyze(entry.getKey(), entry.getValue());
            assertTrue(analysis.supported(), entry.getKey() + ": " + analysis.rejection());
            defaults.put(entry.getKey() + entry.getValue(), analysis.formula().expression());
        }
        var transformed = transformed(defaults);
        var reference = new FormulaFixture();
        for (int base : new int[]{0, 7, 31}) {
            reference.baseDamage = base;
            transformed.getClass().getField("baseDamage").setInt(transformed, base);
            for (int level : new int[]{Integer.MIN_VALUE, -7, -1, 0, 1, 2, 3, 7, 12, Integer.MAX_VALUE}) {
                for (String method : signatures.keySet()) {
                    var expected = invoke(reference, method, level);
                    var actual = invoke(transformed, method, level);
                    assertNumericallyIdentical(expected, actual, method + " level=" + level + " base=" + base);
                }
            }
        }
    }

    @Test
    void oneGenericTransformationChangesTwoMethodsWithoutAffectingUnspecifiedMethods() throws Exception {
        var transformed = transformed(Map.of("mana(I)I", "101", "damage(I)F", "77.25f"));
        var reference = new FormulaFixture();
        for (int level : new int[]{-3, 0, 1, 4, 11}) {
            assertEquals(101, invoke(transformed, "mana", level));
            assertEquals(77.25f, invoke(transformed, "damage", level));
            assertEquals(invoke(reference, "untouched", level), invoke(transformed, "untouched", level));
            assertEquals(invoke(reference, "branch", level), invoke(transformed, "branch", level));
        }
        assertEquals(6, invoke(transformed, "sideEffect", 3));
        assertEquals(1, transformed.getClass().getField("counter").getInt(transformed));
        assertEquals(8, invoke(transformed, "sideEffect", 4));
        assertEquals(2, transformed.getClass().getField("counter").getInt(transformed));
    }

    @Test
    void editableFormulaUsesExtractedFieldAndArgumentVariables() throws Exception {
        var transformed = transformed(Map.of("damage(I)F", "float(baseDamage + level * 4)"));
        transformed.getClass().getField("baseDamage").setInt(transformed, 11);
        assertEquals(31f, invoke(transformed, "damage", 5));
        assertEquals(3f, invoke(transformed, "damage", -2));
        transformed.getClass().getField("baseDamage").setInt(transformed, 20);
        assertEquals(40f, invoke(transformed, "damage", 5));
    }

    @Test
    void expressionCompilationPreservesIntegerDivisionBeforeFloatConversion() throws Exception {
        var transformed = transformed(Map.of("mana(I)I", "int(float(level / 2) * 1.5f)",
                "damage(I)F", "(float(level) / 2.0f) % 3.0f"));
        for (int level : new int[]{-7, -3, -1, 0, 1, 3, 7, 12}) {
            assertEquals((int) ((float) (level / 2) * 1.5f), invoke(transformed, "mana", level));
            assertNumericallyIdentical(((float) level / 2.0f) % 3.0f,
                    invoke(transformed, "damage", level), "floating remainder level=" + level);
        }
        assertEquals(4, invoke(transformed, "mana", 7));
        assertEquals(-4, invoke(transformed, "mana", -7));
    }

    @Test
    void equalValuedFloatingDivisorsChangeIntegerResults() throws Exception {
        var reference = new FormulaFixture();
        assertEquals(42, reference.arrowVolleyCount(3));
        for (String divisor : new String[]{"2.0", "2f", "double(2)", "float(2)"}) {
            var transformed = transformed(Map.of("integerDivision(I)I", "level / " + divisor,
                    "arrowVolleyCount(I)I", "(4 + level) * (5 + level / " + divisor + ')'));
            assertEquals(45, invoke(transformed, "arrowVolleyCount", 3), divisor);
            for (int level : new int[]{-7, -3, -1, 0, 1, 3, 7, 12}) {
                assertEquals((int) (level / 2.0), invoke(transformed, "integerDivision", level), divisor);
                assertEquals((int) ((4 + level) * (5 + level / 2.0)),
                        invoke(transformed, "arrowVolleyCount", level), divisor);
            }
        }
    }

    @Test
    void expressionsSupportLongDoubleFloatAndIntConversions() throws Exception {
        var transformed = transformed(Map.of("mana(I)I", "int(long(level) * 3 + 1)",
                "damage(I)F", "float(double(level) / 2.0)"));
        for (int level : new int[]{Integer.MIN_VALUE, -3, 0, 3, Integer.MAX_VALUE}) {
            assertEquals((int) ((long) level * 3 + 1), invoke(transformed, "mana", level));
            assertNumericallyIdentical((float) ((double) level / 2.0),
                    invoke(transformed, "damage", level), "numeric conversions level=" + level);
        }
    }

    @Test
    void unknownVariablesAndMalformedExpressionsAreRejected() throws IOException {
        var original = originalBytes();
        for (String expression : new String[]{"unknown_parameter + level", "level + )", "level ** 2", ""}) {
            var exception = assertThrows(IllegalArgumentException.class,
                    () -> FormulaCompiler.transform(original, Map.of("mana(I)I", expression)), expression);
            assertNotNull(exception.getMessage());
            assertFalse(exception.getMessage().isBlank(), expression);
        }
    }

    @Test
    void unknownMethodNamesAndDescriptorsAreRejected() throws IOException {
        var original = originalBytes();
        for (String key : new String[]{"missing(I)I", "mana(F)I", "mana", "<init>()V"}) {
            var exception = assertThrows(IllegalArgumentException.class,
                    () -> FormulaCompiler.transform(original, Map.of(key, "10")), key);
            assertNotNull(exception.getMessage());
            assertFalse(exception.getMessage().isBlank(), key);
        }
    }

    @Test
    void sideEffectsAndBranchesAreExplicitlyRejectedInsteadOfSilentlyReplaced() throws IOException {
        var original = originalBytes();
        for (String name : new String[]{"sideEffect", "branch"}) {
            var analysis = analyze(name, "(I)I");
            assertFalse(analysis.supported(), name);
            assertNotNull(analysis.rejection());
            assertFalse(analysis.rejection().isBlank(), name);
            var exception = assertThrows(IllegalArgumentException.class,
                    () -> FormulaCompiler.transform(original, Map.of(name + "(I)I", "99")), name);
            assertNotNull(exception.getMessage());
            assertFalse(exception.getMessage().isBlank(), name);
        }
        var mixed = new LinkedHashMap<String, String>();
        mixed.put("mana(I)I", "101");
        mixed.put("sideEffect(I)I", "99");
        assertThrows(IllegalArgumentException.class, () -> FormulaCompiler.transform(original, mixed));
    }

    private static AutomaticFormulaExtractor.Analysis analyze(String name, String descriptor) throws IOException {
        var owner = new ClassNode();
        new ClassReader(originalBytes()).accept(owner, 0);
        MethodNode method = owner.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(descriptor)).findFirst().orElseThrow();
        return AutomaticFormulaExtractor.analyze(owner, method);
    }

    private static byte[] originalBytes() throws IOException {
        String path = '/' + FormulaFixture.class.getName().replace('.', '/') + ".class";
        try (var input = FormulaFixture.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return input.readAllBytes();
        }
    }

    private static Object transformed(Map<String, String> overrides) throws Exception {
        byte[] original = originalBytes();
        var owner = new ClassNode();
        new ClassReader(original).accept(owner, 0);
        byte[] bytes = FormulaCompiler.compileForVerification(original, overrides, FormulaClassRepository.of(owner));
        assertNotNull(bytes);
        var loader = new ClassLoader(FormulaPrototypeTest.class.getClassLoader()) {
            Class<?> defineFixture() {
                return defineClass(FormulaFixture.class.getName(), bytes, 0, bytes.length);
            }
        };
        var type = loader.defineFixture();
        assertNotSame(FormulaFixture.class, type);
        assertSame(loader, type.getClassLoader());
        return type.getConstructor().newInstance();
    }

    private static Object invoke(Object receiver, String method, int level) throws ReflectiveOperationException {
        return receiver.getClass().getMethod(method, int.class).invoke(receiver, level);
    }

    private static void assertNumericallyIdentical(Object expected, Object actual, String context) {
        if (expected instanceof Float number) {
            assertInstanceOf(Float.class, actual, context);
            assertEquals(Float.floatToRawIntBits(number), Float.floatToRawIntBits((Float) actual), context);
        } else if (expected instanceof Double number) {
            assertInstanceOf(Double.class, actual, context);
            assertEquals(Double.doubleToRawLongBits(number), Double.doubleToRawLongBits((Double) actual), context);
        } else {
            assertEquals(expected, actual, context);
        }
    }

    public static class FormulaFixture {
        public int baseDamage = 7;
        public int counter;

        public FormulaFixture() {}

        public int mana(int level) {
            return 30 + 5 * (level - 1);
        }

        public float damage(int level) {
            return (baseDamage + 2 * (level - 1)) * 0.5f;
        }

        public int integerDivision(int level) {
            return level / 2;
        }

        public int arrowVolleyCount(int level) {
            return (4 + level) * (5 + level / 2);
        }

        public float floatAfterIntegerDivision(int level) {
            return (float) (level / 2) * 1.5f;
        }

        public double doubleArithmetic(int level) {
            return (double) (level / 3) + 0.125;
        }

        public int untouched(int level) {
            return level * level + 1;
        }

        public int sideEffect(int level) {
            counter = counter + 1;
            return level * 2;
        }

        public int branch(int level) {
            return level > 0 ? level : -level;
        }
    }
}
