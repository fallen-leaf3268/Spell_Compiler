package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaCastContext {
    private static final String OWNER = "com/example/spell_compiler/formula/FormulaCastContext";
    private static final String ENTER = "(Ljava/lang/Object;ILjava/lang/Object;)V";
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final Map<String, List<Root>> ROOTS = new ConcurrentHashMap<>();
    private static final Set<Root> VERIFIED = ConcurrentHashMap.newKeySet();

    public record Frame(Object spell, int level, Object caster, Frame previous) {}

    public record Root(String owner, String methodName, String methodDescriptor, String methodFingerprint,
                       int casterSlot, int levelSlot, int spellSlot) {
        public Root {
            owner = Objects.requireNonNull(owner).replace('/', '.');
            Objects.requireNonNull(methodName);
            Type.getMethodType(methodDescriptor);
            Objects.requireNonNull(methodFingerprint);
            if (casterSlot < 0 || levelSlot < 0 || spellSlot < 0) throw new IllegalArgumentException("作用域入口缺少原施法参数");
        }
    }

    private FormulaCastContext() {}

    public static void enter(Object spell, int level, Object caster) {
        CURRENT.set(new Frame(spell, level, caster, CURRENT.get()));
    }

    public static void enterDisabled() { enter(null, 0, null); }

    public static void exit() {
        Frame current = CURRENT.get();
        if (current == null || current.previous() == null) CURRENT.remove();
        else CURRENT.set(current.previous());
    }

    public static Frame peek() { return CURRENT.get(); }

    public static int overrideInt(int original, String key) {
        Number value = evaluate(original, key);
        return value == null ? original : value.intValue();
    }

    public static float overrideFloat(float original, String key) {
        Number value = evaluate(original, key);
        return value == null ? original : value.floatValue();
    }

    public static double overrideDouble(double original, String key) {
        Number value = evaluate(original, key);
        return value == null ? original : value.doubleValue();
    }

    private static Number evaluate(Number original, String key) {
        return evaluate(original, key, null);
    }

    public static int overrideInt(int original, Object[] inputs, String key) {
        Number value = evaluate(original, key, inputs);
        return value == null ? original : value.intValue();
    }

    public static float overrideFloat(float original, Object[] inputs, String key) {
        Number value = evaluate(original, key, inputs);
        return value == null ? original : value.floatValue();
    }

    public static double overrideDouble(double original, Object[] inputs, String key) {
        Number value = evaluate(original, key, inputs);
        return value == null ? original : value.doubleValue();
    }

    private static Number evaluate(Number original, String key, Object[] inputs) {
        Frame frame = CURRENT.get();
        var anchor = FormulaAutomaticSites.find(key);
        if (frame == null || frame.spell() == null || anchor == null
                || anchor.contextMode() != FormulaAutomaticSites.ContextMode.SCOPED
                || !hasVerifiedSource(frame.spell().getClass().getName())) return null;
        var site = FormulaAutomaticSites.findForSpell(frame.spell().getClass().getName(), anchor);
        return site == null ? null : FormulaNumericHooks.evaluate(site.definition(), original,
                frame.spell(), frame.level(), frame.caster(), inputs);
    }

    public static synchronized void register(String spellClass, List<Root> roots) {
        ROOTS.put(spellClass.replace('/', '.'), List.copyOf(roots));
        Set<Root> required = new HashSet<>();
        ROOTS.values().forEach(required::addAll);
        VERIFIED.retainAll(required);
    }

    public static boolean hasRoots(String owner) { return !roots(owner).isEmpty(); }

    public static Set<String> targetClasses() {
        Set<String> result = new HashSet<>();
        ROOTS.values().forEach(roots -> roots.forEach(root -> result.add(root.owner())));
        return Set.copyOf(result);
    }

    private static List<Root> roots(String owner) {
        String name = owner.replace('/', '.');
        return ROOTS.values().stream().flatMap(List::stream).filter(root -> root.owner().equals(name)).distinct().toList();
    }

    public static Runnable prepareRoots(String owner, List<MethodNode> methods) {
        Map<MethodNode, Root> selected = new LinkedHashMap<>();
        for (Root root : roots(owner)) {
            var matching = methods.stream().filter(method -> method.name.equals(root.methodName())
                    && method.desc.equals(root.methodDescriptor())).toList();
            if (matching.size() != 1) throw new IllegalArgumentException("作用域入口方法数量不符：" + root);
            MethodNode method = matching.get(0);
            if (!FormulaAutomaticInjector.fingerprint(method).equals(root.methodFingerprint())
                    || !FormulaAutomaticInjector.contextIntact(method, root.casterSlot(), root.levelSlot(), root.spellSlot()))
                throw new IllegalArgumentException("作用域入口结构或原参数槽发生变化：" + root);
            validate(method, root);
            Root previous = selected.putIfAbsent(method, root);
            if (previous != null && !previous.equals(root)) throw new IllegalArgumentException("同一入口存在冲突作用域：" + root);
        }
        return () -> selected.forEach(FormulaCastContext::wrap);
    }

    private static void validate(MethodNode method, Root root) {
        if (method.name.startsWith("<") || (method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0)
            throw new IllegalArgumentException("作用域入口不是普通可执行方法：" + root);
        Map<Integer, Type> parameters = new HashMap<>();
        int slot = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) parameters.put(slot++, Type.getType(Object.class));
        for (Type type : Type.getArgumentTypes(method.desc)) {
            parameters.put(slot, type);
            slot += type.getSize();
        }
        if (!Type.INT_TYPE.equals(parameters.get(root.levelSlot()))) throw new IllegalArgumentException("作用域等级不是原整数参数");
        for (int reference : new int[]{root.spellSlot(), root.casterSlot()}) {
            Type type = parameters.get(reference);
            if (type == null || type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY)
                throw new IllegalArgumentException("作用域对象不是原引用参数");
        }
    }

    private static void wrap(MethodNode method, Root root) {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        boolean expanded = false;
        for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode frame && frame.type == Opcodes.F_NEW) expanded = true;
            if (normalReturn(instruction)) method.instructions.insertBefore(instruction,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "exit", "()V", false));
        }
        InsnList prefix = new InsnList();
        if (root == null) prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "enterDisabled", "()V", false));
        else {
            prefix.add(new VarInsnNode(Opcodes.ALOAD, root.spellSlot()));
            prefix.add(new VarInsnNode(Opcodes.ILOAD, root.levelSlot()));
            prefix.add(new VarInsnNode(Opcodes.ALOAD, root.casterSlot()));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "enter", ENTER, false));
        }
        prefix.add(start);
        method.instructions.insert(prefix);
        method.instructions.add(end);
        method.instructions.add(handler);
        method.instructions.add(new FrameNode(expanded ? Opcodes.F_NEW : Opcodes.F_FULL, 0, null,
                1, new Object[]{"java/lang/Throwable"}));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "exit", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        method.maxStack = Math.max(method.maxStack, 3);
    }

    public static boolean verifyRootHooks(String owner, ClassNode target) {
        boolean retained = true;
        for (Root root : roots(owner)) {
            if (!target.name.replace('/', '.').equals(root.owner())) {
                VERIFIED.remove(root);
                retained = false;
                continue;
            }
            var methods = target.methods.stream().filter(method -> method.name.equals(root.methodName())
                    && method.desc.equals(root.methodDescriptor())).toList();
            if (methods.size() != 1) {
                VERIFIED.remove(root);
                retained = false;
                continue;
            }
            MethodNode method = methods.get(0);
            List<AbstractInsnNode> instructions = FormulaAutomaticInjector.executable(method);
            if (retained(method, root, instructions)) VERIFIED.add(root);
            else {
                VERIFIED.remove(root);
                retained = false;
                if (!disabledRetained(method, instructions)) disable(method);
            }
        }
        return retained;
    }

    public static boolean hasVerifiedSource(String spellClass) {
        List<Root> required = ROOTS.getOrDefault(spellClass.replace('/', '.'), List.of());
        return !required.isEmpty() && VERIFIED.containsAll(required);
    }

    private static boolean retained(MethodNode method, Root root, List<AbstractInsnNode> instructions) {
        if (instructions.size() < 6 || !load(instructions.get(0), Opcodes.ALOAD, root.spellSlot())
                || !load(instructions.get(1), Opcodes.ILOAD, root.levelSlot())
                || !load(instructions.get(2), Opcodes.ALOAD, root.casterSlot())
                || !call(instructions.get(3), "enter", ENTER)) return false;
        long enters = instructions.stream().filter(instruction -> call(instruction, "enter", ENTER)
                || call(instruction, "enterDisabled", "()V")).count();
        if (enters != 1) return false;
        return cleanupRetained(method, instructions, instructions.get(3));
    }

    private static boolean disabledRetained(MethodNode method, List<AbstractInsnNode> instructions) {
        if (instructions.isEmpty() || !call(instructions.get(0), "enterDisabled", "()V")) return false;
        if (instructions.stream().filter(instruction -> call(instruction, "enter", ENTER)
                || call(instruction, "enterDisabled", "()V")).count() != 1) return false;
        return cleanupRetained(method, instructions, instructions.get(0));
    }

    private static boolean cleanupRetained(MethodNode method, List<AbstractInsnNode> instructions, AbstractInsnNode enter) {
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if (block.type != null || previous(block.start) != enter) continue;
            AbstractInsnNode exit = next(block.handler);
            AbstractInsnNode throwing = next(exit);
            if (!call(exit, "exit", "()V") || throwing == null || throwing.getOpcode() != Opcodes.ATHROW
                    || next(throwing) != null || next(block.end) != exit) continue;
            int start = method.instructions.indexOf(block.start), end = method.instructions.indexOf(block.end);
            if (end <= start || method.instructions.indexOf(block.handler) < end) continue;
            boolean valid = true;
            for (AbstractInsnNode instruction : instructions) {
                if (normalReturn(instruction)) {
                    int index = method.instructions.indexOf(instruction);
                    if (index <= start || index >= end || !call(previous(instruction), "exit", "()V")) valid = false;
                }
                if (call(instruction, "exit", "()V") && instruction != exit && !normalReturn(next(instruction))) valid = false;
            }
            if (valid) return true;
        }
        return false;
    }

    private static void disable(MethodNode method) {
        Set<LabelNode> handlers = new HashSet<>();
        for (var block : method.tryCatchBlocks) {
            AbstractInsnNode exit = next(block.handler);
            AbstractInsnNode throwing = next(exit);
            if (call(exit, "exit", "()V") && throwing != null && throwing.getOpcode() == Opcodes.ATHROW)
                handlers.add(block.handler);
        }
        method.tryCatchBlocks.removeIf(block -> handlers.contains(block.handler));
        for (var instruction : method.instructions.toArray()) {
            if (call(instruction, "enter", ENTER)) {
                InsnList discard = new InsnList();
                for (int index = 0; index < 3; index++) discard.add(new InsnNode(Opcodes.POP));
                method.instructions.insertBefore(instruction, discard);
                method.instructions.remove(instruction);
            } else if (call(instruction, "exit", "()V") || call(instruction, "enterDisabled", "()V"))
                method.instructions.remove(instruction);
        }
        wrap(method, null);
    }

    private static boolean normalReturn(AbstractInsnNode instruction) {
        return instruction != null && instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN;
    }

    private static boolean call(AbstractInsnNode instruction, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals(OWNER) && call.name.equals(name) && call.desc.equals(descriptor);
    }

    private static boolean load(AbstractInsnNode instruction, int opcode, int slot) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode && variable.var == slot;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        for (var next = instruction.getNext(); next != null; next = next.getNext()) if (next.getOpcode() >= 0) return next;
        return null;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        for (var previous = instruction.getPrevious(); previous != null; previous = previous.getPrevious())
            if (previous.getOpcode() >= 0) return previous;
        return null;
    }
}
