package com.example.spell_compiler.formula;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class FormulaDataFlow {
    private static final int MAX_INSTRUCTIONS = 50000;
    private static final int MAX_OPERATIONS = 2000000;

    public enum NodeKind { PARAMETER, INSTRUCTION, EXCEPTION }
    public enum EdgeKind { NORMAL, EXCEPTION }
    public enum Lowering { DIRECT, CONTROL_FLOW, FIELD_SOURCE, CALL_SUMMARY, JVM_OPERATION }
    public record MethodId(String owner, String name, String descriptor) {
        public MethodId { owner = owner.replace('.', '/'); }
        public String key() { return owner + '#' + name + descriptor; }
    }
    public record Node(int id, NodeKind kind, Type type, int parameterSlot,
                       AbstractInsnNode instruction, int outputIndex) {}
    public record Edge(int from, int to, EdgeKind kind, String catchType) {}
    public record ExceptionRegion(int start, int end, int handler, String catchType) {}
    public record Diagnostic(String code, int instruction, String message) {}
    public record Origins(Set<Node> nodes, boolean complete) {
        public Origins { nodes = Set.copyOf(nodes); }
    }
    public record FrameState(List<Value> locals, List<Value> stack) {
        public FrameState { locals = List.copyOf(locals); stack = List.copyOf(stack); }
    }
    public record IncomingFrame(Edge edge, FrameState frame) {}
    public record FieldAccess(String owner, String name, String descriptor, boolean write,
                              boolean isStatic, Value receiver, Value value) {}
    public record Call(String owner, String name, String descriptor, int opcode, boolean dynamic,
                       Value receiver, List<Value> arguments, Value result) {
        public Call { arguments = List.copyOf(arguments); }
    }
    public record Instruction(int index, AbstractInsnNode instruction, List<Value> operands,
                              List<Value> results, FieldAccess field, Call call, Lowering lowering) {
        public Instruction { operands = List.copyOf(operands); results = List.copyOf(results); }
    }

    public static final class Value extends SourceValue {
        private final Type type;
        private final Set<Node> sources;
        private Value(Type type, int size, Set<Node> sources) {
            super(size, sourceInstructions(sources));
            this.type = type;
            this.sources = Set.copyOf(sources);
        }
        public Type type() { return type; }
        public Set<Node> sources() { return sources; }
        public boolean merged() { return sources.size() > 1; }
        @Override public boolean equals(Object other) {
            return other instanceof Value value && size == value.size
                    && Objects.equals(type, value.type) && sources.equals(value.sources);
        }
        @Override public int hashCode() { return Objects.hash(type, size, sources); }
        private static Set<AbstractInsnNode> sourceInstructions(Set<Node> sources) {
            Set<AbstractInsnNode> result = null;
            for (Node source : sources) {
                if (source.instruction() == null) continue;
                if (result == null) result = Collections.newSetFromMap(new IdentityHashMap<>());
                result.add(source.instruction());
            }
            return result == null ? Set.of() : Collections.unmodifiableSet(result);
        }
    }

    public static final class Analysis {
        private final MethodId method;
        private final boolean successful;
        private final List<Instruction> instructions;
        private final List<Node> parameters;
        private final List<Edge> edges;
        private final List<ExceptionRegion> exceptionRegions;
        private final List<Diagnostic> diagnostics;
        private final Map<AbstractInsnNode, Instruction> byInstruction;
        private final Map<AbstractInsnNode, FrameState> frames;
        private final Map<AbstractInsnNode, FrameState> afterFrames;
        private final Map<Integer, List<IncomingFrame>> incoming;
        private final Map<Integer, List<Edge>> predecessors;
        private final Map<Integer, List<Edge>> successors;
        private final Map<Node, List<Value>> dependencies;
        private final Frame<SourceValue>[] sourceFrames;
        private final Map<Long, Boolean> dominance = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Long, Boolean> completionDominance = new java.util.concurrent.ConcurrentHashMap<>();

        private Analysis(MethodId method, boolean successful, List<Instruction> instructions,
                         List<Node> parameters, List<Edge> edges, List<ExceptionRegion> exceptionRegions, List<Diagnostic> diagnostics,
                         Map<AbstractInsnNode, FrameState> frames, Map<AbstractInsnNode, FrameState> afterFrames,
                         Map<Edge, FrameState> edgeFrames, Map<Node, List<Value>> dependencies,
                         Frame<SourceValue>[] sourceFrames) {
            this.method = method;
            this.successful = successful;
            this.instructions = List.copyOf(instructions);
            this.parameters = List.copyOf(parameters);
            this.edges = List.copyOf(edges);
            this.exceptionRegions = List.copyOf(exceptionRegions);
            this.diagnostics = List.copyOf(diagnostics);
            var indexed = new IdentityHashMap<AbstractInsnNode, Instruction>();
            for (Instruction instruction : instructions) indexed.put(instruction.instruction(), instruction);
            byInstruction = Collections.unmodifiableMap(indexed);
            this.frames = Collections.unmodifiableMap(new IdentityHashMap<>(frames));
            this.afterFrames = Collections.unmodifiableMap(new IdentityHashMap<>(afterFrames));
            Map<Integer, List<IncomingFrame>> incoming = new HashMap<>();
            Map<Integer, List<Edge>> predecessors = new HashMap<>(), successors = new HashMap<>();
            for (Edge edge : edges) {
                predecessors.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge);
                successors.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
                FrameState frame = edgeFrames.get(edge);
                if (frame == null) throw new IllegalArgumentException("Missing frame for control-flow edge " + edge);
                incoming.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(new IncomingFrame(edge, frame));
            }
            this.incoming = immutableLists(incoming);
            this.predecessors = immutableLists(predecessors);
            this.successors = immutableLists(successors);
            var copied = new HashMap<Node, List<Value>>();
            dependencies.forEach((key, value) -> copied.put(key, List.copyOf(value)));
            this.dependencies = Collections.unmodifiableMap(copied);
            this.sourceFrames = sourceFrames.clone();
        }
        public MethodId method() { return method; }
        public boolean successful() { return successful; }
        public List<Instruction> instructions() { return instructions; }
        public List<Node> parameters() { return parameters; }
        public List<Edge> edges() { return edges; }
        public List<ExceptionRegion> exceptionRegions() { return exceptionRegions; }
        public List<Diagnostic> diagnostics() { return diagnostics; }
        public Instruction instruction(AbstractInsnNode instruction) { return byInstruction.get(instruction); }
        public FrameState frameBefore(AbstractInsnNode instruction) { return frames.get(instruction); }
        public FrameState frameAfter(AbstractInsnNode instruction) { return afterFrames.get(instruction); }
        public List<IncomingFrame> incoming(AbstractInsnNode instruction) {
            Instruction selected = byInstruction.get(instruction);
            return selected == null ? List.of() : incoming.getOrDefault(selected.index(), List.of());
        }
        public List<Edge> predecessors(AbstractInsnNode instruction) {
            Instruction selected = byInstruction.get(instruction);
            return selected == null ? List.of() : predecessors.getOrDefault(selected.index(), List.of());
        }
        public List<Edge> successors(AbstractInsnNode instruction) {
            Instruction selected = byInstruction.get(instruction);
            return selected == null ? List.of() : successors.getOrDefault(selected.index(), List.of());
        }
        private static <T> Map<Integer, List<T>> immutableLists(Map<Integer, List<T>> source) {
            Map<Integer, List<T>> result = new HashMap<>();
            source.forEach((key, value) -> result.put(key, List.copyOf(value)));
            return Map.copyOf(result);
        }
        public boolean reachable(AbstractInsnNode instruction) { return frames.containsKey(instruction); }
        public List<Value> operands(AbstractInsnNode instruction) {
            Instruction selected = byInstruction.get(instruction);
            return selected == null ? List.of() : selected.operands();
        }
        public List<Value> arguments(MethodInsnNode instruction) {
            Instruction selected = byInstruction.get(instruction);
            return selected == null || selected.call() == null ? List.of() : selected.call().arguments();
        }
        public Value receiver(MethodInsnNode instruction) {
            Instruction selected = byInstruction.get(instruction);
            return selected == null || selected.call() == null ? null : selected.call().receiver();
        }
        public List<Value> dependencies(Node node) { return dependencies.getOrDefault(node, List.of()); }
        public List<FieldAccess> fields() { return instructions.stream().map(Instruction::field).filter(Objects::nonNull).toList(); }
        public List<Call> calls() { return instructions.stream().map(Instruction::call).filter(Objects::nonNull).toList(); }
        public List<Diagnostic> loweringDiagnostics() {
            return instructions.stream().filter(value -> value.instruction().getOpcode() >= 0 && value.lowering() != Lowering.DIRECT)
                    .map(value -> new Diagnostic("IR_REQUIRES_" + value.lowering(), value.index(),
                            "JVM operation is represented; expression lowering requires a separate proof")).toList();
        }
        @SuppressWarnings("unchecked")
        public Frame<SourceValue>[] sourceFrames() {
            Frame<SourceValue>[] result = new Frame[sourceFrames.length];
            for (int index = 0; index < result.length; index++)
                if (sourceFrames[index] != null) result[index] = new Frame<>(sourceFrames[index]);
            return result;
        }
        public Map<AbstractInsnNode, List<SourceValue>> sourceInputs() {
            var result = new IdentityHashMap<AbstractInsnNode, List<SourceValue>>();
            for (Instruction instruction : instructions) result.put(instruction.instruction(), List.copyOf(instruction.operands()));
            return Collections.unmodifiableMap(result);
        }
        public Node origin(SourceValue source) {
            Origins origins = origins(source);
            return origins.complete() && origins.nodes().size() == 1 ? origins.nodes().iterator().next() : null;
        }
        public Node registryOrigin(SourceValue source) {
            Node node = origin(source);
            Set<Node> seen = new HashSet<>();
            while (node != null && seen.add(node) && node.instruction() instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraftforge/registries/RegistryObject")
                    && call.name.equals("get") && call.desc.equals("()Ljava/lang/Object;"))
                node = origin(receiver(call));
            return node;
        }
        public Origins origins(SourceValue source) {
            if (!(source instanceof Value value) || value.sources().isEmpty()) return new Origins(Set.of(), false);
            if (value.sources().size() == 1) {
                Node node = value.sources().iterator().next();
                if (node.kind() != NodeKind.INSTRUCTION || !alias(node.instruction().getOpcode()))
                    return new Origins(value.sources(), true);
            }
            ArrayDeque<Node> pending = new ArrayDeque<>(value.sources());
            Set<Node> seen = new HashSet<>();
            Set<Node> result = new LinkedHashSet<>();
            boolean complete = true;
            while (!pending.isEmpty()) {
                Node node = pending.removeFirst();
                if (!seen.add(node)) continue;
                if (node.kind() == NodeKind.INSTRUCTION && alias(node.instruction().getOpcode())) {
                    List<Value> inputs = dependencies(node);
                    if (inputs.size() != 1 || inputs.get(0).sources().isEmpty()) complete = false;
                    else pending.addAll(inputs.get(0).sources());
                } else result.add(node);
            }
            return new Origins(result, complete && !result.isEmpty());
        }
        public boolean parameter(SourceValue value, int slot) {
            Node origin = origin(value);
            return origin != null && origin.kind() == NodeKind.PARAMETER && origin.parameterSlot() == slot;
        }
        public boolean dominates(AbstractInsnNode producer, AbstractInsnNode consumer) {
            return dominates(producer, consumer, false);
        }
        public boolean resultDominates(AbstractInsnNode producer, AbstractInsnNode consumer) {
            return dominates(producer, consumer, true);
        }
        private boolean dominates(AbstractInsnNode producer, AbstractInsnNode consumer, boolean completed) {
            Instruction first = instruction(producer), second = instruction(consumer);
            if (first == null || second == null || !reachable(producer) || !reachable(consumer)) return false;
            long key = ((long) first.index() << 32) | Integer.toUnsignedLong(second.index());
            Map<Long, Boolean> cached = completed ? completionDominance : dominance;
            return cached.computeIfAbsent(key, ignored -> !bypasses(first.index(), second.index(), completed));
        }
        private boolean bypasses(int producer, int consumer, boolean completed) {
            if (!completed && producer == 0) return false;
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            BitSet visited = new BitSet(instructions.size());
            pending.add(0);
            while (!pending.isEmpty()) {
                int point = pending.removeFirst();
                if (visited.get(point) || !completed && point == producer) continue;
                if (point == consumer) return true;
                visited.set(point);
                for (Edge edge : successors.getOrDefault(point, List.of()))
                    if (!(completed && point == producer && edge.kind() == EdgeKind.NORMAL)) pending.add(edge.to());
            }
            return false;
        }
    }

    private FormulaDataFlow() {}

    public static Analysis analyze(FormulaClassRepository repository, String owner, MethodNode method) {
        Objects.requireNonNull(repository);
        Objects.requireNonNull(method);
        try { return repository.dataFlow(owner.replace('.', '/'), method); }
        catch (RuntimeException failure) {
            return failed(new MethodId(owner, method.name, method.desc), "IR_PREPARATION_FAILED",
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    public static Analysis analyze(FormulaClassRepository repository, String owner, String name, String descriptor) {
        var resolved = repository.resolveMethod(owner.replace('.', '/'), name, descriptor);
        if (resolved == null) return failed(new MethodId(owner, name, descriptor), "IR_METHOD_UNAVAILABLE", "Method bytecode is unavailable");
        return analyze(repository, resolved.owner().name, resolved.method());
    }

    static String fingerprint(String owner, MethodNode method) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        method.accept(writer);
        writer.visitEnd();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(writer.toByteArray())); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    static Analysis create(FormulaClassRepository repository, String owner, MethodNode method) {
        MethodId id = new MethodId(owner, method.name, method.desc);
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions.size() == 0)
            return failed(id, "IR_NO_METHOD_BODY", "Abstract, native or empty method has no analyzable body");
        if (method.instructions.size() > MAX_INSTRUCTIONS)
            return failed(id, "IR_INSTRUCTION_BOUND", "Method exceeds the static analysis instruction bound");
        try {
            Tracker tracker = new Tracker(repository, method);
            Set<Edge> edges = new LinkedHashSet<>();
            Analyzer<SourceValue> analyzer = new Analyzer<>(tracker) {
                @Override protected Frame<SourceValue> newFrame(int locals, int stack) { return new TrackedFrame(locals, stack); }
                @Override protected Frame<SourceValue> newFrame(Frame<? extends SourceValue> frame) { return new TrackedFrame(frame); }
                @Override protected void newControlFlowEdge(int from, int to) { edges.add(new Edge(from, to, EdgeKind.NORMAL, null)); }
                @Override protected boolean newControlFlowExceptionEdge(int from, TryCatchBlockNode block) {
                    if (!mayThrow(method.instructions.get(from), method)) return false;
                    edges.add(new Edge(from, method.instructions.indexOf(block.handler), EdgeKind.EXCEPTION, block.type));
                    return true;
                }
            };
            Frame<SourceValue>[] computed = analyzer.analyze(owner, method);
            tracker.recording = true;
            List<Instruction> instructions = new ArrayList<>();
            Map<AbstractInsnNode, FrameState> frames = new IdentityHashMap<>();
            Map<AbstractInsnNode, FrameState> afterFrames = new IdentityHashMap<>();
            for (int index = 0; index < method.instructions.size(); index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                if (computed[index] == null) {
                    instructions.add(new Instruction(index, instruction, List.of(), List.of(), null, null, lowering(instruction)));
                    continue;
                }
                Frame<SourceValue> frame = computed[index];
                FrameState before = frameState(frame);
                List<Value> stack = before.stack();
                frames.put(instruction, before);
                if (instruction.getOpcode() >= 0) {
                    TrackedFrame after = new TrackedFrame(frame);
                    after.execute(instruction, tracker);
                    afterFrames.put(instruction, frameState(after));
                } else {
                    tracker.begin();
                    afterFrames.put(instruction, before);
                }
                List<Value> inputs = tracker.inputs;
                List<Value> results = tracker.results;
                int consumed = consumedStackValues(instruction.getOpcode(), stack);
                if (consumed > 0) inputs = List.copyOf(stack.subList(stack.size() - consumed, stack.size()));
                instructions.add(new Instruction(index, instruction, inputs, results,
                        field(instruction, inputs, results), call(instruction, inputs, results), lowering(instruction)));
            }
            Map<Edge, FrameState> edgeFrames = new HashMap<>();
            for (Edge edge : edges) {
                AbstractInsnNode source = method.instructions.get(edge.from());
                if (edge.kind() == EdgeKind.NORMAL) edgeFrames.put(edge, afterFrames.get(source));
                else {
                    Type caught = Type.getObjectType(edge.catchType() == null ? "java/lang/Throwable" : edge.catchType());
                    edgeFrames.put(edge, new FrameState(frames.get(source).locals(),
                            List.of(value(tracker.exceptionValue(edge.to(), caught)))));
                }
            }
            return new Analysis(id, true, instructions, tracker.parameters.values().stream()
                    .sorted(Comparator.comparingInt(Node::parameterSlot)).toList(), new ArrayList<>(edges),
                    method.tryCatchBlocks.stream().map(block -> new ExceptionRegion(method.instructions.indexOf(block.start),
                            method.instructions.indexOf(block.end), method.instructions.indexOf(block.handler), block.type)).toList(), tracker.diagnostics,
                    frames, afterFrames, edgeFrames, tracker.dependencies, computed);
        } catch (AnalyzerException | RuntimeException failure) {
            return failed(id, "IR_ANALYSIS_FAILED", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Analysis failed(MethodId method, String code, String message) {
        return new Analysis(method, false, List.of(), List.of(), List.of(), List.of(), List.of(new Diagnostic(code, -1, message)),
                Map.of(), Map.of(), Map.of(), Map.of(), new Frame[0]);
    }

    private static FrameState frameState(Frame<? extends SourceValue> frame) {
        List<Value> locals = new ArrayList<>(), stack = new ArrayList<>();
        for (int slot = 0; slot < frame.getLocals(); slot++) locals.add(value(frame.getLocal(slot)));
        for (int slot = 0; slot < frame.getStackSize(); slot++) stack.add(value(frame.getStack(slot)));
        return new FrameState(locals, stack);
    }

    private static FieldAccess field(AbstractInsnNode instruction, List<Value> inputs, List<Value> results) {
        if (!(instruction instanceof FieldInsnNode field)) return null;
        boolean write = field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC;
        boolean isStatic = field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.PUTSTATIC;
        Value receiver = isStatic || inputs.isEmpty() ? null : inputs.get(0);
        Value value = write ? inputs.isEmpty() ? null : inputs.get(inputs.size() - 1) : results.isEmpty() ? null : results.get(results.size() - 1);
        return new FieldAccess(field.owner, field.name, field.desc, write, isStatic, receiver, value);
    }

    private static Call call(AbstractInsnNode instruction, List<Value> inputs, List<Value> results) {
        if (instruction instanceof MethodInsnNode call) {
            boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
            return new Call(call.owner, call.name, call.desc, call.getOpcode(), false,
                    isStatic || inputs.isEmpty() ? null : inputs.get(0),
                    isStatic ? inputs : inputs.isEmpty() ? List.of() : inputs.subList(1, inputs.size()),
                    results.isEmpty() ? null : results.get(results.size() - 1));
        }
        if (instruction instanceof InvokeDynamicInsnNode call)
            return new Call(call.bsm.getOwner(), call.name, call.desc, call.getOpcode(), true, null, inputs,
                    results.isEmpty() ? null : results.get(results.size() - 1));
        return null;
    }

    private static Lowering lowering(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (instruction instanceof MethodInsnNode || instruction instanceof InvokeDynamicInsnNode) return Lowering.CALL_SUMMARY;
        if (instruction instanceof FieldInsnNode) return Lowering.FIELD_SOURCE;
        if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode
                || opcode == Opcodes.ATHROW || opcode == Opcodes.RET) return Lowering.CONTROL_FLOW;
        if (opcode < 0 || opcode == Opcodes.NOP || opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1
                || opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH || instruction instanceof LdcInsnNode
                || alias(opcode) || opcode >= Opcodes.IADD && opcode <= Opcodes.DCMPG
                || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.IINC) return Lowering.DIRECT;
        return Lowering.JVM_OPERATION;
    }

    private static boolean alias(int opcode) {
        return opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD || opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE
                || opcode >= Opcodes.DUP && opcode <= Opcodes.SWAP || opcode == Opcodes.CHECKCAST;
    }

    private static int consumedStackValues(int opcode, List<Value> stack) {
        return switch (opcode) {
            case Opcodes.POP, Opcodes.DUP, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN -> 1;
            case Opcodes.POP2, Opcodes.DUP2 -> stack.get(stack.size() - 1).size == 2 ? 1 : 2;
            case Opcodes.DUP_X1, Opcodes.SWAP -> 2;
            case Opcodes.DUP_X2 -> stack.get(stack.size() - 2).size == 2 ? 2 : 3;
            case Opcodes.DUP2_X1 -> stack.get(stack.size() - 1).size == 2 ? 2 : 3;
            case Opcodes.DUP2_X2 -> stack.get(stack.size() - 1).size == 2
                    ? stack.get(stack.size() - 2).size == 2 ? 2 : 3
                    : stack.get(stack.size() - 3).size == 2 ? 3 : 4;
            default -> 0;
        };
    }

    private static boolean mayThrow(AbstractInsnNode instruction, MethodNode method) {
        int opcode = instruction.getOpcode();
        if (instruction instanceof MethodInsnNode || instruction instanceof InvokeDynamicInsnNode
                || instruction instanceof FieldInsnNode || instruction instanceof MultiANewArrayInsnNode) return true;
        if (instruction instanceof LdcInsnNode literal) return !(literal.cst instanceof Number);
        return opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD || opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE
                || opcode == Opcodes.IDIV || opcode == Opcodes.LDIV || opcode == Opcodes.IREM || opcode == Opcodes.LREM
                || opcode == Opcodes.NEW || opcode == Opcodes.NEWARRAY || opcode == Opcodes.ANEWARRAY || opcode == Opcodes.ARRAYLENGTH
                || opcode == Opcodes.CHECKCAST || opcode == Opcodes.INSTANCEOF || opcode == Opcodes.MONITORENTER
                || opcode == Opcodes.MONITOREXIT || opcode == Opcodes.ATHROW
                || (method.access & Opcodes.ACC_SYNCHRONIZED) != 0 && opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
    }

    private static Value value(SourceValue value) {
        if (value == null) return new Value(null, 1, Set.of());
        if (!(value instanceof Value typed)) throw new IllegalArgumentException("Foreign source value is not part of the typed IR");
        return typed;
    }

    private static final class TrackedFrame extends Frame<SourceValue> {
        private TrackedFrame(int locals, int stack) { super(locals, stack); }
        private TrackedFrame(Frame<? extends SourceValue> frame) { super(frame); }
        @Override public void execute(AbstractInsnNode instruction, Interpreter<SourceValue> interpreter) throws AnalyzerException {
            if (interpreter instanceof Tracker tracker) tracker.begin();
            super.execute(instruction, interpreter);
        }
    }

    private static final class PreciseBasic extends BasicInterpreter {
        private PreciseBasic() { super(Opcodes.ASM9); }
        @Override public BasicValue newValue(Type type) {
            if (type == null) return BasicValue.UNINITIALIZED_VALUE;
            return switch (type.getSort()) {
                case Type.VOID -> null;
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> BasicValue.INT_VALUE;
                default -> new BasicValue(type);
            };
        }
    }

    private static final class Tracker extends Interpreter<SourceValue> {
        private final FormulaClassRepository repository;
        private final PreciseBasic basic = new PreciseBasic();
        private final Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        private final Map<Integer, Node> parameters = new HashMap<>();
        private final Map<String, Node> nodes = new HashMap<>();
        private final Map<Node, List<Value>> dependencies = new HashMap<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private boolean recording;
        private int operations;
        private int output;
        private int nextException;
        private List<Value> inputs = List.of();
        private List<Value> results = List.of();

        private Tracker(FormulaClassRepository repository, MethodNode method) {
            super(Opcodes.ASM9);
            this.repository = repository;
            int index = 0;
            for (AbstractInsnNode instruction : method.instructions) positions.put(instruction, index++);
            nextException = index * 8;
        }
        private void begin() {
            output = 0;
            if (recording) {
                inputs = new ArrayList<>();
                results = new ArrayList<>();
            }
        }
        private BasicValue basic(SourceValue source) {
            Value value = value(source);
            return value.type() == null ? BasicValue.UNINITIALIZED_VALUE : new BasicValue(value.type());
        }
        private SourceValue operation(AbstractInsnNode instruction, BasicValue result, SourceValue... operands) throws AnalyzerException {
            if (++operations > MAX_OPERATIONS) throw new AnalyzerException(instruction, "IR operation analysis bound exceeded");
            List<Value> values = recording ? Arrays.stream(operands).map(FormulaDataFlow::value).toList() : List.of();
            if (recording) inputs.addAll(values);
            if (result == null) return null;
            int selected = output++;
            int position = positions.get(instruction);
            String key = position + ":" + selected;
            Type nodeType = result.getType();
            if (alias(instruction.getOpcode()) && instruction.getOpcode() != Opcodes.CHECKCAST
                    && nodeType != null && nodeType.getSort() >= Type.ARRAY) nodeType = Type.getObjectType("java/lang/Object");
            Type declaredType = nodeType;
            Node node = nodes.computeIfAbsent(key, ignored -> new Node(position * 8 + selected, NodeKind.INSTRUCTION,
                    declaredType, -1, instruction, selected));
            Value value = new Value(result.getType(), result.getSize(), Set.of(node));
            if (recording) {
                dependencies.put(node, values);
                results.add(value);
            }
            return value;
        }
        @Override public SourceValue newValue(Type type) {
            BasicValue result = basic.newValue(type);
            return result == null ? null : new Value(result.getType(), result.getSize(), Set.of());
        }
        @Override public SourceValue newParameterValue(boolean instance, int slot, Type type) {
            Node node = parameters.computeIfAbsent(slot, ignored -> new Node(-slot - 1, NodeKind.PARAMETER, type, slot, null, 0));
            BasicValue result = basic.newValue(type);
            return new Value(result.getType(), result.getSize(), Set.of(node));
        }
        @Override public SourceValue newExceptionValue(TryCatchBlockNode block, Frame<SourceValue> frame, Type type) {
            return exceptionValue(positions.get(block.handler), type);
        }
        private SourceValue exceptionValue(int position, Type type) {
            String key = "exception:" + position + ':' + type;
            Node node = nodes.computeIfAbsent(key, ignored -> new Node(nextException++,
                    NodeKind.EXCEPTION, type, -1, null, 0));
            return new Value(type, 1, Set.of(node));
        }
        @Override public SourceValue newOperation(AbstractInsnNode instruction) throws AnalyzerException {
            return operation(instruction, basic.newOperation(instruction));
        }
        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) throws AnalyzerException {
            return operation(instruction, basic.copyOperation(instruction, basic(value)), value);
        }
        @Override public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) throws AnalyzerException {
            return operation(instruction, basic.unaryOperation(instruction, basic(value)), value);
        }
        @Override public SourceValue binaryOperation(AbstractInsnNode instruction, SourceValue left, SourceValue right) throws AnalyzerException {
            return operation(instruction, basic.binaryOperation(instruction, basic(left), basic(right)), left, right);
        }
        @Override public SourceValue ternaryOperation(AbstractInsnNode instruction, SourceValue first, SourceValue second,
                                                      SourceValue third) throws AnalyzerException {
            return operation(instruction, basic.ternaryOperation(instruction, basic(first), basic(second), basic(third)), first, second, third);
        }
        @Override public SourceValue naryOperation(AbstractInsnNode instruction, List<? extends SourceValue> values) throws AnalyzerException {
            return operation(instruction, basic.naryOperation(instruction, values.stream().map(this::basic).toList()),
                    values.toArray(SourceValue[]::new));
        }
        @Override public void returnOperation(AbstractInsnNode instruction, SourceValue value, SourceValue expected) throws AnalyzerException {
            basic.returnOperation(instruction, basic(value), expected == null ? null : basic(expected));
            operation(instruction, null, value);
        }
        @Override public SourceValue merge(SourceValue first, SourceValue second) {
            if (first.equals(second)) return first;
            Value left = value(first), right = value(second);
            Type type = common(left.type(), right.type());
            Set<Node> sources = new LinkedHashSet<>(left.sources());
            sources.addAll(right.sources());
            return new Value(type, type == null ? 1 : left.size, sources);
        }
        private Type common(Type left, Type right) {
            if (Objects.equals(left, right)) return left;
            if (left == null || right == null) return null;
            if (left.getSort() < Type.ARRAY || right.getSort() < Type.ARRAY) return null;
            if (left.equals(BasicInterpreter.NULL_TYPE)) return right;
            if (right.equals(BasicInterpreter.NULL_TYPE)) return left;
            if (left.getSort() == Type.ARRAY || right.getSort() == Type.ARRAY) return Type.getObjectType("java/lang/Object");
            try { return Type.getObjectType(repository.commonSuperClass(left.getInternalName(), right.getInternalName())); }
            catch (IllegalArgumentException unavailable) {
                Diagnostic diagnostic = new Diagnostic("IR_REFERENCE_JOIN_WIDENED", -1,
                        "Unavailable hierarchy widened a reference join to java/lang/Object: " + left + " / " + right);
                if (!diagnostics.contains(diagnostic)) diagnostics.add(diagnostic);
                return Type.getObjectType("java/lang/Object");
            }
        }
    }
}
