package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaThirdPartyCompatibilityTest {
    private static final String METHOD = "getDamage(I)F";

    @Test
    void replacedNativeNumericCallsRejectTheActualChangedMethod() {
        var fixture = fixture("Replaced", 1, "getSpellPower");
        FormulaMethodCompatibility.recordOriginal(fixture.owner().name, fixture.method());
        replaceOne(fixture);
        FormulaMethodCompatibility.inspect(fixture.owner().name, METHOD, fixture.method());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name, METHOD));
        assertTrue(failure.getMessage().contains("可能绕过第三方修改"), failure.getMessage());
        assertTrue(failure.getMessage().contains(fixture.owner().name.replace('/', '.') + '#' + METHOD), failure.getMessage());
        assertTrue(failure.getMessage().contains("getSpellPower(I)F"), failure.getMessage());
    }

    @Test
    void returnCallbacksDoNotTurnPreservedNativeCallsIntoConflicts() {
        var fixture = fixture("ReturnCallbacks", 1, "getSpellPower");
        var plugin = nativePlugin(fixture);
        plugin.preApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "GeneratedSpellFormulaMixin", null);
        MethodNode guarded = method(fixture.owner(), METHOD);
        guarded.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "foreign/Callbacks", "head", "()V", false));
        for (var instruction : guarded.instructions.toArray())
            if (instruction.getOpcode() == Opcodes.FRETURN)
                guarded.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "foreign/Callbacks", "returned", "(F)F", false));
        plugin.postApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "GeneratedSpellFormulaMixin", null);
        assertDoesNotThrow(() -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name, METHOD));
        assertEquals(1, count(guarded, "getSpellPower"));
        assertEquals(2, count(guarded, "returned"));
    }

    @Test
    void losingOneOfRepeatedNativeCallsIsRejected() {
        var fixture = fixture("RepeatedCalls", 2, "getEntityPowerMultiplier");
        FormulaMethodCompatibility.recordOriginal(fixture.owner().name, fixture.method());
        replaceOne(fixture);
        FormulaMethodCompatibility.inspect(fixture.owner().name, METHOD, fixture.method());
        assertEquals(1, count(fixture.method(), "getEntityPowerMultiplier"));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name, METHOD));
        assertTrue(failure.getMessage().contains("原有 2 次，现有 1 次"), failure.getMessage());
    }

    @Test
    void independentMethodsAndUnrecordedOwnersRemainCompatible() {
        var changed = fixture("ChangedOnly", 1, "getSpellPower");
        var nativeMethod = fixture("StillNative", 1, "getSpellPower");
        FormulaMethodCompatibility.recordOriginal(changed.owner().name, changed.method());
        FormulaMethodCompatibility.recordOriginal(nativeMethod.owner().name, nativeMethod.method());
        replaceOne(changed);
        FormulaMethodCompatibility.inspect(changed.owner().name, METHOD, changed.method());
        FormulaMethodCompatibility.inspect(nativeMethod.owner().name, METHOD, nativeMethod.method());
        assertThrows(IllegalArgumentException.class,
                () -> FormulaMethodCompatibility.requireCompatible(changed.owner().name, METHOD));
        assertDoesNotThrow(() -> FormulaMethodCompatibility.requireCompatible(nativeMethod.owner().name, METHOD));
        assertDoesNotThrow(() -> FormulaMethodCompatibility.requireCompatible(changed.owner().name, "getDamage(J)F"));
        assertDoesNotThrow(() -> FormulaMethodCompatibility.requireCompatible("unrecorded.Spell", METHOD));
    }

    @Test
    void numericProducersAreRecognizedByDescriptorRatherThanMethodNames() {
        var fixture = fixture("GenericNumeric", 1, "customNumeric");
        FormulaMethodCompatibility.recordOriginal(fixture.owner().name, fixture.method());
        replaceOne(fixture);
        FormulaMethodCompatibility.inspect(fixture.owner().name, METHOD, fixture.method());
        assertThrows(IllegalArgumentException.class,
                () -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name, METHOD));
    }

    @Test
    void nativePluginRecordsTheGuardedMethodAndChecksItsTransformedCallSites() {
        var fixture = fixture("NativePlugin", 1, "getSpellPower");
        var plugin = nativePlugin(fixture);
        plugin.preApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "GeneratedSpellFormulaMixin", null);
        var guarded = method(fixture.owner(), METHOD);
        assertTrue(count(guarded, "resolve") > 0);
        replaceOne(new Fixture(fixture.owner(), guarded, fixture.producer()));
        plugin.postApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "GeneratedSpellFormulaMixin", null);
        assertThrows(IllegalArgumentException.class,
                () -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name.replace('/', '.'), METHOD));
        assertEquals(1, count(guarded, "adjusted"));
    }

    @Test
    void deferredCallReplacementAfterPluginPostApplyCannotReuseAnEarlierCompatibleResult() {
        var fixture = fixture("DeferredReplacement", 1, "getSpellPower");
        var plugin = nativePlugin(fixture);
        plugin.preApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "GeneratedSpellFormulaMixin", null);
        plugin.postApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "GeneratedSpellFormulaMixin", null);
        assertDoesNotThrow(() -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name, METHOD));
        replaceOne(new Fixture(fixture.owner(), method(fixture.owner(), METHOD), fixture.producer()));
        assertThrows(IllegalArgumentException.class,
                () -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name, METHOD));
        plugin.postApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "GeneratedSpellFormulaMixin", null);
        assertThrows(IllegalArgumentException.class,
                () -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name, METHOD));
    }

    @Test
    void deferredMethodObjectReplacementIsReadFromTheCurrentTransformedClass() {
        var fixture = fixture("DeferredMethodObject", 1, "getSpellPower");
        var plugin = nativePlugin(fixture);
        plugin.preApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "GeneratedSpellFormulaMixin", null);
        plugin.postApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "GeneratedSpellFormulaMixin", null);
        MethodNode previous = method(fixture.owner(), METHOD);
        var replacement = new MethodNode(previous.access, previous.name, previous.desc, previous.signature, null);
        previous.accept(replacement);
        replaceOne(new Fixture(fixture.owner(), replacement, fixture.producer()));
        fixture.owner().methods.set(fixture.owner().methods.indexOf(previous), replacement);
        assertEquals(1, count(previous, fixture.producer()));
        assertEquals(0, count(replacement, fixture.producer()));
        assertThrows(IllegalArgumentException.class,
                () -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name, METHOD));
    }

    @Test
    void dynamicPluginUsesTheDeclaringTargetForItsTrackedNumericMethods() throws Exception {
        var fixture = fixture("DynamicPlugin", 1, "getSpellPower");
        var plugin = new FormulaMixinPlugin(new SpellFormulaCatalog(List.of()));
        var repository = FormulaClassRepository.of(fixture.owner());
        set(plugin, "addons", new FormulaAddonDiscovery.Discovery(repository, repository.sourceClasses(),
                Set.of(fixture.owner().name), Map.of(fixture.owner().name, "fixture")));
        set(plugin, "mixinPackage", "compat.dynamic");
        set(plugin, "dynamicMixin", "GeneratedAddonMixin");
        plugin.preApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "compat.dynamic.GeneratedAddonMixin", null);
        var guarded = method(fixture.owner(), METHOD);
        assertEquals(1, count(guarded, "resolveDeclared"));
        replaceOne(new Fixture(fixture.owner(), guarded, fixture.producer()));
        plugin.postApply(fixture.owner().name.replace('/', '.'), fixture.owner(), "compat.dynamic.GeneratedAddonMixin", null);
        assertThrows(IllegalArgumentException.class,
                () -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name, METHOD));
        assertDoesNotThrow(() -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name, fixture.producer() + "(I)F"));
        assertDoesNotThrow(() -> FormulaMethodCompatibility.requireCompatible(fixture.owner().name + "$OtherLeaf", METHOD));
    }

    private static FormulaMixinPlugin nativePlugin(Fixture fixture) {
        var binding = new SpellFormulaCatalog.Method("damage", "getDamage", "(I)F", "float(level)", "level",
                Map.of("level", "I"), fixture.owner().name.replace('/', '.'));
        var spell = new SpellFormulaCatalog.Spell("compat_fixture:" + fixture.owner().name.substring(
                fixture.owner().name.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT),
                fixture.owner().name.replace('/', '.'), "irons_spellbooks:fire", new JsonObject(), new JsonObject(), List.of(binding));
        return new FormulaMixinPlugin(new SpellFormulaCatalog(List.of(spell)));
    }

    private static Fixture fixture(String suffix, int calls, String producer) {
        var owner = new ClassNode();
        owner.version = Opcodes.V17;
        owner.access = Opcodes.ACC_PUBLIC;
        owner.name = "compat/thirdparty/" + suffix;
        owner.superName = "java/lang/Object";
        var method = new MethodNode(Opcodes.ACC_PUBLIC, "getDamage", "(I)F", null, null);
        for (int index = 0; index < calls; index++) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner.name, producer, "(I)F", false));
            if (index > 0) method.instructions.add(new InsnNode(Opcodes.FADD));
        }
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        method.maxStack = 3;
        method.maxLocals = 2;
        owner.methods.add(method);
        var helper = new MethodNode(Opcodes.ACC_PUBLIC, producer, "(I)F", null, null);
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new InsnNode(Opcodes.I2F));
        helper.instructions.add(new InsnNode(Opcodes.FRETURN));
        helper.maxStack = 1;
        helper.maxLocals = 2;
        owner.methods.add(helper);
        return new Fixture(owner, method, producer);
    }

    private static void replaceOne(Fixture fixture) {
        for (var instruction : fixture.method().instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(fixture.producer())) {
                fixture.method().instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "foreign/NumericHook", "adjusted", "(L" + fixture.owner().name + ";I)F", false));
                return;
            }
        }
        fail("No native numeric call remained to replace");
    }

    private static MethodNode method(ClassNode owner, String key) {
        return owner.methods.stream().filter(method -> (method.name + method.desc).equals(key)).findFirst().orElseThrow();
    }

    private static long count(MethodNode method, String name) {
        long count = 0;
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) count++;
        return count;
    }

    private static void set(FormulaMixinPlugin plugin, String name, Object value) throws Exception {
        Field field = FormulaMixinPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    private record Fixture(ClassNode owner, MethodNode method, String producer) {}
}
