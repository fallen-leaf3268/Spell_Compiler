package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaAddonInheritanceTest {
    private static final String DAMAGE = "damage(I)I";
    private static final String MIXED = "mixed(I)I";

    @Test
    void inheritedFormulaAnalysisUsesTheExplicitLeafOverrideAndLeafOnlyFields() throws Exception {
        for (Class<?> leaf : List.of(LeafA.class, LeafB.class)) {
            var fixture = fixture(leaf, DAMAGE);
            var analysis = AutomaticFormulaExtractor.analyzeRuntime(fixture.owner(), fixture.method(),
                    fixture.repository(), leaf.getName());
            assertTrue(analysis.supported(), analysis.rejection());
            assertTrue(analysis.formula().variables().containsKey("leafBonus"), analysis.formula().readableExpression());
            assertFalse(analysis.formula().variables().containsKey("base"), analysis.formula().readableExpression());
            assertTrue(analysis.formula().readableExpression().contains(leaf == LeafA.class ? "3" : "5"),
                    analysis.formula().readableExpression());
        }
    }

    @Test
    void contextualInheritedDelegatesApplyToTheirProvenLeafAndKeepOtherReceiversNative() throws Throwable {
        var first = FormulaDelegateFactory.compileSpell(LeafA.class, definition(LeafA.class, DAMAGE), Map.of(DAMAGE, "100"));
        var second = FormulaDelegateFactory.compileSpell(LeafB.class, definition(LeafB.class, DAMAGE), Map.of(DAMAGE, "100"));
        assertNotSame(first.get(DAMAGE), second.get(DAMAGE));
        var leafA = new LeafA();
        var leafB = new LeafB();
        var parent = new Parent();
        assertEquals(100, first.get(DAMAGE).invoke(leafA, new Object[]{2}).intValue());
        assertEquals(100, second.get(DAMAGE).invoke(leafB, new Object[]{2}).intValue());
        assertEquals(leafB.damage(2), first.get(DAMAGE).invoke(leafB, new Object[]{2}).intValue());
        assertEquals(leafA.damage(2), second.get(DAMAGE).invoke(leafA, new Object[]{2}).intValue());
        assertEquals(parent.damage(2), first.get(DAMAGE).invoke(parent, new Object[]{2}).intValue());
        assertEquals(34, leafA.damage(2));
        assertEquals(54, leafB.damage(2));
    }

    @Test
    void contextualInheritedFormulasKeepSpecialSuperCallsAndDispatchVirtualCallsToEachLeaf() throws Throwable {
        for (Class<?> leaf : List.of(LeafA.class, LeafB.class)) {
            var definition = definition(leaf, MIXED);
            var binding = definition.formulas().get(0);
            var delegate = FormulaDelegateFactory.compileSpell(leaf, definition,
                    Map.of(MIXED, '(' + binding.readableExpression() + ") + 5")).get(MIXED);
            var receiver = (Parent) leaf.getConstructor().newInstance();
            for (int level : new int[]{-2, 0, 2, 7}) {
                int expected = level + 1 + receiver.value(level) + 5;
                assertEquals(expected, delegate.invoke(receiver, new Object[]{level}).intValue(), leaf.getSimpleName() + " level=" + level);
                assertEquals(expected - 5, receiver.mixed(level));
            }
        }
    }

    @Test
    void analysisWithoutAConcreteReceiverKeepsTheExistingSubclassFallback() throws Throwable {
        var fixture = fixture(Parent.class, DAMAGE);
        var analysis = AutomaticFormulaExtractor.analyzeRuntime(fixture.owner(), fixture.method(), fixture.repository());
        assertTrue(analysis.supported(), analysis.rejection());
        String edited = '(' + analysis.formula().readableExpression() + ") + 5";
        var delegate = FormulaDelegateFactory.compile(Parent.class, Map.of(DAMAGE, edited)).get(DAMAGE);
        var parent = new Parent();
        var leaf = new LeafA();
        assertEquals(parent.damage(2) + 5, delegate.invoke(parent, new Object[]{2}).intValue());
        assertEquals(leaf.damage(2), delegate.invoke(leaf, new Object[]{2}).intValue());
    }

    @Test
    void packagePrivateHelpersAreNotOverriddenBySameNamedMethodsInAnotherPackage() throws Exception {
        verifyDispatch(dispatchFixture(0, Opcodes.INVOKEVIRTUAL, false, false), 7);
    }

    @Test
    void helperVisibilityKeepsPublicProtectedPrivateAndSpecialDispatchSemantics() throws Exception {
        verifyDispatch(dispatchFixture(Opcodes.ACC_PUBLIC, Opcodes.INVOKEVIRTUAL, false, false), 100);
        verifyDispatch(dispatchFixture(Opcodes.ACC_PROTECTED, Opcodes.INVOKEVIRTUAL, false, false), 100);
        verifyDispatch(dispatchFixture(Opcodes.ACC_PRIVATE, Opcodes.INVOKEVIRTUAL, false, false), 7);
        verifyDispatch(dispatchFixture(Opcodes.ACC_PROTECTED, Opcodes.INVOKESPECIAL, false, false), 7);
    }

    @Test
    void packagePrivateHelpersBecomeOverridableAcrossPackagesOnlyThroughAnActualOverride() throws Exception {
        verifyDispatch(dispatchFixture(0, Opcodes.INVOKEVIRTUAL, true, true), 100);
        verifyDispatch(dispatchFixture(0, Opcodes.INVOKEVIRTUAL, true, false), 7);
    }

    private static void verifyDispatch(DispatchFixture fixture, int nativeOffset) throws Exception {
        var analysis = AutomaticFormulaExtractor.analyzeRuntime(fixture.owner(), fixture.method(),
                fixture.repository(), fixture.leaf());
        assertTrue(analysis.supported(), analysis.rejection());
        var original = receiver(fixture.bytecode(), fixture.leaf());
        var bytecode = new LinkedHashMap<>(fixture.bytecode());
        String edited = '(' + analysis.formula().readableExpression() + ") + 5";
        bytecode.put(fixture.owner().name, FormulaCompiler.compileForVerification(
                bytecode.get(fixture.owner().name), Map.of("value(I)I", edited), fixture.repository(), fixture.leaf()));
        var changed = receiver(bytecode, fixture.leaf());
        for (int level : new int[]{-3, 0, 2, 7}) {
            assertEquals(level + nativeOffset, original.getClass().getMethod("value", int.class).invoke(original, level));
            assertEquals(level + nativeOffset + 5, changed.getClass().getMethod("value", int.class).invoke(changed, level));
        }
    }

    private static DispatchFixture dispatchFixture(int parentAccess, int opcode, boolean middle, boolean samePackage) {
        String parent = "addon/parent/PackageParent";
        String intermediary = samePackage ? "addon/parent/PackageMiddle" : "addon/middle/PackageMiddle";
        String leaf = "addon/leaf/PackageLeaf";
        var hierarchy = new ArrayList<ClassNode>();
        var parentNode = dispatchType(parent, "java/lang/Object", parentAccess, 7);
        hierarchy.add(parentNode);
        if (middle) hierarchy.add(dispatchType(intermediary, parent, Opcodes.ACC_PUBLIC, 40));
        var leafNode = dispatchType(leaf, middle ? intermediary : parent, Opcodes.ACC_PUBLIC, 100);
        hierarchy.add(leafNode);
        var root = opcode == Opcodes.INVOKESPECIAL ? leafNode : parentNode;
        var value = new MethodNode(Opcodes.ACC_PUBLIC, "value", "(I)I", null, null);
        value.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        value.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        value.instructions.add(new MethodInsnNode(opcode, parent, "helper", "(I)I", false));
        value.instructions.add(new InsnNode(Opcodes.IRETURN));
        root.methods.add(value);
        Map<String, byte[]> bytecode = new LinkedHashMap<>();
        var parsed = new ArrayList<ClassNode>();
        for (ClassNode type : hierarchy) {
            var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            type.accept(writer);
            byte[] bytes = writer.toByteArray();
            bytecode.put(type.name, bytes);
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            parsed.add(node);
        }
        var repository = FormulaClassRepository.of(parsed.get(0), parsed.subList(1, parsed.size()).toArray(ClassNode[]::new));
        var owner = repository.classNode(root.name);
        var method = owner.methods.stream().filter(candidate -> candidate.name.equals("value")).findFirst().orElseThrow();
        return new DispatchFixture(owner, method, repository, bytecode, leaf);
    }

    private static ClassNode dispatchType(String name, String parent, int access, int offset) {
        var type = new ClassNode();
        type.version = Opcodes.V17;
        type.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        type.name = name;
        type.superName = parent;
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        type.methods.add(constructor);
        var helper = new MethodNode(access, "helper", "(I)I", null, null);
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new LdcInsnNode(offset));
        helper.instructions.add(new InsnNode(Opcodes.IADD));
        helper.instructions.add(new InsnNode(Opcodes.IRETURN));
        type.methods.add(helper);
        return type;
    }

    private static Object receiver(Map<String, byte[]> bytecode, String leaf) throws Exception {
        var loader = new ClassLoader(FormulaAddonInheritanceTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = bytecode.get(name.replace('.', '/'));
                if (bytes == null) return super.findClass(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return loader.loadClass(leaf.replace('/', '.')).getConstructor().newInstance();
    }

    private record DispatchFixture(ClassNode owner, MethodNode method, FormulaClassRepository repository,
                                   Map<String, byte[]> bytecode, String leaf) {}

    private static SpellFormulaCatalog.Spell definition(Class<?> leaf, String key) throws Exception {
        var fixture = fixture(leaf, key);
        var analysis = AutomaticFormulaExtractor.analyzeRuntime(fixture.owner(), fixture.method(), fixture.repository(), leaf.getName());
        assertTrue(analysis.supported(), analysis.rejection());
        var formula = analysis.formula();
        Map<String, String> variables = new LinkedHashMap<>();
        formula.variables().forEach((name, variable) -> variables.put(name, variable.type().getDescriptor()));
        var binding = new SpellFormulaCatalog.Method(fixture.method().name, fixture.method().name,
                fixture.method().desc, formula.expression(), formula.readableExpression(), variables, Parent.class.getName());
        return new SpellFormulaCatalog.Spell("addon_fixture:" + leaf.getSimpleName().toLowerCase(java.util.Locale.ROOT),
                leaf.getName(), "irons_spellbooks:fire", new JsonObject(), new JsonObject(), List.of(binding));
    }

    private static Fixture fixture(Class<?> leaf, String key) throws Exception {
        var owner = node(Parent.class);
        var repository = FormulaClassRepository.of(owner, node(Grandparent.class), node(leaf));
        var method = owner.methods.stream().filter(value -> (value.name + value.desc).equals(key)).findFirst().orElseThrow();
        return new Fixture(owner, method, repository);
    }

    private static ClassNode node(Class<?> type) throws Exception {
        try (var stream = type.getResourceAsStream('/' + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(stream);
            var result = new ClassNode();
            new ClassReader(stream.readAllBytes()).accept(result, 0);
            return result;
        }
    }

    private record Fixture(ClassNode owner, MethodNode method, FormulaClassRepository repository) {}

    public static class Grandparent {
        protected int value(int level) {
            return level + 1;
        }
    }

    public static class Parent extends Grandparent {
        protected int base = 7;

        public int damage(int level) {
            return value(level) * 2;
        }

        public int mixed(int level) {
            return super.value(level) + value(level);
        }

        @Override
        protected int value(int level) {
            return base + level;
        }
    }

    public static class LeafA extends Parent {
        private int leafBonus = 11;

        @Override
        protected int value(int level) {
            return leafBonus + level * 3;
        }
    }

    public static class LeafB extends Parent {
        private int leafBonus = 17;

        @Override
        protected int value(int level) {
            return leafBonus + level * 5;
        }
    }
}
