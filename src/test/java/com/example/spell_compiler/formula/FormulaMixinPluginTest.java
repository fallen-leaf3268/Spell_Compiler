package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaMixinPluginTest {
    private static final String OWNER = "formula/NativeSpellFixture";
    private static final String DOTTED = OWNER.replace('/', '.');

    @AfterEach
    void clearDispatchers() {
        FormulaRuntimeDispatcher.clearServer();
        FormulaRuntimeDispatcher.clearClient();
    }

    @Test
    void catalogBindingsApplyWithoutCustomFilesAndUnsupportedClassesAreSkipped() {
        var plugin = new FormulaMixinPlugin(catalog(Map.of("damage", "(I)I")));
        assertTrue(plugin.shouldApplyMixin(DOTTED, "GeneratedSpellFormulaMixin"));
        assertFalse(plugin.shouldApplyMixin("other.Spell", "GeneratedSpellFormulaMixin"));
        assertFalse(new FormulaMixinPlugin(new SpellFormulaCatalog(List.of()))
                .shouldApplyMixin(DOTTED, "GeneratedSpellFormulaMixin"));
    }

    @Test
    void inheritedOnlyBindingsDoNotInstrumentOrRequireMethodsOnTheConcreteClass() {
        var inherited = new SpellFormulaCatalog.Method("spell_power", "getSpellPower",
                "(ILnet/minecraft/world/entity/Entity;)F", "float(1)", "1", Map.of(),
                "io.redspace.ironsspellbooks.api.spells.AbstractSpell");
        var definition = new SpellFormulaCatalog.Spell("irons_spellbooks:test", DOTTED,
                "irons_spellbooks:fire", new JsonObject(), new JsonObject(), List.of(inherited));
        var plugin = new FormulaMixinPlugin(new SpellFormulaCatalog(List.of(definition)));
        assertTrue(plugin.shouldApplyMixin(DOTTED, "GeneratedSpellFormulaMixin"));
        ClassNode target = node(fixture(), 0);
        var original = List.copyOf(target.methods);
        plugin.preApply(DOTTED, target, "GeneratedSpellFormulaMixin", null);
        assertEquals(original, target.methods);
    }

    @Test
    void mixedBindingsInstrumentOnlyConcreteDeclarations() {
        var declared = catalog(Map.of("damage", "(I)I")).spells().get(0);
        var inherited = new SpellFormulaCatalog.Method("spell_power", "getSpellPower",
                "(ILnet/minecraft/world/entity/Entity;)F", "float(1)", "1", Map.of(),
                "io.redspace.ironsspellbooks.api.spells.AbstractSpell");
        var definition = new SpellFormulaCatalog.Spell(declared.id(), declared.className(), declared.school(),
                declared.advanced(), declared.parameters(), List.of(declared.formulas().get(0), inherited));
        var plugin = new FormulaMixinPlugin(new SpellFormulaCatalog(List.of(definition)));
        assertTrue(plugin.shouldApplyMixin(DOTTED, "GeneratedSpellFormulaMixin"));
        ClassNode target = node(fixture(), 0);
        MethodNode original = method(target, "damage");
        MethodNode untouched = method(target, "untouched");
        plugin.preApply(DOTTED, target, "GeneratedSpellFormulaMixin", null);
        assertNotSame(original, method(target, "damage"));
        assertSame(untouched, method(target, "untouched"));
        assertTrue(target.methods.stream().noneMatch(method -> method.name.equals("getSpellPower")));
    }

    @Test
    void defaultPathRunsOriginalBodyBeforeAnyArgumentBoxingOrArrayAllocation() throws Exception {
        ClassNode target = instrument(Map.of("damage", "(I)I"), 0);
        MethodNode damage = method(target, "damage");
        int guard = -1;
        int array = -1;
        for (int index = 0; index < damage.instructions.size(); index++) {
            var instruction = damage.instructions.get(index);
            if (guard < 0 && instruction instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNULL) guard = index;
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.ANEWARRAY) array = index;
            if (instruction instanceof MethodInsnNode call && call.name.equals("valueOf")) assertTrue(index > guard);
        }
        assertTrue(guard >= 0 && array > guard);
        var jump = (JumpInsnNode) damage.instructions.get(guard);
        assertTrue(damage.instructions.indexOf(jump.label) > array);
        Class<?> type = load(bytes(target, 0));
        Object instance = type.getConstructor().newInstance();
        assertEquals(16, type.getMethod("damage", int.class).invoke(instance, 5));
        assertEquals(16, type.getMethod("untouched", int.class).invoke(instance, 5));
    }

    @Test
    void reloadChangesExistingInstancesAndClearingReturnsToOriginalBody() throws Exception {
        ClassNode target = instrument(Map.of("damage", "(I)I"), 0);
        Class<?> type = load(bytes(target, 0));
        Object instance = type.getConstructor().newInstance();
        String key = FormulaRuntimeDispatcher.key(OWNER, "damage(I)I");
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, (self, arguments) -> {
            assertSame(instance, self);
            return (Integer) arguments[0] * 4 + 2;
        }));
        assertEquals(22, type.getMethod("damage", int.class).invoke(instance, 5));
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, (self, arguments) -> 99));
        assertEquals(99, type.getMethod("damage", int.class).invoke(instance, 5));
        FormulaRuntimeDispatcher.clearClient();
        assertEquals(16, type.getMethod("damage", int.class).invoke(instance, 5));
    }

    @Test
    void privateStaticAndWideArgumentsKeepTheirTypesAndSlotsWithExpandedFrames() throws Exception {
        ClassNode target = instrument(Map.of("mixed", "(IJFDLjava/lang/Object;)D", "total", "(JD)J",
                "small", "()F"), ClassReader.EXPAND_FRAMES);
        Class<?> type = load(bytes(target, 0));
        Object instance = type.getConstructor().newInstance();
        var mixed = type.getDeclaredMethod("mixed", int.class, long.class, float.class, double.class, Object.class);
        mixed.setAccessible(true);
        Object marker = new Object();
        assertEquals(10d, mixed.invoke(instance, 1, 2L, 3f, 4d, marker));
        assertEquals(-1d, mixed.invoke(instance, -1, 2L, 3f, 4d, marker));
        FormulaRuntimeDispatcher.replaceClient(Map.of(
                FormulaRuntimeDispatcher.key(OWNER, "mixed(IJFDLjava/lang/Object;)D"), (self, arguments) -> {
                    assertSame(instance, self);
                    assertEquals(List.of(1, 2L, 3f, 4d, marker), List.of(arguments));
                    return 123.5;
                },
                FormulaRuntimeDispatcher.key(OWNER, "total(JD)J"), (self, arguments) -> {
                    assertNull(self);
                    assertEquals(List.of(2L, 4.75), List.of(arguments));
                    return 456L;
                },
                FormulaRuntimeDispatcher.key(OWNER, "small()F"), (self, arguments) -> {
                    assertEquals(0, arguments.length);
                    return 7.25f;
                }));
        assertEquals(123.5, mixed.invoke(instance, 1, 2L, 3f, 4d, marker));
        assertEquals(456L, type.getMethod("total", long.class, double.class).invoke(null, 2L, 4.75));
        assertEquals(7.25f, type.getMethod("small").invoke(instance));
    }

    @Test
    void executionFailuresAndNonFiniteResultsRunOriginalBodyWithEveryNumericReturnType() throws Exception {
        for (int flags : new int[]{0, ClassReader.EXPAND_FRAMES}) {
            ClassNode target = instrument(Map.of("damage", "(I)I", "mixed", "(IJFDLjava/lang/Object;)D",
                    "total", "(JD)J", "small", "()F"), flags);
            Class<?> type = load(bytes(target, 0));
            Object instance = type.getConstructor().newInstance();
            var mixed = type.getDeclaredMethod("mixed", int.class, long.class, float.class, double.class, Object.class);
            mixed.setAccessible(true);
            AtomicInteger calls = new AtomicInteger();
            FormulaRuntimeDispatcher.replaceClient(Map.of(
                    FormulaRuntimeDispatcher.key(OWNER, "damage(I)I"), (self, arguments) -> {
                        calls.incrementAndGet();
                        throw new ArithmeticException("/ by zero");
                    },
                    FormulaRuntimeDispatcher.key(OWNER, "mixed(IJFDLjava/lang/Object;)D"), (self, arguments) -> Double.NaN,
                    FormulaRuntimeDispatcher.key(OWNER, "total(JD)J"), (self, arguments) -> Double.POSITIVE_INFINITY,
                    FormulaRuntimeDispatcher.key(OWNER, "small()F"), (self, arguments) -> Float.NEGATIVE_INFINITY));
            assertEquals(16, type.getMethod("damage", int.class).invoke(instance, 5));
            assertEquals(22, type.getMethod("damage", int.class).invoke(instance, 7));
            assertEquals(1, calls.get());
            assertEquals(10d, mixed.invoke(instance, 1, 2L, 3f, 4d, new Object()));
            assertEquals(-1d, mixed.invoke(instance, -1, 2L, 3f, 4d, new Object()));
            assertEquals(6L, type.getMethod("total", long.class, double.class).invoke(null, 2L, 4.75));
            assertEquals(2.5f, type.getMethod("small").invoke(instance));
            FormulaRuntimeDispatcher.replaceClient(Map.of(FormulaRuntimeDispatcher.key(OWNER, "damage(I)I"),
                    (self, arguments) -> 99));
            assertEquals(99, type.getMethod("damage", int.class).invoke(instance, 5));
        }
    }

    @Test
    void originalBodyExceptionsPropagateAfterCustomDelegateFallsBack() throws Exception {
        Class<?> type = load(bytes(instrument(Map.of("exceptional", "()I"), 0), 0));
        Object instance = type.getConstructor().newInstance();
        IllegalArgumentException expected = new IllegalArgumentException("native failure");
        type.getField("nativeFailure").set(null, expected);
        FormulaRuntimeDispatcher.replaceClient(Map.of(FormulaRuntimeDispatcher.key(OWNER, "exceptional()I"),
                (self, arguments) -> { throw new ArithmeticException("custom failure"); }));
        var failure = assertThrows(InvocationTargetException.class, () -> type.getMethod("exceptional").invoke(instance));
        assertSame(expected, failure.getCause());
        var repeated = assertThrows(InvocationTargetException.class, () -> type.getMethod("exceptional").invoke(instance));
        assertSame(expected, repeated.getCause());
    }

    @Test
    void laterReturnInjectionsApplyToBothOverrideAndOriginalReturns() throws Exception {
        var plugin = new FormulaMixinPlugin(catalog(Map.of("damage", "(I)I")));
        ClassNode target = node(fixture(), 0);
        MethodNode untouched = method(target, "untouched");
        plugin.preApply(DOTTED, target, "GeneratedSpellFormulaMixin", null);
        assertSame(untouched, method(target, "untouched"));
        MethodNode changed = method(target, "damage");
        for (var instruction : changed.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.IRETURN) continue;
            InsnList injection = new InsnList();
            injection.add(new LdcInsnNode(7));
            injection.add(new InsnNode(Opcodes.IADD));
            changed.instructions.insertBefore(instruction, injection);
        }
        plugin.postApply(DOTTED, target, "GeneratedSpellFormulaMixin", null);
        assertSame(changed, method(target, "damage"));
        Class<?> type = load(bytes(target, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS));
        Object instance = type.getConstructor().newInstance();
        assertEquals(23, type.getMethod("damage", int.class).invoke(instance, 5));
        FormulaRuntimeDispatcher.replaceClient(Map.of(FormulaRuntimeDispatcher.key(OWNER, "damage(I)I"),
                (self, arguments) -> 22));
        assertEquals(29, type.getMethod("damage", int.class).invoke(instance, 5));
        FormulaRuntimeDispatcher.replaceClient(Map.of(FormulaRuntimeDispatcher.key(OWNER, "damage(I)I"),
                (self, arguments) -> { throw new ArithmeticException("broken override"); }));
        assertEquals(23, type.getMethod("damage", int.class).invoke(instance, 5));
        FormulaRuntimeDispatcher.replaceClient(Map.of(FormulaRuntimeDispatcher.key(OWNER, "damage(I)I"),
                (self, arguments) -> Double.NaN));
        assertEquals(23, type.getMethod("damage", int.class).invoke(instance, 5));
    }

    @Test
    void existingForeignBodyChangesArePreservedAndMissingBindingsFailAtomically() throws Exception {
        ClassNode target = node(fixture(), 0);
        MethodNode damage = method(target, "damage");
        InsnList addition = new InsnList();
        addition.add(new LdcInsnNode(1));
        addition.add(new InsnNode(Opcodes.IADD));
        damage.instructions.insertBefore(damage.instructions.getLast(), addition);
        var plugin = new FormulaMixinPlugin(catalog(Map.of("damage", "(I)I")));
        plugin.preApply(DOTTED, target, "GeneratedSpellFormulaMixin", null);
        Class<?> type = load(bytes(target, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS));
        assertEquals(17, type.getMethod("damage", int.class).invoke(type.getConstructor().newInstance(), 5));
        ClassNode incomplete = node(fixture(), 0);
        var originals = List.copyOf(incomplete.methods);
        var invalid = new FormulaMixinPlugin(catalog(Map.of("damage", "(I)I", "missing", "()I")));
        assertThrows(IllegalStateException.class, () -> invalid.preApply(DOTTED, incomplete,
                "GeneratedSpellFormulaMixin", null));
        for (int index = 0; index < originals.size(); index++) assertSame(originals.get(index), incomplete.methods.get(index));
    }

    private static SpellFormulaCatalog catalog(Map<String, String> methods) {
        var formulas = methods.entrySet().stream().map(entry -> new SpellFormulaCatalog.Method(entry.getKey(),
                entry.getKey(), entry.getValue(), "int(1)", "1", Map.of(), DOTTED)).toList();
        return new SpellFormulaCatalog(List.of(new SpellFormulaCatalog.Spell("irons_spellbooks:test", DOTTED,
                "irons_spellbooks:fire", new JsonObject(), new JsonObject(), formulas)));
    }

    private static ClassNode instrument(Map<String, String> selected, int readerFlags) {
        ClassNode target = node(fixture(), readerFlags);
        new FormulaMixinPlugin(catalog(selected)).preApply(DOTTED, target, "GeneratedSpellFormulaMixin", null);
        return target;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    private static ClassNode node(byte[] bytes, int flags) {
        ClassNode result = new ClassNode();
        new ClassReader(bytes).accept(result, flags);
        return result;
    }

    private static byte[] bytes(ClassNode owner, int flags) {
        ClassWriter writer = new ClassWriter(flags);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static Class<?> load(byte[] definition) {
        return new ClassLoader(FormulaMixinPluginTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(DOTTED, definition, 0, definition.length); }
        }.define();
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nativeFailure",
                "Ljava/lang/RuntimeException;", null, null).visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        for (String name : List.of("damage", "untouched")) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "(I)I", null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ILOAD, 1);
            method.visitInsn(Opcodes.ICONST_3);
            method.visitInsn(Opcodes.IMUL);
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IADD);
            method.visitInsn(Opcodes.IRETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        var mixed = writer.visitMethod(Opcodes.ACC_PRIVATE, "mixed", "(IJFDLjava/lang/Object;)D", null, null);
        mixed.visitCode();
        mixed.visitVarInsn(Opcodes.ILOAD, 1);
        Label positive = new Label();
        mixed.visitJumpInsn(Opcodes.IFGE, positive);
        mixed.visitLdcInsn(-1d);
        mixed.visitInsn(Opcodes.DRETURN);
        mixed.visitLabel(positive);
        mixed.visitVarInsn(Opcodes.ILOAD, 1);
        mixed.visitInsn(Opcodes.I2D);
        mixed.visitVarInsn(Opcodes.LLOAD, 2);
        mixed.visitInsn(Opcodes.L2D);
        mixed.visitInsn(Opcodes.DADD);
        mixed.visitVarInsn(Opcodes.FLOAD, 4);
        mixed.visitInsn(Opcodes.F2D);
        mixed.visitInsn(Opcodes.DADD);
        mixed.visitVarInsn(Opcodes.DLOAD, 5);
        mixed.visitInsn(Opcodes.DADD);
        mixed.visitInsn(Opcodes.DRETURN);
        mixed.visitMaxs(0, 0);
        mixed.visitEnd();
        var total = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "total", "(JD)J", null, null);
        total.visitCode();
        total.visitVarInsn(Opcodes.LLOAD, 0);
        total.visitVarInsn(Opcodes.DLOAD, 2);
        total.visitInsn(Opcodes.D2L);
        total.visitInsn(Opcodes.LADD);
        total.visitInsn(Opcodes.LRETURN);
        total.visitMaxs(0, 0);
        total.visitEnd();
        var small = writer.visitMethod(Opcodes.ACC_PUBLIC, "small", "()F", null, null);
        small.visitCode();
        small.visitLdcInsn(2.5f);
        small.visitInsn(Opcodes.FRETURN);
        small.visitMaxs(0, 0);
        small.visitEnd();
        var exceptional = writer.visitMethod(Opcodes.ACC_PUBLIC, "exceptional", "()I", null, null);
        exceptional.visitCode();
        exceptional.visitFieldInsn(Opcodes.GETSTATIC, OWNER, "nativeFailure", "Ljava/lang/RuntimeException;");
        exceptional.visitInsn(Opcodes.ATHROW);
        exceptional.visitMaxs(0, 0);
        exceptional.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
