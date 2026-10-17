package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FormulaExplicitPowerRestorationTest {
    private static final Path JAR = Path.of("libs/ironsspells-3.16.1.jar");
    private static final String HOOK = "com/example/spell_compiler/formula/FormulaRuntimeHooks";
    private static final String ATTRIBUTES = "spell_power * school_spell_power * power_multiplier";
    private static final String NATIVE_POWER = "(baseSpellPower + spellPowerPerLevel * (level - 1)) * " + ATTRIBUTES;
    private static final RealSpellFormulaHarness.Inputs INPUT = new RealSpellFormulaHarness.Inputs(
            5, 13, 4, 1.25, 1.5, 1, RealSpellFormulaHarness.Caster.PRESENT);

    @Test
    void customNumericAndCalculatedBasesRetainTheCompleteAttributePowerBoundary() throws Exception {
        assertDamage("100 * " + ATTRIBUTES, adjusted(100, INPUT), 1, INPUT);
        assertDamage("(100 + level * 2) * " + ATTRIBUTES, adjusted(110, INPUT), 1, INPUT);
    }

    @Test
    void fixedOffsetsOutsideRebuiltPowerStayOutsideTheModifier() throws Exception {
        assertDamage("5 + 100 * " + ATTRIBUTES + " + 3", 5 + adjusted(100, INPUT) + 3, 1, INPUT);
        assertDamage("3 + (100 + level * 2) * " + ATTRIBUTES + " + 5", 3 + adjusted(110, INPUT) + 5, 1, INPUT);
    }

    @Test
    void nativeAndRebuiltPowerBranchesEachApplyExactlyOneModifier() throws Exception {
        float nativeDamage = 5 + 5 * adjusted(29, INPUT);
        assertDamage("(5 + 5 * " + NATIVE_POWER + ") + (100 * " + ATTRIBUTES + ") + 3",
                nativeDamage + adjusted(100, INPUT) + 3, 2, INPUT);
        assertDamage("(100 * " + ATTRIBUTES + ") + (5 + 5 * " + NATIVE_POWER + ") + 3",
                adjusted(100, INPUT) + nativeDamage + 3, 2, INPUT);
    }

    @Test
    void alreadyRestoredNativeMarkerIsNotWrappedAgain() throws Exception {
        var fixture = fixture();
        var analysis = AutomaticFormulaExtractor.analyzeRuntime(fixture.owner(), fixture.damage(), fixture.repository());
        assertTrue(analysis.supported(), analysis.rejection());
        assertDamage('(' + analysis.formula().expression() + ") + 3", 5 + 5 * adjusted(29, INPUT) + 3, 1, INPUT);
    }

    @Test
    void constantsRemainUnmarkedForTheSeparateFinalDamageStage() throws Exception {
        for (String expression : List.of("100", "int(100)", "double(100)", "ceil(100)"))
            assertDamage(expression, 100, 0, INPUT);
    }

    @Test
    void explicitGroupingAndFactorOrderKeepTheirRequestedArithmeticInsideOneBoundary() throws Exception {
        var input = new RealSpellFormulaHarness.Inputs(5, 13, 4, 1.234567890123, 0.987654321,
                0.34567890123, RealSpellFormulaHarness.Caster.PRESENT);
        float grouped = (float) (100 * (input.global() * (input.school() * (float) input.config()))) * 1.5f;
        assertDamage("100 * (spell_power * (school_spell_power * power_multiplier))", grouped, 1, input);
        float reordered = (float) (input.school() * (100 * input.global()) * (float) input.config()) * 1.5f;
        assertDamage("school_spell_power * (100 * spell_power) * power_multiplier", reordered, 1, input);
        assertDamage("100 * " + ATTRIBUTES, adjusted(100, input), 1, input);
    }

    @Test
    void severalRebuiltBranchesDoNotAcquireAnExtraWholeResultModifier() throws Exception {
        assertDamage("(100 * " + ATTRIBUTES + ") + (200 * " + ATTRIBUTES + ") + 3",
                adjusted(100, INPUT) + adjusted(200, INPUT) + 3, 2, INPUT);
    }

    @Test
    void multiplicativeCustomBasisRemainsWholeInNativeAndExplicitMixedBranches() throws Exception {
        float nativeDamage = 5 + 5 * adjusted(29, INPUT);
        assertDamage("(5 + 5 * " + NATIVE_POWER + ") + (level * 2) * " + ATTRIBUTES + " + 3",
                nativeDamage + adjusted(10, INPUT) + 3, 2, INPUT);
        assertDamage("(level * 2) * " + ATTRIBUTES, adjusted(10, INPUT), 1, INPUT);
        assertDamage("((10 * level) * (level + 1)) * " + ATTRIBUTES, adjusted(300, INPUT), 1, INPUT);
    }

    @Test
    void trailingCoefficientsAndIncompleteNonlinearAttributeProductsKeepTheirOwnBoundaries() throws Exception {
        float nativeDamage = 5 + 5 * adjusted(29, INPUT);
        float scaled = (float) (nativeDamage + adjusted(100, INPUT) * 1.00000006);
        assertDamage("(5 + 5 * " + NATIVE_POWER + ") + 100 * " + ATTRIBUTES + " * 1.00000006",
                scaled, 2, INPUT);
        float nonlinear = (float) (nativeDamage + Math.sqrt(INPUT.global()) * INPUT.school() * (float) INPUT.config());
        assertDamage("(5 + 5 * " + NATIVE_POWER + ") + sqrt(spell_power) * school_spell_power * power_multiplier",
                nonlinear, 1, INPUT);
    }

    private static float adjusted(double basis, RealSpellFormulaHarness.Inputs input) {
        return (float) (basis * (float) input.global() * input.school() * (float) input.config()) * 1.5f;
    }

    private static void assertDamage(String expression, float expected, int hooks,
                                     RealSpellFormulaHarness.Inputs input) throws Exception {
        var fixture = fixture();
        byte[] changed = FormulaCompiler.transformRuntime(fixture.original(),
                Map.of(fixture.damage().name + fixture.damage().desc, expression), fixture.repository());
        var transformed = new ClassNode();
        new ClassReader(changed).accept(transformed, 0);
        var compiledMethod = transformed.methods.stream().filter(method -> method.name.equals("getDamage"))
                .findFirst().orElseThrow();
        int emitted = 0;
        for (var instruction : compiledMethod.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOK) && call.name.equals("applyPower")) emitted++;
        assertEquals(hooks, emitted, expression);
        var harness = new RealSpellFormulaHarness(JAR);
        harness.addDependency(HOOK, powerHook());
        Class<?> compiled = harness.load(fixture.owner(), changed, List.of(fixture.damage()));
        float actual = harness.invoke(compiled, harness.create(compiled, input), fixture.damage(), input).floatValue();
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), expression);
        assertEquals(hooks, compiled.getClassLoader().loadClass(HOOK.replace('/', '.')).getField("calls").getInt(null), expression);
    }

    private static Fixture fixture() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        var owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell");
        var damage = owner.methods.stream().filter(method -> method.name.equals("getDamage")).findFirst().orElseThrow();
        return new Fixture(repository, owner, damage, RealSpellFormulaHarness.bytes(owner));
    }

    private record Fixture(FormulaClassRepository repository, ClassNode owner, MethodNode damage, byte[] original) {}

    private static byte[] powerHook() {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HOOK, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd();
        for (String name : List.of("applyPower", "applyEntityPower")) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                    "(FLjava/lang/Object;Ljava/lang/Object;)F", null, null);
            method.visitCode();
            method.visitFieldInsn(Opcodes.GETSTATIC, HOOK, "calls", "I");
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD);
            method.visitFieldInsn(Opcodes.PUTSTATIC, HOOK, "calls", "I");
            method.visitVarInsn(Opcodes.ALOAD, 2);
            var unchanged = new Label();
            method.visitJumpInsn(Opcodes.IFNULL, unchanged);
            method.visitVarInsn(Opcodes.FLOAD, 0);
            method.visitLdcInsn(1.5f);
            method.visitInsn(Opcodes.FMUL);
            method.visitInsn(Opcodes.FRETURN);
            method.visitLabel(unchanged);
            method.visitVarInsn(Opcodes.FLOAD, 0);
            method.visitInsn(Opcodes.FRETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
