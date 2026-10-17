package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormulaFunctionsTest {
    @Test
    void untouchedAndIdenticalDefaultsReturnTheOriginalByteArray() throws Exception {
        byte[] original = originalBytes();
        assertSame(original, FormulaCompiler.transform(original, Map.of()));
        var owner = new ClassNode();
        new ClassReader(original).accept(owner, 0);
        var method = owner.methods.stream().filter(value -> value.name.equals("integer")).findFirst().orElseThrow();
        var analysis = AutomaticFormulaExtractor.analyze(owner, method);
        assertTrue(analysis.supported(), analysis.rejection());
        assertSame(original, FormulaCompiler.transform(original,
                Map.of("integer(I)I", " \n " + analysis.formula().expression() + " \t ")));
        assertThrows(IllegalArgumentException.class,
                () -> FormulaCompiler.transform(original, Map.of("integer(I)I", "le vel")));
    }

    @Test
    void nestedFunctionsPreserveArithmeticOrderAndIntegerDivision() throws Exception {
        Object transformed = transformed(Map.of("integer(I)I",
                "clamp(int(sqrt(abs(double(level)))) + min(level / 2, 7), 0, max(2, 6))"));
        for (int level : new int[]{-16, -7, -1, 0, 1, 3, 7, 16, 99}) {
            int expected = Math.min(Math.max((int) Math.sqrt(Math.abs((double) level)) + Math.min(level / 2, 7), 0), 6);
            assertEquals(expected, invoke(transformed, "integer", int.class, level));
        }
        assertEquals(11, invoke(transformed, "untouched", int.class, 10));
    }

    @Test
    void minMaxAndAbsoluteValuePreserveNumericOverloads() throws Exception {
        Object integer = transformed(Map.of("integer(I)I", "abs(level)"));
        assertEquals(Integer.MIN_VALUE, invoke(integer, "integer", int.class, Integer.MIN_VALUE));
        Object longValues = transformed(Map.of("decimal(D)D", "double(max(min(3000000001l, 3000000000l), 2999999999l))"));
        assertRawDouble(3000000000d, (Double) invoke(longValues, "decimal", double.class, 0d));
        for (String name : new String[]{"min", "max", "abs"}) {
            String formula = name.equals("abs") ? "abs(arg0)" : name + "(arg0, 0.0f)";
            Object floating = transformed(Map.of("floating(F)F", formula));
            for (float input : new float[]{-0.0f, 0.0f, -2.5f, 2.5f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
                float expected = switch (name) {
                    case "min" -> Math.min(input, 0.0f);
                    case "max" -> Math.max(input, 0.0f);
                    default -> Math.abs(input);
                };
                assertRawFloat(expected, (Float) invoke(floating, "floating", float.class, input));
            }
        }
    }

    @Test
    void clampMatchesMinecraftForEachAvailableNumericType() throws Exception {
        Object integer = transformed(Map.of("integer(I)I", "clamp(level, 5, -2)"));
        for (int input : new int[]{-10, 0, 5, 10}) {
            assertEquals(Math.min(Math.max(input, 5), -2), invoke(integer, "integer", int.class, input));
        }
        Object floating = transformed(Map.of("floating(F)F", "clamp(arg0, 5.0f, -2.0f)"));
        for (float input : new float[]{-10f, -0.0f, 0f, 5f, 10f, Float.NaN}) {
            float expected = input < 5f ? 5f : Math.min(input, -2f);
            assertRawFloat(expected, (Float) invoke(floating, "floating", float.class, input));
        }
        Object decimal = transformed(Map.of("decimal(D)D", "clamp(arg0, -0.0, 2.0)"));
        for (double input : new double[]{-10d, -0.0d, 0d, 1.25d, 10d, Double.NaN}) {
            double expected = input < -0.0d ? -0.0d : Math.min(input, 2d);
            assertRawDouble(expected, (Double) invoke(decimal, "decimal", double.class, input));
        }
        Object nanBound = transformed(Map.of("floating(F)F", "clamp(-0.0f, arg0, 2.0f)"));
        assertRawFloat(-0.0f, (Float) invoke(nanBound, "floating", float.class, Float.NaN));
    }

    @Test
    void roundFloorCeilingAndSquareRootUseTheirNativeReturnTypes() throws Exception {
        Object roundedFloat = transformed(Map.of("rounded(F)I", "round(arg0)"));
        for (float input : new float[]{-2.5f, -1.5f, -0.5f, -0.0f, 0.5f, 1.5f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertEquals(Math.round(input), invoke(roundedFloat, "rounded", float.class, input));
        }
        Object roundedDouble = transformed(Map.of("decimal(D)D", "double(round(arg0))"));
        for (double input : new double[]{-2.5d, -0.5d, 0.5d, 3000000000.5d, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertRawDouble((double) Math.round(input), (Double) invoke(roundedDouble, "decimal", double.class, input));
        }
        for (String name : new String[]{"floor", "ceil", "sqrt"}) {
            Object transformed = transformed(Map.of("decimal(D)D", name + "(arg0)"));
            for (double input : new double[]{-2.75d, -0.0d, 0d, 0.25d, 2.75d, Double.NaN, Double.POSITIVE_INFINITY}) {
                double expected = switch (name) {
                    case "floor" -> Math.floor(input);
                    case "ceil" -> Math.ceil(input);
                    default -> Math.sqrt(input);
                };
                assertRawDouble(expected, (Double) invoke(transformed, "decimal", double.class, input));
            }
        }
    }

    @Test
    void nativeIntegerFloorAndCeilingKeepOverflowAndNanSemantics() throws Exception {
        Object floorFloat = transformed(Map.of("rounded(F)I", "mth_floor(arg0)"));
        Object ceilFloat = transformed(Map.of("rounded(F)I", "mth_ceil(arg0)"));
        assertEquals(Integer.MAX_VALUE, invoke(floorFloat, "rounded", float.class, Float.NEGATIVE_INFINITY));
        assertEquals(Integer.MIN_VALUE, invoke(ceilFloat, "rounded", float.class, Float.POSITIVE_INFINITY));
        assertEquals(0, invoke(floorFloat, "rounded", float.class, Float.NaN));
        assertEquals(0, invoke(ceilFloat, "rounded", float.class, Float.NaN));
        assertEquals(-3, invoke(floorFloat, "rounded", float.class, -2.25f));
        assertEquals(-2, invoke(ceilFloat, "rounded", float.class, -2.25f));
        Object floorDouble = transformed(Map.of("decimal(D)D", "double(mth_floor(arg0))"));
        Object ceilDouble = transformed(Map.of("decimal(D)D", "double(mth_ceil(arg0))"));
        assertRawDouble(Integer.MAX_VALUE, (Double) invoke(floorDouble, "decimal", double.class, Double.NEGATIVE_INFINITY));
        assertRawDouble(Integer.MIN_VALUE, (Double) invoke(ceilDouble, "decimal", double.class, Double.POSITIVE_INFINITY));
        Object roundInteger = transformed(Map.of("integer(I)I", "round(level)"));
        assertEquals(Integer.MAX_VALUE, invoke(roundInteger, "integer", int.class, Integer.MAX_VALUE));
    }

    @Test
    void verificationCompilationDoesNotUseTheUnchangedDefaultBypass() throws Exception {
        byte[] original = originalBytes();
        var owner = new ClassNode();
        new ClassReader(original).accept(owner, 0);
        var repository = FormulaClassRepository.of(owner);
        byte[] compiled = FormulaCompiler.compileForVerification(original, Map.of("integer(I)I", "level"), repository);
        assertNotSame(original, compiled);
        assertSame(original, FormulaCompiler.transform(original, Map.of("integer(I)I", "level"), repository));
    }

    @Test
    void compilingAHelperAndItsCallerKeepsTheAnalysisSourceUnchanged() throws Exception {
        byte[] original = originalBytes();
        var owner = new ClassNode();
        new ClassReader(original).accept(owner, 0);
        var repository = FormulaClassRepository.of(owner);
        Map<String, String> defaults = new LinkedHashMap<>();
        for (String name : new String[]{"integer", "nested"}) {
            var method = owner.methods.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
            var analysis = AutomaticFormulaExtractor.analyze(owner, method, repository);
            assertTrue(analysis.supported(), analysis.rejection());
            defaults.put(method.name + method.desc, analysis.formula().expression());
        }
        byte[] snapshot = classBytes(owner);
        byte[] rebuilt = FormulaCompiler.compileForVerification(original, defaults, repository);
        assertArrayEquals(snapshot, classBytes(repository.classNode(owner.name)));
        Object roundTrip = defineFixture(rebuilt);
        for (int level : new int[]{-2, 3, 15}) {
            assertEquals(level, invoke(roundTrip, "integer", int.class, level));
            assertEquals(level * 2, invoke(roundTrip, "nested", int.class, level));
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("integer(I)I", "int(clamp(float(level), 0.0f, 10.0f))");
        overrides.put("nested(I)I", '(' + defaults.get("nested(I)I") + ") + 7");
        Object modified = defineFixture(FormulaCompiler.transform(original, overrides, repository));
        assertArrayEquals(snapshot, classBytes(repository.classNode(owner.name)));
        for (int level : new int[]{-2, 3, 15}) {
            assertEquals(Math.min(Math.max(level, 0), 10), invoke(modified, "integer", int.class, level));
            assertEquals(level * 2 + 7, invoke(modified, "nested", int.class, level));
        }
    }

    @Test
    void minimumNegativeIntegerLiteralsCompileWithoutAcceptingPositiveOverflow() throws Exception {
        Object integer = transformed(Map.of("integer(I)I", "-2147483648"));
        assertEquals(Integer.MIN_VALUE, invoke(integer, "integer", int.class, 0));
        Object longValue = transformed(Map.of("integer(I)I", "int(-9223372036854775808l % 17l)"));
        assertEquals((int) (Long.MIN_VALUE % 17L), invoke(longValue, "integer", int.class, 0));
        Object uppercase = transformed(Map.of("integer(I)I", "int(- 9223372036854775808L % 17L)"));
        assertEquals((int) (Long.MIN_VALUE % 17L), invoke(uppercase, "integer", int.class, 0));
        byte[] original = originalBytes();
        for (String literal : new String[]{"2147483648", "9223372036854775808l", "-2147483649", "-9223372036854775809l"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> FormulaCompiler.transform(original, Map.of("integer(I)I", literal)), literal);
        }
    }

    @Test
    void minimumIntegerLiteralHandlingLeavesFloatingUnaryNegationUnchanged() throws Exception {
        Object negated = transformed(Map.of("decimal(D)D", "-arg0"));
        for (double input : new double[]{-0.0d, 0.0d, 0.25d, -2.5d, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertRawDouble(-input, (Double) invoke(negated, "decimal", double.class, input));
        }
        for (String literal : new String[]{"-2147483648.0", "-9223372036854775808.0", "-0.0"}) {
            Object value = transformed(Map.of("decimal(D)D", literal));
            assertRawDouble(Double.parseDouble(literal), (Double) invoke(value, "decimal", double.class, 0d));
        }
    }

    @Test
    void invalidFunctionNamesAndAritiesRejectTheWholeTransformation() throws Exception {
        byte[] original = originalBytes();
        for (String formula : new String[]{"min(level)", "max(level, 2, 3)", "clamp(level, 0)",
                "round(level, 1)", "sqrt()", "abs()", "floor(level, 2)", "unknown(level)",
                "min(level,)", "int(level, 2)"}) {
            var exception = assertThrows(IllegalArgumentException.class,
                    () -> FormulaCompiler.transform(original,
                            Map.of("integer(I)I", "42", "floating(F)F", formula)), formula);
            assertFalse(exception.getMessage().isBlank());
        }
        Object untouched = Fixture.class.getConstructor().newInstance();
        assertEquals(7, invoke(untouched, "integer", int.class, 7));
    }

    @Test
    void excessiveComplexityIsRejectedByValidationAndContextualCompilation() throws Exception {
        byte[] original = originalBytes();
        for (String expression : new String[]{"(".repeat(3000) + "level" + ")".repeat(3000),
                "-".repeat(3000) + "level", "abs(".repeat(1000) + "level" + ")".repeat(1000),
                "level + ".repeat(1200) + "level"}) {
            var validation = assertThrows(IllegalArgumentException.class,
                    () -> FormulaCompiler.validateExpression(expression, Map.of("level", Type.INT_TYPE)));
            assertTrue(validation.getMessage().contains("limit"), validation.getMessage());
            var compilation = assertThrows(IllegalArgumentException.class,
                    () -> FormulaCompiler.transform(original, Map.of("integer(I)I", expression)));
            assertTrue(compilation.getMessage().contains("#integer(I)I"), compilation.getMessage());
            assertTrue(compilation.getMessage().contains("limit"), compilation.getMessage());
        }
        assertDoesNotThrow(() -> transformed(Map.of("integer(I)I", "level + ".repeat(40) + "level")));
    }

    @Test
    void runtimeCompilationChecksNonFiniteValuesBeforeIntegerAndLongConversions() throws Exception {
        for (String expression : new String[]{"1.0 / (level - level)", "int(1.0 / (level - level))",
                "int(1.0f / float(level - level))", "int(long(1.0 / (level - level)))",
                "int(long(1.0f / float(level - level)))", "int(round(1.0 / (level - level)))",
                "round(1.0f / float(level - level))", "mth_floor(1.0 / (level - level))",
                "mth_ceil(1.0f / float(level - level))", "int(sqrt(-1))"}) {
            Object compiled = transformedRuntime(Map.of("integer(I)I", expression));
            var failure = assertThrows(InvocationTargetException.class,
                    () -> invoke(compiled, "integer", int.class, 3), expression);
            assertInstanceOf(ArithmeticException.class, failure.getCause(), expression);
            assertTrue(failure.getCause().getMessage().contains("Non-finite"), expression);
        }
        Object offlineInfinity = transformed(Map.of("integer(I)I", "int(1.0 / (level - level))"));
        assertEquals(Integer.MAX_VALUE, invoke(offlineInfinity, "integer", int.class, 3));
        Object offlineNan = transformed(Map.of("integer(I)I", "int(sqrt(-1))"));
        assertEquals(0, invoke(offlineNan, "integer", int.class, 3));
    }

    @Test
    void runtimeCompilationChecksFinalFloatingResultsAndLeavesUnmodifiedMethodsNative() throws Exception {
        Object compiled = transformedRuntime(Map.of("floating(F)F", "arg0 * 2", "decimal(D)D", "arg0 * 2"));
        assertRawFloat(1f, (Float) invoke(compiled, "floating", float.class, 0.5f));
        assertRawDouble(1d, (Double) invoke(compiled, "decimal", double.class, 0.5d));
        assertRawFloat(-0.0f, (Float) invoke(compiled, "floating", float.class, -0.0f));
        assertRawDouble(-0.0d, (Double) invoke(compiled, "decimal", double.class, -0.0d));
        for (float input : new float[]{Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY}) {
            var failure = assertThrows(InvocationTargetException.class,
                    () -> invoke(compiled, "floating", float.class, input));
            assertInstanceOf(ArithmeticException.class, failure.getCause());
        }
        for (double input : new double[]{Double.MAX_VALUE, Double.NaN, Double.NEGATIVE_INFINITY}) {
            var failure = assertThrows(InvocationTargetException.class,
                    () -> invoke(compiled, "decimal", double.class, input));
            assertInstanceOf(ArithmeticException.class, failure.getCause());
        }
        assertEquals(Integer.MAX_VALUE, invoke(compiled, "rounded", float.class, Float.POSITIVE_INFINITY));
        assertEquals(0, invoke(compiled, "rounded", float.class, Float.NaN));
        byte[] original = originalBytes();
        var owner = new ClassNode();
        new ClassReader(original).accept(owner, 0);
        assertSame(original, FormulaCompiler.transformRuntime(original,
                Map.of("floating(F)F", "arg0", "rounded(F)I", "int(arg0)"), FormulaClassRepository.of(owner)));
    }

    private static byte[] originalBytes() throws IOException {
        try (var stream = Fixture.class.getResourceAsStream('/' + Fixture.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }

    private static Object transformed(Map<String, String> overrides) throws Exception {
        return defineFixture(FormulaCompiler.transform(originalBytes(), overrides));
    }

    private static Object transformedRuntime(Map<String, String> overrides) throws Exception {
        byte[] original = originalBytes();
        var owner = new ClassNode();
        new ClassReader(original).accept(owner, 0);
        return defineFixture(FormulaCompiler.transformRuntime(original, overrides, FormulaClassRepository.of(owner)));
    }

    private static Object defineFixture(byte[] bytes) throws Exception {
        var loader = new ClassLoader(FormulaFunctionsTest.class.getClassLoader()) {
            Class<?> defineFixture() {
                return defineClass(Fixture.class.getName(), bytes, 0, bytes.length);
            }
        };
        return loader.defineFixture().getConstructor().newInstance();
    }

    private static byte[] classBytes(ClassNode owner) {
        var writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static Object invoke(Object receiver, String name, Class<?> argumentType, Object argument) throws Exception {
        return receiver.getClass().getMethod(name, argumentType).invoke(receiver, argument);
    }

    private static void assertRawFloat(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    private static void assertRawDouble(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    public static class Fixture {
        public int integer(int level) {
            return level;
        }

        public int nested(int level) {
            return integer(level) * 2;
        }

        public float floating(float input) {
            return input;
        }

        public double decimal(double input) {
            return input;
        }

        public int rounded(float input) {
            return (int) input;
        }

        public int untouched(int level) {
            return level + 1;
        }
    }
}
