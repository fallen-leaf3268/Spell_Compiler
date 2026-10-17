package com.example.spell_compiler.formula;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaConsumerSitesTest {
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaConsumerHooks";

    @Test
    void everyActualEffectConsumerHasOneExactTransactionalBridge() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs", "ironsspells-3.16.1.jar"));
        FormulaConsumerSites.validate(repository);
        assertEquals(9, FormulaConsumerSites.targetClasses().size());
        int points = 0;
        for (var owner : FormulaConsumerSites.targetClasses()) {
            var source = repository.classNode(owner.replace('.', '/'));
            var calls = calls(source, false);
            FormulaConsumerSites.apply(owner, source);
            assertEquals(calls, calls(source, false), owner);
            int expected = owner.endsWith("OakskinEffect") ? 2 : 1;
            int actual = calls(source, true).values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(expected, actual, owner);
            for (var method : source.methods)
                if (java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                        .anyMatch(instruction -> instruction instanceof MethodInsnNode call && call.owner.equals(HOOKS)))
                    new Analyzer<>(new BasicInterpreter()).analyze(source.name, method);
            points += actual;
        }
        assertEquals(10, points);
    }

    @Test
    void alteredNativeConsumerAnchorsFailWithoutPublishingPartialMethods() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs", "ironsspells-3.16.1.jar"));
        String owner = "io.redspace.ironsspellbooks.effect.SpiderAspectEffect";
        var source = repository.classNode(owner.replace('.', '/'));
        for (var method : source.methods) if (method.name.equals("increaseDamage"))
            for (var instruction : method.instructions)
                if (instruction instanceof LdcInsnNode constant && constant.cst.equals(0.05f)) constant.cst = 0.06f;
        var methods = java.util.List.copyOf(source.methods);
        assertThrows(IllegalArgumentException.class, () -> FormulaConsumerSites.apply(owner, source));
        for (int index = 0; index < methods.size(); index++) assertSame(methods.get(index), source.methods.get(index));
        assertTrue(calls(source, true).isEmpty());
    }

    @Test
    void sevenExportedValuesMapToFiveRegisteredEffectsAndPreserveTheirUnits() {
        var effects = java.util.List.of("frostbite", "charged", "gluttony", "oakskin", "spider_aspect");
        assertEquals(7, effects.stream().mapToInt(effect -> FormulaConsumerSites.forEffect("irons_spellbooks:" + effect).size()).sum());
        assertEquals(3, FormulaConsumerSites.forEffect("irons_spellbooks:charged").size());
        for (var effect : effects) for (var definition : FormulaConsumerSites.forEffect("irons_spellbooks:" + effect)) {
            assertTrue(definition.key().endsWith("(ILnet/minecraft/world/entity/LivingEntity;)F"));
            assertEquals(definition.spellClassName() + '#' + definition.key(), definition.dispatchKey());
        }
        assertTrue(FormulaConsumerSites.forEffect("minecraft:speed").isEmpty());
        assertFalse(FormulaConsumerSites.excludedGetter("io.redspace.ironsspellbooks.spells.holy.SunbeamSpell",
                "getDamage(ILnet/minecraft/world/entity/LivingEntity;)F"));
    }

    private static Map<String, Integer> calls(ClassNode source, boolean hooks) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var method : source.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOKS) == hooks)
                result.merge(method.name + method.desc + ':' + call.owner + '#' + call.name + call.desc, 1, Integer::sum);
        return result;
    }
}
