package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.ReadableFormula;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadableFormulaTest {
    private static final String FIREBALL = "(float(5.0) + (float(5.0) * float((((double((baseSpellPower + (spellPowerPerLevel * (level - int(1))))) * double(float(spell_power))) * school_spell_power) * double(power_multiplier)))))";
    private static final String FIREBALL_READABLE = "5 + 5 * (baseSpellPower + spellPowerPerLevel * (level - 1)) * spell_power * school_spell_power * power_multiplier";
    private static final Map<String, Type> TYPES = Map.of(
            "baseSpellPower", Type.INT_TYPE, "spellPowerPerLevel", Type.INT_TYPE,
            "level", Type.INT_TYPE, "spell_power", Type.DOUBLE_TYPE,
            "school_spell_power", Type.DOUBLE_TYPE, "power_multiplier", Type.FLOAT_TYPE,
            "a", Type.FLOAT_TYPE, "b", Type.FLOAT_TYPE, "c", Type.FLOAT_TYPE,
            "d", Type.FLOAT_TYPE);

    @Test
    void fireballMatchesTheApprovedPlayerFormula() {
        assertEquals(FIREBALL_READABLE, ReadableFormula.display(FIREBALL));
    }

    @Test
    void explicitIntegerTruncationAndRequiredGroupingStayVisible() {
        assertEquals("(2 + int(spell_power * school_spell_power)) / 2",
                ReadableFormula.display("(int(2) + int(float((double(float(spell_power)) * school_spell_power)))) / int(2)"));
        assertEquals("a - (b - c)", ReadableFormula.display("(a - (b - c))"));
        assertEquals("a / (b / c)", ReadableFormula.display("(a / (b / c))"));
        assertEquals("a % (b * c)", ReadableFormula.display("(a % (b * c))"));
        assertEquals("-(a + b)", ReadableFormula.display("(-(a + b))"));
        assertEquals("a * (b / c)", ReadableFormula.display("(a * (b / c))"));
    }

    @Test
    void numericalFunctionsRemainEditableAndFloatLiteralsLoseDecoration() {
        assertEquals("min(5 + sqrt(abs(spell_power)), 16)",
                ReadableFormula.display("min((float(5.0) + sqrt(abs(double(spell_power)))), int(16))"));
        assertEquals("long(spell_power)", ReadableFormula.display("long(double(spell_power))"));
        assertEquals("-0.0", ReadableFormula.display("double(-0.0)"));
        assertEquals("3000000000L", ReadableFormula.display("long(3000000000L)"));
        assertEquals("3000000000.0", ReadableFormula.display("double(3000000000.0)"));
        assertEquals("min(int(3000000000.0), 1)", ReadableFormula.display("min(int(3000000000.0), int(1))"));
    }

    @Test
    void readableAndStrictDefaultsRestoreTheExactSourceText() {
        assertMapped(FIREBALL, FIREBALL_READABLE, FIREBALL);
        assertMapped(FIREBALL, " \n (" + FIREBALL_READABLE + ") \t ", FIREBALL);
        assertMapped(FIREBALL, FIREBALL, FIREBALL);
    }

    @Test
    void editingAFloatingLiteralRetainsAllTheOriginalPrecisionBoundaries() {
        assertMapped(FIREBALL, FIREBALL_READABLE.replaceFirst("5", "7.5"),
                FIREBALL.replaceFirst("float\\(5.0\\)", "float(7.5)"));
        assertMapped("(float(5) + float(a))", "5.5 + a", "(float(5.5) + float(a))");
        assertMapped("(5f + a)", "7.5 + a", "(float(7.5) + a)");
        assertMapped("(5.0 / 2)", "7 / 2", "(double(7) / 2)");
    }

    @Test
    void flattenedDisplayEditsRestoreTheOriginalAssociation() {
        String strict = "float((float(a) + float((float(b) + float(c)))))";
        assertEquals("a + b + c", ReadableFormula.display(strict));
        assertMapped(strict, "a + b + d", strict.replace("float(c)", "float(d)"));
    }

    @Test
    void wholeSourceSubexpressionsAreRestoredInsideNewOuterMath() {
        assertMapped(FIREBALL, "min(" + FIREBALL_READABLE + ", 16)", "min(" + FIREBALL + ", 16)");
        assertMapped(FIREBALL, "(" + FIREBALL_READABLE + ") + 7", '(' + FIREBALL + " + 7)");
    }

    @Test
    void integerToDecimalEditsUseNaturalDslInsteadOfHiddenTruncation() {
        assertNatural("(int(1) + level)", "1.5 + level");
        assertNatural("(long(1L) + long(level))", "1.5 + long(level)");
    }

    @Test
    void equalValuedNumericTypeEditsChangeIntegerArithmetic() {
        String strict = "((int(4) + level) * (int(5) + (level / int(2))))";
        assertMapped(strict, "(4 + level) * (5 + level / 2)", strict);
        for (String divisor : new String[]{"2.0", "2f", "2d", "2L", "double(2)", "float(2)"}) {
            assertNatural(strict, "(4 + level) * (5 + level / " + divisor + ')');
        }
        assertNatural("(long(1L) + long(level))", "1.0 + long(level)");
    }

    @Test
    void floatingSourceDecorationsRetainPrecisionUnlessAnExplicitTypeChanges() {
        assertMapped(FIREBALL, FIREBALL_READABLE.replaceFirst("5", "5.000"), FIREBALL);
        assertMapped("float(5)", "5.0", "float(5)");
        assertMapped("float(5)", "5f", "float(5)");
        assertMapped("float(5L)", ReadableFormula.display("float(5L)"), "float(5L)");
        assertNatural("float(5)", "5d");
        assertNatural("double(5)", "5f");
        assertNatural("-0.0f", "-0.0d");
        assertNatural("float(spell_power)", "double(spell_power)");
        assertNatural("double(spell_power)", "float(spell_power)");
    }

    @Test
    void recursiveAndFlatExpressionComplexityIsRejectedWithoutStackOverflow() {
        for (String expression : new String[]{"(".repeat(3000) + "level" + ")".repeat(3000),
                "-".repeat(3000) + "level", "abs(".repeat(1000) + "level" + ")".repeat(1000),
                "level + ".repeat(1200) + "level"}) {
            var displayed = assertThrows(IllegalArgumentException.class, () -> ReadableFormula.display(expression));
            assertTrue(displayed.getMessage().contains("limit"), displayed.getMessage());
            var resolved = assertThrows(IllegalArgumentException.class,
                    () -> ReadableFormula.resolve(FIREBALL, expression, TYPES));
            assertTrue(resolved.getMessage().contains("limit"), resolved.getMessage());
        }
        assertDoesNotThrow(() -> ReadableFormula.display("(".repeat(24) + "level" + ")".repeat(24)));
        assertDoesNotThrow(() -> ReadableFormula.resolve(FIREBALL, "level + ".repeat(40) + "level", TYPES));
    }

    @Test
    void variableEditsWithDifferentTypesUseNaturalDslAndFloatingConstantsPreserveSourceTypes() {
        assertNatural("(float(a) + float(b))", "a + level");
        assertMapped("(float(a) + float(b))", "a + 2", "(float(a) + float(float(2)))");
        assertMapped(FIREBALL, FIREBALL_READABLE.replace("school_spell_power", "1"),
                FIREBALL.replace("school_spell_power", "double(1)"));
        assertMapped("(float(a) + float(b))", "a + -0.0", "(float(a) + float(float((-0.0))))");
        assertNatural("(int(1) + level)", "1 + 1.5");
        assertNatural("(float(a) + float(b))", "a + 1e100");
        assertNatural("(float(a) + double(spell_power))", "a + 1e400");
    }

    @Test
    void ambiguousHiddenPrecisionSourcesNeverSelectTheFirstCandidate() {
        assertNatural("(float(spell_power) + double(spell_power))", "min(spell_power, 1)");
    }

    @Test
    void structuralRewritesUseNaturalDslAndInvalidSyntaxIsRejected() {
        assertNatural(FIREBALL, "max(spell_power, school_spell_power) * 10");
        for (String invalid : new String[]{"", "a +", "a b", "le vel", "min(a,)", "float()"}) {
            assertThrows(IllegalArgumentException.class, () -> ReadableFormula.display(invalid), invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> ReadableFormula.resolve(FIREBALL, invalid, TYPES), invalid);
        }
    }

    @Test
    void changingNegativeZeroToPositiveZeroDoesNotRestoreTheOldDefault() {
        var resolution = ReadableFormula.resolve("double(-0.0)", "0", TYPES);
        assertEquals(ReadableFormula.Strategy.SOURCE_MAPPED, resolution.strategy());
        assertEquals("double(0)", resolution.expression());
        assertNotEquals("double(-0.0)", resolution.expression());
    }

    private static void assertMapped(String strict, String edited, String expected) {
        var resolved = ReadableFormula.resolve(strict, edited, TYPES);
        assertEquals(ReadableFormula.Strategy.SOURCE_MAPPED, resolved.strategy());
        assertEquals(compact(expected), compact(resolved.expression()));
    }

    private static void assertNatural(String strict, String edited) {
        var resolved = ReadableFormula.resolve(strict, edited, TYPES);
        assertEquals(ReadableFormula.Strategy.NATURAL_DSL, resolved.strategy());
        assertEquals(edited, resolved.expression());
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", "");
    }
}
