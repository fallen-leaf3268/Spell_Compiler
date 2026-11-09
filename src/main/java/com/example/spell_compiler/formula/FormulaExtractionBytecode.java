package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

import static com.example.spell_compiler.formula.FormulaExtractionModel.*;

final class FormulaExtractionBytecode {
    static final int MAX_SYNTHESIZED_INPUT_INSTRUCTIONS = 8192;
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String HOLDER = "net/minecraft/world/entity/ai/attributes/Attribute";

    private FormulaExtractionBytecode() {}

    static Value[] callInputs(Value receiver, List<Value> arguments) {
        int offset = receiver == null ? 0 : 1;
        Value[] values = new Value[arguments.size() + offset];
        if (receiver != null) values[0] = receiver;
        for (int index = 0; index < arguments.size(); index++) values[index + offset] = arguments.get(index);
        return values;
    }

    static List<AbstractInsnNode> append(AbstractInsnNode instruction, Value... values) {
        var instructions = new ArrayList<AbstractInsnNode>();
        for (Value value : values) instructions.addAll(copyCode(value.instructions()));
        instructions.add(instruction);
        return instructions;
    }

    static List<AbstractInsnNode> copyCode(List<AbstractInsnNode> code) {
        if (code.size() > MAX_SYNTHESIZED_INPUT_INSTRUCTIONS)
            throw new Rejected("Synthesized input code exceeds its analysis bound");
        var labels = new IdentityHashMap<LabelNode, LabelNode>();
        for (AbstractInsnNode instruction : code)
            if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
        var result = new ArrayList<AbstractInsnNode>(code.size());
        for (AbstractInsnNode instruction : code) result.add(instruction.clone(labels));
        return result;
    }

    static int argumentSlots(MethodNode method) {
        int slots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) slots += argument.getSize();
        return slots;
    }

    static Type numericReturn(MethodNode method) {
        Type type = Type.getReturnType(method.desc);
        return type.equals(Type.BOOLEAN_TYPE) ? Type.INT_TYPE : type;
    }

    static Type arithmeticType(int category) {
        return switch (category) {
            case 0 -> Type.INT_TYPE;
            case 1 -> Type.LONG_TYPE;
            case 2 -> Type.FLOAT_TYPE;
            case 3 -> Type.DOUBLE_TYPE;
            default -> throw new Rejected("Invalid numeric opcode category " + category);
        };
    }

    static boolean numeric(Type type) {
        return type.equals(Type.INT_TYPE) || type.equals(Type.LONG_TYPE)
                || type.equals(Type.FLOAT_TYPE) || type.equals(Type.DOUBLE_TYPE);
    }

    static void requireNumeric(Type type) {
        if (!numeric(type)) throw new Rejected("Unsupported numeric type " + type.getDescriptor());
    }

    static boolean reference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    static boolean variableMatches(AbstractInsnNode instruction, int opcode, int slot) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode && variable.var == slot;
    }

    static boolean jumpMatches(AbstractInsnNode instruction, int opcode, AbstractInsnNode expected) {
        if (!(instruction instanceof JumpInsnNode jump) || jump.getOpcode() != opcode) return false;
        AbstractInsnNode target = jump.label;
        while (target != null && target.getOpcode() < 0) target = target.getNext();
        return target == expected;
    }

    static void privateLabels(List<AbstractInsnNode> code) {
        var labels = new IdentityHashMap<LabelNode, LabelNode>();
        for (AbstractInsnNode instruction : code)
            if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
        code.replaceAll(instruction -> instruction.clone(labels));
    }

    static List<AbstractInsnNode> executable(AbstractInsnNode[] instructions, int start, int end) {
        var result = new ArrayList<AbstractInsnNode>();
        for (int index = start; index < end; index++) if (instructions[index].getOpcode() >= 0) result.add(instructions[index]);
        return result;
    }

    static boolean fieldMatches(AbstractInsnNode instruction, String owner, String name, String descriptor) {
        return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(owner) && FormulaMinecraftNames.field(field.name).equals(FormulaMinecraftNames.field(name)) && field.desc.equals(descriptor);
    }

    static boolean callMatches(AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == opcode
                && call.owner.equals(owner) && FormulaMinecraftNames.method(call.name).equals(FormulaMinecraftNames.method(name)) && call.desc.equals(descriptor);
    }

    static boolean castMatches(AbstractInsnNode instruction, String owner) {
        return instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals(owner);
    }

    static boolean attributeValueCall(AbstractInsnNode instruction) {
        return callMatches(instruction, Opcodes.INVOKEVIRTUAL, LIVING_ENTITY, "getAttributeValue",
                "(L" + HOLDER + ";)D") || callMatches(instruction, Opcodes.INVOKEVIRTUAL, LIVING_ENTITY, "m_21133_",
                "(L" + HOLDER + ";)D");
    }
}
