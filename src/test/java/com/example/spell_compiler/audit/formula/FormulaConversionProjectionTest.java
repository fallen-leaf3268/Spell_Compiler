package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.FormulaCompiler;
import com.example.spell_compiler.formula.ReadableFormula;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaConversionProjectionTest {
    private static final Map<String, Type> TYPES = Map.of("level", Type.INT_TYPE,
            "multiplier", Type.FLOAT_TYPE);

    @Test
    void hidesFinalConversionsAndRestoresStrictDefaultsExactly() {
        for (String strict : new String[]{"int(float(level * multiplier))", "round(float(level * multiplier))",
                "int(round(float(level * multiplier)))", "8 + int(float(level * multiplier))",
                "8 - int(float(level * multiplier))", "int(float(level * multiplier)) - 8"}) {
            String readable = ReadableFormula.display(strict);
            assertFalse(readable.contains("int("), readable);
            assertFalse(readable.contains("round("), readable);
            var resolved = ReadableFormula.resolve(strict, readable, TYPES);
            assertEquals(ReadableFormula.Strategy.SOURCE_MAPPED, resolved.strategy(), strict);
            assertEquals(strict, resolved.expression(), strict);
        }
    }

    @Test
    void preservesInternalIntegerConversionsAndNestedRoundingInPlayerFormulas() {
        assertEquals("(8 + int(level * multiplier)) / 2",
                ReadableFormula.display("(int(8) + int(float(level * multiplier))) / int(2)"));
        assertEquals("min(int(level * multiplier), 8)",
                ReadableFormula.display("min(int(float(level * multiplier)), int(8))"));
        assertEquals("round(level * multiplier) * 2",
                ReadableFormula.display("round(float(level * multiplier)) * int(2)"));
    }

    @Test
    void structuralOffsetEditsKeepTruncationBeforeTheIntegerOffsetForNegativeFractions() throws Exception {
        Object fixture = transformed(Map.of("shifted(I)I", "8 + level * 0.5",
                "subtracted(I)I", "8 - level * 0.5", "reverse(I)I", "level * 0.5 - 8"));
        assertEquals(7, invoke(fixture, "shifted", -3));
        assertEquals(9, invoke(fixture, "subtracted", -3));
        assertEquals(-9, invoke(fixture, "reverse", -3));
        assertEquals(9, invoke(fixture, "shifted", 3));
    }

    @Test
    void wholeFormulaEditsStillApplyTheOriginalFinalConversionAndFloatRounding() throws Exception {
        Object fixture = transformed(Map.of("truncated(I)I", "37.9", "rounded(I)I", "37.49999999"));
        assertEquals(37, invoke(fixture, "truncated", 1));
        assertEquals(38, invoke(fixture, "rounded", 1));
    }

    @Test
    void explicitFinalAdaptersAreRestoredOnceAndExplicitPrecisionChangesAreHonored() throws Exception {
        var rounded = ReadableFormula.resolve("round(float(level * multiplier))",
                "round(float(level * multiplier + 0.25))", TYPES);
        assertEquals(1, java.util.regex.Pattern.compile("\\bround\\s*\\(").matcher(rounded.expression()).results().count());
        var truncated = ReadableFormula.resolve("int(float(level * multiplier))",
                "int(double(level * multiplier + 0.25))", TYPES);
        assertEquals(1, java.util.regex.Pattern.compile("\\bint\\s*\\(").matcher(truncated.expression()).results().count());
        assertTrue(truncated.expression().contains("double("), truncated.expression());
        assertFalse(truncated.expression().contains("float("), truncated.expression());
        Object fixture = transformed(Map.of("rounded(I)I", "round(double(16777217))"));
        assertEquals(16_777_217, invoke(fixture, "rounded", 1));
        var different = ReadableFormula.resolve("round(float(level * multiplier))", "ceil(level * multiplier)", TYPES);
        assertTrue(different.expression().contains("ceil("), different.expression());
        assertEquals(1, java.util.regex.Pattern.compile("\\bround\\s*\\(").matcher(different.expression()).results().count());
    }

    @Test
    void rewrittenGrowthBranchesAndDecimalOffsetsKeepTheHiddenInternalTruncation() throws Exception {
        Object multiplied = transformed(Map.of("shifted(I)I", "8 + level * multiplier * 0.5"));
        assertEquals(8, invoke(multiplied, "shifted", -3));
        Object bounded = transformed(Map.of("shifted(I)I", "8 + min(level * multiplier, 0.5)"));
        assertEquals(8, invoke(bounded, "shifted", -3));
        Object decimalOffset = transformed(Map.of("shifted(I)I", "8.5 + level * multiplier"));
        assertEquals(8, invoke(decimalOffset, "shifted", 3));
        Object appended = transformed(Map.of("shifted(I)I", "(8 + level * multiplier) + 1"));
        assertEquals(9, invoke(appended, "shifted", -3));
        Object prepended = transformed(Map.of("shifted(I)I", "1 + (8 + level * multiplier)"));
        assertEquals(9, invoke(prepended, "shifted", -3));
        Object subtracted = transformed(Map.of("reverse(I)I", "(level * multiplier - 8) - 1"));
        assertEquals(-9, invoke(subtracted, "reverse", 3));
    }

    private static Object transformed(Map<String, String> formulas) throws Exception {
        String name = ConversionFixture.class.getName();
        String path = '/' + name.replace('.', '/') + ".class";
        byte[] original;
        try (var stream = ConversionFixture.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            original = stream.readAllBytes();
        }
        byte[] changed = FormulaCompiler.transform(original, formulas);
        assertNotSame(original, changed);
        var loader = new ClassLoader(FormulaConversionProjectionTest.class.getClassLoader()) {
            Class<?> loadFixture() {
                return defineClass(name, changed, 0, changed.length);
            }
        };
        return loader.loadFixture().getConstructor().newInstance();
    }

    private static int invoke(Object fixture, String method, int level) throws Exception {
        return ((Number) fixture.getClass().getMethod(method, int.class).invoke(fixture, level)).intValue();
    }

    public static final class ConversionFixture {
        public float multiplier = 0.25f;

        public int truncated(int level) {
            return (int) (level * multiplier);
        }

        public int shifted(int level) {
            return 8 + (int) (level * multiplier);
        }

        public int subtracted(int level) {
            return 8 - (int) (level * multiplier);
        }

        public int reverse(int level) {
            return (int) (level * multiplier) - 8;
        }

        public int rounded(int level) {
            return Math.round(level * multiplier);
        }
    }
}
