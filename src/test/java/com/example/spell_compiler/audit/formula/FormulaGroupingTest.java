package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;
import com.example.spell_compiler.formula.ReadableFormula;
import com.example.spell_compiler.formula.SpellFormulaCatalog;
import com.example.spell_compiler.formula.SpellFormulaFiles;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public final class FormulaGroupingTest {
    @Test
    void readableDefaultsAndRedundantParenthesesKeepTheExactOriginalBytes() throws Exception {
        byte[] original = fixtureBytes();
        var owner = owner(original);
        Map<String, String> defaults = new LinkedHashMap<>();
        for (var method : owner.methods) {
            if (method.name.equals("<init>")) continue;
            var analysis = AutomaticFormulaExtractor.analyze(owner, method);
            assertTrue(analysis.supported(), method.name + ": " + analysis.rejection());
            defaults.put(method.name + method.desc, analysis.formula().readableExpression());
        }
        assertSame(original, FormulaCompiler.transform(original, defaults));
        defaults.replaceAll((key, expression) -> " (( (" + expression + ") )) ");
        assertSame(original, FormulaCompiler.transform(original, defaults));
        assertFloatBits(0f, new GroupingFixture().rightAssociated(3));
    }

    @Test
    void multiplicationRegroupingIsNotMistakenForAnUnchangedFormula() throws Exception {
        var baseline = new GroupingFixture();
        assertNotEquals(Float.floatToRawIntBits(30f), Float.floatToRawIntBits(baseline.charge(3)));
        Object transformed = transformed("charge", "level * (0.1 * 100)");
        assertFloatBits(30f, invoke(transformed, "charge", 3));
    }

    @Test
    void coefficientChangesAndMultiplicationRegroupingAreBothApplied() throws Exception {
        Object transformed = transformed("charge", "level * (0.1 * 200)");
        assertFloatBits(60f, invoke(transformed, "charge", 3));
    }

    @Test
    void wrappingARegroupedFormulaDoesNotRestoreTheOldInnerAssociation() throws Exception {
        Object transformed = transformed("charge", "ceil(level * (0.1 * 100))");
        assertFloatBits(30f, invoke(transformed, "charge", 3));
    }

    @Test
    void floatingPointAdditionHonorsTheRequestedRightHandGroup() throws Exception {
        var baseline = new GroupingFixture();
        assertFloatBits(3.14f, baseline.sum(3));
        Object transformed = transformed("sum", "a + (b + c)");
        assertFloatBits(0f, invoke(transformed, "sum", 3));
    }

    @Test
    void flattenedCoefficientEditsRetainTheNativeRightAssociatedPrecision() throws Exception {
        var owner = owner(fixtureBytes());
        var formula = AutomaticFormulaExtractor.analyze(owner, method(owner, "rightAssociated")).formula();
        assertEquals("a + b + c", formula.readableExpression());
        Object transformed = transformed("rightAssociated", "a + b + 4");
        var baseline = new GroupingFixture();
        assertNotEquals(Float.floatToRawIntBits(baseline.a + baseline.b + 4f),
                Float.floatToRawIntBits(baseline.a + (baseline.b + 4f)));
        assertFloatBits(baseline.a + (baseline.b + 4f), invoke(transformed, "rightAssociated", 3));
    }

    @Test
    void explicitRightHandGroupingIsPreservedEvenWhenTheNativeSourceWasRightAssociated() throws Exception {
        var owner = owner(fixtureBytes());
        var formula = AutomaticFormulaExtractor.analyze(owner, method(owner, "rightAssociated")).formula();
        String expression = "a + (b + 4)";
        Map<String, Type> types = Map.of("a", Type.FLOAT_TYPE, "b", Type.FLOAT_TYPE, "c", Type.FLOAT_TYPE);
        var resolved = ReadableFormula.resolve(formula.expression(), expression, types);
        assertEquals(ReadableFormula.Strategy.NATURAL_DSL, resolved.strategy());
        assertEquals(expression, resolved.expression());
        Object transformed = transformed("rightAssociated", expression);
        var baseline = new GroupingFixture();
        assertFloatBits(baseline.a + (baseline.b + 4f), invoke(transformed, "rightAssociated", 3));
    }

    @Test
    void mixedSamePrecedenceDefaultsAndCoefficientEditsRetainNativePrecision() throws Exception {
        byte[] original = fixtureBytes();
        var owner = owner(original);
        var formula = AutomaticFormulaExtractor.analyze(owner, method(owner, "mixed")).formula();
        assertEquals("j * k / l * m", formula.readableExpression());
        assertSame(original, FormulaCompiler.transform(original, Map.of("mixed(I)F", formula.readableExpression())));
        Object transformed = transformed("mixed", "j * k / l * 0.2");
        var baseline = new GroupingFixture();
        assertTrue(Float.isInfinite(baseline.j * baseline.k / baseline.l * 0.2f));
        float expected = baseline.j * ((baseline.k / baseline.l) * 0.2f);
        assertTrue(Float.isFinite(expected));
        assertFloatBits(expected, invoke(transformed, "mixed", 3));
    }

    @Test
    void explicitGroupingChangesMixedSamePrecedenceArithmetic() throws Exception {
        var baseline = new GroupingFixture();
        Object transformed = transformed("mixed", "j * (k / (l * m))");
        float expected = baseline.j * (baseline.k / (baseline.l * baseline.m));
        assertNotEquals(Float.floatToRawIntBits(baseline.mixed(3)), Float.floatToRawIntBits(expected));
        assertFloatBits(expected, invoke(transformed, "mixed", 3));
    }

    @Test
    void multiplicationRegroupingInsideAnAdditionKeepsTheOtherBranch() throws Exception {
        var baseline = new GroupingFixture();
        Object transformed = transformed("nestedProducts", "d * (e * f) + g * h * i");
        float expected = baseline.d * (baseline.e * baseline.f) + baseline.g * baseline.h * baseline.i;
        assertNotEquals(Float.floatToRawIntBits(baseline.nestedProducts(3)), Float.floatToRawIntBits(expected));
        assertFloatBits(expected, invoke(transformed, "nestedProducts", 3));
    }

    @Test
    void additionRegroupingInsideAMultiplicationKeepsTheOtherBranch() throws Exception {
        var baseline = new GroupingFixture();
        Object transformed = transformed("nestedSums", "(a + (b + c)) * (d + e + f)");
        float expected = (baseline.a + (baseline.b + baseline.c)) * (baseline.d + baseline.e + baseline.f);
        assertNotEquals(Float.floatToRawIntBits(baseline.nestedSums(3)), Float.floatToRawIntBits(expected));
        assertFloatBits(expected, invoke(transformed, "nestedSums", 3));
    }

    @Test
    void actualChargeCatalogKeepsAllRegroupedPlayerOverrides() throws Exception {
        var catalog = SpellFormulaCatalog.load(getClass().getClassLoader());
        var spell = catalog.spells().stream().filter(value -> value.id().equals("irons_spellbooks:charge"))
                .findFirst().orElseThrow();
        var method = spell.formulas().stream().filter(value -> value.alias().equals("percent_attack_damage"))
                .findFirst().orElseThrow();
        assertTrue(SpellFormulaFiles.parse(spell, SpellFormulaFiles.preview(spell).toString()).isEmpty());
        for (String expression : new String[]{"level * (0.1 * 100)", "level * (0.1 * 200)",
                "ceil(level * (0.1 * 100))"}) {
            var json = SpellFormulaFiles.preview(spell);
            json.getAsJsonObject("formulas").addProperty(method.alias(), expression);
            var parsed = SpellFormulaFiles.parse(spell, json.toString());
            assertEquals(Map.of(method.key(), expression), parsed.formulas(), expression);
        }
    }

    @Test
    void regroupingACompletePowerSubtreeRetainsExactlyOneRuntimeModifierBoundary() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs/ironsspells-3.16.1.jar"));
        var owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell");
        var damage = method(owner, "getDamage");
        var formula = AutomaticFormulaExtractor.analyzeRuntime(owner, damage, repository).formula();
        assertEquals(1, formula.nativePowers().size());
        byte[] original = RealSpellFormulaHarness.bytes(owner);
        String expression = "5 + 5 * ((baseSpellPower + spellPowerPerLevel * (level - 1))"
                + " * (spell_power * (school_spell_power * power_multiplier)))";
        byte[] changed = FormulaCompiler.transformRuntime(original, Map.of(damage.name + damage.desc, expression), repository);
        assertNotSame(original, changed);
        int hooks = 0;
        for (var instruction : method(owner(changed), "getDamage").instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/example/spell_compiler/formula/FormulaRuntimeHooks")
                    && call.name.equals("applyPower")) hooks++;
        }
        assertEquals(1, hooks);
    }

    @Test
    void regroupingAnotherBranchKeepsAnUneditedNativePowerBoundaryAndItsCasts() {
        String source = "(__native_power_0(float(double(float(spell_power))*school_spell_power))"
                + " + (float(a)*(float(b)*float(c))))";
        String edited = "spell_power * school_spell_power + a * (b * c)";
        Map<String, Type> types = Map.of("spell_power", Type.DOUBLE_TYPE, "school_spell_power", Type.DOUBLE_TYPE,
                "a", Type.FLOAT_TYPE, "b", Type.FLOAT_TYPE, "c", Type.FLOAT_TYPE);
        String resolved = ReadableFormula.resolve(source, edited, types).expression().replaceAll("\\s+", "");
        assertEquals(1, resolved.split("__native_power_0", -1).length - 1);
        assertTrue(resolved.contains("__native_power_0(float((double(float(spell_power))*school_spell_power)))"), resolved);
        assertTrue(resolved.contains("+(a*(b*c))"), resolved);
    }

    private static byte[] fixtureBytes() throws Exception {
        String path = '/' + GroupingFixture.class.getName().replace('.', '/') + ".class";
        try (var stream = GroupingFixture.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return stream.readAllBytes();
        }
    }

    private static ClassNode owner(byte[] bytes) {
        var owner = new ClassNode();
        new ClassReader(bytes).accept(owner, 0);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
    }

    private static Object transformed(String method, String expression) throws Exception {
        byte[] original = fixtureBytes();
        byte[] changed = FormulaCompiler.transform(original, Map.of(method + "(I)F", expression));
        assertNotSame(original, changed, method + ": " + expression);
        var loader = new ClassLoader(FormulaGroupingTest.class.getClassLoader()) {
            private Class<?> defineFixture() {
                return defineClass(GroupingFixture.class.getName(), changed, 0, changed.length);
            }
        };
        return loader.defineFixture().getConstructor().newInstance();
    }

    private static float invoke(Object receiver, String name, int level) throws Exception {
        return ((Number) receiver.getClass().getMethod(name, int.class).invoke(receiver, level)).floatValue();
    }

    private static void assertFloatBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    public static class GroupingFixture {
        public float a = 1e20f;
        public float b = -1e20f;
        public float c = 3.14f;
        public float d = 3f;
        public float e = 0.1f;
        public float f = 100f;
        public float g = 3f;
        public float h = 0.1f;
        public float i = 100f;
        public float j = 1e20f;
        public float k = 1e20f;
        public float l = 1e20f;
        public float m = 0.1f;

        public GroupingFixture() {}

        public float charge(int level) {
            return level * 0.1f * 100f;
        }

        public float sum(int level) {
            return a + b + c;
        }

        public float rightAssociated(int level) {
            return a + (b + c);
        }

        public float nestedProducts(int level) {
            return d * e * f + g * h * i;
        }

        public float nestedSums(int level) {
            return (a + b + c) * (d + e + f);
        }

        public float mixed(int level) {
            return j * ((k / l) * m);
        }
    }
}
