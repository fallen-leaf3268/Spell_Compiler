package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;

final class FormulaDispatchContract {
    private static final String DISPATCHER = "com/example/spell_compiler/formula/FormulaRuntimeDispatcher";
    private static final String INVOKER = 'L' + DISPATCHER + "$Invoker;";
    private final String owner, key;
    private final boolean dynamic, isStatic;
    private final int actor;

    FormulaDispatchContract(String owner, MethodNode method, boolean dynamic) {
        this.owner = owner.replace('/', '.');
        this.key = method.name + method.desc;
        this.dynamic = dynamic;
        this.isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        int selectedActor = -1, slot = isStatic ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (selectedActor < 0 && argument.getSort() == Type.OBJECT
                    && argument.getInternalName().startsWith("net/minecraft/world/entity/")) selectedActor = slot;
            slot += argument.getSize();
        }
        actor = selectedActor;
    }

    String failure(MethodNode method) {
        try {
            var flow = new Flow(owner.replace('.', '/'), method);
            var resolve = flow.single(DISPATCHER, dynamic ? "resolveDeclared" : "resolve",
                    dynamic ? "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)" + INVOKER
                            : "(Ljava/lang/String;Ljava/lang/Object;)" + INVOKER);
            var invoke = flow.single(DISPATCHER, "invoke", '(' + INVOKER + "Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Number;");
            if (resolve == null || invoke == null) return "分派查询或执行调用缺失、重复或不可达";
            List<SourceValue> resolved = flow.arguments(resolve);
            if (dynamic) {
                if (!constant(resolved.get(0), owner) || !constant(resolved.get(1), key)
                        || !reference(resolved.get(2), isStatic ? -1 : 0) || !reference(resolved.get(3), actor))
                    return "动态分派键或来源参数改变";
            } else if (!constant(resolved.get(0), owner + '#' + key) || !reference(resolved.get(1), actor))
                return "分派键或施法者参数改变";
            List<SourceValue> invoked = flow.arguments(invoke);
            if (!flow.producedBy(invoked.get(0), resolve) || !reference(invoked.get(1), isStatic ? -1 : 0))
                return "执行调用未使用对应查询结果或原接收对象";
            if (!argumentsIntact(flow, invoked.get(2), invoke)) return "执行参数数组不再来自原方法参数";
            JumpInsnNode absent = flow.nullBranch(resolve, invoke), missing = flow.nullBranch(invoke, null);
            if (absent == null || missing == null || flow.executable(absent.label) != flow.executable(missing.label))
                return "空查询或空执行结果未回到同一原生路径";
            int fallback = flow.executable(absent.label), invocation = flow.index(invoke);
            if (flow.reaches(0, fallback, flow.index(resolve))
                    || flow.reaches(flow.index(resolve) + 1, invocation, flow.index(absent))
                    || !flow.reaches(flow.index(absent) + 1, invocation, fallback)
                    || flow.reaches(fallback, invocation, -1)) return "分派执行或原生回退控制流改变";
            var unbox = flow.singleUnbox(invoke, Type.getReturnType(method.desc));
            if (unbox == null || !flow.returning(flow.index(missing) + 1, fallback, unbox)
                    || !flow.returning(fallback, invocation, null)) return "配置结果未到达返回路径或原生回退不可用";
            return null;
        } catch (AnalyzerException | RuntimeException failure) {
            return "无法验证分派控制流：" + failure.getMessage();
        }
    }

    private boolean argumentsIntact(Flow flow, SourceValue value, MethodInsnNode invocation) {
        var arrays = value.insns.stream().filter(instruction -> instruction instanceof TypeInsnNode type
                && type.getOpcode() == Opcodes.ANEWARRAY && type.desc.equals("java/lang/Object")).toList();
        if (arrays.size() != 1) return false;
        var array = arrays.get(0);
        Type[] arguments = Type.getArgumentTypes(flow.method.desc);
        if (!integer(flow.stack(array, 0), arguments.length)) return false;
        Set<Integer> assigned = new HashSet<>();
        int[] slots = new int[arguments.length];
        int slot = isStatic ? 0 : 1;
        for (int index = 0; index < arguments.length; index++) { slots[index] = slot; slot += arguments[index].getSize(); }
        for (var instruction : flow.method.instructions) {
            if (instruction.getOpcode() != Opcodes.AASTORE || !flow.reachable(instruction)
                    || !flow.stack(instruction, 2).insns.contains(array)) continue;
            int selected = -1;
            for (int index = 0; index < arguments.length; index++) if (integer(flow.stack(instruction, 1), index)) selected = index;
            if (selected < 0 || !assigned.add(selected) || !parameter(flow.stack(instruction, 0), slots[selected], arguments[selected])
                    || flow.reaches(flow.index(array), flow.index(invocation), flow.index(instruction))) return false;
        }
        return assigned.size() == arguments.length;
    }

    private static boolean parameter(SourceValue value, int slot, Type type) {
        Set<AbstractInsnNode> origins = new HashSet<>(value.insns);
        origins.removeIf(instruction -> instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.name.equals("valueOf") && call.owner.equals(wrapper(type))
                && call.desc.equals('(' + type.getDescriptor() + ")L" + wrapper(type) + ';'));
        return origins.size() == 1 && origins.iterator().next() instanceof VarInsnNode load
                && load.getOpcode() == type.getOpcode(Opcodes.ILOAD) && load.var == slot;
    }

    private static String wrapper(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> "";
        };
    }

    private static boolean reference(SourceValue value, int slot) {
        if (slot >= 0) return parameter(value, slot, Type.getType(Object.class));
        return value.insns.size() == 1 && value.insns.iterator().next().getOpcode() == Opcodes.ACONST_NULL;
    }

    private static boolean constant(SourceValue value, Object expected) {
        return value.insns.size() == 1 && value.insns.iterator().next() instanceof LdcInsnNode literal && expected.equals(literal.cst);
    }

    private static boolean integer(SourceValue value, int expected) {
        if (constant(value, expected)) return true;
        if (value.insns.size() != 1) return false;
        var instruction = value.insns.iterator().next();
        return instruction instanceof IntInsnNode integer && (integer.getOpcode() == Opcodes.BIPUSH || integer.getOpcode() == Opcodes.SIPUSH)
                ? integer.operand == expected : instruction.getOpcode() >= Opcodes.ICONST_M1 && instruction.getOpcode() <= Opcodes.ICONST_5
                && instruction.getOpcode() - Opcodes.ICONST_0 == expected;
    }

    private static final class Flow {
        private final MethodNode method;
        private final Frame<SourceValue>[] frames;
        private final Map<Integer, Set<Integer>> edges = new HashMap<>();

        Flow(String owner, MethodNode method) throws AnalyzerException {
            this.method = method;
            frames = new Analyzer<>(new Origins()) {
                @Override protected void newControlFlowEdge(int from, int to) {
                    edges.computeIfAbsent(from, ignored -> new HashSet<>()).add(to);
                }
            }.analyze(owner, method);
        }

        int index(AbstractInsnNode instruction) { return method.instructions.indexOf(instruction); }
        boolean reachable(AbstractInsnNode instruction) { return frames[index(instruction)] != null; }
        int executable(AbstractInsnNode instruction) {
            while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getNext();
            return index(instruction);
        }
        SourceValue stack(AbstractInsnNode instruction, int offset) {
            var frame = frames[index(instruction)];
            return frame.getStack(frame.getStackSize() - 1 - offset);
        }
        List<SourceValue> arguments(MethodInsnNode call) {
            List<SourceValue> values = new ArrayList<>();
            int count = Type.getArgumentTypes(call.desc).length;
            for (int index = count - 1; index >= 0; index--) values.add(stack(call, index));
            return values;
        }
        boolean producedBy(SourceValue value, MethodInsnNode call) {
            Set<AbstractInsnNode> expected = new HashSet<>();
            expected.add(call);
            arguments(call).forEach(argument -> expected.addAll(argument.insns));
            if (call.getOpcode() != Opcodes.INVOKESTATIC)
                expected.addAll(stack(call, Type.getArgumentTypes(call.desc).length).insns);
            return value.insns.equals(expected);
        }
        MethodInsnNode single(String owner, String name, String descriptor) {
            MethodInsnNode selected = null;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(owner)
                    && call.name.equals(name) && call.desc.equals(descriptor) && reachable(call)) {
                if (selected != null) return null;
                selected = call;
            }
            return selected;
        }
        JumpInsnNode nullBranch(MethodInsnNode producer, MethodInsnNode excluded) {
            JumpInsnNode selected = null;
            for (var instruction : method.instructions) if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFNULL && reachable(jump)
                    && producedBy(stack(jump, 0), producer)
                    && (excluded == null || !stack(jump, 0).insns.contains(excluded))) {
                if (selected != null) return null;
                selected = jump;
            }
            return selected;
        }
        MethodInsnNode singleUnbox(MethodInsnNode invocation, Type result) {
            MethodInsnNode selected = null;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("java/lang/Number") && call.name.equals(result.getClassName() + "Value")
                    && call.desc.equals("()" + result.getDescriptor()) && reachable(call)
                    && producedBy(stack(call, 0), invocation)) {
                if (selected != null) return null;
                selected = call;
            }
            return selected;
        }
        boolean reaches(int start, int target, int blocked) {
            Set<Integer> visited = new HashSet<>();
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                int current = pending.removeFirst();
                if (current == blocked || !visited.add(current)) continue;
                if (current == target) return true;
                pending.addAll(edges.getOrDefault(current, Set.of()));
            }
            return false;
        }
        boolean returning(int start, int forbidden, MethodInsnNode value) {
            boolean returned = false;
            Set<Integer> visited = new HashSet<>();
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                int current = pending.removeFirst();
                if (current == forbidden) return false;
                if (!visited.add(current)) continue;
                var instruction = method.instructions.get(current);
                if (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.DRETURN) {
                    if (value != null && !stack(instruction, 0).insns.contains(value)) return false;
                    returned = true;
                }
                pending.addAll(edges.getOrDefault(current, Set.of()));
            }
            return returned;
        }
    }

    private static final class Origins extends SourceInterpreter {
        Origins() { super(Opcodes.ASM9); }
        @Override public SourceValue newParameterValue(boolean instance, int local, Type type) {
            return new SourceValue(type.getSize(), new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
        }
        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
        @Override public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            return combine(super.unaryOperation(instruction, value), instruction, List.of(value));
        }
        @Override public SourceValue binaryOperation(AbstractInsnNode instruction, SourceValue first, SourceValue second) {
            return combine(super.binaryOperation(instruction, first, second), instruction, List.of(first, second));
        }
        @Override public SourceValue naryOperation(AbstractInsnNode instruction, List<? extends SourceValue> values) {
            return combine(super.naryOperation(instruction, values), instruction, values);
        }
        private SourceValue combine(SourceValue result, AbstractInsnNode instruction, List<? extends SourceValue> values) {
            if (result == null) return null;
            Set<AbstractInsnNode> origins = new HashSet<>();
            origins.add(instruction);
            for (var value : values) origins.addAll(value.insns);
            return new SourceValue(result.getSize(), origins);
        }
    }
}
