package com.example.spell_compiler.formula;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaSitesTest {
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaSiteHooks";

    @AfterEach
    void clearDispatchers() {
        FormulaRuntimeDispatcher.clearClient();
        FormulaRuntimeDispatcher.clearServer();
    }

    @Test
    void everyNativeSiteValidatesAndInstrumentsWithExactCallCounts() throws Exception {
        FormulaClassRepository repository = nativeRepository();
        FormulaSites.validate(repository);
        assertEquals(7, FormulaSites.targetClasses().size());
        Map<String, Integer> expected = Map.of(
                "holy.HealingCircleSpell", 1, "ice.SummonPolarBearSpell", 1, "fire.FireboltSpell", 1,
                "lightning.ThunderstormSpell", 1, "holy.HasteSpell", 2, "blood.SacrificeSpell", 1);
        int calls = 0;
        for (String name : FormulaSites.targetClasses()) {
            ClassNode source = repository.classNode(name.replace('.', '/'));
            Map<String, Long> nativeCalls = calls(source, false);
            int locals = source.methods.stream().mapToInt(method -> method.maxLocals).sum();
            FormulaSites.apply(name, source);
            assertEquals(nativeCalls, calls(source, false), name);
            long count = calls(source, true).values().stream().mapToLong(Long::longValue).sum();
            assertEquals(name.endsWith("ThunderstormEffect") ? 6
                    : expected.get(name.substring("io.redspace.ironsspellbooks.spells.".length())), count, name);
            assertEquals(name.endsWith("SacrificeSpell") ? locals + 1 : locals,
                    source.methods.stream().mapToInt(method -> method.maxLocals).sum(), name);
            for (MethodNode method : source.methods) for (var instruction : method.instructions) {
                if (instruction.getOpcode() == Opcodes.ANEWARRAY) continue;
                if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOKS)) calls++;
            }
        }
        assertEquals(13, calls);
    }

    @Test
    void srgAndMappedNamesMatchTheSameNativeAnchors() throws Exception {
        FormulaClassRepository repository = nativeRepository();
        for (String name : FormulaSites.targetClasses()) {
            ClassNode source = repository.classNode(name.replace('.', '/'));
            for (MethodNode method : source.methods) {
                if (method.name.equals("m_6584_")) method.name = "isDurationEffectTick";
                if (method.name.equals("m_6742_")) method.name = "applyEffectTick";
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.name.equals("m_21223_")) call.name = "getHealth";
                }
            }
        }
        FormulaSites.validate(repository);
        int points = 0;
        for (String name : FormulaSites.targetClasses()) {
            var source = repository.classNode(name.replace('.', '/'));
            FormulaSites.apply(name, source);
            points += calls(source, true).values().stream().mapToLong(Long::longValue).sum();
        }
        assertEquals(13, points);
    }

    @Test
    void missingOrDuplicateAnchorsLeaveAllOriginalMethodsUntouched() throws Exception {
        var repository = nativeRepository();
        String owner = "io.redspace.ironsspellbooks.spells.holy.HasteSpell";
        ClassNode source = repository.classNode(owner.replace('.', '/'));
        var onCast = source.methods.stream().filter(method -> method.name.equals("onCast")).findFirst().orElseThrow();
        for (var instruction : onCast.instructions) {
            if (instruction instanceof LdcInsnNode value && value.cst.equals(3f)) {
                value.cst = 4f;
                break;
            }
        }
        var originals = List.copyOf(source.methods);
        assertThrows(IllegalArgumentException.class, () -> FormulaSites.apply(owner, source));
        for (int index = 0; index < originals.size(); index++) assertSame(originals.get(index), source.methods.get(index));
        assertTrue(calls(source, true).isEmpty());
        var effect = repository.classNode("io/redspace/ironsspellbooks/effect/ThunderstormEffect");
        MethodNode damage = effect.methods.stream().filter(method -> method.name.equals("getDamageFromAmplifier")).findFirst().orElseThrow();
        MethodNode duplicate = new MethodNode(Opcodes.ASM9, damage.access, damage.name, damage.desc, damage.signature, null);
        damage.accept(duplicate);
        effect.methods.add(duplicate);
        var before = List.copyOf(effect.methods);
        assertThrows(IllegalArgumentException.class, () -> FormulaSites.apply(effect.name.replace('/', '.'), effect));
        for (int index = 0; index < before.size(); index++) assertSame(before.get(index), effect.methods.get(index));
    }

    @Test
    void siteBoundsRejectUnsafeValuesAndKeepNativeDefaultsOutsideOverrides() {
        var healing = site("healing_interval_ticks");
        var duration = site("summon_duration_ticks");
        var horizontal = site("horizontal_range");
        var radius = site("radius");
        assertEquals(1, FormulaSites.validateValue(healing.key(), 1));
        assertEquals(0, FormulaSites.validateValue(duration.key(), 0));
        assertEquals(46340, FormulaSites.validateValue(horizontal.key(), 46340));
        assertThrows(ArithmeticException.class, () -> FormulaSites.validateValue(healing.key(), 0));
        assertThrows(ArithmeticException.class, () -> FormulaSites.validateValue(duration.key(), -1));
        assertThrows(ArithmeticException.class, () -> FormulaSites.validateValue(horizontal.key(), 46341));
        assertThrows(ArithmeticException.class, () -> FormulaSites.validateValue(radius.key(), Float.NaN));
        assertThrows(ArithmeticException.class, () -> FormulaSites.validateValue(radius.key(), Float.POSITIVE_INFINITY));
        assertThrows(ArithmeticException.class, () -> FormulaSites.validateValue(radius.key(), Float.MAX_VALUE));
        assertEquals(0, FormulaSiteHooks.overrideInt(0, healing.dispatchKey(), null));
        assertEquals(Double.doubleToRawLongBits(-0d), Double.doubleToRawLongBits(
                FormulaSiteHooks.overrideDouble(-0d, site("vertical_range").dispatchKey(), null)));
    }

    @Test
    void compiledIntegerOverridesKeepTruncationAndTheHorizontalConsumersAgree() {
        var healing = site("healing_interval_ticks");
        install(healing, "3.9");
        assertEquals(3, FormulaSiteHooks.overrideInt(10, healing.dispatchKey(), null));
        var horizontal = site("horizontal_range");
        install(horizontal, "7.9");
        assertEquals(7, FormulaSiteHooks.overrideInt(20, horizontal.dispatchKey(), null));
        assertEquals(7d, FormulaSiteHooks.overrideIntAsDouble(20d, horizontal.dispatchKey(), null));
        assertEquals(49, FormulaSiteHooks.overrideSquaredInt(400, horizontal.dispatchKey(), null));
    }

    @Test
    void summonDurationKeepsItsContextParameterWithoutAddingAnEditableNumericInput() {
        var duration = site("summon_duration_ticks");
        assertEquals("(Ljava/lang/Object;)I", duration.descriptor());
        assertEquals(12000, FormulaSiteValues.summonDuration(null));
        assertThrows(IllegalArgumentException.class, () ->
                FormulaSiteHooks.validateValue(duration.key(), 60, new Object[0]));
        install(duration, "60.9");
        assertEquals(60, FormulaSiteHooks.overrideSummonDuration(12000, duration.dispatchKey(), null));
    }

    @Test
    void invalidOverrideIsDisabledOnceUntilReloadAndRetainsEveryNativeConsumer() {
        var horizontal = site("horizontal_range");
        AtomicInteger invocations = new AtomicInteger();
        FormulaRuntimeDispatcher.replaceClient(Map.of(horizontal.dispatchKey(), (self, arguments) -> {
            invocations.incrementAndGet();
            return FormulaSites.validateValue(horizontal.key(), 46341);
        }));
        assertEquals(20, FormulaSiteHooks.overrideInt(20, horizontal.dispatchKey(), null));
        assertEquals(20d, FormulaSiteHooks.overrideIntAsDouble(20d, horizontal.dispatchKey(), null));
        assertEquals(400, FormulaSiteHooks.overrideSquaredInt(400, horizontal.dispatchKey(), null));
        assertEquals(1, invocations.get());
        install(horizontal, "9");
        assertEquals(81, FormulaSiteHooks.overrideSquaredInt(400, horizontal.dispatchKey(), null));
    }

    @Test
    void nativeSummonHealthIsPassedAsOneCapturedPrimitiveInput() {
        var bonus = site("summon_health_damage_bonus");
        install(bonus, "summon_health * 0.75 + 1");
        assertEquals(16f, FormulaSiteHooks.overrideFloat(10f, bonus.dispatchKey(), null, 20f));
        assertEquals(7f, FormulaSiteHooks.overrideFloat(4f, bonus.dispatchKey(), null, 8f));
        FormulaRuntimeDispatcher.clearClient();
        assertEquals(10f, FormulaSiteHooks.overrideFloat(10f, bonus.dispatchKey(), null, 20f));
    }

    @Test
    void pureThunderstormInputsKeepTheNativeFloatAndDoubleGrouping() {
        for (int amplifier : new int[]{0, 8, 11, Integer.MAX_VALUE}) {
            for (float global : new float[]{0.73f, 1f, 2.11f}) {
                double school = 0.6133333333333333;
                float multiplier = 1.27f;
                float power = (float) ((double) (multiplier * global) * school);
                float expected = (amplifier - 7) * power + 7;
                float actual = FormulaSiteValues.thunderstormDamage(amplifier, global, school, multiplier, null, null);
                assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
            }
        }
    }

    @Test
    void constantThunderstormDamageIgnoresEffectLevelPowerInputsAndContext() throws Throwable {
        var damage = site("effect_damage");
        var delegate = FormulaDelegateFactory.compile(FormulaSiteValues.class, Map.of(damage.key(), "100")).get(damage.key());
        assertNotNull(delegate);
        for (Object[] arguments : List.of(
                new Object[]{4, 1f, 1d, 1f, null, null},
                new Object[]{19, 3.25f, 4.5d, 7f, new Object(), new Object()},
                new Object[]{0, 0.25f, 0.125d, 0.5f, new Object(), null})) {
            assertEquals(100f, delegate.invoke(null, arguments).floatValue());
        }
    }

    private static FormulaClassRepository nativeRepository() throws Exception {
        return FormulaClassRepository.fromJar(Path.of("libs", "ironsspells-3.16.1.jar"));
    }

    private static FormulaSites.Definition site(String alias) {
        return FormulaSites.targetClasses().stream().flatMap(owner -> FormulaSites.definitions(owner).stream())
                .filter(value -> value.alias().equals(alias)).findFirst().orElseThrow();
    }

    private static void install(FormulaSites.Definition definition, String expression) {
        var delegates = FormulaDelegateFactory.compile(FormulaSiteValues.class, Map.of(definition.key(), expression));
        var delegate = delegates.get(definition.key());
        assertNotNull(delegate);
        FormulaRuntimeDispatcher.replaceClient(Map.of(definition.dispatchKey(), (self, arguments) ->
                FormulaSiteHooks.validateValue(definition.key(), delegate.invoke(self, arguments), arguments)));
    }

    private static Map<String, Long> calls(ClassNode source, boolean hooks) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (MethodNode method : source.methods) for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || call.owner.equals(HOOKS) != hooks) continue;
            result.merge(method.name + method.desc + ':' + call.owner + '#' + call.name + call.desc, 1L, Long::sum);
        }
        return result;
    }
}
