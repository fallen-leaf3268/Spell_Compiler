package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormulaExtractionTest {
    @Test
    void expandsPrivateNumericHelpersUsingActualArguments() throws Exception {
        var formula = analyze("expanded", "(I)I");
        assertTrue(formula.supported(), formula.rejection());
        assertEquals(Type.INT_TYPE, formula.formula().returnType());
        assertTrue(formula.formula().variables().containsKey("level"));
        assertFalse(formula.formula().expression().contains("multiply"));
        assertFalse(formula.formula().expression().contains("adjust"));
    }

    @Test
    void acceptsPureNumericLocalVariables() throws Exception {
        var formula = analyze("withLocals", "(I)F");
        assertTrue(formula.supported(), formula.rejection());
        assertEquals(Type.FLOAT_TYPE, formula.formula().returnType());
        assertTrue(formula.formula().expression().contains("level"));
    }

    @Test
    void exportsWhitelistedMathCallsWithTheirNumericTypes() throws Exception {
        var formula = analyze("bounded", "(I)F");
        assertTrue(formula.supported(), formula.rejection());
        assertEquals(Type.FLOAT_TYPE, formula.formula().returnType());
        assertTrue(formula.formula().expression().contains("max("));
        assertTrue(formula.formula().expression().contains("abs("));
    }

    @Test
    void rejectsRecursiveCalls() throws Exception {
        assertFalse(analyze("recursive", "(I)I").supported());
    }

    @Test
    void rejectsFieldWrites() throws Exception {
        assertFalse(analyze("mutating", "(I)I").supported());
    }

    @Test
    void recompiledHelpersAndLocalsMatchTheirOriginalExecution() throws Exception {
        ClassNode owner = node(Fixture.class);
        var repository = FormulaClassRepository.of(owner);
        var overrides = new LinkedHashMap<String, String>();
        for (String name : new String[]{"expanded", "withLocals", "bounded", "crossClass"}) {
            MethodNode method = owner.methods.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
            var analysis = AutomaticFormulaExtractor.analyze(owner, method, repository);
            assertTrue(analysis.supported(), analysis.rejection());
            overrides.put(method.name + method.desc, analysis.formula().expression());
        }
        Class<?> transformed = new Loader().define(FormulaCompiler.compileForVerification(bytes(Fixture.class), overrides, repository));
        for (int level : new int[]{-5, 0, 1, 2, 7, 10000}) {
            assertEquals(Fixture.expanded(level), transformed.getMethod("expanded", int.class).invoke(null, level));
            assertEquals(Fixture.withLocals(level), transformed.getMethod("withLocals", int.class).invoke(null, level));
            assertEquals(Fixture.bounded(level), transformed.getMethod("bounded", int.class).invoke(null, level));
            assertEquals(Fixture.crossClass(level), transformed.getMethod("crossClass", int.class).invoke(null, level));
        }
    }

    @Test
    void rejectsUnprovenStaticInitializationAndUnknownVirtualObjects() throws Exception {
        assertFalse(analyze("initializing", "(I)I").supported());
        assertFalse(analyze("initializingParent", "(I)I").supported());
        assertFalse(analyze("unknownReceiver", "(L" + Type.getInternalName(Receiver.class) + ";I)I").supported());
    }

    @Test
    void nativePowerReadsBecomeIndependentTypedInputs() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs", "ironsspells-3.16.1.jar"));
        ClassNode owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/FireballSpell");
        MethodNode method = owner.methods.stream().filter(value -> value.name.equals("getDamage")).findFirst().orElseThrow();
        var analysis = AutomaticFormulaExtractor.analyze(owner, method, repository);
        assertTrue(analysis.supported(), analysis.rejection());
        var formula = analysis.formula();
        for (String name : new String[]{"level", "baseSpellPower", "spellPowerPerLevel", "spell_power", "school_spell_power", "power_multiplier"})
            assertTrue(formula.variables().containsKey(name), name);
        assertEquals(Type.DOUBLE_TYPE, formula.variables().get("spell_power").type());
        assertEquals(Type.DOUBLE_TYPE, formula.variables().get("school_spell_power").type());
        assertEquals(Type.FLOAT_TYPE, formula.variables().get("power_multiplier").type());
        assertFalse(formula.variables().containsKey("power_scale"));
        assertTrue(formula.requiresFrames());
        assertFalse(formula.variables().values().stream().flatMap(value -> value.instructions().stream())
                .anyMatch(value -> value instanceof MethodInsnNode call && call.name.equals("getSpellPower")));
        int config = callIndex(formula, "getSpellConfigValue");
        int global = callIndex(formula, "m_21133_");
        int school = callIndex(formula, "getPowerFor");
        assertTrue(config >= 0 && global > config && school > global);
    }

    @Test
    void explicitNullCasterUsesNativeDefaultWithoutPlayerInputs() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs", "ironsspells-3.16.1.jar"));
        ClassNode owner = new ClassNode();
        owner.version = Opcodes.V17;
        owner.access = Opcodes.ACC_PUBLIC;
        owner.name = "formula/NullPowerFixture";
        owner.superName = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "damage", "(I)F", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner.superName, "getSpellPower",
                "(ILnet/minecraft/world/entity/Entity;)F", false));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        method.maxLocals = 2;
        owner.methods.add(method);
        var analysis = AutomaticFormulaExtractor.analyze(owner, method, repository);
        assertTrue(analysis.supported(), analysis.rejection());
        assertTrue(analysis.formula().variables().containsKey("power_multiplier"));
        assertFalse(analysis.formula().variables().containsKey("spell_power"));
        assertFalse(analysis.formula().variables().containsKey("school_spell_power"));
    }

    @Test
    void changedNativeNullControlFlowIsRejected() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs", "ironsspells-3.16.1.jar"));
        ClassNode base = repository.classNode("io/redspace/ironsspellbooks/api/spells/AbstractSpell");
        MethodNode multiplier = base.methods.stream().filter(value -> value.name.equals("getEntityPowerMultiplier")).findFirst().orElseThrow();
        boolean replaced = false;
        for (var instruction : multiplier.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.FRETURN) {
                multiplier.instructions.set(instruction.getPrevious(), new LdcInsnNode(99f));
                replaced = true;
                break;
            }
        }
        assertTrue(replaced);
        ClassNode owner = repository.classNode("io/redspace/ironsspellbooks/spells/fire/MagmaBombSpell");
        MethodNode method = owner.methods.stream().filter(value -> value.name.equals("getRadius")).findFirst().orElseThrow();
        var analysis = AutomaticFormulaExtractor.analyze(owner, method, repository);
        assertFalse(analysis.supported());
        assertTrue(analysis.rejection().contains("Native power"));
    }

    @Test
    void unusedReferenceArgumentsStillPerformTheirOriginalCast() throws Exception {
        ClassNode owner = node(Fixture.class);
        var repository = FormulaClassRepository.of(owner);
        MethodNode method = owner.methods.stream().filter(value -> value.name.equals("unusedReference")).findFirst().orElseThrow();
        var analysis = AutomaticFormulaExtractor.analyze(owner, method, repository);
        assertTrue(analysis.supported(), analysis.rejection());
        Class<?> transformed = new Loader().define(FormulaCompiler.compileForVerification(bytes(Fixture.class),
                Map.of(method.name + method.desc, analysis.formula().expression()), repository));
        var error = assertThrows(InvocationTargetException.class,
                () -> transformed.getMethod("unusedReference", Object.class, int.class).invoke(null, new Object(), 1));
        assertInstanceOf(ClassCastException.class, error.getCause());
    }

    @Test
    void earlierDivisionRunsBeforeLaterStaticFieldInitialization() throws Exception {
        ClassNode owner = node(Fixture.class);
        var repository = FormulaClassRepository.of(owner);
        MethodNode method = owner.methods.stream().filter(value -> value.name.equals("orderedArguments")).findFirst().orElseThrow();
        var analysis = AutomaticFormulaExtractor.analyze(owner, method, repository);
        assertTrue(analysis.supported(), analysis.rejection());
        Class<?> transformed = new Loader().define(FormulaCompiler.compileForVerification(bytes(Fixture.class),
                Map.of(method.name + method.desc, analysis.formula().expression()), repository));
        var error = assertThrows(InvocationTargetException.class,
                () -> transformed.getMethod("orderedArguments", int.class).invoke(null, 0));
        assertInstanceOf(ArithmeticException.class, error.getCause());
    }

    @Test
    void virtualOverridesUseOriginalCodeOutsideTheProvenReceiverClass() throws Exception {
        ClassNode owner = node(Fixture.class);
        var repository = FormulaClassRepository.of(owner);
        MethodNode method = owner.methods.stream().filter(value -> value.name.equals("dynamic")).findFirst().orElseThrow();
        var analysis = AutomaticFormulaExtractor.analyze(owner, method, repository);
        assertTrue(analysis.supported(), analysis.rejection());
        Loader loader = new Loader();
        Class<?> transformed = loader.define(FormulaCompiler.compileForVerification(bytes(Fixture.class),
                Map.of(method.name + method.desc, analysis.formula().expression()), repository));
        Class<?> derived = loader.define(bytes(Derived.class));
        assertEquals(11, transformed.getMethod("dynamic", int.class).invoke(transformed.getConstructor().newInstance(), 5));
        assertEquals(16, derived.getMethod("dynamic", int.class).invoke(derived.getConstructor().newInstance(), 5));
    }

    private static int callIndex(AutomaticFormulaExtractor.Formula formula, String name) {
        for (int index = 0; index < formula.inputPrelude().size(); index++)
            if (formula.inputPrelude().get(index) instanceof MethodInsnNode call && call.name.equals(name)) return index;
        return -1;
    }

    private static ClassNode node(Class<?> type) throws Exception {
        ClassNode owner = new ClassNode();
        new ClassReader(bytes(type)).accept(owner, 0);
        return owner;
    }

    private static byte[] bytes(Class<?> type) throws Exception {
        try (var stream = type.getResourceAsStream('/' + Type.getInternalName(type) + ".class")) {
            return java.util.Objects.requireNonNull(stream).readAllBytes();
        }
    }

    private static class Loader extends ClassLoader {
        private Loader() { super(FormulaExtractionTest.class.getClassLoader()); }
        private Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }

    private static AutomaticFormulaExtractor.Analysis analyze(String name, String descriptor) throws Exception {
        ClassNode owner = new ClassNode();
        new ClassReader(Fixture.class.getName()).accept(owner, 0);
        var method = owner.methods.stream()
                .filter(value -> value.name.equals(name) && value.desc.equals(descriptor))
                .findFirst().orElseThrow();
        return AutomaticFormulaExtractor.analyze(owner, method);
    }

    public static class Fixture {
        public static int changed;

        public static int expanded(int level) {
            int shifted = level + 1;
            return multiply(shifted, 2) + adjust(level);
        }

        private static int multiply(int value, int factor) {
            return value * factor;
        }

        private static int adjust(int value) {
            return value - 1;
        }

        public static float withLocals(int level) {
            float half = level * 0.5f;
            return half + 2f;
        }

        public static float bounded(int level) {
            return Math.max(1f, Math.abs(level - 4f));
        }

        public static int crossClass(int level) { return Helper.scale(level + 1); }
        public static int initializing(int level) { return InitializedHelper.scale(level); }
        public static int initializingParent(int level) { return ChildHelper.scale(level); }
        public static int unknownReceiver(Receiver receiver, int level) { return receiver.scale(level); }
        public static int unusedReference(Object value, int level) { return discard((Receiver) value, level); }
        private static int discard(Receiver ignored, int level) { return level + 1; }
        public static int orderedArguments(int level) { return multiply(1 / level, DangerousInitializer.value); }
        public int dynamic(int level) { return overridable(level) + 1; }
        public int overridable(int level) { return level * 2; }

        public static int recursive(int level) {
            return recursive(level);
        }

        public static int mutating(int level) {
            changed = level;
            return level;
        }
    }

    public static class Helper {
        public static int scale(int value) { return value * 3; }
    }

    public static class InitializedHelper {
        public static int factor = 3;
        public static int scale(int value) { return value * factor; }
    }

    public static class InitializedParent { public static int factor = 3; }
    public static class ChildHelper extends InitializedParent {
        public static int scale(int value) { return value * 3; }
    }

    public static class Receiver { public int scale(int value) { return value; } }
    public static class Derived extends Fixture {
        @Override
        public int overridable(int level) { return level * 3; }
    }

    public static class DangerousInitializer {
        public static int value = fail();
        private static int fail() { throw new IllegalStateException("initialization must follow division"); }
    }
}
