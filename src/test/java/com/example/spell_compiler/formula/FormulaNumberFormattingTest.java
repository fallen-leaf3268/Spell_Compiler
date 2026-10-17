package com.example.spell_compiler.formula;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaNumberFormattingTest {
    @Test
    void integersAboveTheIntLimitKeepTheirFullDecimalValue() {
        assertEquals("2147483648", FormulaNumberFormatting.wideInteger(2_147_483_648d));
        assertEquals("5000000000", FormulaNumberFormatting.wideInteger(5_000_000_000d));
        assertEquals("-2147483649", FormulaNumberFormatting.wideInteger(-2_147_483_649d));
        assertEquals("-5000000000", FormulaNumberFormatting.wideInteger(-5_000_000_000d));
    }

    @Test
    void originalIntegerRangeAndSignedZeroKeepTheNativePath() {
        for (double value : new double[]{Integer.MIN_VALUE, Integer.MAX_VALUE, -500, -1, -0d, 0, 1, 500})
            assertNull(FormulaNumberFormatting.wideInteger(value), Double.toString(value));
    }

    @Test
    void fractionalValuesKeepTheNativePrecisionAndRoundingPath() {
        for (double value : new double[]{24.95, -24.95, 2_147_483_648.5, -2_147_483_648.5})
            assertNull(FormulaNumberFormatting.wideInteger(value), Double.toString(value));
    }

    @Test
    void nonFiniteValuesKeepTheirExistingNativeBehavior() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertNull(FormulaNumberFormatting.wideInteger(value), Double.toString(value));
    }

    @Test
    void largeFloatingPointIntegersUseDecimalTextWithoutScientificNotation() {
        String largest = FormulaNumberFormatting.wideInteger(Double.MAX_VALUE);
        assertNotNull(largest);
        assertEquals(309, largest.length());
        assertTrue(largest.matches("[0-9]+"));
        assertEquals("100000000000000000000", FormulaNumberFormatting.wideInteger(1e20));
        assertEquals("7500000256", FormulaNumberFormatting.wideInteger((double) 7_500_000_000f));
    }
}
