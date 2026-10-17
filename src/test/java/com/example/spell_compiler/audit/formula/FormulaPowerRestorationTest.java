package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;
import com.example.spell_compiler.formula.ReadableFormula;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class FormulaPowerRestorationTest {
    private static final Path JAR = Path.of("libs/ironsspells-3.16.1.jar");
    private static final String HOOK = "com/example/spell_compiler/formula/FormulaRuntimeHooks";
    private static final String POWER = "(baseSpellPower + spellPowerPerLevel * (level - 1))"
            + " * spell_power * school_spell_power * power_multiplier";
    private static final String D5 = "5 + 5 * " + POWER;
    private static final String D7 = "5 + 7 * " + POWER;
    private static final float DAMAGE_5 = 548.75f;
    private static final float DAMAGE_7 = 766.25f;
    private static final RealSpellFormulaHarness.Inputs INPUT = new RealSpellFormulaHarness.Inputs(
            5, 13, 4, 1.25, 1.5, 1, RealSpellFormulaHarness.Caster.PRESENT);

    @Test
    void everyBranchGetsOnePowerModifierRegardlessOfWhichBranchMatchesTheDefault() throws Exception {
        assertDamage('(' + D5 + ") + (" + D5 + ')', DAMAGE_5 + DAMAGE_5, 2);
        assertDamage('(' + D5 + ") + (" + D7 + ')', DAMAGE_5 + DAMAGE_7, 2);
        assertDamage('(' + D7 + ") + (" + D5 + ')', DAMAGE_7 + DAMAGE_5, 2);
        assertDamage('(' + D7 + ") + (" + D7 + ')', DAMAGE_7 + DAMAGE_7, 2);
    }

    @Test
    void threeNestedBranchesAreAllRestoredWithoutDoubleApplyingAnyModifier() throws Exception {
        assertDamage('(' + D5 + ") + ((" + D7 + ") + (" + D7 + "))",
                DAMAGE_5 + (DAMAGE_7 + DAMAGE_7), 3);
        assertDamage("((" + D7 + ") + (" + D5 + ")) + (" + D7 + ')',
                (DAMAGE_7 + DAMAGE_5) + DAMAGE_7, 3);
    }

    @Test
    void numericalFunctionsRestoreEveryArgumentAndOnlyOncePerArgument() throws Exception {
        assertDamage("min(" + D5 + ", " + D7 + ')', DAMAGE_5, 2);
        assertDamage("ceil((" + D5 + ") + (" + D7 + "))", 1315f, 2);
        assertDamage("min(ceil(" + D7 + "), ceil(" + D5 + "))", 549f, 2);
        assertDamage("min((" + D5 + ") + (" + D7 + "), 1000000)", 1315f, 2);
        assertDamage("clamp(" + D5 + ", 0, " + D7 + ')', DAMAGE_5, 2);
    }

    @Test
    void outerIntegerAndFloatingCastsKeepTheNativePowerBoundary() throws Exception {
        for (String cast : List.of("int", "long", "float", "double")) {
            assertDamage(cast + '(' + D5 + ')', castValue(cast, DAMAGE_5), 1);
            assertDamage(cast + '(' + D7 + ')', castValue(cast, DAMAGE_7), 1);
        }
    }

    @Test
    void castsInsideMixedBranchesKeepIndependentNativeModifiers() throws Exception {
        assertDamage("int(" + D5 + ") + (" + D7 + ')', 548f + DAMAGE_7, 2);
        assertDamage("int(" + D5 + ") + long(" + D7 + ')', 548f + 766f, 2);
        assertDamage("double(" + D7 + ") + float(" + D5 + ')', DAMAGE_7 + DAMAGE_5, 2);
        assertDamage("ceil(int(" + D5 + ") + (" + D7 + "))", 1315f, 2);
    }

    @Test
    void aSchoolFactorChangedInOneBranchDoesNotBypassOrDuplicateItsModifier() throws Exception {
        String schoolless = D7.replace("school_spell_power", "1");
        assertDamage('(' + D5 + ") + (" + schoolless + ')', 1061.25f, 2);
    }

    @Test
    void changingTheRadiusConversionToLongKeepsThatExplicitConversion() throws Exception {
        var fixture = fixture();
        MethodNode radius = fixture.owner().methods.stream().filter(method -> method.name.equals("getRadius"))
                .findFirst().orElseThrow();
        var formula = AutomaticFormulaExtractor.analyzeRuntime(fixture.owner(), radius, fixture.repository()).formula();
        var types = new LinkedHashMap<String, Type>();
        formula.variables().forEach((name, variable) -> types.put(name, variable.type()));
        String edited = "2 + long(" + POWER + ')';
        String resolved = ReadableFormula.resolve(formula.expression(), edited, types).expression();
        assertTrue(resolved.replaceAll("\\s+", "").contains("long(__native_power_"), resolved);
        assertFalse(resolved.contains("int(long("), resolved);
        byte[] changed = FormulaCompiler.transformRuntime(fixture.original(),
                Map.of(radius.name + radius.desc, edited), fixture.repository());
        var harness = new RealSpellFormulaHarness(JAR);
        harness.addDependency(HOOK, powerHook());
        Class<?> compiled = harness.load(fixture.owner(), changed, List.of(radius));
        assertEquals(110, harness.invoke(compiled, harness.create(compiled, INPUT), radius, INPUT).intValue());
        assertEquals(1, compiled.getClassLoader().loadClass(HOOK.replace('/', '.')).getField("calls").getInt(null));
    }

    @Test
    void explicitLocalGroupingRemainsNaturalInsideTheRestoredPowerBoundary() throws Exception {
        String grouped = "5 + 7 * ((baseSpellPower + spellPowerPerLevel * (level - 1))"
                + " * (spell_power * (school_spell_power * power_multiplier)))";
        var input = new RealSpellFormulaHarness.Inputs(5, 13, 4, 1.234567890123, 0.987654321,
                0.34567890123, RealSpellFormulaHarness.Caster.PRESENT);
        float adjusted = (float) (29 * (input.global() * (input.school() * (float) input.config()))) * 2f;
        assertDamage(grouped, 5f + 7f * adjusted, 1, input);
        assertDamage('(' + D5 + ") + (" + grouped + ')', nativeDamage(input, 5) + (5f + 7f * adjusted),
                2, input);
    }

    @Test
    void defaultReadableAndStrictFormulasRetainTheExactOriginalByteArray() throws Exception {
        var fixture = fixture();
        var formula = AutomaticFormulaExtractor.analyzeRuntime(fixture.owner(), fixture.damage(), fixture.repository()).formula();
        assertEquals(D5, formula.readableExpression());
        assertSame(fixture.original(), FormulaCompiler.transformRuntime(fixture.original(),
                Map.of(fixture.key(), D5), fixture.repository()));
        assertSame(fixture.original(), FormulaCompiler.transformRuntime(fixture.original(),
                Map.of(fixture.key(), " ((" + D5 + ")) "), fixture.repository()));
        String strict = AutomaticFormulaExtractor.analyze(fixture.owner(), fixture.damage(), fixture.repository())
                .formula().expression();
        assertSame(fixture.original(), FormulaCompiler.transformRuntime(fixture.original(),
                Map.of(fixture.key(), strict), fixture.repository()));
        assertDamage(D7, DAMAGE_7, 1);
    }

    @Test
    void fixedAndWrappedConstantsRunWithoutAnImplicitNativePowerModifier() throws Exception {
        for (String expression : List.of("99", "int(99)", "double(99)", "ceil(99)")) {
            assertDamage(expression, 99f, 0);
        }
    }

    @Test
    void changingAnInnerCastDoesNotRestoreTheOldHiddenPrecisionBoundary() {
        var types = Map.of("spell_power", Type.DOUBLE_TYPE);
        var resolved = ReadableFormula.resolve("float(spell_power)", "double(spell_power)", types);
        assertEquals(ReadableFormula.Strategy.NATURAL_DSL, resolved.strategy());
        assertEquals("double(spell_power)", resolved.expression());
        resolved = ReadableFormula.resolve("double(spell_power)", "float(spell_power)", types);
        assertEquals(ReadableFormula.Strategy.NATURAL_DSL, resolved.strategy());
        assertEquals("float(spell_power)", resolved.expression());
    }

    @Test
    void constantOnlyProductsOutsideMagmaBombPowerDoNotAcquireNativeModifiers() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        var owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/MagmaBombSpell");
        var radius = owner.methods.stream().filter(method -> method.name.equals("getRadius"))
                .findFirst().orElseThrow();
        var formula = AutomaticFormulaExtractor.analyzeRuntime(owner, radius, repository).formula();
        assertEquals("3 + power_multiplier * spell_power * school_spell_power", formula.readableExpression());
        byte[] original = RealSpellFormulaHarness.bytes(owner);
        for (String added : List.of("(2 * 3 * 4)", "min(2 * 3 * 4, 100)")) {
            String expression = '(' + formula.readableExpression() + ") + " + added;
            byte[] changed = FormulaCompiler.transformRuntime(original,
                    Map.of(radius.name + radius.desc, expression), repository);
            var transformed = new ClassNode();
            new ClassReader(changed).accept(transformed, 0);
            var compiledRadius = transformed.methods.stream().filter(method -> method.name.equals("getRadius"))
                    .findFirst().orElseThrow();
            int emitted = 0;
            for (var instruction : compiledRadius.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOK)
                        && call.name.equals("applyEntityPower")) emitted++;
            }
            assertEquals(1, emitted, expression);
            var harness = new RealSpellFormulaHarness(JAR);
            harness.addDependency(HOOK, powerHook());
            Class<?> compiled = harness.load(owner, changed, List.of(radius));
            float actual = harness.invoke(compiled, harness.create(compiled, INPUT), radius, INPUT).floatValue();
            assertEquals(Float.floatToRawIntBits(30.75f), Float.floatToRawIntBits(actual), expression);
            assertEquals(1, compiled.getClassLoader().loadClass(HOOK.replace('/', '.')).getField("calls").getInt(null),
                    expression);
        }
    }

    @Test
    void wrappingAcupuncturesProjectedRootRetainsFinalConversionAndOneModifier() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        var owner = repository.classNode("io/redspace/ironsspellbooks/spells/blood/AcupunctureSpell");
        var count = owner.methods.stream().filter(method -> method.name.equals("getCount"))
                .findFirst().orElseThrow();
        var formula = AutomaticFormulaExtractor.analyzeRuntime(owner, count, repository).formula();
        assertFalse(formula.readableExpression().startsWith("int("), formula.readableExpression());
        assertTrue(formula.expression().startsWith("int("), formula.expression());
        byte[] original = RealSpellFormulaHarness.bytes(owner);
        String key = count.name + count.desc;
        assertSame(original, FormulaCompiler.transformRuntime(original,
                Map.of(key, formula.readableExpression()), repository));
        assertSame(original, FormulaCompiler.transformRuntime(original,
                Map.of(key, "int(" + formula.readableExpression() + ')'), repository));
        var types = new LinkedHashMap<String, Type>();
        formula.variables().forEach((name, variable) -> types.put(name, variable.type()));
        for (String cast : List.of("int", "long", "double")) {
            String edited = cast + '(' + formula.readableExpression() + ')';
            if (cast.equals("int")) edited += " + 0";
            String resolved = ReadableFormula.resolve(formula.expression(), edited, types).expression();
            assertTrue(resolved.replaceAll("\\s+", "").startsWith("int("), resolved);
            assertTrue(resolved.contains(cast + '('), resolved);
            byte[] changed = FormulaCompiler.transformRuntime(original, Map.of(key, edited), repository);
            var transformed = new ClassNode();
            new ClassReader(changed).accept(transformed, 0);
            var compiledCount = transformed.methods.stream().filter(method -> method.name.equals("getCount"))
                    .findFirst().orElseThrow();
            int emitted = 0;
            for (var instruction : compiledCount.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOK)
                        && call.name.equals("applyPower")) emitted++;
            }
            assertEquals(1, emitted, edited);
            var harness = new RealSpellFormulaHarness(JAR);
            harness.addDependency(HOOK, powerHook());
            Class<?> compiled = harness.load(owner, changed, List.of(count));
            assertEquals(978, harness.invoke(compiled, harness.create(compiled, INPUT), count, INPUT).intValue(), edited);
            assertEquals(1, compiled.getClassLoader().loadClass(HOOK.replace('/', '.')).getField("calls").getInt(null),
                    edited);
        }
    }

    @Test
    void editingAConstantHealingCircleRadiusRetainsItsOriginalFloatingPointType() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        var owner = repository.classNode("io/redspace/ironsspellbooks/spells/holy/HealingCircleSpell");
        var radius = owner.methods.stream().filter(method -> method.name.equals("getRadius"))
                .findFirst().orElseThrow();
        var formula = AutomaticFormulaExtractor.analyzeRuntime(owner, radius, repository).formula();
        assertEquals("5", formula.readableExpression());
        assertTrue(formula.nativePowers().isEmpty());
        byte[] original = RealSpellFormulaHarness.bytes(owner);
        String key = radius.name + radius.desc;
        assertSame(original, FormulaCompiler.transformRuntime(original,
                Map.of(key, formula.readableExpression()), repository));
        String resolved = ReadableFormula.resolve(formula.expression(), "5 / 2", Map.of()).expression();
        assertTrue(resolved.replaceAll("\\s+", "").contains("float(5"), resolved);
        byte[] changed = FormulaCompiler.transformRuntime(original, Map.of(key, "5 / 2"), repository);
        var transformed = new ClassNode();
        new ClassReader(changed).accept(transformed, 0);
        var compiledRadius = transformed.methods.stream().filter(method -> method.name.equals("getRadius"))
                .findFirst().orElseThrow();
        for (var instruction : compiledRadius.instructions) {
            assertFalse(instruction instanceof MethodInsnNode call && call.owner.equals(HOOK)
                    && call.name.equals("applyPower"));
        }
        var harness = new RealSpellFormulaHarness(JAR);
        harness.addDependency(HOOK, powerHook());
        Class<?> compiled = harness.load(owner, changed, List.of(radius));
        assertEquals(2.5f, harness.invoke(compiled, harness.create(compiled, INPUT), radius, INPUT).floatValue());
        assertEquals(0, compiled.getClassLoader().loadClass(HOOK.replace('/', '.')).getField("calls").getInt(null));
    }

    private static float nativeDamage(RealSpellFormulaHarness.Inputs input, float coefficient) {
        float power = (float) ((29d * (float) input.global()) * input.school() * (float) input.config());
        return 5f + coefficient * (power * 2f);
    }

    private static float castValue(String cast, float value) {
        return switch (cast) {
            case "int" -> (int) value;
            case "long" -> (long) value;
            default -> value;
        };
    }

    private static void assertDamage(String expression, float expected, int hooks) throws Exception {
        assertDamage(expression, expected, hooks, INPUT);
    }

    private static void assertDamage(String expression, float expected, int hooks,
                                     RealSpellFormulaHarness.Inputs input) throws Exception {
        var fixture = fixture();
        byte[] changed = FormulaCompiler.transformRuntime(fixture.original(),
                Map.of(fixture.key(), expression), fixture.repository());
        var transformed = new ClassNode();
        new ClassReader(changed).accept(transformed, 0);
        MethodNode damage = transformed.methods.stream().filter(method -> method.name.equals("getDamage"))
                .findFirst().orElseThrow();
        int emitted = 0;
        for (var instruction : damage.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOK)
                    && call.name.equals("applyPower")) emitted++;
        }
        assertEquals(hooks, emitted, expression);
        var harness = new RealSpellFormulaHarness(JAR);
        harness.addDependency(HOOK, powerHook());
        Class<?> compiled = harness.load(fixture.owner(), changed, List.of(fixture.damage()));
        Object instance = harness.create(compiled, input);
        float actual = harness.invoke(compiled, instance, fixture.damage(), input).floatValue();
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), expression);
        Class<?> hook = compiled.getClassLoader().loadClass(HOOK.replace('/', '.'));
        assertEquals(hooks, hook.getField("calls").getInt(null), expression);
    }

    private static Fixture fixture() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        var owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell");
        var damage = owner.methods.stream().filter(method -> method.name.equals("getDamage"))
                .findFirst().orElseThrow();
        return new Fixture(repository, owner, damage, RealSpellFormulaHarness.bytes(owner));
    }

    private record Fixture(FormulaClassRepository repository, ClassNode owner, MethodNode damage, byte[] original) {
        String key() {
            return damage.name + damage.desc;
        }
    }

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
