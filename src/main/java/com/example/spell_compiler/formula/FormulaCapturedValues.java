package com.example.spell_compiler.formula;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class FormulaCapturedValues {
    private static final String DESCRIPTOR = "(DIDDDLjava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)D";
    private static final AtomicLong SERIAL = new AtomicLong();

    private FormulaCapturedValues() {}

    public static boolean active(String key, Object spell, Object caster, Object carrier) {
        var anchor = FormulaAutomaticSites.find(key);
        if (anchor == null) return false;
        if (anchor.contextMode() == FormulaAutomaticSites.ContextMode.ENTITY) {
            if (!(carrier instanceof net.minecraft.world.entity.Entity entity)) return false;
            var context = entity.getPersistentData().getCompound(FormulaEntityContext.DATA_KEY);
            if (!FormulaEntityContext.valid(context)) return false;
            var site = FormulaAutomaticSites.findForSpell(context.getString("class"), anchor);
            return site != null && FormulaRuntimeDispatcher.isActive(site.definition().dispatchKey(), entity);
        }
        if (anchor.contextMode() == FormulaAutomaticSites.ContextMode.SCOPED) {
            var frame = FormulaCastContext.peek();
            if (frame == null || frame.spell() == null || !FormulaCastContext.hasVerifiedSource(frame.spell().getClass().getName())) return false;
            spell = frame.spell();
            caster = frame.caster();
        }
        var definition = FormulaNumericHooks.runtimeDefinition(key, spell);
        return definition != null && FormulaRuntimeDispatcher.isActive(definition.dispatchKey(), caster);
    }

    static FormulaRuntimeDispatcher.Invoker compile(String expression, List<FormulaCapturedInput> inputs) {
        Map<String, AutomaticFormulaExtractor.Variable> variables = new LinkedHashMap<>();
        parameter(variables, "native_value", Type.DOUBLE_TYPE, 0);
        parameter(variables, "level", Type.INT_TYPE, 2);
        parameter(variables, "spell_power", Type.DOUBLE_TYPE, 3);
        parameter(variables, "school_spell_power", Type.DOUBLE_TYPE, 5);
        parameter(variables, "power_multiplier", Type.DOUBLE_TYPE, 7);
        for (int index = 0; index < inputs.size(); index++) {
            FormulaCapturedInput input = inputs.get(index);
            Type type = input.type();
            var previous = variables.putIfAbsent(input.name(), new AutomaticFormulaExtractor.Variable(input.name(), type,
                    List.of(new VarInsnNode(Opcodes.ALOAD, 11), new LdcInsnNode(index), new InsnNode(Opcodes.AALOAD),
                            new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"),
                            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", type.getClassName() + "Value",
                                    "()" + input.descriptor(), false))));
            if (previous != null) throw new IllegalArgumentException("重复的捕获输入：" + input.name());
        }
        ClassNode generated = new ClassNode(Opcodes.ASM9);
        generated.version = Opcodes.V21;
        generated.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
        generated.name = FormulaCapturedValues.class.getName().replace('.', '/') + "$Compiled$" + SERIAL.incrementAndGet();
        generated.superName = "java/lang/Object";
        generated.methods.add(FormulaCompiler.compileExpressionMethod("evaluate", DESCRIPTOR, expression, variables));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        generated.accept(writer);
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(writer.toByteArray(), false,
                    MethodHandles.Lookup.ClassOption.NESTMATE);
            MethodHandle invocation = lookup.findStatic(lookup.lookupClass(), "evaluate",
                            MethodType.fromMethodDescriptorString(DESCRIPTOR, FormulaCapturedValues.class.getClassLoader()))
                    .asSpreader(Object[].class, 8).asType(MethodType.methodType(Number.class, Object[].class));
            return (receiver, arguments) -> (Number) invocation.invokeExact(arguments);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("无法编译捕获输入公式", failure);
        }
    }

    private static void parameter(Map<String, AutomaticFormulaExtractor.Variable> variables, String name, Type type, int slot) {
        variables.put(name, new AutomaticFormulaExtractor.Variable(name, type,
                List.of(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot)), slot));
    }
}
