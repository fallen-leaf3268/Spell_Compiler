package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaRuntimeHooksTest {
    private static final String ABSTRACT_SPELL = "io.redspace.ironsspellbooks.api.spells.AbstractSpell";
    private static final String POWER = "getSpellPower(ILnet/minecraft/world/entity/Entity;)F";
    private static final String MANA = "getManaCost(I)I";
    private static final String COOLDOWN = "getSpellCooldown()I";

    @AfterEach
    void restoreBindingsAndDispatchers() {
        FormulaRuntimeHooks.initialize(new SpellFormulaCatalog(List.of()));
        FormulaRuntimeDispatcher.clearServer();
        FormulaRuntimeDispatcher.clearClient();
    }

    @Test
    void defaultAndUnboundPathsKeepTheOriginalValue() {
        Fixture receiver = new Fixture();
        FormulaRuntimeHooks.initialize(catalog(ABSTRACT_SPELL));
        assertEquals(Float.floatToRawIntBits(-0f), Float.floatToRawIntBits(
                FormulaRuntimeHooks.overrideSpellPower(-0f, receiver, 3, null)));
        assertEquals(12f, FormulaRuntimeHooks.overrideSpellPower(12f, new Object(), 3, null));
        assertEquals(63, FormulaRuntimeHooks.overrideManaCost(63, receiver, 3));
        assertEquals(63, FormulaRuntimeHooks.overrideManaCost(63, new Object(), 3));
        assertEquals(500, FormulaRuntimeHooks.overrideCooldown(500, receiver));
        assertEquals(500, FormulaRuntimeHooks.overrideCooldown(500, new Object()));
    }

    @Test
    void inheritedNumericBindingsPassOnlyTheirOwnArgumentsAndKeepTheMethodsIsolated() {
        Fixture receiver = new Fixture();
        FormulaRuntimeHooks.initialize(catalog(ABSTRACT_SPELL));
        AtomicInteger manaCalls = new AtomicInteger();
        AtomicInteger cooldownCalls = new AtomicInteger();
        FormulaRuntimeDispatcher.replaceClient(Map.of(
                key(MANA), (self, arguments) -> {
                    assertSame(receiver, self);
                    assertArrayEquals(new Object[]{3}, arguments);
                    manaCalls.incrementAndGet();
                    return 67;
                },
                key(COOLDOWN), (self, arguments) -> {
                    assertSame(receiver, self);
                    assertEquals(0, arguments.length);
                    cooldownCalls.incrementAndGet();
                    return 600;
                }));
        assertEquals(67, FormulaRuntimeHooks.overrideManaCost(63, receiver, 3));
        assertEquals(600, FormulaRuntimeHooks.overrideCooldown(500, receiver));
        assertEquals(12f, FormulaRuntimeHooks.overrideSpellPower(12f, receiver, 3, null));
        assertEquals(1, manaCalls.get());
        assertEquals(1, cooldownCalls.get());
        FormulaRuntimeDispatcher.clearClient();
        assertEquals(63, FormulaRuntimeHooks.overrideManaCost(63, receiver, 3));
        assertEquals(500, FormulaRuntimeHooks.overrideCooldown(500, receiver));
    }

    @Test
    void inheritedBindingPassesTheConcreteReceiverAndOriginalArguments() {
        Fixture receiver = new Fixture();
        FormulaRuntimeHooks.initialize(catalog(ABSTRACT_SPELL));
        FormulaRuntimeDispatcher.replaceClient(Map.of(key(), (self, arguments) -> {
            assertSame(receiver, self);
            assertEquals(2, arguments.length);
            assertEquals(3, arguments[0]);
            assertNull(arguments[1]);
            return 17f;
        }));
        assertEquals(17f, FormulaRuntimeHooks.overrideSpellPower(12f, receiver, 3, null));
        FormulaRuntimeDispatcher.clearClient();
        assertEquals(12f, FormulaRuntimeHooks.overrideSpellPower(12f, receiver, 3, null));
    }

    @Test
    void declaredSameNameBindingDoesNotInterceptItsSuperReturn() {
        FormulaRuntimeHooks.initialize(catalog(Fixture.class.getName()));
        AtomicInteger calls = new AtomicInteger();
        FormulaRuntimeDispatcher.Invoker invoker = (self, arguments) -> {
            calls.incrementAndGet();
            return 99;
        };
        FormulaRuntimeDispatcher.replaceClient(Map.of(key(), invoker, key(MANA), invoker, key(COOLDOWN), invoker));
        assertEquals(12f, FormulaRuntimeHooks.overrideSpellPower(12f, new Fixture(), 3, null));
        assertEquals(63, FormulaRuntimeHooks.overrideManaCost(63, new Fixture(), 3));
        assertEquals(500, FormulaRuntimeHooks.overrideCooldown(500, new Fixture()));
        assertEquals(0, calls.get());
    }

    @Test
    void failuresKeepTheNativeReturnAndReloadCanRecover() {
        FormulaRuntimeHooks.initialize(catalog(ABSTRACT_SPELL));
        Fixture receiver = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        FormulaRuntimeDispatcher.replaceClient(Map.of(key(), (self, arguments) -> {
            calls.incrementAndGet();
            throw new ArithmeticException("inherited fixture failure");
        }));
        assertEquals(12f, FormulaRuntimeHooks.overrideSpellPower(12f, receiver, 3, null));
        assertEquals(14f, FormulaRuntimeHooks.overrideSpellPower(14f, receiver, 4, null));
        assertEquals(1, calls.get());
        FormulaRuntimeDispatcher.replaceClient(Map.of(key(), (self, arguments) -> 19f));
        assertEquals(19f, FormulaRuntimeHooks.overrideSpellPower(12f, receiver, 3, null));
    }

    @Test
    void invalidManaFormulaDoesNotDisableCooldownAndReloadRestoresBothBindings() {
        FormulaRuntimeHooks.initialize(catalog(ABSTRACT_SPELL));
        Fixture receiver = new Fixture();
        AtomicInteger failures = new AtomicInteger();
        FormulaRuntimeDispatcher.replaceClient(Map.of(
                key(MANA), (self, arguments) -> {
                    failures.incrementAndGet();
                    throw new ArithmeticException("mana fixture failure");
                },
                key(COOLDOWN), (self, arguments) -> 600));
        assertEquals(63, FormulaRuntimeHooks.overrideManaCost(63, receiver, 3));
        assertEquals(64, FormulaRuntimeHooks.overrideManaCost(64, receiver, 4));
        assertEquals(600, FormulaRuntimeHooks.overrideCooldown(500, receiver));
        assertEquals(1, failures.get());
        FormulaRuntimeDispatcher.replaceClient(Map.of(key(MANA), (self, arguments) -> 67,
                key(COOLDOWN), (self, arguments) -> 700));
        assertEquals(67, FormulaRuntimeHooks.overrideManaCost(63, receiver, 3));
        assertEquals(700, FormulaRuntimeHooks.overrideCooldown(500, receiver));
        FormulaRuntimeHooks.initialize(new SpellFormulaCatalog(List.of()));
        assertEquals(63, FormulaRuntimeHooks.overrideManaCost(63, receiver, 3));
        assertEquals(500, FormulaRuntimeHooks.overrideCooldown(500, receiver));
    }

    private static String key() {
        return key(POWER);
    }

    private static String key(String method) {
        return FormulaRuntimeDispatcher.key(Fixture.class.getName(), method);
    }

    private static SpellFormulaCatalog catalog(String declaring) {
        var formula = new SpellFormulaCatalog.Method("spell_power", "getSpellPower",
                "(ILnet/minecraft/world/entity/Entity;)F", "float(1)", "1", Map.of(), declaring);
        var mana = new SpellFormulaCatalog.Method("mana_cost", "getManaCost", "(I)I",
                "int(1)", "1", Map.of(), declaring);
        var cooldown = new SpellFormulaCatalog.Method("cooldown_ticks", "getSpellCooldown", "()I",
                "int(1)", "1", Map.of(), declaring);
        var spell = new SpellFormulaCatalog.Spell("irons_spellbooks:fixture", Fixture.class.getName(),
                "irons_spellbooks:lightning", new JsonObject(), new JsonObject(), List.of(formula, mana, cooldown));
        return new SpellFormulaCatalog(List.of(spell));
    }

    private static final class Fixture {}
}
