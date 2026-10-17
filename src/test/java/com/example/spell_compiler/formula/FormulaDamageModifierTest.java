package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaDamageModifierTest {
    private static final String DAMAGE = "getDamage(I)F";
    private FormulaIntegration.Modifiers previous;
    private final CountingModifiers modifiers = new CountingModifiers();

    @BeforeEach
    void installCountingProvider() throws Exception {
        Field field = FormulaIntegration.class.getDeclaredField("modifiers");
        field.setAccessible(true);
        previous = (FormulaIntegration.Modifiers) field.get(null);
        FormulaIntegration.register(modifiers);
    }

    @AfterEach
    void restoreProviderAndDispatchers() {
        FormulaIntegration.register(previous);
        FormulaRuntimeDispatcher.clearServer();
        FormulaRuntimeDispatcher.clearClient();
    }

    @Test
    void pureConstantDamageAppliesTheExternalModifierOnceWithoutRestoringOmittedAttributes() throws Throwable {
        var fixture = new DamageFixture();
        var delegate = compile("damage", "100");
        for (int level : List.of(1, 5)) {
            fixture.globalPower = level * 7;
            fixture.schoolPower = level * 11;
            fixture.configPower = level * 13;
            modifiers.calls.set(0);
            assertEquals(150F, delegate.invoke(fixture, new Object[]{level}).floatValue());
            assertEquals(1, modifiers.calls.get());
            assertSame(fixture, modifiers.spell);
            assertNull(modifiers.caster);
        }
    }

    @Test
    void levelOnlyDamageKeepsThePlayersMathAndAppliesOnlyOneExternalModifier() throws Throwable {
        var fixture = new DamageFixture();
        var delegate = compile("damage", "level * 2 + 1");
        fixture.globalPower = 99;
        fixture.schoolPower = 101;
        fixture.configPower = 103;
        assertEquals(16.5F, delegate.invoke(fixture, new Object[]{5}).floatValue());
        assertEquals(1, modifiers.calls.get());
    }

    @Test
    void severalMarkerlessBranchesUseOneFinalFallbackRatherThanMultiplyingEveryBranchTwice() throws Throwable {
        var fixture = new DamageFixture();
        String expression = "100 * globalPower * schoolPower * configPower + (level * 2 + 1)";
        var delegate = compile("damage", expression);
        assertEquals((100 * 1.5F * 1.25F * 2 + 11) * 1.5F,
                delegate.invoke(fixture, new Object[]{5}).floatValue());
        assertEquals(1, modifiers.calls.get());
        assertEquals(23.75F, fixture.getDamage(5));
    }

    @Test
    void defaultAndAbsentOverridesDoNotCreateDelegatesOrTouchTheExternalProvider() throws Exception {
        var definition = definition("damage");
        assertTrue(FormulaDelegateFactory.compileSpell(DamageFixture.class, definition, Map.of()).isEmpty());
        assertTrue(FormulaDelegateFactory.compileSpell(DamageFixture.class, definition,
                Map.of(DAMAGE, definition.formulas().get(0).readableExpression())).isEmpty());
        assertEquals(23.75F, new DamageFixture().getDamage(5));
        assertEquals(0, modifiers.calls.get());
    }

    @Test
    void originalEvaluationKeepsNativeMarkerlessDamageWithoutApplyingTheConfiguredModifier() throws Throwable {
        assertNativeEvaluationPreservesMarkerlessDamage(true);
    }

    @Test
    void nativeEvaluationKeepsNativeMarkerlessDamageWithoutApplyingTheConfiguredModifier() throws Throwable {
        assertNativeEvaluationPreservesMarkerlessDamage(false);
    }

    @Test
    void damageAliasesReceiveTheFallbackWhilePercentModifiersRemainPlainNumbers() throws Throwable {
        var fixture = new DamageFixture();
        for (String alias : List.of("damage", "effect_damage", "aoe_damage")) {
            modifiers.calls.set(0);
            assertEquals(150F, compile(alias, "100").invoke(fixture, new Object[]{5}).floatValue(), alias);
            assertEquals(1, modifiers.calls.get(), alias);
        }
        for (String alias : List.of("damage_percent", "percent_attack_damage")) {
            modifiers.calls.set(0);
            assertEquals(100F, compile(alias, "100").invoke(fixture, new Object[]{5}).floatValue(), alias);
            assertEquals(0, modifiers.calls.get(), alias);
        }
    }

    @Test
    void doubleDamageKeepsItsPrecisionThroughTheSingleExternalModifier() throws Throwable {
        var fixture = new DamageFixture();
        Number value = compile("damage", "getDoubleDamage(I)D", "1000000000000.5")
                .invoke(fixture, new Object[]{5});
        assertInstanceOf(Double.class, value);
        assertEquals(Double.doubleToRawLongBits(1500000000000.75), Double.doubleToRawLongBits(value.doubleValue()));
        assertEquals(1, modifiers.calls.get());
    }

    @Test
    void integerAndLongDamageKeepTheirReturnTypesAndRejectExternalMultiplierOverflow() throws Throwable {
        var fixture = new DamageFixture();
        for (String key : List.of("getIntegerDamage(I)I", "getLongDamage(I)J")) {
            modifiers.calls.set(0);
            Number value = compile("damage", key, "100").invoke(fixture, new Object[]{5});
            assertEquals(key.endsWith("I") ? Integer.class : Long.class, value.getClass());
            assertEquals(150, value.longValue());
            assertEquals(1, modifiers.calls.get());
            String limit = key.endsWith("I") ? "2147483647" : "9223372036854775807L";
            var overflowing = compile("damage", key, limit);
            assertThrows(ArithmeticException.class, () -> overflowing.invoke(fixture, new Object[]{5}), key);
            assertEquals(2, modifiers.calls.get());
        }
    }

    @Test
    void invalidFormulaDisablesItsDelegateWithoutMultiplyingTheFailedValueAndValidReloadRecovers() throws Exception {
        var fixture = new DamageFixture();
        String key = FormulaRuntimeDispatcher.key(DamageFixture.class.getName(), DAMAGE);
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, compile("damage", "1.0 / (level - level)")));
        var guarded = FormulaRuntimeDispatcher.resolve(key, null);
        assertNotNull(guarded);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertNull(FormulaRuntimeDispatcher.invoke(guarded, fixture, new Object[]{5}));
            assertEquals(23.75F, fixture.getDamage(5));
        }
        assertEquals(0, modifiers.calls.get());
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, compile("damage", "100")));
        assertEquals(150F, FormulaRuntimeDispatcher.invoke(FormulaRuntimeDispatcher.resolve(key, null),
                fixture, new Object[]{5}).floatValue());
        assertEquals(1, modifiers.calls.get());
    }

    @Test
    void externalModifierOverflowIsRejectedWithoutRetryingOrReturningANonFiniteValue() throws Exception {
        var fixture = new DamageFixture();
        modifiers.multiplier = Float.MAX_VALUE;
        String key = FormulaRuntimeDispatcher.key(DamageFixture.class.getName(), DAMAGE);
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, compile("damage", "100")));
        var guarded = FormulaRuntimeDispatcher.resolve(key, null);
        assertNotNull(guarded);
        assertNull(FormulaRuntimeDispatcher.invoke(guarded, fixture, new Object[]{5}));
        assertNull(FormulaRuntimeDispatcher.invoke(guarded, fixture, new Object[]{5}));
        assertEquals(1, modifiers.calls.get());
        assertEquals(23.75F, fixture.getDamage(5));
        modifiers.multiplier = 1.5F;
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, compile("damage", "100")));
        assertEquals(150F, FormulaRuntimeDispatcher.invoke(FormulaRuntimeDispatcher.resolve(key, null),
                fixture, new Object[]{5}).floatValue());
        assertEquals(2, modifiers.calls.get());
    }

    private void assertNativeEvaluationPreservesMarkerlessDamage(boolean original) throws Throwable {
        Field field = FormulaNativeValues.class.getDeclaredField("definitions");
        field.setAccessible(true);
        Object previousDefinitions = field.get(null);
        try {
            FormulaNativeValues.initialize(new SpellFormulaCatalog(List.of(definition("damage"))));
            var fixture = new DamageFixture();
            assertEquals(23.75F, fixture.getDamage(5));
            Number evaluated = original
                    ? FormulaNativeValues.evaluateOriginal(fixture, DAMAGE, new Object[]{5})
                    : FormulaNativeValues.evaluateNative(fixture, DAMAGE, new Object[]{5});
            assertAll(
                    () -> assertEquals(23.75F, evaluated.floatValue()),
                    () -> assertEquals(0, modifiers.calls.get()),
                    () -> assertFalse(FormulaNativeValues.isEvaluatingOriginal()));
        } finally {
            field.set(null, previousDefinitions);
        }
        assertSame(previousDefinitions, field.get(null));
    }

    private static FormulaRuntimeDispatcher.Invoker compile(String alias, String expression) throws Exception {
        return compile(alias, DAMAGE, expression);
    }

    private static FormulaRuntimeDispatcher.Invoker compile(String alias, String key, String expression) throws Exception {
        var delegates = FormulaDelegateFactory.compileSpell(DamageFixture.class, definition(alias, key), Map.of(key, expression));
        assertEquals(java.util.Set.of(key), delegates.keySet());
        return delegates.get(key);
    }

    private static SpellFormulaCatalog.Spell definition(String alias) throws Exception {
        return definition(alias, DAMAGE);
    }

    private static SpellFormulaCatalog.Spell definition(String alias, String key) throws Exception {
        var owner = new ClassNode();
        String resource = '/' + DamageFixture.class.getName().replace('.', '/') + ".class";
        try (var input = DamageFixture.class.getResourceAsStream(resource)) {
            assertNotNull(input);
            new ClassReader(input.readAllBytes()).accept(owner, 0);
        }
        var method = owner.methods.stream().filter(value -> (value.name + value.desc).equals(key)).findFirst().orElseThrow();
        var analysis = AutomaticFormulaExtractor.analyzeRuntime(owner, method, FormulaClassRepository.of(owner));
        assertTrue(analysis.supported(), analysis.rejection());
        assertTrue(analysis.formula().nativePowers().isEmpty(), "该夹具必须命中无原生marker的Factory兜底路径");
        Map<String, String> variables = new LinkedHashMap<>();
        analysis.formula().variables().forEach((name, variable) -> variables.put(name, variable.type().getDescriptor()));
        var binding = new SpellFormulaCatalog.Method(alias, method.name, method.desc, analysis.formula().expression(),
                analysis.formula().readableExpression(), variables, DamageFixture.class.getName());
        return new SpellFormulaCatalog.Spell("fixture:damage_modifier", DamageFixture.class.getName(), "irons_spellbooks:fire",
                new JsonObject(), new JsonObject(), List.of(binding));
    }

    private static final class CountingModifiers implements FormulaIntegration.Modifiers {
        private final AtomicInteger calls = new AtomicInteger();
        private float multiplier = 1.5F;
        private Object spell;
        private Object caster;

        @Override
        public float damageMultiplier(Object spell, Object caster) {
            calls.incrementAndGet();
            this.spell = spell;
            this.caster = caster;
            return multiplier;
        }
    }

    public static class DamageFixture {
        public float globalPower = 1.5F;
        public double schoolPower = 1.25;
        public float configPower = 2;

        public float getDamage(int level) {
            return (float) (5 + level * globalPower * schoolPower * configPower);
        }

        public double getDoubleDamage(int level) {
            return 0.25 + level;
        }

        public int getIntegerDamage(int level) {
            return 5 + level;
        }

        public long getLongDamage(int level) {
            return 5L + level;
        }
    }
}
