package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormulaRuntimeIntegerSafetyTest {
    @Test
    void runtimeRejectsIntegerOverflowBeforeTheWrappedResultCanReachAnEntryLimit() throws Exception {
        for (String expression : new String[]{"65536 * 65536 + 1", "2147483647 + 1",
                "-2147483648 - 1", "-2147483648 / -1", "-(-2147483648)", "abs(-2147483648)",
                "clamp(2147483647 + 1, 0, 10)"}) {
            assertArithmeticFailure("integer(I)I", expression, int.class, 1);
        }
    }

    @Test
    void runtimeRejectsLongOverflowEvenWhenTheFinalValueWouldFitAnInteger() throws Exception {
        for (String expression : new String[]{"(9223372036854775807L + 1L) % 17L",
                "(-9223372036854775808L - 1L) % 17L", "(4294967296L * 4294967296L) % 17L",
                "(-9223372036854775808L / -1L) % 17L", "-(-9223372036854775808L) % 17L",
                "abs(-9223372036854775808L) % 17L"}) {
            assertArithmeticFailure("integer(I)I", expression, int.class, 1);
        }
    }

    @Test
    void runtimeRejectsNarrowingAndImplicitReturnConversionsThatLoseIntegerRange() throws Exception {
        for (String expression : new String[]{"4294967297L", "int(4294967297L)", "2147483648.0",
                "int(-2147483649.0)", "int(2147483648.0f)", "int(-2147483904.0f)",
                "long(9223372036854775808.0)", "long(-9223372036854777856.0)",
                "long(9223372036854775808.0f)", "long(-18446744073709551616.0f)"}) {
            assertArithmeticFailure("integer(I)I", expression, int.class, 1);
        }
    }

    @Test
    void runtimeRejectsSaturatedRoundAndMinecraftFloorCeilingResults() throws Exception {
        for (String expression : new String[]{"round(2147483648.0f)", "round(-2147483904.0f)",
                "mth_floor(2147483648.0)", "mth_floor(-2147483648.25)",
                "mth_ceil(2147483647.25)", "mth_ceil(-2147483649.0)",
                "mth_floor(2147483648.0f)", "mth_ceil(2147483648.0f)"}) {
            assertArithmeticFailure("integer(I)I", expression, int.class, 1);
        }
        for (String expression : new String[]{"round(9223372036854775808.0)",
                "round(-9223372036854777856.0)"}) {
            assertArithmeticFailure("longValue(J)J", expression, long.class, 1L);
        }
    }

    @Test
    void runtimeRetainsRepresentableIntegerBoundariesAndNativeTruncation() throws Exception {
        Map<String, Integer> expectations = Map.ofEntries(
                Map.entry("2147483647 + 0", Integer.MAX_VALUE),
                Map.entry("-2147483648 - 0", Integer.MIN_VALUE),
                Map.entry("2147483647L", Integer.MAX_VALUE),
                Map.entry("-2147483648L", Integer.MIN_VALUE),
                Map.entry("int(2147483647.75)", Integer.MAX_VALUE),
                Map.entry("int(-2147483648.75)", Integer.MIN_VALUE),
                Map.entry("int(2147483520.0f)", 2147483520),
                Map.entry("mth_floor(-2147483648.0)", Integer.MIN_VALUE),
                Map.entry("mth_ceil(2147483647.0)", Integer.MAX_VALUE),
                Map.entry("round(2147483520.0f)", 2147483520),
                Map.entry("-2147483648 % -1", 0),
                Map.entry("int(-9223372036854775808L % -1L)", 0));
        for (var expectation : expectations.entrySet()) {
            assertEquals(expectation.getValue(), evaluate("integer(I)I", expectation.getKey(), int.class, 1, true),
                    expectation.getKey());
        }
        assertEquals(Long.MIN_VALUE, evaluate("longValue(J)J", "long(-9223372036854775808.0)", long.class, 1L, true));
        assertEquals(9223372036854774784L,
                evaluate("longValue(J)J", "round(9223372036854774784.0)", long.class, 1L, true));
        assertEquals(Long.MIN_VALUE, evaluate("longValue(J)J", "round(-9223372036854775808.0)", long.class, 1L, true));
    }

    @Test
    void verificationAndOfflineTransformationRetainOriginalIntegerSemantics() throws Exception {
        assertEquals(1, evaluate("integer(I)I", "65536 * 65536 + 1", int.class, 1, false));
        assertEquals(1, evaluate("integer(I)I", "4294967297L", int.class, 1, false));
        assertEquals(Integer.MAX_VALUE,
                evaluate("integer(I)I", "2147483648.0", int.class, 1, false));
        assertEquals(Integer.MIN_VALUE,
                evaluate("integer(I)I", "abs(-2147483648)", int.class, 1, false));
    }

    private static void assertArithmeticFailure(String target, String expression, Class<?> argumentType, Object argument)
            throws Exception {
        Object compiled = compile(target, expression, true);
        var failure = assertThrows(InvocationTargetException.class,
                () -> invoke(compiled, target, argumentType, argument), expression);
        assertInstanceOf(ArithmeticException.class, failure.getCause(), expression);
    }

    private static Object evaluate(String target, String expression, Class<?> argumentType, Object argument, boolean runtime)
            throws Exception {
        return invoke(compile(target, expression, runtime), target, argumentType, argument);
    }

    private static Object invoke(Object compiled, String target, Class<?> argumentType, Object argument) throws Exception {
        return compiled.getClass().getMethod(target.substring(0, target.indexOf('(')), argumentType).invoke(compiled, argument);
    }

    private static Object compile(String target, String expression, boolean runtime) throws Exception {
        byte[] original;
        try (var stream = Fixture.class.getResourceAsStream('/' + Fixture.class.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);
            original = stream.readAllBytes();
        }
        var owner = new ClassNode();
        new ClassReader(original).accept(owner, 0);
        var repository = FormulaClassRepository.of(owner);
        byte[] compiled = runtime ? FormulaCompiler.transformRuntime(original, Map.of(target, expression), repository)
                : FormulaCompiler.transform(original, Map.of(target, expression), repository);
        var loader = new ClassLoader(FormulaRuntimeIntegerSafetyTest.class.getClassLoader()) {
            Class<?> defineFixture() {
                return defineClass(Fixture.class.getName(), compiled, 0, compiled.length);
            }
        };
        return loader.defineFixture().getConstructor().newInstance();
    }

    public static class Fixture {
        public int integer(int level) {
            return level;
        }

        public long longValue(long input) {
            return input;
        }
    }
}
