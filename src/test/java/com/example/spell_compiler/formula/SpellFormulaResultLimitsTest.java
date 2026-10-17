package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class SpellFormulaResultLimitsTest {
    @AfterEach
    void reset() {
        FormulaRuntimeDispatcher.clearServer();
        FormulaRuntimeDispatcher.clearClient();
    }

    @Test
    void integerReturnTypesUseNativeTruncationWithoutPlayerCasts() throws Throwable {
        var definition = nativeDefinition();
        var invoker = FormulaDelegateFactory.compileSpell(NumericSpell.class, definition,
                Map.of("getManaCost(I)I", "7.9")).get("getManaCost(I)I");
        assertEquals(7, invoker.invoke(new NumericSpell(), new Object[]{1}));
    }

    @Test
    void negativeManaResultsAreRejectedInsideTheExistingRuntimeFallback() {
        var definition = nativeDefinition();
        String key = FormulaRuntimeDispatcher.key(definition.className(), "getManaCost(I)I");
        var invoker = FormulaDelegateFactory.compileSpell(NumericSpell.class, definition,
                Map.of("getManaCost(I)I", "-1")).get("getManaCost(I)I");
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, invoker));
        var guarded = FormulaRuntimeDispatcher.resolve(key, null);
        assertNull(FormulaRuntimeDispatcher.invoke(guarded, new NumericSpell(), new Object[]{1}));
        assertNull(FormulaRuntimeDispatcher.invoke(guarded, new NumericSpell(), new Object[]{2}));
        assertEquals(21, new NumericSpell().getManaCost(1));
    }

    @Test
    void negativeDurationResultsFallBackAcrossNativeTimeBindings() {
        for (String alias : List.of("duration_ticks", "chain_duration_ticks", "recast_window_ticks",
                "portal_duration_ticks", "swirl_duration_ticks", "effect_duration_ticks", "freeze_ticks", "rend_duration_ticks")) {
            var definition = durationDefinition(alias, "getDuration", "(I)I");
            String key = FormulaRuntimeDispatcher.key(definition.className(), "getDuration(I)I");
            var invoker = FormulaDelegateFactory.compileSpell(NumericSpell.class, definition,
                    Map.of("getDuration(I)I", "-1")).get("getDuration(I)I");
            FormulaRuntimeDispatcher.replaceClient(Map.of(key, invoker));
            var guarded = FormulaRuntimeDispatcher.resolve(key, null);
            assertNull(FormulaRuntimeDispatcher.invoke(guarded, new NumericSpell(), new Object[]{1}), alias);
            assertNull(FormulaRuntimeDispatcher.invoke(guarded, new NumericSpell(), new Object[]{2}), alias);
        }
        assertEquals(21, new NumericSpell().getDuration(1));
    }

    @Test
    void floatDurationRejectsNegativeButAllowsZeroAndFinitePositiveResults() throws Throwable {
        var definition = durationDefinition("duration_ticks", "getFloatDuration", "(I)F");
        String methodKey = "getFloatDuration(I)F";
        var negative = FormulaDelegateFactory.compileSpell(NumericSpell.class, definition,
                Map.of(methodKey, "-1")).get(methodKey);
        assertThrows(ArithmeticException.class, () -> negative.invoke(new NumericSpell(), new Object[]{1}));
        for (String expression : List.of("0", "2.5")) {
            var invoker = FormulaDelegateFactory.compileSpell(NumericSpell.class, definition,
                    Map.of(methodKey, expression)).get(methodKey);
            assertEquals(Float.parseFloat(expression), invoker.invoke(new NumericSpell(), new Object[]{1}));
        }
    }

    @Test
    void unchangedDurationDoesNotInstallAnyNewRuntimeConstraint() {
        var definition = durationDefinition("duration_ticks", "getDuration", "(I)I");
        assertTrue(FormulaDelegateFactory.compileSpell(NumericSpell.class, definition,
                Map.of("getDuration(I)I", "20 + level")).isEmpty());
    }

    @Test
    void zeroIntervalsAndNonFiniteIntegerConversionsFallBackBeforeUse() {
        var method = new SpellFormulaCatalog.Method("healing_interval_ticks", "healingInterval", "()I",
                "int(10)", "10", Map.of(), FormulaSiteValues.class.getName());
        var definition = new SpellFormulaCatalog.Spell("irons_spellbooks:healing_circle", NumericSpell.class.getName(),
                "irons_spellbooks:holy", new JsonObject(), new JsonObject(), List.of(method));
        String key = FormulaRuntimeDispatcher.key(definition.className(), method.key());
        for (String expression : List.of("0", "1 / 0.0")) {
            var invoker = FormulaDelegateFactory.compileSpell(NumericSpell.class, definition,
                    Map.of(method.key(), expression)).get(method.key());
            FormulaRuntimeDispatcher.replaceClient(Map.of(key, invoker));
            assertEquals(10, FormulaSiteHooks.overrideInt(10, key, null));
            assertEquals(10, FormulaSiteHooks.overrideInt(10, key, null));
        }
        var recovered = FormulaDelegateFactory.compileSpell(NumericSpell.class, definition,
                Map.of(method.key(), "2.9")).get(method.key());
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, recovered));
        assertEquals(2, FormulaSiteHooks.overrideInt(10, key, null));
    }

    private static SpellFormulaCatalog.Spell nativeDefinition() {
        var method = new SpellFormulaCatalog.Method("mana_cost", "getManaCost", "(I)I",
                "int(20) + level", "20 + level", Map.of("level", "I"), NumericSpell.class.getName());
        return new SpellFormulaCatalog.Spell("irons_spellbooks:test", NumericSpell.class.getName(),
                "irons_spellbooks:holy", new JsonObject(), new JsonObject(), List.of(method));
    }

    private static SpellFormulaCatalog.Spell durationDefinition(String alias, String name, String descriptor) {
        var method = new SpellFormulaCatalog.Method(alias, name, descriptor,
                "int(20) + level", "20 + level", Map.of("level", "I"), NumericSpell.class.getName());
        return new SpellFormulaCatalog.Spell("irons_spellbooks:test", NumericSpell.class.getName(),
                "irons_spellbooks:holy", new JsonObject(), new JsonObject(), List.of(method));
    }

    public static final class NumericSpell {
        public int getManaCost(int level) { return 20 + level; }
        public int getDuration(int level) { return 20 + level; }
        public float getFloatDuration(int level) { return 20 + level; }
    }
}
