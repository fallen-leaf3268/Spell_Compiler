package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;
import com.example.spell_compiler.formula.ReadableFormula;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class FormulaConstantMappingTest {
    private static final Path JAR = Path.of("libs/ironsspells-3.16.1.jar");
    private static final String HOOK = "com/example/spell_compiler/formula/FormulaRuntimeHooks";
    private static final String DAMAGE = "5 + 7 * (baseSpellPower + spellPowerPerLevel * (level - 1))"
            + " * spell_power * school_spell_power * power_multiplier";
    private static final RealSpellFormulaHarness.Inputs INPUT = new RealSpellFormulaHarness.Inputs(
            5, 13, 4, 1.25, 1.5, 1, RealSpellFormulaHarness.Caster.PRESENT);
    private static final RealSpellFormulaHarness.Inputs LOWER_LEVEL = new RealSpellFormulaHarness.Inputs(
            3, 13, 4, 1.25, 1.5, 1, RealSpellFormulaHarness.Caster.PRESENT);

    @Test
    void explicitIntegerAndLongRadiusConversionsKeepIntegerDivision() throws Exception {
        assertRadius("int(5) / 2", 2f);
        assertRadius("long(5) / 2", 2f);
        assertRadius("int(5) / int(2)", 2f);
    }

    @Test
    void newIntegerConstantsInsideFunctionsDoNotInheritTheOriginalFloatingRadius() throws Exception {
        assertRadius("ceil(4 / 3)", 1f);
        assertRadius("5 + ceil(4 / 3)", 6f);
        assertRadius("min(int(5) / 2, 3)", 2f);
    }

    @Test
    void matchingTheOriginalRadiusStillPreservesItsNativeFloatingArithmetic() throws Exception {
        var fixture = fixture("holy/HealingCircleSpell", "getRadius");
        var formula = AutomaticFormulaExtractor.analyzeRuntime(fixture.owner(), fixture.method(), fixture.repository())
                .formula();
        assertEquals("5", formula.readableExpression());
        assertSame(fixture.original(), FormulaCompiler.transformRuntime(fixture.original(),
                Map.of(fixture.key(), formula.readableExpression()), fixture.repository()));
        assertSame(fixture.original(), FormulaCompiler.transformRuntime(fixture.original(),
                Map.of(fixture.key(), formula.expression()), fixture.repository()));
        assertRadius("5 / 2", 2.5f);
    }

    @Test
    void fixedIntegerLevelKeepsThePowerBoundaryAndIgnoresTheRuntimeLevel() throws Exception {
        String fixed = DAMAGE.replace("level", "5");
        assertDamage(fixed, INPUT, 766.25f);
        assertDamage(fixed, LOWER_LEVEL, 766.25f);
        assertDamage(DAMAGE, LOWER_LEVEL, 556.25f);
    }

    @Test
    void explicitlyConvertedIntegerLevelAlsoKeepsThePowerBoundary() throws Exception {
        String fixed = DAMAGE.replace("level", "int(5)");
        assertDamage(fixed, INPUT, 766.25f);
        assertDamage(fixed, LOWER_LEVEL, 766.25f);
    }

    @Test
    void integerPowerParametersCanBeReplacedWithFixedConstants() throws Exception {
        assertDamage(DAMAGE.replace("baseSpellPower", "13"), INPUT, 766.25f);
        assertDamage(DAMAGE.replace("spellPowerPerLevel", "4"), INPUT, 766.25f);
        var altered = new RealSpellFormulaHarness.Inputs(
                5, 23, 9, 1.25, 1.5, 1, RealSpellFormulaHarness.Caster.PRESENT);
        assertDamage(DAMAGE.replace("baseSpellPower", "13"), altered, 1291.25f);
        assertDamage(DAMAGE.replace("spellPowerPerLevel", "4"), altered, 1028.75f);
    }

    @Test
    void aFixedZeroOrNegativeLevelIsEvaluatedWithoutAnImplicitClamp() throws Exception {
        assertDamage(DAMAGE.replace("level", "0"), INPUT, 241.25f);
        assertDamage(DAMAGE.replace("level", "-2"), INPUT, 31.25f);
    }

    @Test
    void constantsAndLevelOnlyFormulasDoNotRetainRemovedPowerDependencies() throws Exception {
        var fixture = fixture("fire/FireballSpell", "getDamage");
        for (var input : List.of(INPUT, LOWER_LEVEL, new RealSpellFormulaHarness.Inputs(
                7, 23, 9, 3.25, 4.5, 7, RealSpellFormulaHarness.Caster.NULL))) {
            assertFormula(fixture, "99", input, 99f, 0);
            assertFormula(fixture, "level * 2 + 1", input, input.level() * 2f + 1f, 0);
        }
    }

    @Test
    void integerVariableConstantsRetainTheirSourceTypeAcrossTheFullIntegerRange() {
        for (String constant : List.of("5", "-5", "0", "2147483647", "-2147483648")) {
            var resolved = ReadableFormula.resolve("level", constant, Map.of("level", Type.INT_TYPE));
            assertEquals(ReadableFormula.Strategy.SOURCE_MAPPED, resolved.strategy(), constant);
            assertEquals(Type.INT_TYPE, FormulaCompiler.validateExpression(resolved.expression(), Map.of()), constant);
        }
    }

    @Test
    void longVariableConstantsRetainTheirSourceTypeIncludingBareSmallIntegers() {
        for (String constant : List.of("5", "-5", "0", "9223372036854775807L", "-9223372036854775808L")) {
            var resolved = ReadableFormula.resolve("level", constant, Map.of("level", Type.LONG_TYPE));
            assertEquals(ReadableFormula.Strategy.SOURCE_MAPPED, resolved.strategy(), constant);
            assertEquals(Type.LONG_TYPE, FormulaCompiler.validateExpression(resolved.expression(), Map.of()), constant);
        }
    }

    @Test
    void explicitFloatingConstantsAreNotImplicitlyTruncatedToAnIntegerVariableType() {
        for (Type type : List.of(Type.INT_TYPE, Type.LONG_TYPE)) {
            for (String constant : List.of("5.0", "5.5", "5f", "5d")) {
                var resolved = ReadableFormula.resolve("level", constant, Map.of("level", type));
                assertEquals(ReadableFormula.Strategy.NATURAL_DSL, resolved.strategy(), type + ": " + constant);
                assertEquals(constant, resolved.expression());
                Type expected = constant.endsWith("f") ? Type.FLOAT_TYPE : Type.DOUBLE_TYPE;
                assertEquals(expected, FormulaCompiler.validateExpression(resolved.expression(), Map.of()), constant);
            }
        }
    }

    @Test
    void constantsOutsideTheVariableRangeAreNotImplicitlyWrappedOrSaturated() {
        for (String constant : List.of("2147483648L", "-2147483649L")) {
            var resolved = ReadableFormula.resolve("level", constant, Map.of("level", Type.INT_TYPE));
            assertEquals(ReadableFormula.Strategy.NATURAL_DSL, resolved.strategy(), constant);
            assertEquals(constant, resolved.expression());
            assertEquals(Type.LONG_TYPE, FormulaCompiler.validateExpression(resolved.expression(), Map.of()), constant);
        }
        for (String constant : List.of("9223372036854775808L", "-9223372036854775809L")) {
            var resolved = ReadableFormula.resolve("level", constant, Map.of("level", Type.LONG_TYPE));
            assertEquals(ReadableFormula.Strategy.NATURAL_DSL, resolved.strategy(), constant);
            assertEquals(constant, resolved.expression());
            assertThrows(IllegalArgumentException.class,
                    () -> FormulaCompiler.validateExpression(resolved.expression(), Map.of()), constant);
        }
    }

    @Test
    void explicitLongAndNegativeFloatingZeroKeepTheirRequestedTypesWhenReplacingAnInteger() {
        for (String constant : List.of("5L", "-0.0")) {
            var resolved = ReadableFormula.resolve("level", constant, Map.of("level", Type.INT_TYPE));
            assertEquals(ReadableFormula.Strategy.NATURAL_DSL, resolved.strategy(), constant);
            assertEquals(constant, resolved.expression());
            Type expected = constant.endsWith("L") ? Type.LONG_TYPE : Type.DOUBLE_TYPE;
            assertEquals(expected, FormulaCompiler.validateExpression(resolved.expression(), Map.of()), constant);
        }
    }

    @Test
    void matchingExplicitIntegerConversionsKeepTheRequestedConversionSemantics() throws Exception {
        assertDamage(DAMAGE.replace("level", "int(5.5)"), INPUT, 766.25f);
        assertDamage(DAMAGE.replace("level", "int(5.5)"), LOWER_LEVEL, 766.25f);
        for (String constant : List.of("int(5.5)", "int(2147483648L)")) {
            var resolved = ReadableFormula.resolve("level", constant, Map.of("level", Type.INT_TYPE));
            assertEquals(ReadableFormula.Strategy.SOURCE_MAPPED, resolved.strategy(), constant);
            assertEquals(constant, resolved.expression());
            assertEquals(Type.INT_TYPE, FormulaCompiler.validateExpression(resolved.expression(), Map.of()), constant);
        }
    }

    @Test
    void changingTheLiteralTypeInsideAnIntegerCastIsNotMistakenForTheOriginalSource() throws Exception {
        for (String prefix : List.of("", "2f + ")) {
            String edited = prefix + "int(5.5d)";
            var resolved = ReadableFormula.resolve(prefix + "int(5.5f)", edited, Map.of());
            assertEquals(prefix.isEmpty() ? ReadableFormula.Strategy.SOURCE_MAPPED : ReadableFormula.Strategy.NATURAL_DSL,
                    resolved.strategy(), edited);
            assertEquals(edited, resolved.expression());
            assertNotEquals(prefix + "int(5.5f)", resolved.expression());
            Type expected = prefix.isEmpty() ? Type.INT_TYPE : Type.FLOAT_TYPE;
            assertEquals(expected, FormulaCompiler.validateExpression(resolved.expression(), Map.of()), edited);
        }
        assertIntegerFormula("castInput", "int(level * 0.99999999d)", 10);
    }

    @Test
    void replacingAConstantOnlyCompositeWithANewFunctionDoesNotRecoverOldFloatingCasts() {
        String edited = "ceil(4 / 3)";
        var resolved = ReadableFormula.resolve("(float(5) / float(2))", edited, Map.of());
        assertEquals(ReadableFormula.Strategy.NATURAL_DSL, resolved.strategy());
        assertEquals(edited, resolved.expression());
        assertEquals(Type.DOUBLE_TYPE, FormulaCompiler.validateExpression(resolved.expression(), Map.of()));
    }

    @Test
    void editingTheWholeConstantRadiusPreservesItsNativeFloatingType() throws Exception {
        var fixture = fixture("holy/HealingCircleSpell", "getRadius");
        var formula = AutomaticFormulaExtractor.analyzeRuntime(fixture.owner(), fixture.method(), fixture.repository())
                .formula();
        var resolved = ReadableFormula.resolve(formula.expression(), "7", Map.of());
        assertEquals(ReadableFormula.Strategy.SOURCE_MAPPED, resolved.strategy());
        assertEquals(Type.FLOAT_TYPE, FormulaCompiler.validateExpression(resolved.expression(), Map.of()));
        assertRadius("7", 7f);
    }

    @Test
    void aNegativeConstantReplacingANegatedVariableIsConsumedOnlyOnce() throws Exception {
        assertIntegerFormula("negative", "-5 + 2", -3);
    }

    @Test
    void nestedNegationAndBinaryContextsKeepTheRequestedConstantSigns() throws Exception {
        assertIntegerFormula("doubleNegative", "--5 + 2", 7);
        assertIntegerFormula("negativeProduct", "(-5) * 3 + 2", -13);
    }

    @Test
    void negativeConstantsBehindAHiddenFloatingCastKeepTheirRequestedSign() throws Exception {
        assertSignedFormula("negatedAfterCast", "-5 + 2", "F", -3f);
        assertSignedFormula("negatedAfterCast", "5 + 2", "F", 7f);
    }

    private static void assertIntegerFormula(String name, String expression, int expected) throws Exception {
        assertSignedFormula(name, expression, "I", expected);
    }

    private static void assertSignedFormula(String name, String expression, String returnDescriptor, Number expected)
            throws Exception {
        String resource = '/' + SignedFixture.class.getName().replace('.', '/') + ".class";
        byte[] original;
        try (var stream = SignedFixture.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            original = stream.readAllBytes();
        }
        byte[] changed = FormulaCompiler.transform(original, Map.of(name + "(I)" + returnDescriptor, expression));
        assertNotSame(original, changed);
        var loader = new ClassLoader(FormulaConstantMappingTest.class.getClassLoader()) {
            Class<?> defineFixture() {
                return defineClass(SignedFixture.class.getName(), changed, 0, changed.length);
            }
        };
        Class<?> compiled = loader.defineFixture();
        Object value = compiled.getMethod(name, int.class).invoke(compiled.getConstructor().newInstance(), 11);
        assertEquals(expected, value, expression);
    }

    private static void assertRadius(String expression, float expected) throws Exception {
        assertFormula(fixture("holy/HealingCircleSpell", "getRadius"), expression, INPUT, expected, 0);
    }

    private static void assertDamage(String expression, RealSpellFormulaHarness.Inputs input, float expected)
            throws Exception {
        assertFormula(fixture("fire/FireballSpell", "getDamage"), expression, input, expected, 1);
    }

    private static void assertFormula(Fixture fixture, String expression, RealSpellFormulaHarness.Inputs input,
                                      float expected, int expectedHooks) throws Exception {
        byte[] changed = FormulaCompiler.transformRuntime(fixture.original(),
                Map.of(fixture.key(), expression), fixture.repository());
        var transformed = new ClassNode();
        new ClassReader(changed).accept(transformed, 0);
        MethodNode method = transformed.methods.stream().filter(value -> value.name.equals(fixture.method().name))
                .findFirst().orElseThrow();
        int emitted = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOK)
                    && call.name.equals("applyPower")) emitted++;
        }
        assertEquals(expectedHooks, emitted, expression);
        var harness = new RealSpellFormulaHarness(JAR);
        harness.addDependency(HOOK, powerHook());
        Class<?> compiled = harness.load(fixture.owner(), changed, List.of(fixture.method()));
        float actual = harness.invoke(compiled, harness.create(compiled, input), fixture.method(), input).floatValue();
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), expression);
        Class<?> hook = compiled.getClassLoader().loadClass(HOOK.replace('/', '.'));
        assertEquals(expectedHooks, hook.getField("calls").getInt(null), expression);
    }

    private static byte[] powerHook() throws Exception {
        var method = FormulaPowerRestorationTest.class.getDeclaredMethod("powerHook");
        method.setAccessible(true);
        return (byte[]) method.invoke(null);
    }

    private static Fixture fixture(String spell, String methodName) throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        var owner = repository.classNode("io/redspace/ironsspellbooks/spells/" + spell);
        var method = owner.methods.stream().filter(value -> value.name.equals(methodName))
                .findFirst().orElseThrow();
        return new Fixture(repository, owner, method, RealSpellFormulaHarness.bytes(owner));
    }

    private record Fixture(FormulaClassRepository repository, ClassNode owner, MethodNode method, byte[] original) {
        String key() {
            return method.name + method.desc;
        }
    }

    public static class SignedFixture {
        public int castInput(int level) {
            return (int) (level * 0.99999999f);
        }

        public int negative(int level) {
            return -level + 2;
        }

        public int doubleNegative(int level) {
            return -(-level) + 2;
        }

        public int negativeProduct(int level) {
            return -level * 3 + 2;
        }

        public float negatedAfterCast(int level) {
            return -(float) level + 2f;
        }
    }
}
