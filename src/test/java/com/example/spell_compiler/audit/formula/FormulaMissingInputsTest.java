package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaMissingInputsTest {
    private static final Path JAR = Path.of("libs/ironsspells-3.16.1.jar");
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String UTILS = "io/redspace/ironsspellbooks/api/util/Utils";
    private static final String SITE_VALUES = "com/example/spell_compiler/formula/FormulaSiteValues";
    private static final String INPUT = "Lcom/example/spell_compiler/formula/FormulaInput;";

    @Test
    void extractsAndCompilesAllElevenMissingNumericMethods() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        Map<String, List<String>> targets = Map.of(
                "blood/BloodStepSpell", List.of("getDistance"),
                "ender/TeleportSpell", List.of("getDistance"),
                "ice/FrostStepSpell", List.of("getDistance"),
                "ender/ShadowSlashSpell", List.of("getDamage"),
                "evocation/ThrowSpell", List.of("getDamage"),
                "fire/FlamingStrikeSpell", List.of("getDamage"),
                "fire/RaiseHellSpell", List.of("getDamage"),
                "holy/DivineSmiteSpell", List.of("getDamage"),
                "blood/SacrificeSpell", List.of("getDamage", "getRadius"),
                "ice/FrostbiteSpell", List.of("getDamage"));
        int count = 0;
        for (var entry : targets.entrySet()) {
            var owner = repository.classNode("io/redspace/ironsspellbooks/spells/" + entry.getKey());
            byte[] original = bytes(owner);
            for (String name : entry.getValue()) {
                var method = method(owner, name);
                var analysis = AutomaticFormulaExtractor.analyzeRuntime(owner, method, repository);
                assertTrue(analysis.supported(), analysis.rejection());
                var formula = analysis.formula();
                var labels = java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<org.objectweb.asm.tree.LabelNode, Boolean>());
                for (var instruction : formula.inputPrelude())
                    if (instruction instanceof org.objectweb.asm.tree.LabelNode label)
                        assertTrue(labels.add(label), owner.name + '#' + name + method.desc);
                String readable = formula.readableExpression();
                assertSame(original, FormulaCompiler.transformRuntime(original, Map.of(name + method.desc, readable), repository));
                assertNotSame(original, assertDoesNotThrow(() -> FormulaCompiler.transformRuntime(original,
                        Map.of(name + method.desc, '(' + readable + ") + 7"), repository), owner.name + '#' + name + method.desc));
                if (name.equals("getDistance")) assertTrue(readable.contains("softcap("), readable);
                if (entry.getKey().equals("blood/SacrificeSpell") && name.equals("getRadius"))
                    assertTrue(formula.variables().keySet().containsAll(List.of("entity_health", "entity_max_health")));
                if (entry.getKey().equals("blood/SacrificeSpell") && name.equals("getDamage"))
                    assertTrue(formula.variables().containsKey("summon_damage_multiplier"));
                if (entry.getKey().equals("ice/FrostbiteSpell"))
                    assertTrue(formula.variables().containsKey("frostbite_power_multiplier"));
                if (entry.getKey().equals("fire/FlamingStrikeSpell"))
                    assertTrue(formula.variables().keySet().containsAll(List.of("weapon_damage", "fire_aspect_level")));
                count++;
            }
        }
        assertEquals(11, count);
    }

    @Test
    void softcapRetainsTheActualNativeThresholdAndFloatingPointSemantics() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        var nativeUtils = repository.classNode(UTILS);
        var softcap = nativeUtils.methods.stream().filter(value -> value.name.equals("softCapFormula")
                && value.desc.equals("(D)D")).findFirst().orElseThrow();
        ClassNode helper = owner(UTILS);
        helper.methods.add(softcap);
        ClassNode fixture = owner("formula/SoftCapFixture");
        MethodNode evaluate = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "evaluate", "(D)D", null, null);
        evaluate.instructions.add(new VarInsnNode(Opcodes.DLOAD, 0));
        evaluate.instructions.add(new InsnNode(Opcodes.DRETURN));
        evaluate.maxLocals = 2;
        evaluate.maxStack = 2;
        fixture.methods.add(evaluate);
        byte[] compiled = FormulaCompiler.transform(bytes(fixture), Map.of("evaluate(D)D", "softcap(arg0)"));
        var definitions = Map.of("formula.SoftCapFixture", compiled, UTILS.replace('/', '.'), bytes(helper));
        var loader = loader(definitions);
        var actual = loader.loadClass("formula.SoftCapFixture").getMethod("evaluate", double.class);
        var reference = loader.loadClass(UTILS.replace('/', '.')).getMethod("softCapFormula", double.class);
        for (double value : new double[]{-Double.MAX_VALUE, -1, -0.0, 0, 1, Math.nextDown(1.5),
                1.5, Math.nextUp(1.5), 2, 10, Double.MAX_VALUE, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.NaN}) {
            double expected = (Double) reference.invoke(null, value);
            double result = (Double) actual.invoke(null, value);
            assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(result), "value=" + value);
        }
        assertEquals(Type.DOUBLE_TYPE, FormulaCompiler.validateExpression("softcap(2)", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> FormulaCompiler.validateExpression("softcap()", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> FormulaCompiler.validateExpression("softcap(1, 2)", Map.of()));
    }

    @Test
    void healthInputsReadTheSuppliedEntityRatherThanAnAmbientCaster() throws Exception {
        ClassNode fixture = owner("formula/HealthInputFixture");
        MethodNode radius = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "radius", "(L" + LIVING + ";)F", null, null);
        for (String method : List.of("getHealth", "getMaxHealth")) {
            radius.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            radius.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING, method, "()F", false));
        }
        radius.instructions.add(new InsnNode(Opcodes.FDIV));
        radius.instructions.add(new InsnNode(Opcodes.FRETURN));
        radius.maxLocals = 1;
        radius.maxStack = 2;
        fixture.methods.add(radius);
        var analysis = AutomaticFormulaExtractor.analyze(fixture, radius);
        assertTrue(analysis.supported(), analysis.rejection());
        byte[] compiled = FormulaCompiler.transform(bytes(fixture), Map.of(radius.name + radius.desc,
                "entity_health / entity_max_health * 10"));
        ClassNode entity = owner(LIVING);
        entity.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PUBLIC, "health", "F", null, null));
        entity.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PUBLIC, "maximum", "F", null, null));
        for (var entry : Map.of("getHealth", "health", "getMaxHealth", "maximum").entrySet()) {
            MethodNode getter = new MethodNode(Opcodes.ACC_PUBLIC, entry.getKey(), "()F", null, null);
            getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getter.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, LIVING, entry.getValue(), "F"));
            getter.instructions.add(new InsnNode(Opcodes.FRETURN));
            getter.maxLocals = 1;
            getter.maxStack = 1;
            entity.methods.add(getter);
        }
        var loader = loader(Map.of("formula.HealthInputFixture", compiled, LIVING.replace('/', '.'), bytes(entity)));
        var entityType = loader.loadClass(LIVING.replace('/', '.'));
        var target = entityType.getConstructor().newInstance();
        var caster = entityType.getConstructor().newInstance();
        entityType.getField("health").setFloat(target, 25);
        entityType.getField("maximum").setFloat(target, 100);
        entityType.getField("health").setFloat(caster, 90);
        entityType.getField("maximum").setFloat(caster, 100);
        var calculate = loader.loadClass("formula.HealthInputFixture").getMethod("radius", entityType);
        assertEquals(2.5f, calculate.invoke(null, target));
        assertEquals(9f, calculate.invoke(null, caster));
    }

    @Test
    void nullableInputMatchingRejectsChangedFallbacksAndUnrelatedRegistrySources() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        var sacrifice = repository.classNode("io/redspace/ironsspellbooks/spells/blood/SacrificeSpell");
        var damage = method(sacrifice, "getDamage");
        boolean replaced = false;
        for (var instruction : damage.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.FCONST_1) {
                damage.instructions.set(instruction, new InsnNode(Opcodes.FCONST_2));
                replaced = true;
                break;
            }
        }
        assertTrue(replaced);
        assertFalse(AutomaticFormulaExtractor.analyze(sacrifice, damage, repository).supported());
        var effect = repository.classNode("io/redspace/ironsspellbooks/effect/FrostbiteEffect");
        var helper = method(effect, "getDamageForAmplifier");
        for (var instruction : helper.instructions)
            if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field && field.name.equals("FROSTBITE_SPELL"))
                field.name = "FIREBALL_SPELL";
        var frostbite = repository.classNode("io/redspace/ironsspellbooks/spells/ice/FrostbiteSpell");
        assertFalse(AutomaticFormulaExtractor.analyze(frostbite, method(frostbite, "getDamage"), repository).supported());
    }

    @Test
    void parameterNamesAreOnlyAcceptedForTheDedicatedSiteHelper() {
        var helper = namedFixture(SITE_VALUES, "entity_health", "entity_max_health");
        var analysis = AutomaticFormulaExtractor.analyze(helper, method(helper, "combine"));
        assertTrue(analysis.supported(), analysis.rejection());
        assertEquals(List.of("entity_health", "entity_max_health"), analysis.formula().variables().keySet().stream().toList());
        var other = namedFixture("formula/OtherAnnotatedFixture", "entity_health", "entity_max_health");
        assertEquals(List.of("arg0", "arg1"), AutomaticFormulaExtractor.analyze(other, method(other, "combine"))
                .formula().variables().keySet().stream().toList());
        for (List<String> names : List.of(List.of("bad name", "valid"), List.of("same", "same"), List.of("中文", "valid"))) {
            var invalid = namedFixture(SITE_VALUES, names.get(0), names.get(1));
            assertFalse(AutomaticFormulaExtractor.analyze(invalid, method(invalid, "combine")).supported());
        }
    }

    @Test
    void sitePowerBoundaryIsHiddenForExportAndNotRepeatedInCapturedPrelude() {
        var helper = owner(SITE_VALUES);
        var method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "boundary",
                "(FLjava/lang/Object;Ljava/lang/Object;)F", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/example/spell_compiler/formula/FormulaRuntimeHooks", "applyPower",
                "(FLjava/lang/Object;Ljava/lang/Object;)F", false));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 3));
        method.instructions.add(new InsnNode(Opcodes.FCONST_2));
        method.instructions.add(new InsnNode(Opcodes.FADD));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        method.maxLocals = 4;
        method.maxStack = 3;
        helper.methods.add(method);
        var exported = AutomaticFormulaExtractor.analyze(helper, method);
        assertTrue(exported.supported(), exported.rejection());
        assertFalse(exported.formula().expression().contains("__native_power"));
        var runtime = AutomaticFormulaExtractor.analyzeRuntime(helper, method, FormulaClassRepository.of(helper));
        assertTrue(runtime.supported(), runtime.rejection());
        assertEquals(1, runtime.formula().nativePowers().size());
        assertTrue(runtime.formula().inputPrelude().stream().noneMatch(value -> value instanceof org.objectweb.asm.tree.MethodInsnNode call
                && call.name.equals("applyPower")));
        var other = owner("formula/OtherPowerBoundary");
        other.methods.add(method);
        assertFalse(AutomaticFormulaExtractor.analyze(other, method).supported());
    }

    private static ClassNode namedFixture(String name, String first, String second) {
        var owner = owner(name);
        var method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "combine", "(II)I", null, null);
        method.invisibleParameterAnnotations = new List[2];
        for (int index = 0; index < 2; index++) {
            AnnotationNode annotation = new AnnotationNode(INPUT);
            annotation.values = List.of("value", index == 0 ? first : second);
            method.invisibleParameterAnnotations[index] = List.of(annotation);
        }
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 2;
        method.maxStack = 2;
        owner.methods.add(method);
        return owner;
    }

    private static ClassNode owner(String name) {
        ClassNode owner = new ClassNode();
        owner.version = Opcodes.V17;
        owner.access = Opcodes.ACC_PUBLIC;
        owner.name = name;
        owner.superName = "java/lang/Object";
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.maxLocals = 1;
        constructor.maxStack = 1;
        owner.methods.add(constructor);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
    }

    private static byte[] bytes(ClassNode owner) {
        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static ClassLoader loader(Map<String, byte[]> definitions) {
        var classes = new HashMap<>(definitions);
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }
}
