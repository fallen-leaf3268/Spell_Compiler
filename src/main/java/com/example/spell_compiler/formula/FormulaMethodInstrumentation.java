package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

final class FormulaMethodInstrumentation {
    enum TargetKind { BASELINE, DISCOVERED_NATIVE, ADDON }

    private static final String DISPATCHER = "com/example/spell_compiler/formula/FormulaRuntimeDispatcher";
    private static final String INVOKER = DISPATCHER + "$Invoker";

    private FormulaMethodInstrumentation() {}

    static MethodNode instrument(String owner, MethodNode original, TargetKind targetKind, boolean duration) {
        MethodNode replacement = copy(original);
        boolean dynamicReceiver = targetKind != TargetKind.BASELINE;
        FormulaMethodCompatibility.recordOriginal(owner, replacement);
        prependGuard(owner, replacement, duration, dynamicReceiver);
        FormulaMethodCompatibility.requireDispatch(owner, replacement, dynamicReceiver);
        boolean instance = (original.access & Opcodes.ACC_STATIC) == 0;
        if (targetKind != TargetKind.ADDON && (targetKind == TargetKind.BASELINE || instance)) {
            protectCastReturns(replacement);
            Type result = Type.getReturnType(original.desc);
            if (instance && (result.getSort() == Type.FLOAT || result.getSort() == Type.DOUBLE))
                protectFiniteReturns(owner, replacement, result);
        }
        return replacement;
    }

    private static MethodNode copy(MethodNode original) {
        MethodNode result = new MethodNode(Opcodes.ASM9, original.access, original.name, original.desc,
                original.signature, original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        original.accept(result);
        return result;
    }

    private static void prependGuard(String owner, MethodNode method, boolean duration, boolean dynamicReceiver) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        int actor = -1;
        int[] slots = new int[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            slots[index] = slot;
            if (actor < 0 && arguments[index].getSort() == Type.OBJECT
                    && arguments[index].getInternalName().startsWith("net/minecraft/world/entity/")) actor = slot;
            slot += arguments[index].getSize();
        }
        int invokerSlot = Math.max(method.maxLocals, slot);
        InsnList prefix = new InsnList();
        if (dynamicReceiver) {
            prefix.add(new LdcInsnNode(owner.replace('/', '.')));
            prefix.add(new LdcInsnNode(method.name + method.desc));
            prefix.add(isStatic ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, 0));
            prefix.add(actor < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, actor));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCHER, "resolveDeclared",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)L" + INVOKER + ';', false));
        } else {
            prefix.add(new LdcInsnNode(owner.replace('/', '.') + '#' + method.name + method.desc));
            prefix.add(actor < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, actor));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCHER, "resolve",
                    "(Ljava/lang/String;Ljava/lang/Object;)L" + INVOKER + ';', false));
        }
        prefix.add(new VarInsnNode(Opcodes.ASTORE, invokerSlot));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, invokerSlot));
        LabelNode original = new LabelNode();
        prefix.add(new JumpInsnNode(Opcodes.IFNULL, original));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, invokerSlot));
        prefix.add(isStatic ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new LdcInsnNode(arguments.length));
        prefix.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int index = 0; index < arguments.length; index++) {
            Type argument = arguments[index];
            prefix.add(new InsnNode(Opcodes.DUP));
            prefix.add(new LdcInsnNode(index));
            prefix.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slots[index]));
            box(prefix, argument);
            prefix.add(new InsnNode(Opcodes.AASTORE));
        }
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCHER, "invoke",
                "(L" + INVOKER + ";Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Number;", false));
        int resultSlot = invokerSlot + 1;
        prefix.add(new VarInsnNode(Opcodes.ASTORE, resultSlot));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        prefix.add(new JumpInsnNode(Opcodes.IFNULL, original));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        Type result = Type.getReturnType(method.desc);
        prefix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", result.getClassName() + "Value",
                "()" + result.getDescriptor(), false));
        if (duration) {
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
            prefix.add(new LdcInsnNode(method.name + method.desc));
            prefix.add(new VarInsnNode(Opcodes.ILOAD, 1));
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 2));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/example/spell_compiler/formula/FormulaDurationSafety",
                    result.getSort() == Type.INT ? "validateOverrideInt" : "validateOverrideFloat",
                    '(' + result.getDescriptor() + "Ljava/lang/Object;Ljava/lang/String;ILjava/lang/Object;)"
                            + result.getDescriptor(), false));
        }
        prefix.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        prefix.add(original);
        boolean expanded = false;
        for (var instruction : method.instructions)
            if (instruction instanceof FrameNode frame && frame.type == Opcodes.F_NEW) {
                expanded = true;
                break;
            }
        if (expanded) {
            List<Object> locals = initialLocals(owner, method, arguments);
            prefix.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, null));
        } else prefix.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(prefix);
        method.maxLocals = resultSlot + 1;
        method.maxStack = Math.max(method.maxStack, 8);
    }

    private static void protectCastReturns(MethodNode method) {
        boolean cast = (method.name + method.desc).equals("getCastTime(I)I");
        boolean effective = (method.name + method.desc).equals("getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I");
        if (!cast && !effective) return;
        for (var instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.IRETURN) continue;
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            if (effective) code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/example/spell_compiler/formula/FormulaCastSafety",
                    effective ? "guardEffectiveCastTime" : "guardCastTime",
                    effective ? "(ILjava/lang/Object;ILjava/lang/Object;)I" : "(ILjava/lang/Object;I)I", false));
            method.instructions.insertBefore(instruction, code);
        }
        method.maxStack += effective ? 3 : 2;
    }

    private static void protectFiniteReturns(String owner, MethodNode method, Type result) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        boolean expanded = false;
        for (var instruction : method.instructions)
            if (instruction instanceof FrameNode frame && frame.type == Opcodes.F_NEW) {
                expanded = true;
                break;
            }
        List<Object> locals = initialLocals(owner, method, arguments);
        for (var instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != result.getOpcode(Opcodes.IRETURN)) continue;
            InsnList code = new InsnList();
            code.add(new InsnNode(result.getSize() == 1 ? Opcodes.DUP : Opcodes.DUP2));
            String wrapper = result.getSort() == Type.FLOAT ? "java/lang/Float" : "java/lang/Double";
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "isFinite", '(' + result.getDescriptor() + ")Z", false));
            LabelNode finite = new LabelNode();
            code.add(new JumpInsnNode(Opcodes.IFNE, finite));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new LdcInsnNode(method.name + method.desc));
            code.add(new LdcInsnNode(arguments.length));
            code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            int slot = 1;
            for (int index = 0; index < arguments.length; index++) {
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode(index));
                code.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ILOAD), slot));
                box(code, arguments[index]);
                code.add(new InsnNode(Opcodes.AASTORE));
                slot += arguments[index].getSize();
            }
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/example/spell_compiler/formula/FormulaRuntimeHooks",
                    result.getSort() == Type.FLOAT ? "finiteFloat" : "finiteDouble",
                    '(' + result.getDescriptor() + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + result.getDescriptor(), false));
            code.add(finite);
            code.add(new FrameNode(expanded ? Opcodes.F_NEW : Opcodes.F_FULL, locals.size(), locals.toArray(),
                    1, new Object[]{result.getSort() == Type.FLOAT ? Opcodes.FLOAT : Opcodes.DOUBLE}));
            method.instructions.insertBefore(instruction, code);
        }
        method.maxStack = Math.max(method.maxStack, 10);
    }

    private static List<Object> initialLocals(String owner, MethodNode method, Type[] arguments) {
        List<Object> locals = new ArrayList<>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) locals.add(owner);
        for (Type type : arguments) {
            locals.add(switch (type.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
                case Type.LONG -> Opcodes.LONG;
                case Type.FLOAT -> Opcodes.FLOAT;
                case Type.DOUBLE -> Opcodes.DOUBLE;
                case Type.ARRAY -> type.getDescriptor();
                default -> type.getInternalName();
            });
        }
        return locals;
    }

    private static void box(InsnList instructions, Type type) {
        String wrapper = switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
        if (wrapper != null) instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf",
                '(' + type.getDescriptor() + ")L" + wrapper + ';', false));
    }
}
