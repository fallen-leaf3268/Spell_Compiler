package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaEvaluationScopeTest {
    private static final String DAMAGE = "getDamage(ILnet/minecraft/world/entity/LivingEntity;)F";
    private static final String DURATION = "getDuration(ILnet/minecraft/world/entity/LivingEntity;)I";
    private static final String POWER = "getSpellPower(ILnet/minecraft/world/entity/Entity;)F";
    private static final String EFFECT_DAMAGE = "thunderstormDamage(IFDFLjava/lang/Object;Ljava/lang/Object;)F";

    @BeforeEach
    void installDefinitions() {
        FormulaNativeValues.initialize(new SpellFormulaCatalog(List.of(new SpellFormulaCatalog.Spell(
                "irons_spellbooks:scope_fixture", Fixture.class.getName(), "irons_spellbooks:lightning",
                new JsonObject(), new JsonObject(), List.of(
                        method("damage", DAMAGE, Map.of("level", "I"), Fixture.class.getName()),
                        method("duration", DURATION, Map.of("level", "I"), Fixture.class.getName()),
                        method("spell_power", POWER, Map.of("level", "I"), Fixture.class.getName()),
                        method("effect_damage", EFFECT_DAMAGE, Map.of("effect_level", "I"),
                                FormulaSiteValues.class.getName()))))));
    }

    @AfterEach
    void clear() {
        FormulaNativeValues.initialize(new SpellFormulaCatalog(List.of()));
        FormulaRuntimeDispatcher.clearServer();
        FormulaRuntimeDispatcher.clearClient();
    }

    @Test
    void configuredDamageDelegateSeesTheActualFallbackLevelWithoutEnteringTheNativeOverrideStage() {
        Fixture spell = new Fixture();
        install(Map.of(key(DAMAGE), (self, arguments) -> {
            Integer level = configuredLevel(self);
            return level == null ? 1 : level;
        }));
        assertNull(configuredLevel(spell));
        assertEquals(5, invoke(DAMAGE, spell, 5, null));
        assertNull(configuredLevel(spell));
    }

    @Test
    void nestedConfiguredCallsRestoreTheOuterReceiverAndLevel() {
        Fixture outer = new Fixture();
        Fixture inner = new Fixture();
        List<Integer> levels = new ArrayList<>();
        install(Map.of(
                key(DAMAGE), (self, arguments) -> {
                    levels.add(configuredLevel(self));
                    assertNull(configuredLevel(inner));
                    assertEquals(2, invoke(DURATION, inner, 2, null));
                    levels.add(configuredLevel(self));
                    return 17;
                },
                key(DURATION), (self, arguments) -> {
                    levels.add(configuredLevel(self));
                    assertNull(configuredLevel(outer));
                    return 2;
                }));
        assertEquals(17, invoke(DAMAGE, outer, 5, null));
        assertEquals(List.of(5, 2, 5), levels);
        assertNull(configuredLevel(outer));
        assertNull(configuredLevel(inner));
    }

    @Test
    void recoverableNestedFailureRestoresTheOuterLevelAndKeepsItsInvokerHealthy() {
        Fixture spell = new Fixture();
        List<Integer> levels = new ArrayList<>();
        install(Map.of(
                key(DAMAGE), (self, arguments) -> {
                    levels.add(configuredLevel(self));
                    assertNull(invoke(DURATION, self, 2, null));
                    levels.add(configuredLevel(self));
                    return 19;
                },
                key(DURATION), (self, arguments) -> {
                    levels.add(configuredLevel(self));
                    throw new ArithmeticException("nested formula failure");
                }));
        assertEquals(19, invoke(DAMAGE, spell, 5, null));
        assertEquals(List.of(5, 2, 5), levels);
        levels.clear();
        assertEquals(19, invoke(DAMAGE, spell, 7, null));
        assertEquals(List.of(7, 7), levels);
        assertNull(configuredLevel(spell));
    }

    @Test
    void checkedAndFatalFailuresRestoreTheContextWithoutHidingTheFailure() {
        Fixture spell = new Fixture();
        for (Throwable failure : new Throwable[]{new IOException("checked"), new AssertionError("fatal")}) {
            AtomicReference<Integer> observed = new AtomicReference<>();
            install(Map.of(key(DAMAGE), (self, arguments) -> {
                observed.set(configuredLevel(self));
                throw failure;
            }));
            if (failure instanceof IOException) {
                var wrapped = assertThrows(IllegalStateException.class, () -> invoke(DAMAGE, spell, 5, null));
                assertSame(failure, wrapped.getCause());
            } else {
                assertSame(failure, assertThrows(AssertionError.class, () -> invoke(DAMAGE, spell, 5, null)));
            }
            assertEquals(5, observed.get());
            assertNull(configuredLevel(spell));
        }
    }

    @Test
    void configuredPowerKeepsItsFallbackLevelAndStaticEffectAmplifierDoesNotBecomeASpellLevel() {
        Fixture spell = new Fixture();
        List<Integer> levels = new ArrayList<>();
        install(Map.of(
                key(DAMAGE), (self, arguments) -> {
                    levels.add(configuredLevel(self));
                    assertEquals(23, invoke(POWER, self, 5, null));
                    levels.add(configuredLevel(self));
                    assertEquals(31, invoke(EFFECT_DAMAGE, null, 11, 1f, 1d, 1f, self, null));
                    levels.add(configuredLevel(self));
                    return 37;
                },
                key(POWER), (self, arguments) -> {
                    levels.add(configuredLevel(self));
                    return 23;
                },
                key(EFFECT_DAMAGE), (self, arguments) -> {
                    assertNull(self);
                    levels.add(configuredLevel(self));
                    assertNull(configuredLevel(spell));
                    return 31;
                }));
        assertEquals(37, invoke(DAMAGE, spell, 5, null));
        assertEquals(Arrays.asList(5, 5, 5, null, 5), levels);
        assertNull(configuredLevel(spell));
    }

    private static Integer configuredLevel(Object spell) {
        assertNull(FormulaNativeValues.nativeLevel(spell));
        return FormulaNativeValues.currentLevel(spell);
    }

    private static SpellFormulaCatalog.Method method(String alias, String key, Map<String, String> variables,
                                                       String declaring) {
        int descriptor = key.indexOf('(');
        return new SpellFormulaCatalog.Method(alias, key.substring(0, descriptor), key.substring(descriptor),
                "int(1)", "1", variables, declaring);
    }

    private static String key(String method) {
        return FormulaRuntimeDispatcher.key(Fixture.class.getName(), method);
    }

    private static void install(Map<String, FormulaRuntimeDispatcher.Invoker> invokers) {
        FormulaRuntimeDispatcher.replaceClient(invokers);
    }

    private static Number invoke(String method, Object self, Object... arguments) {
        return FormulaRuntimeDispatcher.invoke(FormulaRuntimeDispatcher.resolve(key(method), null), self, arguments);
    }

    private static final class Fixture {}
}
