package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.FormulaFunctions;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.JarFile;

final class RealSpellFormulaHarness {
    static final String ABSTRACT = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String SCHOOL = "io/redspace/ironsspellbooks/api/spells/SchoolType";
    private static final String ATTRIBUTE = "net/minecraft/world/entity/ai/attributes/Attribute";
    private static final String ATTRIBUTE_MAP = "net/minecraft/world/entity/ai/attributes/AttributeMap";
    private static final String REGISTRY_OBJECT = "net/minecraftforge/registries/RegistryObject";
    private static final String ATTRIBUTES = "io/redspace/ironsspellbooks/api/registry/AttributeRegistry";
    private static final String CONFIG_PARAMETER = "io/redspace/ironsspellbooks/api/config/SpellConfigParameter";
    private static final String CONFIG_MANAGER = "io/redspace/ironsspellbooks/api/config/SpellConfigManager";
    private final Map<String, byte[]> dependencies = new HashMap<>();

    enum Caster { PRESENT, MISSING_SCHOOL_ATTRIBUTE, NULL }

    record Inputs(int level, int base, int growth, double global, double school, double config, Caster caster) {}

    RealSpellFormulaHarness(Path source) throws Exception {
        try (JarFile jar = new JarFile(source.toFile())) {
            add(abstractSpell(read(jar, ABSTRACT)));
            add(school(read(jar, SCHOOL)));
        }
        add(simple(ENTITY, "java/lang/Object"));
        add(simple(ATTRIBUTE, "java/lang/Object"));
        add(attributeMap());
        add(living());
        add(registryObject());
        add(staticFields(ATTRIBUTES, Map.of("SPELL_POWER", 'L' + REGISTRY_OBJECT + ';')));
        add(staticFields(CONFIG_PARAMETER, Map.of("POWER_MULTIPLIER", 'L' + CONFIG_PARAMETER + ';',
                "SCHOOL", 'L' + CONFIG_PARAMETER + ';')));
        add(configManager());
        add(nativeClamp());
        String helper = "com/example/spell_compiler/formula/FormulaFunctions";
        try (var stream = RealSpellFormulaHarness.class.getClassLoader().getResourceAsStream(helper + ".class")) {
            if (stream == null) throw new IOException("Missing formula runtime functions");
            dependencies.put(helper.replace('/', '.'), stream.readAllBytes());
        }
    }

    Class<?> load(ClassNode original, byte[] bytes, List<MethodNode> methods) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(dependencies);
        classes.put(original.name.replace('/', '.'), shell(bytes, methods));
        ClassLoader loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] definition = classes.get(name);
                if (definition == null) throw new ClassNotFoundException(name);
                return defineClass(name, definition, 0, definition.length);
            }
        };
        Class<?> attribute = loader.loadClass(ATTRIBUTE.replace('/', '.'));
        Object global = attribute.getConstructor().newInstance();
        Object registered = loader.loadClass(REGISTRY_OBJECT.replace('/', '.')).getConstructor().newInstance();
        registered.getClass().getField("value").set(registered, global);
        loader.loadClass(ATTRIBUTES.replace('/', '.')).getField("SPELL_POWER").set(null, registered);
        Class<?> parameter = loader.loadClass(CONFIG_PARAMETER.replace('/', '.'));
        for (String name : List.of("POWER_MULTIPLIER", "SCHOOL")) {
            parameter.getField(name).set(null, parameter.getConstructor().newInstance());
        }
        return loader.loadClass(original.name.replace('/', '.'));
    }

    void addDependency(String name, byte[] bytes) {
        dependencies.put(name.replace('/', '.'), bytes);
    }

    Object create(Class<?> type, Inputs input) throws Exception {
        Object instance = type.getConstructor().newInstance();
        type.getField("baseSpellPower").setInt(instance, input.base());
        type.getField("spellPowerPerLevel").setInt(instance, input.growth());
        type.getField("probeMultiplier").setDouble(instance, input.config());
        ClassLoader loader = type.getClassLoader();
        Object school = loader.loadClass(SCHOOL.replace('/', '.')).getConstructor().newInstance();
        Object schoolAttribute = loader.loadClass(ATTRIBUTE.replace('/', '.')).getConstructor().newInstance();
        school.getClass().getField("powerAttribute").set(school, (Supplier<Object>) () -> schoolAttribute);
        type.getField("probeSchool").set(instance, school);
        return instance;
    }

    Number invoke(Class<?> type, Object instance, MethodNode method, Inputs input) throws Exception {
        List<Class<?>> parameterTypes = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.equals(Type.INT_TYPE)) {
                parameterTypes.add(int.class);
                arguments.add(input.level());
            } else if (argument.getSort() == Type.OBJECT) {
                Class<?> parameterType = Class.forName(argument.getClassName(), false, type.getClassLoader());
                parameterTypes.add(parameterType);
                arguments.add(caster(type, input));
            } else throw new IllegalArgumentException("Unsupported probe argument: " + argument);
        }
        var target = type.getDeclaredMethod(method.name, parameterTypes.toArray(Class<?>[]::new));
        target.setAccessible(true);
        return (Number) target.invoke(instance, arguments.toArray());
    }

    private Object caster(Class<?> type, Inputs input) throws Exception {
        if (input.caster() == Caster.NULL) return null;
        ClassLoader loader = type.getClassLoader();
        Object caster = loader.loadClass(LIVING.replace('/', '.')).getConstructor().newInstance();
        caster.getClass().getField("global").setDouble(caster, input.global());
        caster.getClass().getField("school").setDouble(caster, input.school());
        Object attributes = loader.loadClass(ATTRIBUTE_MAP.replace('/', '.')).getConstructor().newInstance();
        attributes.getClass().getField("present").setBoolean(attributes, input.caster() != Caster.MISSING_SCHOOL_ATTRIBUTE);
        caster.getClass().getField("attributes").set(caster, attributes);
        return caster;
    }

    static Map<String, Integer> defaults(ClassNode original) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (MethodNode method : original.methods) {
            if (!method.name.equals("<init>")) continue;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                        || !field.desc.equals("I")) continue;
                Integer value = literal(instruction.getPrevious());
                if (value != null) values.put(field.name, value);
            }
        }
        return values;
    }

    private static Integer literal(org.objectweb.asm.tree.AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Integer number) return number;
        if (instruction instanceof IntInsnNode constant) return constant.operand;
        if (instruction != null && instruction.getOpcode() >= Opcodes.ICONST_M1 && instruction.getOpcode() <= Opcodes.ICONST_5)
            return instruction.getOpcode() - Opcodes.ICONST_0;
        return null;
    }

    static byte[] bytes(ClassNode owner) {
        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] shell(byte[] source, List<MethodNode> selected) {
        ClassNode original = new ClassNode();
        new ClassReader(source).accept(original, 0);
        ClassWriter writer = type(original.name, ABSTRACT);
        constructor(writer, ABSTRACT);
        for (MethodNode method : original.methods) {
            if (selected.stream().anyMatch(target -> target.name.equals(method.name) && target.desc.equals(method.desc))) {
                method.accept(writer);
            }
        }
        return finish(writer);
    }

    private static byte[] abstractSpell(ClassNode original) {
        ClassWriter writer = type(ABSTRACT, "java/lang/Object");
        for (String field : List.of("baseManaCost", "manaCostPerLevel", "baseSpellPower", "spellPowerPerLevel", "castTime"))
            field(writer, field, "I");
        field(writer, "probeMultiplier", "D");
        field(writer, "probeSchool", 'L' + SCHOOL + ';');
        constructor(writer, "java/lang/Object");
        for (MethodNode method : original.methods) {
            if (List.of("getSpellPower", "getEntityPowerMultiplier", "getSchoolType").contains(method.name)) method.accept(writer);
        }
        return finish(writer);
    }

    private static byte[] school(ClassNode original) {
        ClassWriter writer = type(SCHOOL, "java/lang/Object");
        field(writer, "powerAttribute", "Ljava/util/function/Supplier;");
        constructor(writer, "java/lang/Object");
        original.methods.stream().filter(method -> method.name.equals("getPowerFor")).forEach(method -> method.accept(writer));
        return finish(writer);
    }

    private static byte[] attributeMap() {
        ClassWriter writer = type(ATTRIBUTE_MAP, "java/lang/Object");
        field(writer, "present", "Z");
        constructor(writer, "java/lang/Object");
        var method = method(writer, Opcodes.ACC_PUBLIC, "m_22171_", "(L" + ATTRIBUTE + ";)Z");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, ATTRIBUTE_MAP, "present", "Z");
        method.visitInsn(Opcodes.IRETURN);
        end(method);
        return finish(writer);
    }

    private static byte[] living() {
        ClassWriter writer = type(LIVING, ENTITY);
        field(writer, "global", "D");
        field(writer, "school", "D");
        field(writer, "attributes", 'L' + ATTRIBUTE_MAP + ';');
        constructor(writer, ENTITY);
        var value = method(writer, Opcodes.ACC_PUBLIC, "m_21133_", "(L" + ATTRIBUTE + ";)D");
        value.visitVarInsn(Opcodes.ALOAD, 1);
        value.visitFieldInsn(Opcodes.GETSTATIC, ATTRIBUTES, "SPELL_POWER", 'L' + REGISTRY_OBJECT + ';');
        value.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGISTRY_OBJECT, "get", "()Ljava/lang/Object;", false);
        var school = new org.objectweb.asm.Label();
        value.visitJumpInsn(Opcodes.IF_ACMPNE, school);
        value.visitVarInsn(Opcodes.ALOAD, 0);
        value.visitFieldInsn(Opcodes.GETFIELD, LIVING, "global", "D");
        value.visitInsn(Opcodes.DRETURN);
        value.visitLabel(school);
        value.visitVarInsn(Opcodes.ALOAD, 0);
        value.visitFieldInsn(Opcodes.GETFIELD, LIVING, "school", "D");
        value.visitInsn(Opcodes.DRETURN);
        end(value);
        var map = method(writer, Opcodes.ACC_PUBLIC, "m_21204_", "()L" + ATTRIBUTE_MAP + ';');
        map.visitVarInsn(Opcodes.ALOAD, 0);
        map.visitFieldInsn(Opcodes.GETFIELD, LIVING, "attributes", 'L' + ATTRIBUTE_MAP + ';');
        map.visitInsn(Opcodes.ARETURN);
        end(map);
        return finish(writer);
    }

    private static byte[] registryObject() {
        ClassWriter writer = type(REGISTRY_OBJECT, "java/lang/Object");
        field(writer, "value", "Ljava/lang/Object;");
        constructor(writer, "java/lang/Object");
        var method = method(writer, Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, REGISTRY_OBJECT, "value", "Ljava/lang/Object;");
        method.visitInsn(Opcodes.ARETURN);
        end(method);
        return finish(writer);
    }

    private static byte[] configManager() {
        ClassWriter writer = type(CONFIG_MANAGER, "java/lang/Object");
        var method = method(writer, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getSpellConfigValue",
                "(L" + ABSTRACT + ";L" + CONFIG_PARAMETER + ";)Ljava/lang/Object;");
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitFieldInsn(Opcodes.GETSTATIC, CONFIG_PARAMETER, "POWER_MULTIPLIER", 'L' + CONFIG_PARAMETER + ';');
        var school = new org.objectweb.asm.Label();
        method.visitJumpInsn(Opcodes.IF_ACMPNE, school);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, ABSTRACT, "probeMultiplier", "D");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitLabel(school);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, ABSTRACT, "probeSchool", 'L' + SCHOOL + ';');
        method.visitInsn(Opcodes.ARETURN);
        end(method);
        return finish(writer);
    }

    private static byte[] nativeClamp() throws IOException {
        String owner = "net/minecraft/util/Mth";
        ClassNode original = new ClassNode();
        try (var stream = RealSpellFormulaHarness.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            if (stream == null) throw new IOException("Missing Minecraft Mth bytecode");
            new ClassReader(stream.readAllBytes()).accept(original, 0);
        }
        MethodNode clamp = original.methods.stream().filter(method -> method.desc.equals("(III)I")
                && (method.name.equals("clamp") || method.name.equals("m_14045_"))).findFirst().orElseThrow();
        clamp.name = "m_14045_";
        ClassWriter writer = type(owner, "java/lang/Object");
        clamp.accept(writer);
        return finish(writer);
    }

    private static ClassNode read(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name + ".class");
        if (entry == null) throw new IOException("Missing native class " + name);
        ClassNode node = new ClassNode();
        try (var stream = jar.getInputStream(entry)) {
            new ClassReader(stream.readAllBytes()).accept(node, 0);
        }
        return node;
    }

    private void add(byte[] bytes) {
        dependencies.put(new ClassReader(bytes).getClassName().replace('/', '.'), bytes);
    }

    private static byte[] simple(String name, String parent) {
        ClassWriter writer = type(name, parent);
        constructor(writer, parent);
        return finish(writer);
    }

    private static byte[] staticFields(String name, Map<String, String> fields) {
        ClassWriter writer = type(name, "java/lang/Object");
        fields.forEach((field, descriptor) -> writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                field, descriptor, null, null).visitEnd());
        constructor(writer, "java/lang/Object");
        return finish(writer);
    }

    private static ClassWriter type(String name, String parent) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, parent, null);
        return writer;
    }

    private static void field(ClassWriter writer, String name, String descriptor) {
        writer.visitField(Opcodes.ACC_PUBLIC, name, descriptor, null, null).visitEnd();
    }

    private static void constructor(ClassWriter writer, String parent) {
        var method = method(writer, Opcodes.ACC_PUBLIC, "<init>", "()V");
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        end(method);
    }

    private static MethodVisitor method(ClassWriter writer, int access, String name, String descriptor) {
        var method = writer.visitMethod(access, name, descriptor, null, null);
        method.visitCode();
        return method;
    }

    private static void end(MethodVisitor method) {
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static byte[] finish(ClassWriter writer) {
        writer.visitEnd();
        return writer.toByteArray();
    }
}
