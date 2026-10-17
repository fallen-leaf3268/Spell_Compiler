package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FormulaWidePower {
    private static final Set<String> PARAMETERS = Set.of("baseSpellPower", "spellPowerPerLevel");
    private static final String ENTITY = "net/minecraft/world/entity/Entity";

    private FormulaWidePower() {}

    static boolean applies(Map<String, AutomaticFormulaExtractor.Variable> variables) {
        return variables.values().stream().anyMatch(variable -> parameter(variable) != null);
    }

    static Map<String, AutomaticFormulaExtractor.Variable> variables(MethodNode method,
            FormulaClassRepository repository, Map<String, AutomaticFormulaExtractor.Variable> original) {
        if (!applies(original)) return original;
        if ((method.access & Opcodes.ACC_STATIC) != 0)
            throw new IllegalArgumentException("强度参数的 64 位读取不支持静态公式入口");
        int caster = casterSlot(method, repository);
        Map<String, AutomaticFormulaExtractor.Variable> result = new LinkedHashMap<>(original);
        for (var entry : original.entrySet()) {
            var variable = entry.getValue();
            String parameter = parameter(variable);
            if (parameter == null && !entry.getKey().equals("level")) continue;
            if (!variable.type().equals(Type.INT_TYPE)) continue;
            List<AbstractInsnNode> code = new ArrayList<>();
            if (variable.localSlot() >= 0) code.add(new VarInsnNode(Opcodes.ILOAD, variable.localSlot()));
            else code.addAll(variable.instructions());
            if (parameter != null) {
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new LdcInsnNode(parameter));
                code.add(caster >= 0 ? new VarInsnNode(Opcodes.ALOAD, caster) : new InsnNode(Opcodes.ACONST_NULL));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "com/example/spell_compiler/formula/FormulaWidePower", "readParameter",
                        "(ILjava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)J", false));
            } else code.add(new InsnNode(Opcodes.I2L));
            result.put(entry.getKey(), new AutomaticFormulaExtractor.Variable(entry.getKey(), Type.LONG_TYPE, code));
        }
        return Map.copyOf(result);
    }

    public static long readParameter(int original, Object spell, String name, Object caster) {
        if (!PARAMETERS.contains(name)) throw new IllegalArgumentException("未知强度参数：" + name);
        if (FormulaNativeValues.isEvaluatingOriginal()) return original;
        Object actor = caster == null ? FormulaNativeValues.currentActor() : caster;
        var override = FormulaNativeValues.override(spell, actor);
        var value = override == null ? null : override.parameters().get(name);
        return value == null ? original : value.getAsLong();
    }

    private static String parameter(AutomaticFormulaExtractor.Variable variable) {
        if (!variable.type().equals(Type.INT_TYPE)) return null;
        for (AbstractInsnNode instruction : variable.instructions()) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.desc.equals("I") && PARAMETERS.contains(field.name)) return field.name;
        }
        return null;
    }

    private static int casterSlot(MethodNode method, FormulaClassRepository repository) {
        int slot = 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.getSort() == Type.OBJECT && (argument.getInternalName().equals(ENTITY)
                    || repository.isSubtype(argument.getInternalName(), ENTITY))) return slot;
            slot += argument.getSize();
        }
        return -1;
    }
}
