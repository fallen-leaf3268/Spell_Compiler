package com.example.spell_compiler.formula;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaPowerSourceTest {
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaRuntimeHooks";

    @Test
    void spellAndEntityPowerBoundariesRetainTheirDistinctSources() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs/ironsspells-3.16.1.jar"));
        check(repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell"), "getDamage",
                repository, AutomaticFormulaExtractor.PowerSource.SPELL_POWER);
        check(repository.classNode("io/redspace/ironsspellbooks/spells/ice/SnowballSpell"), "getDuration",
                repository, AutomaticFormulaExtractor.PowerSource.ENTITY_MULTIPLIER);
        check(repository.classNode("io/redspace/ironsspellbooks/spells/ice/IceTombSpell"), "getHealing",
                repository, AutomaticFormulaExtractor.PowerSource.ENTITY_MULTIPLIER);
        check(repository.classNode("io/redspace/ironsspellbooks/spells/fire/MagmaBombSpell"), "getRadius",
                repository, AutomaticFormulaExtractor.PowerSource.ENTITY_MULTIPLIER);
    }

    @Test
    void thunderstormSyntheticSiteRetainsTheEntityMultiplierSource() throws Exception {
        var owner = new ClassNode();
        String path = '/' + FormulaSiteValues.class.getName().replace('.', '/') + ".class";
        try (var stream = FormulaSiteValues.class.getResourceAsStream(path)) {
            assertNotNull(stream);
            new ClassReader(stream.readAllBytes()).accept(owner, 0);
        }
        check(owner, "thunderstormDamage", FormulaClassRepository.of(owner),
                AutomaticFormulaExtractor.PowerSource.ENTITY_MULTIPLIER);
    }

    private static void check(ClassNode owner, String name, FormulaClassRepository repository,
                              AutomaticFormulaExtractor.PowerSource expected) {
        var method = method(owner, name);
        var analysis = AutomaticFormulaExtractor.analyzeRuntime(owner, method, repository);
        assertTrue(analysis.supported(), analysis.rejection());
        assertEquals(1, analysis.formula().nativePowers().size());
        assertEquals(expected, analysis.formula().nativePowers().values().iterator().next().source());
        var writer = new ClassWriter(0);
        owner.accept(writer);
        byte[] original = writer.toByteArray();
        String expression = analysis.formula().readableExpression();
        assertSame(original, FormulaCompiler.transformRuntime(original,
                Map.of(method.name + method.desc, expression), repository));
        byte[] changed = FormulaCompiler.transformRuntime(original,
                Map.of(method.name + method.desc, '(' + expression + ")+0"), repository);
        var compiled = new ClassNode();
        new ClassReader(changed).accept(compiled, 0);
        List<MethodInsnNode> hooks = java.util.Arrays.stream(method(compiled, name).instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(HOOKS) && (call.name.equals("applyPower") || call.name.equals("applyEntityPower")))
                .toList();
        assertEquals(1, hooks.size());
        assertEquals(expected == AutomaticFormulaExtractor.PowerSource.SPELL_POWER ? "applyPower" : "applyEntityPower",
                hooks.get(0).name);
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }
}
