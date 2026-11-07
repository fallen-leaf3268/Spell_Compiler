package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class FormulaInterproceduralFlow {
    public enum IdentityKind { ALLOCATION, PARAMETER }
    public enum Status { SINGLE_WRITE, MULTIPLE_WRITES, ESCAPED, UNKNOWN_MUTATION, NO_WRITE, UNPROVEN_RECEIVER }
    public record CallSite(FormulaDataFlow.MethodId method, int instruction) {}
    public record ObjectIdentity(IdentityKind kind, FormulaDataFlow.MethodId method, int instruction,
                                 int parameterSlot, Type type, List<CallSite> context) {
        public ObjectIdentity { context = List.copyOf(context); }
    }
    public record FieldKey(String owner, String name, String descriptor) {
        public FieldKey { owner = owner.replace('.', '/'); }
    }
    public record Context(FormulaDataFlow.Analysis analysis, Map<Integer, ValueRef> parameterBindings,
                          List<CallSite> callPath) {
        public Context {
            parameterBindings = Map.copyOf(parameterBindings);
            callPath = List.copyOf(callPath);
        }
    }
    public record ValueRef(Context context, FormulaDataFlow.Value value) {}
    public record CallRef(Context context, MethodInsnNode call) {}
    public record FieldRef(Context context, FieldInsnNode instruction, FieldKey field, ObjectIdentity receiver) {}
    public record ObjectResolution(Set<ObjectIdentity> identities, boolean complete) {
        public ObjectResolution { identities = Set.copyOf(identities); }
        public ObjectIdentity exact() { return complete && identities.size() == 1 ? identities.iterator().next() : null; }
    }
    public record Location(FormulaDataFlow.Analysis analysis, AbstractInsnNode instruction) {}
    public record FieldWrite(FieldKey field, ObjectIdentity receiver, ValueRef value, List<Location> path,
                             boolean receiverProven) {
        public FieldWrite { path = List.copyOf(path); }
    }
    public record Diagnostic(String code, FormulaDataFlow.MethodId method, int instruction, String message) {}
    public record ReadResolution(Status status, List<FieldWrite> writes, List<Diagnostic> diagnostics) {
        public ReadResolution { writes = List.copyOf(writes); diagnostics = List.copyOf(diagnostics); }
        public ValueRef source() { return status == Status.SINGLE_WRITE ? writes.getFirst().value() : null; }
    }
    private record Exposure(List<Location> path, boolean escape) {}
    private record ObjectKey(int context, Object value) {}
    private record InvocationKey(int context, MethodInsnNode call) {}
    private record FieldStateKey(int context, ObjectIdentity receiver, List<Location> observation) {}
    private static final ThreadLocal<Query> QUERIES = new ThreadLocal<>();

    private static final class Query {
        private final IdentityHashMap<Context, Integer> contexts = new IdentityHashMap<>();
        private final IdentityHashMap<FormulaDataFlow.Analysis, Context> roots = new IdentityHashMap<>();
        private final IdentityHashMap<FormulaDataFlow.Analysis, Map<SourceValue, FormulaDataFlow.Node>> origins = new IdentityHashMap<>();
        private final Map<ObjectKey, ObjectResolution> objects = new HashMap<>();
        private final Set<ObjectKey> activeObjects = new HashSet<>();
        private final Map<InvocationKey, Context> invocations = new HashMap<>();
        private final Set<InvocationKey> activeInvocations = new HashSet<>();
        private final Map<FieldStateKey, State> states = new HashMap<>();
        private final Set<FieldStateKey> activeStates = new HashSet<>();
        private int cutoffs;
        private int fieldWork;

        private Context canonical(Context context) {
            return context.parameterBindings().isEmpty() && context.callPath().isEmpty()
                    ? roots.computeIfAbsent(context.analysis(), ignored -> context) : context;
        }

        private int id(Context context) {
            return contexts.computeIfAbsent(canonical(context), ignored -> contexts.size());
        }

        private FormulaDataFlow.Node origin(FormulaDataFlow.Analysis analysis, SourceValue value) {
            if (value == null) return null;
            var cached = origins.computeIfAbsent(analysis, ignored -> new HashMap<>());
            if (!cached.containsKey(value)) cached.put(value, analysis.origin(value));
            return cached.get(value);
        }
    }

    private FormulaInterproceduralFlow() {}

    public static Context root(FormulaDataFlow.Analysis analysis) {
        var context = new Context(analysis, Map.of(), List.of());
        return QUERIES.get() == null ? context : QUERIES.get().canonical(context);
    }

    private static <T> T session(Query query, Supplier<T> action) {
        if (QUERIES.get() != null) return action.get();
        QUERIES.set(query == null ? new Query() : query);
        try { return action.get(); }
        finally { QUERIES.remove(); }
    }

    private static FormulaDataFlow.Node origin(FormulaDataFlow.Analysis analysis, SourceValue value) {
        return QUERIES.get() == null ? analysis.origin(value) : QUERIES.get().origin(analysis, value);
    }

    public static ValueRef value(Context context, SourceValue value) {
        return context != null && value instanceof FormulaDataFlow.Value typed ? new ValueRef(context, typed) : null;
    }

    public static ValueRef resolveParameters(ValueRef value) {
        Set<Context> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (value != null && visited.add(value.context())) {
            var origin = origin(value.context().analysis(), value.value());
            if (origin == null || origin.kind() != FormulaDataFlow.NodeKind.PARAMETER) return value;
            ValueRef bound = value.context().parameterBindings().get(origin.parameterSlot());
            if (bound == null) return value;
            value = bound;
        }
        return null;
    }

    public static ValueRef forwarded(ValueRef source) {
        for (int depth = 0; source != null && depth < 128; depth++) {
            source = resolveParameters(source);
            if (source == null) return null;
            var node = source.context().analysis().origin(source.value());
            if (node == null || node.instruction() == null || !Set.of(Opcodes.I2L, Opcodes.I2D, Opcodes.F2D).contains(node.instruction().getOpcode()))
                return source;
            var dependencies = source.context().analysis().dependencies(node);
            if (dependencies.size() != 1) return null;
            source = value(source.context(), dependencies.getFirst());
        }
        return null;
    }

    public static CallRef forwardedCall(ValueRef source) {
        source = forwarded(source);
        if (source == null) return null;
        var origin = source.context().analysis().origin(source.value());
        return origin != null && origin.instruction() instanceof MethodInsnNode call ? new CallRef(source.context(), call) : null;
    }

    public static FieldRef forwardedField(FormulaClassRepository repository, ValueRef source) {
        return forwardedField(repository, source, new HashSet<>(), 0);
    }

    private static FieldRef forwardedField(FormulaClassRepository repository, ValueRef source, Set<String> visited, int depth) {
        if (depth > 32 || (source = forwarded(source)) == null) return null;
        var analysis = source.context().analysis();
        var origin = analysis.origin(source.value());
        if (origin == null) return null;
        if (origin.instruction() instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETFIELD) {
            var access = analysis.instruction(read).field();
            var receiver = object(repository, value(source.context(), access.receiver())).exact();
            var key = field(repository, read.owner, read.name, read.desc);
            return receiver == null || key == null ? null : new FieldRef(source.context(), read, key, receiver);
        }
        if (!(origin.instruction() instanceof MethodInsnNode call) || Type.getArgumentTypes(call.desc).length != 0) return null;
        Context callee = invocation(repository, source.context(), call);
        if (callee == null || !visited.add(callee.analysis().method().key())) return null;
        ValueRef returned = null;
        for (var instruction : callee.analysis().instructions()) {
            if (!callee.analysis().reachable(instruction.instruction())) continue;
            int opcode = instruction.instruction().getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
                if (returned != null || instruction.operands().size() != 1) return null;
                returned = value(callee, instruction.operands().getFirst());
            } else if (opcode >= 0 && opcode != Opcodes.GETFIELD && opcode != Opcodes.I2L && opcode != Opcodes.I2D
                    && opcode != Opcodes.F2D && !alias(opcode)) return null;
        }
        return forwardedField(repository, returned, visited, depth + 1);
    }

    public static ObjectResolution object(FormulaClassRepository repository, ValueRef reference) {
        return session(null, () -> object(repository, reference, new HashSet<>(), 0));
    }

    private static ObjectResolution object(FormulaClassRepository repository, ValueRef reference, Set<String> visited, int depth) {
        if (reference == null) return new ObjectResolution(Set.of(), false);
        Query query = QUERIES.get();
        if (query == null) return session(null, () -> object(repository, reference, visited, depth));
        ValueRef resolved = resolveParameters(reference);
        if (resolved == null || resolved.value().type() == null || resolved.value().type().getSort() != Type.OBJECT
                && resolved.value().type().getSort() != Type.ARRAY) return new ObjectResolution(Set.of(), false);
        resolved = new ValueRef(query.canonical(resolved.context()), resolved.value());
        var producer = origin(resolved.context().analysis(), resolved.value());
        if (depth > 32 && (producer == null || producer.kind() != FormulaDataFlow.NodeKind.PARAMETER
                && !(producer.instruction() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW))) {
            query.cutoffs++;
            return new ObjectResolution(Set.of(), false);
        }
        var key = new ObjectKey(query.id(resolved.context()), producer == null ? resolved.value() : producer);
        ObjectResolution cached = query.objects.get(key);
        if (cached != null) return cached;
        if (!query.activeObjects.add(key)) return new ObjectResolution(Set.of(), false);
        int cutoffs = query.cutoffs;
        try {
            var result = objectValue(repository, resolved, visited, depth);
            if (query.cutoffs == cutoffs) query.objects.put(key, result);
            return result;
        } finally { query.activeObjects.remove(key); }
    }

    private static ObjectResolution objectValue(FormulaClassRepository repository, ValueRef resolved, Set<String> visited, int depth) {
        var analysis = resolved.context().analysis();
        var origin = origin(analysis, resolved.value());
        if (origin != null) {
            if (origin.kind() == FormulaDataFlow.NodeKind.PARAMETER)
                return new ObjectResolution(Set.of(new ObjectIdentity(IdentityKind.PARAMETER, analysis.method(), -1,
                        origin.parameterSlot(), origin.type(), resolved.context().callPath())), true);
            if (origin.instruction() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW)
                return new ObjectResolution(Set.of(new ObjectIdentity(IdentityKind.ALLOCATION, analysis.method(),
                        analysis.instruction(allocation).index(), -1, Type.getObjectType(allocation.desc), resolved.context().callPath())), true);
            if (origin.instruction() instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD)
                return referenceField(repository, resolved, field, visited, depth);
            if (origin.instruction() instanceof MethodInsnNode call) {
                String key = analysis.method().key() + ':' + analysis.instruction(call).index() + ':' + resolved.context().callPath();
                if (!visited.add(key)) return new ObjectResolution(Set.of(), false);
                Context callee = invocation(repository, resolved.context(), call, visited, depth + 1);
                if (callee == null) return new ObjectResolution(Set.of(), false);
                Set<ObjectIdentity> identities = new LinkedHashSet<>();
                boolean complete = true;
                boolean returned = false;
                for (var instruction : callee.analysis().instructions())
                    if (instruction.instruction().getOpcode() == Opcodes.ARETURN && !instruction.operands().isEmpty()) {
                        returned = true;
                        var result = object(repository, value(callee, instruction.operands().getFirst()), new HashSet<>(visited), depth + 1);
                        identities.addAll(result.identities());
                        complete &= result.complete();
                    }
                return new ObjectResolution(identities, complete && returned);
            }
        }
        Set<ObjectIdentity> identities = new LinkedHashSet<>();
        boolean complete = !resolved.value().sources().isEmpty();
        for (var source : resolved.value().sources()) {
            if (source.kind() == FormulaDataFlow.NodeKind.PARAMETER) {
                ValueRef bound = resolved.context().parameterBindings().get(source.parameterSlot());
                if (bound == null) identities.add(new ObjectIdentity(IdentityKind.PARAMETER, analysis.method(), -1,
                        source.parameterSlot(), source.type(), resolved.context().callPath()));
                else {
                    var result = object(repository, bound, new HashSet<>(visited), depth + 1);
                    identities.addAll(result.identities());
                    complete &= result.complete();
                }
            } else {
                List<FormulaDataFlow.Value> dependencies = analysis.dependencies(source);
                if (dependencies.size() != 1 || source.instruction() == null || !alias(source.instruction().getOpcode())) {
                    complete = false;
                    continue;
                }
                var result = object(repository, value(resolved.context(), dependencies.getFirst()), new HashSet<>(visited), depth + 1);
                identities.addAll(result.identities());
                complete &= result.complete();
            }
        }
        return new ObjectResolution(identities, complete);
    }

    private static ObjectResolution referenceField(FormulaClassRepository repository, ValueRef source, FieldInsnNode field,
                                                    Set<String> visited, int depth) {
        var access = source.context().analysis().instruction(field).field();
        ObjectIdentity receiver = object(repository, value(source.context(), access.receiver()), new HashSet<>(visited), depth + 1).exact();
        if (receiver == null || receiver.kind() != IdentityKind.ALLOCATION) return new ObjectResolution(Set.of(), false);
        String key = "FIELD:" + source.context().analysis().method().key() + ':'
                + source.context().analysis().instruction(field).index() + ':' + receiver;
        if (!visited.add(key)) return new ObjectResolution(Set.of(), false);
        Context root = outermost(source.context());
        List<Location> observation = observation(repository, root, source.context(), field);
        FieldKey selected = field(repository, field.owner, field.name, field.desc);
        if (observation == null || selected == null) return new ObjectResolution(Set.of(), false);
        Query query = QUERIES.get();
        var stateKey = new FieldStateKey(query.id(root), receiver, observation);
        State state = query.states.get(stateKey);
        if (state == null) {
            if (!query.activeStates.add(stateKey)) return new ObjectResolution(Set.of(), false);
            try {
                if (++query.fieldWork > 4096) {
                    query.cutoffs++;
                    return new ObjectResolution(Set.of(), false);
                }
                state = new State(repository, root, observation, value(source.context(), access.receiver()), receiver, visited, depth + 1);
                query.states.put(stateKey, state);
            } finally { query.activeStates.remove(stateKey); }
        }
        var resolution = state.resolveField(selected);
        return resolution.source() == null ? new ObjectResolution(Set.of(), false)
                : object(repository, resolution.source(), new HashSet<>(visited), depth + 1);
    }

    private static Context outermost(Context context) {
        Set<Context> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (visited.add(context) && !context.parameterBindings().isEmpty()) {
            Set<Context> callers = Collections.newSetFromMap(new IdentityHashMap<>());
            context.parameterBindings().values().forEach(value -> callers.add(value.context()));
            if (callers.size() != 1) break;
            Context caller = callers.iterator().next();
            if (caller.callPath().size() + 1 != context.callPath().size()
                    || !context.callPath().subList(0, caller.callPath().size()).equals(caller.callPath())) break;
            context = caller;
        }
        return context;
    }

    private static List<Location> observation(FormulaClassRepository repository, Context root, Context current, AbstractInsnNode read) {
        if (current.callPath().size() < root.callPath().size()
                || !current.callPath().subList(0, root.callPath().size()).equals(root.callPath())) return null;
        List<Location> path = new ArrayList<>();
        for (int index = root.callPath().size(); index < current.callPath().size(); index++) {
            CallSite call = current.callPath().get(index);
            var analysis = call.method().equals(root.analysis().method()) ? root.analysis()
                    : FormulaDataFlow.analyze(repository, call.method().owner(), call.method().name(), call.method().descriptor());
            if (!analysis.successful() || call.instruction() < 0 || call.instruction() >= analysis.instructions().size()) return null;
            var instruction = analysis.instructions().get(call.instruction()).instruction();
            if (!(instruction instanceof MethodInsnNode)) return null;
            path.add(new Location(analysis, instruction));
        }
        path.add(new Location(current.analysis(), read));
        return List.copyOf(path);
    }

    public static FormulaClassRepository.ResolvedMethod resolveCall(FormulaClassRepository repository,
                                                                   MethodInsnNode call, String exactReceiver) {
        return resolveCall(repository, null, call, exactReceiver);
    }

    public static FormulaClassRepository.ResolvedMethod resolveCall(FormulaClassRepository repository, String caller,
                                                                   MethodInsnNode call, String exactReceiver) {
        return repository.resolveInvocation(caller, call, exactReceiver).target();
    }

    public static Context invocation(FormulaClassRepository repository, Context caller, MethodInsnNode call) {
        return session(null, () -> invocation(repository, caller, call, new HashSet<>(), 0));
    }

    private static Context invocation(FormulaClassRepository repository, Context caller, MethodInsnNode call,
                                      Set<String> visited, int depth) {
        Query query = QUERIES.get();
        if (query == null) return session(null, () -> invocation(repository, caller, call, visited, depth));
        Context selectedCaller = query.canonical(caller);
        var key = new InvocationKey(query.id(selectedCaller), call);
        var cached = query.invocations.get(key);
        if (cached != null) return cached;
        if (!query.activeInvocations.add(key)) return null;
        try {
            var result = invocationValue(repository, selectedCaller, call, visited, depth);
            if (result != null) query.invocations.put(key, result);
            return result;
        } finally { query.activeInvocations.remove(key); }
    }

    private static Context invocationValue(FormulaClassRepository repository, Context caller, MethodInsnNode call,
                                           Set<String> visited, int depth) {
        var receiver = object(repository, value(caller, caller.analysis().receiver(call)), new HashSet<>(visited), depth + 1).exact();
        var selected = resolveCall(repository, caller.analysis().method().owner(), call, receiver != null && receiver.kind() == IdentityKind.ALLOCATION
                ? receiver.type().getInternalName() : null);
        if (selected == null || platform(selected.owner().name) && !call.name.equals("<init>")
                || (selected.method().access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || selected.method().instructions.size() == 0) return null;
        var analysis = FormulaDataFlow.analyze(repository, selected.owner().name, selected.method());
        if (!analysis.successful()) return null;
        Map<Integer, ValueRef> arguments = new LinkedHashMap<>();
        int slot = 0;
        if ((selected.method().access & Opcodes.ACC_STATIC) == 0) arguments.put(slot++, value(caller, caller.analysis().receiver(call)));
        Type[] types = Type.getArgumentTypes(call.desc);
        var values = caller.analysis().arguments(call);
        if (values.size() != types.length) return null;
        for (int index = 0; index < types.length; index++) {
            arguments.put(slot, value(caller, values.get(index)));
            slot += types[index].getSize();
        }
        List<CallSite> path = new ArrayList<>(caller.callPath());
        path.add(new CallSite(caller.analysis().method(), caller.analysis().instruction(call).index()));
        return new Context(analysis, arguments, path);
    }

    public static State inspectPublishedObject(FormulaClassRepository repository, FormulaDataFlow.Analysis root,
                                                MethodInsnNode publication) {
        return session(null, () -> new State(repository, root(root), publication));
    }

    public static State inspectPublishedObject(FormulaClassRepository repository, Context root, MethodInsnNode publication) {
        return session(null, () -> new State(repository, root, publication));
    }

    public static boolean dependsOnCall(ValueRef source, Predicate<MethodInsnNode> predicate) {
        return dependsOnCall(source, predicate, new IdentityHashMap<>(), 0);
    }

    private static boolean dependsOnCall(ValueRef source, Predicate<MethodInsnNode> predicate,
                                          Map<Context, Set<FormulaDataFlow.Node>> visited, int depth) {
        if (source == null || depth > 128) return false;
        var analysis = source.context().analysis();
        Set<FormulaDataFlow.Node> seen = visited.computeIfAbsent(source.context(), ignored -> new HashSet<>());
        for (var node : source.value().sources()) {
            if (!seen.add(node)) continue;
            if (node.kind() == FormulaDataFlow.NodeKind.PARAMETER) {
                if (dependsOnCall(source.context().parameterBindings().get(node.parameterSlot()), predicate, visited, depth + 1)) return true;
            } else {
                if (node.instruction() instanceof MethodInsnNode call && predicate.test(call)) return true;
                for (var dependency : analysis.dependencies(node))
                    if (dependsOnCall(value(source.context(), dependency), predicate, visited, depth + 1)) return true;
            }
        }
        return false;
    }

    public static final class State {
        private final FormulaClassRepository repository;
        private final Context root;
        private final ObjectIdentity object;
        private final ValueRef publishedReference;
        private final List<Location> publication;
        private final Set<String> identityGuard;
        private final int identityDepth;
        private final Query query;
        private final boolean referenceRead;
        private final boolean proofLimited;
        private final List<FieldWrite> writes = new ArrayList<>();
        private final List<Exposure> exposures = new ArrayList<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final Map<FormulaDataFlow.Analysis, Control> control = new IdentityHashMap<>();

        private State(FormulaClassRepository repository, Context root, MethodInsnNode publication) {
            this(repository, root, List.of(new Location(root.analysis(), publication)),
                    root.analysis().arguments(publication).isEmpty() ? null : value(root, root.analysis().arguments(publication).getFirst()),
                    null, Set.of(), 0);
        }

        private State(FormulaClassRepository repository, Context root, List<Location> observation, ValueRef reference,
                       ObjectIdentity selected, Set<String> identityGuard, int identityDepth) {
            this.repository = repository;
            this.root = root;
            this.publication = List.copyOf(observation);
            this.publishedReference = reference;
            this.identityGuard = Set.copyOf(identityGuard);
            this.identityDepth = identityDepth;
            this.query = QUERIES.get();
            this.referenceRead = selected != null;
            int cutoffs = query.cutoffs;
            this.object = selected == null ? resolve(reference).exact() : selected;
            if (object == null || object.kind() != IdentityKind.ALLOCATION) {
                this.proofLimited = query.cutoffs != cutoffs;
                diagnostic("UNPROVEN_ALLOCATION", root, observation.getFirst().instruction(), "Observed reference is not a unique allocated object");
                return;
            }
            explore(root, List.of(), new HashSet<>(), 0);
            this.proofLimited = query.cutoffs != cutoffs;
            if (proofLimited) diagnostic("REFERENCE_QUERY_BOUND", root, observation.getFirst().instruction(), "Reference proof reached an unresolved query boundary");
        }

        private ObjectResolution resolve(ValueRef reference) {
            ValueRef selected = resolveParameters(reference);
            if (object != null && selected != null && incompatible(selected.value().type())) return new ObjectResolution(Set.of(), false);
            return session(query, () -> FormulaInterproceduralFlow.object(repository, reference, new HashSet<>(identityGuard), identityDepth + 1));
        }

        private boolean incompatible(Type type) {
            if (type == null) return false;
            if (type.getSort() != Type.OBJECT) return true;
            ClassNode required = repository.classNode(type.getInternalName());
            if (required == null || (required.access & Opcodes.ACC_INTERFACE) != 0) return false;
            String current = object.type().getInternalName();
            Set<String> seen = new HashSet<>();
            while (current != null && seen.add(current)) {
                if (current.equals(type.getInternalName())) return false;
                ClassNode node = repository.classNode(current);
                if (node == null || (node.access & Opcodes.ACC_INTERFACE) != 0) return false;
                current = node.superName;
            }
            return current == null;
        }

        public ObjectIdentity object() { return object; }
        public Context rootContext() { return root; }
        public List<FieldWrite> writes() { return List.copyOf(writes); }
        public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }

        public Context callback(FormulaDataFlow.Analysis callback) {
            if (object == null || !repository.isSubtype(object.type().getInternalName(), callback.method().owner())) return null;
            return new Context(callback, Map.of(0, publishedReference), List.of());
        }

        public ReadResolution resolveRead(FormulaDataFlow.Analysis callback, FieldInsnNode instruction) {
            return resolveRead(callback(callback), instruction);
        }

        public ReadResolution resolveRead(Context callback, FieldInsnNode instruction) {
            var selected = callback == null ? null : callback.analysis().instruction(instruction);
            if (object == null || selected == null || selected.field() == null || selected.field().write() || selected.field().isStatic()
                    || !object.equals(resolve(value(callback, selected.field().receiver())).exact()))
                return new ReadResolution(Status.UNPROVEN_RECEIVER, List.of(), diagnostics());
            FieldKey key = field(repository, instruction.owner, instruction.name, instruction.desc);
            return key == null ? new ReadResolution(Status.NO_WRITE, List.of(), diagnostics()) : resolveField(key);
        }

        public ReadResolution resolveField(FieldKey key) {
            List<FieldWrite> possible = writes.stream().filter(write -> write.field().equals(key) && mayBefore(write.path(), publication)).toList();
            if (proofLimited) return new ReadResolution(Status.UNKNOWN_MUTATION, possible, diagnostics());
            if (possible.isEmpty()) return new ReadResolution(Status.NO_WRITE, List.of(), diagnostics());
            if (exposures.stream().anyMatch(exposure -> exposure.escape() && mayBefore(exposure.path(), publication)))
                return new ReadResolution(Status.ESCAPED, possible, diagnostics());
            List<FieldWrite> last = possible.stream().filter(write -> write.receiverProven() && before(write.path(), publication)
                    && possible.stream().noneMatch(other -> other != write && mayBefore(write.path(), other.path()))).toList();
            if (last.size() != 1) return new ReadResolution(Status.MULTIPLE_WRITES, possible, diagnostics());
            FieldWrite chosen = last.getFirst();
            if (exposures.stream().anyMatch(exposure -> !exposure.escape() && mayBefore(chosen.path(), exposure.path())
                    && mayBefore(exposure.path(), publication)))
                return new ReadResolution(Status.UNKNOWN_MUTATION, List.of(chosen), diagnostics());
            return new ReadResolution(Status.SINGLE_WRITE, List.of(chosen), diagnostics());
        }

        private void explore(Context context, List<Location> path, Set<String> active, int depth) {
            boolean recursive = active.contains(context.analysis().method().key());
            if (depth > 32 || active.size() > 128 || recursive) {
                exposures.add(new Exposure(List.copyOf(path), !recursive));
                diagnostic("INTERPROCEDURAL_BOUND", context, null, "Recursive or oversized object initialization graph");
                return;
            }
            active.add(context.analysis().method().key());
            try {
                for (var instruction : context.analysis().instructions()) {
                    if (referenceRead && exposures.stream().anyMatch(exposure -> exposure.escape() && mayBefore(exposure.path(), publication))) return;
                    if (!context.analysis().reachable(instruction.instruction())) continue;
                    List<Location> location = new ArrayList<>(path);
                    location.add(new Location(context.analysis(), instruction.instruction()));
                    int opcode = instruction.instruction().getOpcode();
                    String escape = opcode == Opcodes.AASTORE ? "OBJECT_ARRAY_ESCAPE" : opcode == Opcodes.ATHROW ? "OBJECT_THROW_ESCAPE"
                            : opcode == Opcodes.ARETURN && path.isEmpty() ? "OBJECT_RETURN_ESCAPE" : null;
                    if (escape != null && !instruction.operands().isEmpty()
                            && resolve(value(context, instruction.operands().getLast())).identities().contains(object)) {
                        exposures.add(new Exposure(location, true));
                        diagnostic(escape, context, instruction.instruction(), "Allocated object leaves the proven reference path");
                    }
                    if (instruction.call() != null && instruction.call().dynamic()
                            && instruction.call().arguments().stream().anyMatch(argument -> resolve(value(context, argument)).identities().contains(object))) {
                        exposures.add(new Exposure(location, true));
                        diagnostic("DYNAMIC_CAPTURE_ESCAPE", context, instruction.instruction(), "Allocated object is captured by an unproven dynamic invocation");
                    }
                    var field = instruction.field();
                    if (field != null && field.write()) {
                        var receiver = resolve(value(context, field.receiver()));
                        var assigned = resolve(value(context, field.value()));
                        if (assigned.identities().contains(object) && !receiver.identities().contains(object)) {
                            exposures.add(new Exposure(location, true));
                            diagnostic("OBJECT_FIELD_ESCAPE", context, instruction.instruction(), "Allocated object is stored outside itself");
                        }
                        FieldKey key = field(repository, field.owner(), field.name(), field.descriptor());
                        if (key != null && receiver.identities().contains(object))
                            writes.add(new FieldWrite(key, object, value(context, field.value()), location, object.equals(receiver.exact())));
                    }
                    if (!(instruction.instruction() instanceof MethodInsnNode call)) continue;
                    if (context == root && publication.size() == 1 && call == publication.getFirst().instruction()) continue;
                    var receiver = resolve(value(context, instruction.call().receiver()));
                    boolean receiverUses = receiver.identities().contains(object);
                    boolean argumentUses = instruction.call().arguments().stream()
                            .map(argument -> resolve(value(context, argument))).anyMatch(result -> result.identities().contains(object));
                    boolean resultUses = !receiverUses && !argumentUses && instruction.call().result() != null
                            && resolve(value(context, instruction.call().result())).identities().contains(object);
                    boolean creation = object.context().size() > context.callPath().size()
                            && object.context().subList(0, context.callPath().size()).equals(context.callPath())
                            && object.context().get(context.callPath().size()).equals(new CallSite(context.analysis().method(), instruction.index()));
                    if (!receiverUses && !argumentUses && !resultUses && !creation) continue;
                    if (receiverUses && !argumentUses && call.getOpcode() == Opcodes.INVOKESPECIAL
                            && call.owner.equals("java/lang/Object") && call.name.equals("<init>") && call.desc.equals("()V")) continue;
                    Context callee = invocation(repository, context, call);
                    if (callee != null) {
                        explore(callee, location, active, depth + 1);
                    } else {
                        boolean constructor = call.name.equals("<init>");
                        exposures.add(new Exposure(location, argumentUses || constructor));
                        diagnostic(constructor ? "UNKNOWN_CONSTRUCTOR_ESCAPE" : argumentUses ? "UNKNOWN_CALL_ESCAPE" : "OPAQUE_RECEIVER_MUTATION", context,
                                call, "Object crosses an invocation without a proven body");
                    }
                }
            } finally {
                active.remove(context.analysis().method().key());
            }
        }

        private boolean before(List<Location> first, List<Location> second) {
            int common = 0;
            while (common < first.size() && common < second.size() && same(first.get(common), second.get(common))) common++;
            if (common == first.size() || common == second.size())
                return common == second.size() && first.size() > second.size() && completes(first, common);
            var left = first.get(common);
            var right = second.get(common);
            return left.analysis() == right.analysis() && control(left.analysis()).dominates(left.instruction(), right.instruction())
                    && completes(first, common + 1);
        }

        private boolean completes(List<Location> path, int start) {
            for (int index = start; index < path.size(); index++) {
                Location location = path.get(index);
                Control selected = control(location.analysis());
                if (selected.returns.isEmpty()) return false;
                for (AbstractInsnNode returned : selected.returns)
                    if (!selected.dominates(location.instruction(), returned)) return false;
            }
            return true;
        }

        private boolean mayBefore(List<Location> first, List<Location> second) {
            int common = 0;
            while (common < first.size() && common < second.size() && same(first.get(common), second.get(common))) common++;
            if (common == first.size() || common == second.size()) return common == second.size() && first.size() > second.size();
            var left = first.get(common);
            var right = second.get(common);
            return left.analysis() == right.analysis() && control(left.analysis()).reachable(left.instruction(), right.instruction());
        }

        private Control control(FormulaDataFlow.Analysis analysis) { return control.computeIfAbsent(analysis, Control::new); }

        private void diagnostic(String code, Context context, AbstractInsnNode instruction, String message) {
            var selected = instruction == null ? null : context.analysis().instruction(instruction);
            diagnostics.add(new Diagnostic(code, context.analysis().method(), selected == null ? -1 : selected.index(), message));
        }
    }

    private static FieldKey field(FormulaClassRepository repository, String owner, String name, String descriptor) {
        var resolved = repository.resolveField(owner, name, descriptor);
        return resolved == null ? null : new FieldKey(resolved.owner().name, name, descriptor);
    }

    private static boolean same(Location first, Location second) {
        return first.analysis() == second.analysis() && first.instruction() == second.instruction();
    }

    private static boolean platform(String owner) {
        return owner.startsWith("net/minecraft/") || owner.startsWith("net/neoforged/") || owner.startsWith("java/") || owner.startsWith("jdk/");
    }

    private static boolean alias(int opcode) {
        return opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD || opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE
                || opcode >= Opcodes.DUP && opcode <= Opcodes.SWAP || opcode == Opcodes.CHECKCAST;
    }

    private static final class Control {
        private final FormulaDataFlow.Analysis analysis;
        private final List<AbstractInsnNode> returns;

        private Control(FormulaDataFlow.Analysis analysis) {
            this.analysis = analysis;
            this.returns = analysis.instructions().stream().map(FormulaDataFlow.Instruction::instruction)
                    .filter(instruction -> instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN
                            && analysis.reachable(instruction)).toList();
        }

        private boolean reachable(AbstractInsnNode first, AbstractInsnNode second) {
            int start = analysis.instruction(first).index();
            int target = analysis.instruction(second).index();
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            pending.add(start);
            Set<Integer> seen = new HashSet<>();
            while (!pending.isEmpty()) {
                int current = pending.removeFirst();
                if (!seen.add(current)) continue;
                if (current == target) return true;
                for (var edge : analysis.successors(analysis.instructions().get(current).instruction())) pending.add(edge.to());
            }
            return false;
        }

        private boolean dominates(AbstractInsnNode first, AbstractInsnNode second) {
            return analysis.resultDominates(first, second);
        }
    }
}
