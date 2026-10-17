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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class FormulaCastStructureTest {
    private static final Path JAR = Path.of("libs/ironsspells-3.16.1.jar");
    private static final String HOOK = "com/example/spell_compiler/formula/FormulaRuntimeHooks";

    @Test
    void replacingAnIntegerCastInputKeepsUnchangedFloatingPointArithmetic() throws Exception {
        assertFixture("castInDivision", "(int(5) + 2) / 2", 3.5f);
    }

    @Test
    void replacingANegatedCastInputKeepsItsSignAndFloatingPointArithmetic() throws Exception {
        assertFixture("negativeWithinCast", "(int(-5) + 2) / 2", -1.5f);
    }

    @Test
    void replacingTheInputOfANegatedCastKeepsFloatingPointArithmetic() throws Exception {
        assertFixture("negativeOutsideCast", "(-int(5) + 2) / 2", -1.5f);
    }

    @Test
    void replacingALongCastInputKeepsUnchangedFloatingPointArithmetic() throws Exception {
        assertFixture("longInDivision", "(long(5) + 2) / 2", 3.5f);
    }

    @Test
    void replacingAFloatCastInputKeepsUnchangedFloatingPointArithmetic() throws Exception {
        assertFixture("floatInDivision", "(float(5) + 2) / 2", 3.5f);
    }

    @Test
    void replacingACastInputInTheDenominatorKeepsFloatingPointDivision() throws Exception {
        assertFixture("castInDenominator", "17 / (int(5) + 2)", 17f / 7f);
    }

    @Test
    void replacingNestedCastInputsKeepsUnchangedFloatingPointArithmetic() throws Exception {
        assertFixture("nestedCast", "(int(long(5)) + 2) / 2", 3.5f);
    }

    @Test
    void replacingAFloatVariableInsideAnIntegerCastKeepsFloatingPointArithmetic() throws Exception {
        assertFixture("floatToInt", "(int(5) + 2) / 2", 3.5f);
    }

    @Test
    void replacingACastInputInsideMinimumKeepsUnchangedFloatingPointArithmetic() throws Exception {
        assertFixture("insideMinimum", "(min(int(5), 7) + 2) / 2", 3.5f);
    }

    @Test
    void replacingAnIntegerVariableStillKeepsUnchangedFloatingPointArithmetic() throws Exception {
        assertFixture("integerControl", "(5 + 2) / 2", 3.5f);
    }

    @Test
    void replacingANegatedIntegerVariableDoesNotDuplicateItsSign() throws Exception {
        assertFixture("unaryControl", "(-5 + 2) / 2", -1.5f);
    }

    @Test
    void everyFixtureDefaultRetainsTheExactOriginalByteArray() throws Exception {
        byte[] original = fixtureBytes();
        ClassNode owner = owner(original);
        Map<String, String> defaults = new LinkedHashMap<>();
        for (MethodNode method : owner.methods) {
            if (method.name.equals("<init>")) continue;
            var analysis = AutomaticFormulaExtractor.analyze(owner, method);
            assertTrue(analysis.supported(), method.name + ": " + analysis.rejection());
            defaults.put(method.name + method.desc, analysis.formula().readableExpression());
        }
        assertEquals(11, defaults.size());
        assertSame(original, FormulaCompiler.transform(original, defaults));
    }

    @Test
    void negatingAnIntegerConstantCastKeepsFireballsNativePowerBoundary() throws Exception {
        assertFireball("-int(5)");
    }

    @Test
    void parenthesizingANegatedIntegerConstantCastKeepsFireballsNativePowerBoundary() throws Exception {
        assertFireball("-(int(5))");
    }

    @Test
    void castingANegativeIntegerConstantKeepsFireballsNativePowerBoundary() throws Exception {
        assertFireball("int(-5)");
    }

    @Test
    void constantSubstitutionMatrixMatchesTheUnchangedBytecodeWithTheSameFieldValues() throws Exception {
        ClassNode owner = owner(fixtureBytes());
        Map<String, String> casts = Map.of("value", "double", "held", "float", "count", "int");
        int cases = 0;
        for (MethodNode method : owner.methods) {
            if (method.name.equals("<init>")) continue;
            var formula = AutomaticFormulaExtractor.analyze(owner, method).formula();
            var fields = casts.keySet().stream().filter(formula.variables()::containsKey).toList();
            assertEquals(1, fields.size(), method.name);
            String field = fields.get(0);
            Map<String, Type> types = new LinkedHashMap<>();
            formula.variables().forEach((name, variable) -> types.put(name, variable.type()));
            for (int fixed : List.of(-5, 0, 5, 13)) {
                var reference = new MappingFixture();
                reference.value = fixed;
                reference.held = fixed;
                reference.count = fixed;
                float expected = ((Number) MappingFixture.class.getMethod(method.name, int.class)
                        .invoke(reference, 3)).floatValue();
                for (String replacement : List.of(Integer.toString(fixed),
                        casts.get(field) + '(' + fixed + ')', '-' + casts.get(field) + '(' + -fixed + ')')) {
                    String expression = formula.readableExpression().replaceAll("\\b" + field + "\\b", replacement);
                    var resolved = ReadableFormula.resolve(formula.expression(), expression, types);
                    assertEquals(ReadableFormula.Strategy.SOURCE_MAPPED, resolved.strategy(),
                            method.name + ": " + expression);
                    assertFixture(method.name, expression, expected);
                    cases++;
                }
            }
        }
        assertEquals(132, cases);
    }

    @Test
    void regroupingInsideACeilArgumentRetainsThePlayersParentheses() throws Exception {
        assertGroupedFixture("rounded", "ceil(level * (0.1 * 100))", 30f);
    }

    @Test
    void regroupingInsideNestedIntegerAndMinimumArgumentsRetainsThePlayersParentheses() throws Exception {
        assertGroupedFixture("nestedMinimum", "min(int(ceil(level * (0.1 * 100))), 50) + 0.5", 30.5f);
    }

    private static void assertFixture(String method, String expression, float expected) throws Exception {
        byte[] original = fixtureBytes();
        byte[] changed = FormulaCompiler.transform(original, Map.of(method + "(I)F", expression));
        assertNotSame(original, changed, expression);
        var loader = new ClassLoader(FormulaCastStructureTest.class.getClassLoader()) {
            private Class<?> defineFixture() {
                return defineClass(MappingFixture.class.getName(), changed, 0, changed.length);
            }
        };
        Object instance = loader.defineFixture().getConstructor().newInstance();
        for (int level : List.of(3, 5)) {
            float actual = ((Number) instance.getClass().getMethod(method, int.class).invoke(instance, level))
                    .floatValue();
            assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual),
                    method + ": " + expression + ", level=" + level);
        }
    }

    private static void assertFireball(String constant) throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        var owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell");
        var damage = owner.methods.stream().filter(method -> method.name.equals("getDamage"))
                .findFirst().orElseThrow();
        String expression = "5 + 7 * (baseSpellPower + spellPowerPerLevel * (" + constant + " - 1))"
                + " * spell_power * school_spell_power * power_multiplier";
        byte[] original = RealSpellFormulaHarness.bytes(owner);
        byte[] changed = FormulaCompiler.transformRuntime(original,
                Map.of(damage.name + damage.desc, expression), repository);
        assertNotSame(original, changed, expression);
        var rewrittenDamage = owner(changed).methods.stream().filter(method -> method.name.equals("getDamage"))
                .findFirst().orElseThrow();
        int hooks = 0;
        for (var instruction : rewrittenDamage.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOK)
                    && call.name.equals("applyPower")) hooks++;
        }
        assertEquals(1, hooks, expression);
        var powerHook = FormulaPowerRestorationTest.class.getDeclaredMethod("powerHook");
        powerHook.setAccessible(true);
        for (int level : List.of(5, 3)) {
            var input = new RealSpellFormulaHarness.Inputs(level, 13, 4, 1.25, 1.5, 1,
                    RealSpellFormulaHarness.Caster.PRESENT);
            var harness = new RealSpellFormulaHarness(JAR);
            harness.addDependency(HOOK, (byte[]) powerHook.invoke(null));
            Class<?> compiled = harness.load(owner, changed, List.of(damage));
            float actual = harness.invoke(compiled, harness.create(compiled, input), damage, input).floatValue();
            assertEquals(Float.floatToRawIntBits(-283.75f), Float.floatToRawIntBits(actual),
                    expression + ", runtime level=" + level);
            assertEquals(1, compiled.getClassLoader().loadClass(HOOK.replace('/', '.')).getField("calls").getInt(null),
                    expression);
        }
    }

    private static void assertGroupedFixture(String methodName, String expression, float expected) throws Exception {
        String path = '/' + CallGroupingFixture.class.getName().replace('.', '/') + ".class";
        byte[] original;
        try (var stream = CallGroupingFixture.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            original = stream.readAllBytes();
        }
        var owner = owner(original);
        var method = owner.methods.stream().filter(value -> value.name.equals(methodName)).findFirst().orElseThrow();
        var formula = AutomaticFormulaExtractor.analyze(owner, method).formula();
        var resolved = ReadableFormula.resolve(formula.expression(), expression, Map.of("level", Type.INT_TYPE));
        assertEquals(ReadableFormula.Strategy.NATURAL_DSL, resolved.strategy(), expression);
        assertEquals(expression, resolved.expression());
        byte[] changed = FormulaCompiler.transform(original, Map.of(methodName + "(I)F", expression));
        var loader = new ClassLoader(FormulaCastStructureTest.class.getClassLoader()) {
            private Class<?> defineFixture() {
                return defineClass(CallGroupingFixture.class.getName(), changed, 0, changed.length);
            }
        };
        Class<?> compiled = loader.defineFixture();
        float actual = ((Number) compiled.getMethod(methodName, int.class)
                .invoke(compiled.getConstructor().newInstance(), 3)).floatValue();
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), expression);
    }

    private static byte[] fixtureBytes() throws Exception {
        String path = '/' + MappingFixture.class.getName().replace('.', '/') + ".class";
        try (var stream = MappingFixture.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return stream.readAllBytes();
        }
    }

    private static ClassNode owner(byte[] bytes) {
        var owner = new ClassNode();
        new ClassReader(bytes).accept(owner, 0);
        return owner;
    }

    public static class MappingFixture {
        public double value = 11.25;
        public float held = 11.25f;
        public int count = 11;

        public float castInDivision(int level) {
            return ((int) value + 2f) / 2f;
        }

        public float negativeWithinCast(int level) {
            return ((int) -value + 2f) / 2f;
        }

        public float negativeOutsideCast(int level) {
            return (-(int) value + 2f) / 2f;
        }

        public float longInDivision(int level) {
            return ((long) value + 2f) / 2f;
        }

        public float floatInDivision(int level) {
            return ((float) value + 2f) / 2f;
        }

        public float castInDenominator(int level) {
            return 17f / ((int) value + 2f);
        }

        public float nestedCast(int level) {
            return ((int) (long) value + 2f) / 2f;
        }

        public float floatToInt(int level) {
            return ((int) held + 2f) / 2f;
        }

        public float insideMinimum(int level) {
            return (Math.min((int) value, 7) + 2f) / 2f;
        }

        public float integerControl(int level) {
            return (count + 2f) / 2f;
        }

        public float unaryControl(int level) {
            return (-count + 2f) / 2f;
        }
    }

    public static class CallGroupingFixture {
        public float rounded(int level) {
            return (float) Math.ceil(level * 0.1f * 100f);
        }

        public float nestedMinimum(int level) {
            return Math.min((int) Math.ceil(level * 0.1f * 100f), 50) + 0.5f;
        }
    }
}
