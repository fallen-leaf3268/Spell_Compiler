package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

import static com.example.spell_compiler.formula.AutomaticFormulaExtractor.*;
import static com.example.spell_compiler.formula.FormulaExtractionModel.*;
import static com.example.spell_compiler.formula.FormulaExtractionBytecode.*;

final class FormulaExtractionInputs {
    private final ClassNode rootOwner;
    private final MethodNode rootMethod;
    private final String receiverClass;
    private final Map<String, Variable> variables = new LinkedHashMap<>();
    private final Map<String, String> families = new HashMap<>();
    private final List<AbstractInsnNode> prelude = new ArrayList<>();
    private final List<AbstractInsnNode> initializers = new ArrayList<>();
    private int nextLocal;
    private int conditionalDepth;
    private int conditionalBranches;
    private boolean exactReceiverGuard;

    FormulaExtractionInputs(ClassNode owner, MethodNode method, String receiverClass) {
        rootOwner = owner;
        rootMethod = method;
        this.receiverClass = receiverClass;
        nextLocal = Math.max(method.maxLocals, argumentSlots(method));
    }

    Formula finish(Value value, Map<String, NativePower> nativePowers) {
        return new Formula(rootOwner.name, rootMethod.name, rootMethod.desc, value.node().text(),
                value.type(), variables, finishedPrelude(), nextLocal, nativePowers);
    }

    void checkConditionalBudget() {
        if (++conditionalBranches > 128 || conditionalDepth >= 32)
            throw new Rejected("Conditional control flow exceeds its analysis bound");
    }

    int beginConditional() {
        conditionalDepth++;
        return prelude.size();
    }

    List<AbstractInsnNode> takeBranchPrelude(int prefix) {
        var result = new ArrayList<>(prelude.subList(prefix, prelude.size()));
        prelude.subList(prefix, prelude.size()).clear();
        return result;
    }

    void endConditional() {
        conditionalDepth--;
    }

    void appendBranchPrelude(List<AbstractInsnNode> code) {
        prelude.addAll(code);
        checkPrelude();
    }

    void requireExactReceiver() {
        exactReceiverGuard = true;
    }

    void initializeHelper(String owner) {
        prelude.add(new LdcInsnNode(owner.replace('/', '.')));
        prelude.add(new InsnNode(Opcodes.ICONST_1));
        prelude.add(new LdcInsnNode(Type.getObjectType(rootOwner.name)));
        prelude.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                "()Ljava/lang/ClassLoader;", false));
        prelude.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
        prelude.add(new InsnNode(Opcodes.POP));
    }

    Value parameter(String name, Type type, int slot) {
        if (variables.containsKey(name)) throw new Rejected("Duplicate formula parameter name: " + name);
        List<AbstractInsnNode> code = List.of(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot));
        variables.put(name, new Variable(name, type, code));
        families.put(name, "parameter:" + slot);
        return new Value(type, new Input(type, name), code, null);
    }

    Value bind(String requested, Type type, List<AbstractInsnNode> code, String family) {
        requested = FormulaNames.input(requested);
        String previous = families.putIfAbsent(requested, family);
        if (previous != null && !previous.equals(family))
            throw new Rejected("Variable name has different sources: " + requested);
        String name = requested;
        int suffix = 2;
        while (variables.containsKey(name)) name = requested + '_' + suffix++;
        if (conditionalDepth > 0) {
            Value value = cached(type, new Input(type, name), code, null);
            variables.put(name, new Variable(name, type, copyCode(value.instructions())));
            return value;
        }
        int slot = allocate(type);
        variables.put(name, new Variable(name, type, code, slot));
        prelude.addAll(copyCode(code));
        prelude.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), slot));
        checkPrelude();
        return new Value(type, new Input(type, name),
                List.of(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot)), null);
    }

    int allocate(Type type) {
        int slot = nextLocal;
        nextLocal += type.getSize();
        return slot;
    }

    Value capture(Value value) {
        if (conditionalDepth > 0) return cached(value.type(), value.node(), value.instructions(), value.reference());
        int slot = allocate(value.type());
        prelude.addAll(copyCode(value.instructions()));
        int store = reference(value.type()) ? Opcodes.ASTORE : value.type().getOpcode(Opcodes.ISTORE);
        int load = reference(value.type()) ? Opcodes.ALOAD : value.type().getOpcode(Opcodes.ILOAD);
        prelude.add(new VarInsnNode(store, slot));
        checkPrelude();
        return new Value(value.type(), value.node(),
                List.of(new VarInsnNode(load, slot)), value.reference());
    }

    private Value cached(Type type, Node node, List<AbstractInsnNode> code, Reference source) {
        int slot = allocate(type);
        int ready = allocate(Type.INT_TYPE);
        int store = reference(type) ? Opcodes.ASTORE : type.getOpcode(Opcodes.ISTORE);
        int load = reference(type) ? Opcodes.ALOAD : type.getOpcode(Opcodes.ILOAD);
        int zero = reference(type) ? Opcodes.ACONST_NULL : type.equals(Type.LONG_TYPE) ? Opcodes.LCONST_0
                : type.equals(Type.FLOAT_TYPE) ? Opcodes.FCONST_0 : type.equals(Type.DOUBLE_TYPE) ? Opcodes.DCONST_0 : Opcodes.ICONST_0;
        initializers.add(new InsnNode(zero));
        initializers.add(new VarInsnNode(store, slot));
        initializers.add(new InsnNode(Opcodes.ICONST_0));
        initializers.add(new VarInsnNode(Opcodes.ISTORE, ready));
        var evaluate = copyCode(code);
        evaluate.add(new VarInsnNode(store, slot));
        evaluate.add(new InsnNode(Opcodes.ICONST_1));
        evaluate.add(new VarInsnNode(Opcodes.ISTORE, ready));
        prelude.addAll(copyCode(evaluate));
        checkPrelude();
        var lazy = new ArrayList<AbstractInsnNode>();
        LabelNode loaded = new LabelNode();
        lazy.add(new VarInsnNode(Opcodes.ILOAD, ready));
        lazy.add(new JumpInsnNode(Opcodes.IFNE, loaded));
        lazy.addAll(evaluate);
        lazy.add(loaded);
        lazy.add(new VarInsnNode(load, slot));
        return new Value(type, node, lazy, source);
    }

    private void checkPrelude() {
        if (prelude.size() + initializers.size() > MAX_SYNTHESIZED_INPUT_INSTRUCTIONS)
            throw new Rejected("Synthesized input code exceeds its analysis bound");
    }

    private List<AbstractInsnNode> finishedPrelude() {
        if (!exactReceiverGuard) {
            var result = new ArrayList<AbstractInsnNode>(initializers);
            result.addAll(prelude);
            return result;
        }
        var result = new ArrayList<AbstractInsnNode>();
        LabelNode exact = new LabelNode();
        result.add(new VarInsnNode(Opcodes.ALOAD, 0));
        result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        result.add(new LdcInsnNode(Type.getObjectType(receiverClass == null ? rootOwner.name : receiverClass)));
        result.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, exact));
        for (AbstractInsnNode instruction : rootMethod.instructions) {
            if (!(instruction instanceof FrameNode) && !(instruction instanceof LineNumberNode)) result.add(instruction);
        }
        result.add(exact);
        result.addAll(initializers);
        result.addAll(prelude);
        return result;
    }
}
