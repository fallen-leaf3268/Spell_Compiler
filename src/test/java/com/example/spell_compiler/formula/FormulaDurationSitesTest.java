package com.example.spell_compiler.formula;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaDurationSitesTest {
    private static final String SAFETY = "com/example/spell_compiler/formula/FormulaDurationSafety";

    @Test
    void allNativeDurationMethodsAndDynamicEffectConsumersAreProtectedWithoutReplacingNativeCalls() throws Exception {
        var catalog = SpellFormulaCatalog.load(getClass().getClassLoader());
        var repository = FormulaClassRepository.fromJar(Path.of("libs", "ironsspells-3.16.1.jar"));
        int integers = 0;
        int floats = 0;
        int direct = 0;
        int preview = 0;
        int areas = 0;
        for (var spell : catalog.spells()) {
            ClassNode target = repository.classNode(spell.className().replace('.', '/'));
            var nativeCalls = calls(target);
            int arrays = arrays(target);
            var coverage = FormulaDurationSites.apply(target, spell);
            for (var method : target.methods) for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call && call.owner.equals(SAFETY) && call.name.equals("guardAoeDuration")) areas++;
            for (var method : target.methods) {
                boolean protectedMethod = false;
                for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(SAFETY)) protectedMethod = true;
                if (protectedMethod) assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(target.name, method),
                        spell.id() + '#' + method.name + method.desc);
            }
            assertEquals(nativeCalls, calls(target), spell.id());
            assertEquals(arrays, arrays(target), spell.id());
            integers += coverage.integerMethods();
            floats += coverage.floatingMethods();
            direct += coverage.directConsumers();
            preview += coverage.previewConsumers();
            if (spell.id().equals("irons_spellbooks:evasion") || spell.id().equals("irons_spellbooks:fortify"))
                assertEquals(0, coverage.directConsumers(), "A constant duration was confused with its dynamic effect amplifier");
            if (spell.id().equals("irons_spellbooks:haste")) assertEquals(1, coverage.integerMethods());
            if (spell.id().equals("irons_spellbooks:oakskin")) assertEquals(1, coverage.directConsumers());
        }
        assertEquals(18, integers);
        assertEquals(2, floats);
        assertEquals(9, direct);
        assertEquals(11, preview);
        assertEquals(1, areas);
    }

    @Test
    void powerDurationMathKeepsNativeTruncationOrderAndLegalZero() {
        assertEquals(0, FormulaDurationSafety.calculatePowerDuration(0, 20, false));
        assertEquals(0, FormulaDurationSafety.calculatePowerDuration(-0.05f, 20, true));
        assertEquals(20, FormulaDurationSafety.calculatePowerDuration(1.99f, 20, true));
        assertEquals(39, FormulaDurationSafety.calculatePowerDuration(1.99f, 20, false));
        assertEquals(73, FormulaDurationSafety.calculatePowerDuration(73.9f, 1, false));
        assertEquals(2147483520, FormulaDurationSafety.calculatePowerDuration(2147483520f, 1, false));
    }

    @Test
    void powerDurationMathRejectsInfiniteSentinelsSaturationAndIntegerProductOverflow() {
        assertThrows(ArithmeticException.class, () -> FormulaDurationSafety.calculatePowerDuration(-0.05f, 20, false));
        assertThrows(ArithmeticException.class, () -> FormulaDurationSafety.calculatePowerDuration(-1, 1, false));
        assertThrows(ArithmeticException.class, () -> FormulaDurationSafety.calculatePowerDuration(Float.POSITIVE_INFINITY, 1, false));
        assertThrows(ArithmeticException.class, () -> FormulaDurationSafety.calculatePowerDuration(Float.NaN, 1, false));
        assertThrows(ArithmeticException.class, () -> FormulaDurationSafety.calculatePowerDuration(200000000, 20, false));
        assertThrows(ArithmeticException.class, () -> FormulaDurationSafety.calculatePowerDuration(200000000, 20, true));
        assertThrows(ArithmeticException.class, () -> FormulaDurationSafety.calculatePowerDuration(2147483648f, 1, false));
    }

    private static Map<String, Integer> calls(ClassNode target) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var method : target.methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && !call.owner.equals(SAFETY))
                result.merge(method.name + method.desc + ':' + call.owner + '#' + call.name + call.desc, 1, Integer::sum);
        }
        return result;
    }

    private static int arrays(ClassNode target) {
        int result = 0;
        for (var method : target.methods) for (var instruction : method.instructions)
            if (instruction.getOpcode() == Opcodes.ANEWARRAY || instruction.getOpcode() == Opcodes.NEWARRAY
                    || instruction.getOpcode() == Opcodes.MULTIANEWARRAY) result++;
        return result;
    }
}
