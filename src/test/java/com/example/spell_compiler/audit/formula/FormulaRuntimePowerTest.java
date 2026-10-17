package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaRuntimePowerTest {
    private static final Path JAR = Path.of("libs/ironsspells-3.16.1.jar");

    @Test
    void unchangedReadableDefaultsRetainOriginalBytesAndHideInternalMarkers() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        ClassNode owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell");
        MethodNode damage = method(owner, "getDamage");
        var analysis = AutomaticFormulaExtractor.analyzeRuntime(owner, damage, repository);
        assertTrue(analysis.supported(), analysis.rejection());
        assertFalse(analysis.formula().nativePowers().isEmpty());
        assertFalse(analysis.formula().readableExpression().contains("__native_power"));
        byte[] original = RealSpellFormulaHarness.bytes(owner);
        assertSame(original, FormulaCompiler.transformRuntime(original,
                Map.of(damage.name + damage.desc, analysis.formula().readableExpression()), repository));
    }

    @Test
    void nativePowerModifierRemainsInsideDamageAndBeforeIntegerRadiusConversion() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        ClassNode owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell");
        MethodNode damage = method(owner, "getDamage");
        MethodNode radius = method(owner, "getRadius");
        byte[] original = RealSpellFormulaHarness.bytes(owner);
        String damageText = AutomaticFormulaExtractor.analyzeRuntime(owner, damage, repository).formula().readableExpression();
        String radiusText = AutomaticFormulaExtractor.analyzeRuntime(owner, radius, repository).formula().readableExpression();
        byte[] changed = FormulaCompiler.transformRuntime(original, Map.of(
                damage.name + damage.desc, "min(" + damageText + ", 1000000) + 7",
                radius.name + radius.desc, "min(" + radiusText + ", 1000) + 1"), repository);
        var harness = new RealSpellFormulaHarness(JAR);
        harness.addDependency("com/example/spell_compiler/formula/FormulaRuntimeHooks", powerHook());
        Class<?> compiled = harness.load(owner, changed, List.of(damage, radius));
        for (var caster : RealSpellFormulaHarness.Caster.values()) {
            var input = new RealSpellFormulaHarness.Inputs(5, 13, 4, 1.25, 1.5, 1,
                    caster);
            Object spell = harness.create(compiled, input);
            float nativePower = caster == RealSpellFormulaHarness.Caster.NULL ? 29f
                    : caster == RealSpellFormulaHarness.Caster.MISSING_SCHOOL_ATTRIBUTE ? 36.25f : 54.375f;
            float adjusted = caster == RealSpellFormulaHarness.Caster.NULL ? nativePower : nativePower * 2f;
            assertEquals(5f + 5f * adjusted + 7f, harness.invoke(compiled, spell, damage, input).floatValue());
            assertEquals(2 + (int) adjusted + 1, harness.invoke(compiled, spell, radius, input).intValue());
        }
    }

    @Test
    void entityPowerModifierRemainsInsideScaleCalculation() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        ClassNode owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/MagmaBombSpell");
        MethodNode radius = method(owner, "getRadius");
        var formula = AutomaticFormulaExtractor.analyzeRuntime(owner, radius, repository).formula();
        assertFalse(formula.nativePowers().isEmpty());
        byte[] changed = FormulaCompiler.transformRuntime(RealSpellFormulaHarness.bytes(owner),
                Map.of(radius.name + radius.desc, '(' + formula.readableExpression() + ") + 1"), repository);
        var harness = new RealSpellFormulaHarness(JAR);
        harness.addDependency("com/example/spell_compiler/formula/FormulaRuntimeHooks", powerHook());
        Class<?> compiled = harness.load(owner, changed, List.of(radius));
        var input = new RealSpellFormulaHarness.Inputs(5, 8, 3, 1.25, 1.5, 1,
                RealSpellFormulaHarness.Caster.PRESENT);
        Object spell = harness.create(compiled, input);
        Class<?> reference = harness.load(owner, RealSpellFormulaHarness.bytes(owner), List.of(radius));
        var baselineInput = new RealSpellFormulaHarness.Inputs(5, 8, 3, 2.5, 1.5, 1,
                RealSpellFormulaHarness.Caster.PRESENT);
        assertEquals(harness.invoke(reference, harness.create(reference, baselineInput), radius, baselineInput).floatValue() + 1f,
                harness.invoke(compiled, spell, radius, input).floatValue());
    }

    @Test
    void editingDamageCoefficientKeepsNativePowerModifierAtItsOriginalStage() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        ClassNode owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell");
        MethodNode damage = method(owner, "getDamage");
        var formula = AutomaticFormulaExtractor.analyzeRuntime(owner, damage, repository).formula();
        String edited = formula.readableExpression().replaceFirst("5 \\*", "7 *");
        assertNotEquals(formula.readableExpression(), edited);
        byte[] changed = FormulaCompiler.transformRuntime(RealSpellFormulaHarness.bytes(owner),
                Map.of(damage.name + damage.desc, edited), repository);
        var harness = new RealSpellFormulaHarness(JAR);
        harness.addDependency("com/example/spell_compiler/formula/FormulaRuntimeHooks", powerHook());
        Class<?> compiled = harness.load(owner, changed, List.of(damage));
        var input = new RealSpellFormulaHarness.Inputs(5, 13, 4, 1.25, 1.5, 1,
                RealSpellFormulaHarness.Caster.PRESENT);
        assertEquals(5f + 7f * (54.375f * 2f),
                harness.invoke(compiled, harness.create(compiled, input), damage, input).floatValue());
    }

    @Test
    void strictDefaultsAndWrappedStrictExpressionsRetainNativeBoundaries() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        ClassNode owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell");
        MethodNode damage = method(owner, "getDamage");
        String strict = AutomaticFormulaExtractor.analyze(owner, damage, repository).formula().expression();
        byte[] original = RealSpellFormulaHarness.bytes(owner);
        assertSame(original, FormulaCompiler.transformRuntime(original, Map.of(damage.name + damage.desc, strict), repository));
        byte[] changed = FormulaCompiler.transformRuntime(original,
                Map.of(damage.name + damage.desc, '(' + strict + ") + 7"), repository);
        var harness = new RealSpellFormulaHarness(JAR);
        harness.addDependency("com/example/spell_compiler/formula/FormulaRuntimeHooks", powerHook());
        Class<?> compiled = harness.load(owner, changed, List.of(damage));
        var input = new RealSpellFormulaHarness.Inputs(5, 13, 4, 1.25, 1.5, 1,
                RealSpellFormulaHarness.Caster.PRESENT);
        assertEquals(5f + 5f * (54.375f * 2f) + 7f,
                harness.invoke(compiled, harness.create(compiled, input), damage, input).floatValue());
    }

    @Test
    void replacingSchoolFactorWithOneRemovesSchoolInfluenceAndKeepsPowerModifier() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        ClassNode owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell");
        MethodNode damage = method(owner, "getDamage");
        var formula = AutomaticFormulaExtractor.analyzeRuntime(owner, damage, repository).formula();
        String edited = formula.readableExpression().replace("school_spell_power", "1");
        byte[] changed = FormulaCompiler.transformRuntime(RealSpellFormulaHarness.bytes(owner),
                Map.of(damage.name + damage.desc, edited), repository);
        var harness = new RealSpellFormulaHarness(JAR);
        harness.addDependency("com/example/spell_compiler/formula/FormulaRuntimeHooks", powerHook());
        Class<?> compiled = harness.load(owner, changed, List.of(damage));
        Class<?> reference = harness.load(owner, RealSpellFormulaHarness.bytes(owner), List.of(damage));
        for (var caster : RealSpellFormulaHarness.Caster.values()) {
            for (double school : new double[]{0.7, 1, 1.9999999993, 2.5}) {
                var input = new RealSpellFormulaHarness.Inputs(5, 13, 4, 1.25, school, 1, caster);
                var baseline = new RealSpellFormulaHarness.Inputs(5, 13, 4,
                        caster == RealSpellFormulaHarness.Caster.NULL ? 1.25 : 2.5, 1, 1, caster);
                assertEquals(harness.invoke(reference, harness.create(reference, baseline), damage, baseline).floatValue(),
                        harness.invoke(compiled, harness.create(compiled, input), damage, input).floatValue(),
                        caster + " school=" + school);
            }
        }
    }

    @Test
    void constantEffectLevelsAndLevelOnlyFormulasRunWithoutNativePowerModifiers() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        ClassNode owner = repository.classNode("io/redspace/ironsspellbooks/spells/lightning/ThunderstormSpell");
        MethodNode effectLevel = method(owner, "getAmplifierForLevel");
        for (String expression : List.of("4", "level * 2 + 1")) {
            byte[] changed = FormulaCompiler.transformRuntime(RealSpellFormulaHarness.bytes(owner),
                    Map.of(effectLevel.name + effectLevel.desc, expression), repository);
            var harness = new RealSpellFormulaHarness(JAR);
            String hookName = "com/example/spell_compiler/formula/FormulaRuntimeHooks";
            harness.addDependency(hookName, powerHook());
            Class<?> compiled = harness.load(owner, changed, List.of(effectLevel));
            for (var caster : RealSpellFormulaHarness.Caster.values()) {
                for (int level : new int[]{3, 7}) {
                    var input = new RealSpellFormulaHarness.Inputs(level, 23, 9, 3.25, 4.5, 7, caster);
                    int expected = expression.equals("4") ? 4 : level * 2 + 1;
                    assertEquals(expected, harness.invoke(compiled, harness.create(compiled, input), effectLevel, input).intValue(),
                            expression + " " + caster + " level=" + level);
                }
            }
            assertEquals(0, compiled.getClassLoader().loadClass(hookName.replace('/', '.')).getField("calls").getInt(null));
        }
        assertEquals(Type.DOUBLE_TYPE, FormulaCompiler.validateExpression("min(spell_power, 2)",
                Map.of("spell_power", Type.DOUBLE_TYPE)));
        assertThrows(IllegalArgumentException.class, () -> FormulaCompiler.validateExpression(
                "__native_power_0(1)", Map.of()));
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
    }

    private static byte[] powerHook() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/spell_compiler/formula/FormulaRuntimeHooks",
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd();
        for (String name : List.of("applyPower", "applyEntityPower")) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                    "(FLjava/lang/Object;Ljava/lang/Object;)F", null, null);
            method.visitCode();
            method.visitFieldInsn(Opcodes.GETSTATIC, "com/example/spell_compiler/formula/FormulaRuntimeHooks", "calls", "I");
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD);
            method.visitFieldInsn(Opcodes.PUTSTATIC, "com/example/spell_compiler/formula/FormulaRuntimeHooks", "calls", "I");
            method.visitVarInsn(Opcodes.ALOAD, 2);
            Label unchanged = new Label();
            method.visitJumpInsn(Opcodes.IFNULL, unchanged);
            method.visitVarInsn(Opcodes.FLOAD, 0);
            method.visitInsn(Opcodes.FCONST_2);
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
