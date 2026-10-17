package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaWidePowerTest {
    private final SpellFormulaFiles.Snapshot originalServer = SpellFormulaState.serverSnapshot();
    private final SpellFormulaFiles.Snapshot originalClient = SpellFormulaState.clientSnapshot();

    @AfterEach
    void restoreSnapshots() {
        SpellFormulaState.publishServer(originalServer);
        SpellFormulaState.publishClient(originalClient);
    }

    @Test
    void spellPowerGrowthUsesWideIntermediateArithmetic() throws Throwable {
        var delegates = FormulaDelegateFactory.compile(Fixture.class,
                Map.of("power(I)F", "baseSpellPower + spellPowerPerLevel * (level - 1) + 0"), true, true);
        assertEquals(5_000_000_000f, delegates.get("power(I)F").invoke(new Fixture(), new Object[]{10}).floatValue());
    }

    @Test
    void longParametersReadTheCurrentSnapshotWithoutChangingNativeIntFields() throws Throwable {
        Fixture fixture = new Fixture();
        publish(Fixture.class, 5_000_000_000L, 5_000_000_000L);
        var delegate = compile("baseSpellPower + spellPowerPerLevel * (level - 1)");
        assertEquals(50_000_000_000f, delegate.invoke(fixture, new Object[]{10}).floatValue());
        assertEquals(500_000_000, fixture.baseSpellPower);
        assertEquals(500_000_000, fixture.spellPowerPerLevel);
        publish(Fixture.class, 7_000_000_000L, 0);
        assertEquals(7_000_000_000f, delegate.invoke(fixture, new Object[]{10}).floatValue());
    }

    @Test
    void inheritedPowerDelegatesUseTheConcreteReceiversLongParameters() throws Throwable {
        publish(DerivedFixture.class, 3_000_000_000L, 4_000_000_000L);
        var delegates = FormulaDelegateFactory.compile(DerivedFixture.class,
                Map.of("power(I)F", "baseSpellPower + spellPowerPerLevel * (level - 1)"), true, true);
        assertEquals(15_000_000_000f, delegates.get("power(I)F").invoke(new DerivedFixture(), new Object[]{4}).floatValue());
    }

    @Test
    void levelSubtractionIsWideWithinPowerFormulas() throws Throwable {
        publish(Fixture.class, 0, 1);
        assertEquals((float) ((long) Integer.MIN_VALUE - 1),
                compile("baseSpellPower + spellPowerPerLevel * (level - 1)")
                        .invoke(new Fixture(), new Object[]{Integer.MIN_VALUE}).floatValue());
    }

    @Test
    void longOverflowAndExplicitIntNarrowingRemainRejected() throws Throwable {
        publish(Fixture.class, Long.MAX_VALUE, 1);
        var overflow = compile("baseSpellPower + spellPowerPerLevel * (level - 1)");
        assertThrows(ArithmeticException.class, () -> overflow.invoke(new Fixture(), new Object[]{2}));
        publish(Fixture.class, 500_000_000, 500_000_000);
        var narrowed = compile("int(baseSpellPower + spellPowerPerLevel * (level - 1))");
        assertThrows(ArithmeticException.class, () -> narrowed.invoke(new Fixture(), new Object[]{10}));
    }

    @Test
    void wideAndNativeCompilationKeepIndependentCachesAndSemantics() throws Throwable {
        Map<String, String> formula = Map.of("power(I)F", "baseSpellPower + spellPowerPerLevel * (level - 1) + 0");
        var nativeDelegates = FormulaDelegateFactory.compile(Fixture.class, formula, true);
        var wideDelegates = FormulaDelegateFactory.compile(Fixture.class, formula, true, true);
        assertThrows(ArithmeticException.class, () -> nativeDelegates.get("power(I)F").invoke(new Fixture(), new Object[]{10}));
        assertEquals(5_000_000_000f, wideDelegates.get("power(I)F").invoke(new Fixture(), new Object[]{10}).floatValue());
        assertSame(wideDelegates, FormulaDelegateFactory.compile(Fixture.class, formula, true, true));
    }

    @Test
    void wideFormulasAcceptBareLongIntegerLiteralsAndPreserveTheirRangeChecks() throws Throwable {
        assertEquals(Type.LONG_TYPE, FormulaCompiler.validateExpression("5000000000", Map.of(), true));
        assertEquals(Type.LONG_TYPE, FormulaCompiler.validateExpression("-9223372036854775808", Map.of(), true));
        assertEquals(10_000_000_000f, compile("5000000000 + 5000000000").invoke(new Fixture(), new Object[]{1}).floatValue());
        assertEquals((float) Long.MIN_VALUE, compile("-9223372036854775808").invoke(new Fixture(), new Object[]{1}).floatValue());
        assertThrows(IllegalArgumentException.class, () -> FormulaCompiler.validateExpression("9223372036854775808", Map.of(), true));
        assertThrows(IllegalArgumentException.class, () -> FormulaCompiler.validateExpression("5000000000", Map.of()));
        var overflow = compile("9223372036854775807 + 1");
        assertThrows(ArithmeticException.class, () -> overflow.invoke(new Fixture(), new Object[]{1}));
    }

    private static FormulaRuntimeDispatcher.Invoker compile(String formula) {
        return FormulaDelegateFactory.compile(Fixture.class, Map.of("power(I)F", formula), true, true).get("power(I)F");
    }

    private static void publish(Class<?> owner, long base, long step) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("baseSpellPower", 500_000_000);
        parameters.addProperty("spellPowerPerLevel", 500_000_000);
        var spell = new SpellFormulaCatalog.Spell("test:wide_power", owner.getName(), "test:school",
                new JsonObject(), parameters, List.of());
        var override = new SpellFormulaFiles.SpellOverride(spell, Map.of(),
                Map.of("baseSpellPower", new JsonPrimitive(base), "spellPowerPerLevel", new JsonPrimitive(step)), Map.of());
        var snapshot = SpellFormulaFiles.snapshot(Map.of(owner.getName(), override), List.of());
        SpellFormulaState.publishServer(snapshot);
        SpellFormulaState.publishClient(snapshot);
    }

    public static class Fixture {
        private int baseSpellPower = 500_000_000;
        private int spellPowerPerLevel = 500_000_000;

        public float power(int level) {
            return baseSpellPower + spellPowerPerLevel * (level - 1);
        }
    }

    public static final class DerivedFixture extends Fixture {}
}
