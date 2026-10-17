package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaCastInputsTest {
    private static final Path JAR = Path.of("libs/ironsspells-3.16.1.jar");
    private static final String SPELL = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String TYPE = "io/redspace/ironsspellbooks/api/spells/CastType";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String ATTRIBUTE = "net/minecraft/world/entity/ai/attributes/Attribute";
    private static final String REGISTRY = "net/minecraftforge/registries/RegistryObject";
    private static final String ATTRIBUTES = "io/redspace/ironsspellbooks/api/registry/AttributeRegistry";
    private static final String UTILS = "io/redspace/ironsspellbooks/api/util/Utils";
    private static final String EFFECTIVE = "getEffectiveCastTime(IL" + LIVING + ";)I";

    @Test
    void extractsTheTwoAuditedParentsAndAllFiveDeclaredEffectiveMethods() throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        int count = 0;
        for (String name : List.of(SPELL, "io/redspace/ironsspellbooks/spells/evocation/ThrowSpell",
                "io/redspace/ironsspellbooks/spells/fire/FlamingStrikeSpell", "io/redspace/ironsspellbooks/spells/fire/RaiseHellSpell",
                "io/redspace/ironsspellbooks/spells/holy/DivineSmiteSpell", "io/redspace/ironsspellbooks/spells/nature/StompSpell")) {
            ClassNode owner = repository.classNode(name);
            assertNotNull(owner, name);
            byte[] original = bytes(owner);
            for (String key : name.equals(SPELL) ? List.of("getCastTime(I)I", EFFECTIVE) : List.of(EFFECTIVE)) {
                MethodNode method = method(owner, key);
                var analysis = AutomaticFormulaExtractor.analyzeRuntime(owner, method, repository);
                assertTrue(analysis.supported(), analysis.rejection());
                String readable = analysis.formula().readableExpression();
                assertSame(original, FormulaCompiler.transformRuntime(original, Map.of(key, readable), repository));
                assertNotSame(original, assertDoesNotThrow(() -> FormulaCompiler.transformRuntime(original,
                        Map.of(key, '(' + readable + ") + 7"), repository), name + '#' + key));
                if (!name.equals(SPELL)) assertEquals("castTime", readable);
                else if (key.equals(EFFECTIVE)) assertTrue(analysis.formula().variables().keySet()
                        .containsAll(List.of("castTime", "cast_time_multiplier")));
                else assertTrue(analysis.formula().variables().keySet().containsAll(List.of("castTime", "is_instant")));
                count++;
            }
        }
        assertEquals(7, count);
    }

    @Test
    void baseCastProjectionPreservesNativeTypeQueriesAndIntegerValues() throws Exception {
        Fixture fixture = fixture(false);
        byte[] original = fixture.definitions().get(SPELL.replace('/', '.'));
        ClassNode owner = read(original);
        var repository = FormulaClassRepository.of(owner);
        var analysis = AutomaticFormulaExtractor.analyze(owner, method(owner, "getCastTime(I)I"), repository);
        assertTrue(analysis.supported(), analysis.rejection());
        byte[] compiled = FormulaCompiler.compileForVerification(original,
                Map.of("getCastTime(I)I", analysis.formula().readableExpression()), repository);
        for (String kind : List.of("INSTANT", "CONTINUOUS", "NORMAL")) {
            for (int time : new int[]{Integer.MIN_VALUE, -1, 0, 1, 40, 16_777_217, Integer.MAX_VALUE}) {
                for (byte[] implementation : List.of(original, compiled)) {
                    Map<String, byte[]> definitions = new HashMap<>(fixture.definitions());
                    definitions.put(SPELL.replace('/', '.'), implementation);
                    ClassLoader loader = loader(definitions);
                    Class<?> spellType = loader.loadClass(SPELL.replace('/', '.'));
                    Object spell = spellType.getConstructor().newInstance();
                    Object castType = loader.loadClass(TYPE.replace('/', '.')).getField(kind).get(null);
                    spellType.getField("castKind").set(spell, castType);
                    spellType.getField("castTime").setInt(spell, time);
                    assertEquals(kind.equals("INSTANT") ? 0 : time, spellType.getMethod("getCastTime", int.class).invoke(spell, 3));
                    assertEquals(1, spellType.getField("typeCalls").getInt(spell));
                }
            }
        }
    }

    @Test
    void effectiveCastProjectionRetainsNativeNullContinuousSoftcapRoundingAndVirtualDispatch() throws Exception {
        Fixture fixture = fixture(true);
        byte[] original = fixture.definitions().get(SPELL.replace('/', '.'));
        ClassNode owner = read(original);
        var repository = FormulaClassRepository.of(owner);
        var analysis = AutomaticFormulaExtractor.analyze(owner, method(owner, EFFECTIVE), repository);
        assertTrue(analysis.supported(), analysis.rejection());
        byte[] compiled = FormulaCompiler.compileForVerification(original,
                Map.of(EFFECTIVE, analysis.formula().readableExpression()), repository);
        ClassLoader nativeLoader = loader(fixture.definitions());
        var rewritten = new HashMap<>(fixture.definitions());
        rewritten.put(SPELL.replace('/', '.'), compiled);
        ClassLoader compiledLoader = loader(rewritten);
        int checked = 0;
        for (String kind : List.of("INSTANT", "CONTINUOUS", "NORMAL")) {
            for (int time : new int[]{0, 1, 3, 40, 16_777_217, Integer.MAX_VALUE}) {
                for (Double attribute : new Double[]{null, -1d, 0d, 0.5d, 1d, Math.nextDown(1.5), 1.5,
                        Math.nextUp(1.5), 2d, 10d, Double.POSITIVE_INFINITY, Double.NaN}) {
                    int expected = invoke(nativeLoader, SPELL, kind, time, attribute, null);
                    assertEquals(expected, invoke(compiledLoader, SPELL, kind, time, attribute, null),
                            kind + ":time=" + time + ":attribute=" + attribute);
                    checked++;
                }
            }
        }
        assertEquals(216, checked);
        byte[] changed = FormulaCompiler.transform(original,
                Map.of(EFFECTIVE, '(' + analysis.formula().readableExpression() + ") + 7"), repository);
        rewritten.put(SPELL.replace('/', '.'), changed);
        var changedLoader = loader(rewritten);
        assertEquals(49, invoke(changedLoader, "formula/CastOverride", "NORMAL", 4, null, 42));
        assertEquals(28, invoke(changedLoader, "formula/CastOverride", "CONTINUOUS", 4, 0.5d, 42));
    }

    @Test
    void contextualCastProjectionMatchesNativeValuesAcrossFixedTypesAndVirtualBaseOverrides() throws Exception {
        Fixture fixture = fixture(true);
        byte[] original = fixture.definitions().get(SPELL.replace('/', '.'));
        ClassLoader nativeLoader = loader(fixture.definitions());
        int[] times = {0, 1, 3, 20, 25, 40, 16_777_217, Integer.MAX_VALUE};
        Double[] attributes = {null, -1d, 0d, Math.nextDown(0.5), 0.5d, Math.nextUp(0.5),
                0.625d, 1d, 1d + Math.scalb(1d, -25), Math.nextDown(1.5), 1.5d, Math.nextUp(1.5),
                2d, 10d, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN};
        int checked = 0;
        for (String kind : List.of("INSTANT", "CONTINUOUS", "LONG")) {
            for (boolean overrideBase : List.of(false, true)) {
                String receiver = "formula/Context" + (overrideBase ? "Override" : "") + kind;
                ClassNode owner = read(original);
                var repository = FormulaClassRepository.of(owner);
                fixture.definitions().values().forEach(value -> repository.add(read(value)));
                var analysis = AutomaticFormulaExtractor.analyze(owner, method(owner, EFFECTIVE), repository, receiver);
                assertTrue(analysis.supported(), receiver + ": " + analysis.rejection());
                byte[] compiled = FormulaCompiler.compileForVerification(original,
                        Map.of(EFFECTIVE, analysis.formula().readableExpression()), repository, receiver);
                var rewritten = new HashMap<>(fixture.definitions());
                rewritten.put(SPELL.replace('/', '.'), compiled);
                ClassLoader compiledLoader = loader(rewritten);
                for (int time : times) {
                    for (Double attribute : attributes) {
                        int baseTime = overrideBase ? 4 : time;
                        Integer overrideTime = overrideBase ? time : null;
                        int expected = invoke(nativeLoader, receiver, kind, baseTime, attribute, overrideTime);
                        assertEquals(expected, invoke(compiledLoader, receiver, kind, baseTime, attribute, overrideTime),
                                receiver + ":time=" + time + ":attribute=" + attribute);
                        checked++;
                    }
                }
                if (!overrideBase && !kind.equals("INSTANT")) {
                    double reduction = kind.equals("CONTINUOUS") ? 0.5 : 1.5;
                    assertEquals(13, invoke(compiledLoader, receiver, kind, 25, reduction, null));
                }
            }
        }
        assertEquals(816, checked);
    }

    @Test
    void changedCastFieldsConstantsAndBranchesRemainRejected() throws Exception {
        for (int mutation = 0; mutation < 5; mutation++) {
            var repository = FormulaClassRepository.fromJar(JAR);
            var owner = repository.classNode(SPELL);
            MethodNode target = method(owner, mutation < 2 ? "getCastTime(I)I" : EFFECTIVE);
            if (mutation == 0) {
                target.instructions.iterator().forEachRemaining(instruction -> {
                    if (instruction instanceof FieldInsnNode field && field.name.equals("castTime")) field.name = "otherTime";
                });
            } else if (mutation == 1) {
                for (AbstractInsnNode instruction : target.instructions.toArray())
                    if (instruction instanceof JumpInsnNode jump)
                        target.instructions.set(instruction, new JumpInsnNode(Opcodes.IF_ACMPEQ, jump.label));
            } else if (mutation == 2) {
                target.instructions.iterator().forEachRemaining(instruction -> {
                    if (instruction instanceof LdcInsnNode value && value.cst.equals(2d)) value.cst = 3d;
                });
            } else if (mutation == 3) {
                target.instructions.iterator().forEachRemaining(instruction -> {
                    if (instruction instanceof FieldInsnNode field && field.name.equals("CAST_TIME_REDUCTION")) field.name = "OTHER";
                });
            } else {
                var jumps = target.instructions.iterator();
                JumpInsnNode first = null;
                while (jumps.hasNext()) {
                    var instruction = jumps.next();
                    if (instruction instanceof JumpInsnNode jump) {
                        if (first == null) first = jump;
                        else { first.label = jump.label; break; }
                    }
                }
            }
            assertFalse(AutomaticFormulaExtractor.analyze(owner, target, repository).supported(), "mutation=" + mutation);
        }
    }

    private static int invoke(ClassLoader loader, String ownerName, String kind, int time,
                              Double attribute, Integer overriddenTime) throws Exception {
        Class<?> spellType = loader.loadClass(SPELL.replace('/', '.'));
        Object spell = loader.loadClass(ownerName.replace('/', '.')).getConstructor().newInstance();
        Object castType = loader.loadClass(TYPE.replace('/', '.')).getField(kind).get(null);
        spellType.getField("castKind").set(spell, castType);
        spellType.getField("castTime").setInt(spell, time);
        if (overriddenTime != null) spell.getClass().getField("overriddenTime").setInt(spell, overriddenTime);
        Class<?> livingType = loader.loadClass(LIVING.replace('/', '.'));
        Object caster = attribute == null ? null : livingType.getConstructor().newInstance();
        if (caster != null) livingType.getField("attribute").setDouble(caster, attribute);
        int result = (Integer) spellType.getMethod("getEffectiveCastTime", int.class, livingType).invoke(spell, 3, caster);
        assertEquals(1, spellType.getField("timeCalls").getInt(spell));
        if (caster != null) assertEquals(1, livingType.getField("attributeCalls").getInt(caster));
        return result;
    }

    private static Fixture fixture(boolean countTime) throws Exception {
        var repository = FormulaClassRepository.fromJar(JAR);
        var nativeSpell = repository.classNode(SPELL);
        ClassNode spell = owner(SPELL);
        for (var field : List.of(new FieldNode(Opcodes.ACC_PUBLIC, "castTime", "I", null, null),
                new FieldNode(Opcodes.ACC_PUBLIC, "castKind", 'L' + TYPE + ';', null, null),
                new FieldNode(Opcodes.ACC_PUBLIC, "timeCalls", "I", null, null),
                new FieldNode(Opcodes.ACC_PUBLIC, "typeCalls", "I", null, null))) spell.fields.add(field);
        MethodNode base = copy(method(nativeSpell, "getCastTime(I)I"));
        if (countTime) base.instructions.insert(increment(SPELL, "timeCalls"));
        spell.methods.add(base);
        spell.methods.add(copy(method(nativeSpell, EFFECTIVE)));
        var typeGetter = new MethodNode(Opcodes.ACC_PUBLIC, "getCastType", "()L" + TYPE + ';', null, null);
        typeGetter.instructions.add(increment(SPELL, "typeCalls"));
        typeGetter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        typeGetter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, SPELL, "castKind", 'L' + TYPE + ';'));
        typeGetter.instructions.add(new InsnNode(Opcodes.ARETURN));
        spell.methods.add(typeGetter);
        ClassNode castType = owner(TYPE);
        var typeInit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        for (String name : List.of("INSTANT", "CONTINUOUS", "NORMAL", "LONG")) {
            castType.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, 'L' + TYPE + ';', null, null));
            construct(typeInit.instructions, TYPE);
            typeInit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, TYPE, name, 'L' + TYPE + ';'));
        }
        typeInit.instructions.add(new InsnNode(Opcodes.RETURN));
        castType.methods.add(typeInit);
        ClassNode registry = owner(REGISTRY);
        registry.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "value", "Ljava/lang/Object;", null, null));
        var get = new MethodNode(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, REGISTRY, "value", "Ljava/lang/Object;"));
        get.instructions.add(new InsnNode(Opcodes.ARETURN));
        registry.methods.add(get);
        ClassNode attributes = owner(ATTRIBUTES);
        attributes.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "CAST_TIME_REDUCTION", 'L' + REGISTRY + ';', null, null));
        var attributeInit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        construct(attributeInit.instructions, REGISTRY);
        attributeInit.instructions.add(new InsnNode(Opcodes.DUP));
        construct(attributeInit.instructions, ATTRIBUTE);
        attributeInit.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, REGISTRY, "value", "Ljava/lang/Object;"));
        attributeInit.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, ATTRIBUTES, "CAST_TIME_REDUCTION", 'L' + REGISTRY + ';'));
        attributeInit.instructions.add(new InsnNode(Opcodes.RETURN));
        attributes.methods.add(attributeInit);
        ClassNode living = owner(LIVING);
        living.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "attribute", "D", null, null));
        living.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "attributeCalls", "I", null, null));
        for (String name : List.of("getAttributeValue", "m_21133_")) {
            var getter = new MethodNode(Opcodes.ACC_PUBLIC, name, "(L" + ATTRIBUTE + ";)D", null, null);
            getter.instructions.add(increment(LIVING, "attributeCalls"));
            getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, LIVING, "attribute", "D"));
            getter.instructions.add(new InsnNode(Opcodes.DRETURN));
            living.methods.add(getter);
        }
        ClassNode utils = owner(UTILS);
        utils.methods.add(copy(method(repository.classNode(UTILS), "softCapFormula(D)D")));
        ClassNode override = owner("formula/CastOverride");
        override.superName = SPELL;
        ((MethodInsnNode) method(override, "<init>()V").instructions.get(1)).owner = SPELL;
        override.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "overriddenTime", "I", null, null));
        var overrideTime = new MethodNode(Opcodes.ACC_PUBLIC, "getCastTime", "(I)I", null, null);
        overrideTime.instructions.add(increment(SPELL, "timeCalls"));
        overrideTime.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        overrideTime.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, override.name, "overriddenTime", "I"));
        overrideTime.instructions.add(new InsnNode(Opcodes.IRETURN));
        override.methods.add(overrideTime);
        Map<String, byte[]> definitions = new HashMap<>();
        for (ClassNode node : List.of(spell, castType, registry, attributes, living, utils, owner(ATTRIBUTE), override))
            definitions.put(node.name.replace('/', '.'), computedBytes(node));
        for (String kind : List.of("INSTANT", "CONTINUOUS", "LONG")) {
            for (boolean overrideBase : List.of(false, true)) {
                ClassNode context = owner("formula/Context" + (overrideBase ? "Override" : "") + kind);
                context.superName = overrideBase ? override.name : SPELL;
                ((MethodInsnNode) method(context, "<init>()V").instructions.get(1)).owner = context.superName;
                var fixedType = new MethodNode(Opcodes.ACC_PUBLIC, "getCastType", "()L" + TYPE + ';', null, null);
                fixedType.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, TYPE, kind, 'L' + TYPE + ';'));
                fixedType.instructions.add(new InsnNode(Opcodes.ARETURN));
                context.methods.add(fixedType);
                definitions.put(context.name.replace('/', '.'), computedBytes(context));
            }
        }
        return new Fixture(definitions);
    }

    private static InsnList increment(String owner, String name) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, name, "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, name, "I"));
        return code;
    }

    private static void construct(InsnList code, String owner) {
        code.add(new TypeInsnNode(Opcodes.NEW, owner));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false));
    }

    private static ClassNode owner(String name) {
        ClassNode result = new ClassNode();
        result.version = Opcodes.V17;
        result.access = Opcodes.ACC_PUBLIC;
        result.name = name;
        result.superName = "java/lang/Object";
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        result.methods.add(constructor);
        return result;
    }

    private static MethodNode copy(MethodNode original) {
        MethodNode copy = new MethodNode(original.access, original.name, original.desc, original.signature,
                original.exceptions.toArray(String[]::new));
        original.accept(copy);
        return copy;
    }

    private static MethodNode method(ClassNode owner, String key) {
        return owner.methods.stream().filter(value -> (value.name + value.desc).equals(key)).findFirst().orElseThrow();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode();
        new ClassReader(bytes).accept(owner, 0);
        return owner;
    }

    private static byte[] bytes(ClassNode owner) {
        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] computedBytes(ClassNode owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static ClassLoader loader(Map<String, byte[]> definitions) {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = definitions.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private record Fixture(Map<String, byte[]> definitions) {}
}
