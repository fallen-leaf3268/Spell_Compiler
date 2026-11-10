package com.example.spell_compiler.formula;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FormulaExecutionGraph {
    private static final String SPELL = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    private static final String EFFECT = "net/minecraft/world/effect/MobEffect";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String REGISTER = "net/neoforged/neoforge/registries/DeferredRegister";
    private static final int MAX_METHODS = 512;
    private static final int MAX_EDGES = 8192;
    private static final Map<String, String> ENTITY_QUERIES = Map.ofEntries(
            Map.entry("getEntities(L" + ENTITY + ";Lnet/minecraft/world/phys/AABB;)Ljava/util/List;", ENTITY),
            Map.entry("getEntities(L" + ENTITY + ";Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", ENTITY),
            Map.entry("getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", ENTITY),
            Map.entry("getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Ljava/util/function/Predicate;)Ljava/util/List;", ENTITY),
            Map.entry("getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;", ENTITY),
            Map.entry("getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", ENTITY),
            Map.entry("getNearbyEntities(Ljava/lang/Class;Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;L" + LIVING
                    + ";Lnet/minecraft/world/phys/AABB;)Ljava/util/List;", LIVING));
    private static final Set<String> EXECUTION_ROOTS = Set.of("onCast", "onServerPreCast", "onServerCastTick",
            "onServerCastComplete", "onRecastFinished", "checkPreCastConditions");
    private static final Set<String> DESCRIPTION_ROOTS = Set.of("getUniqueInfo");
    private static final Set<String> EFFECT_CALLBACKS = Set.of("applyEffectTick", "applyInstantenousEffect",
            "onEffectAdded", "onEffectStarted", "onEffectRemoved", "shouldApplyEffectTickThisTick");
    private static final Set<String> ENTITY_CALLBACKS = Set.of("tick", "onHit", "onHitEntity", "onHitBlock",
            "applyEffect", "customServerAiStep");

    public record MethodId(String owner, String name, String descriptor) {
        public MethodId {
            owner = Objects.requireNonNull(owner).replace('.', '/');
            Objects.requireNonNull(name);
            Objects.requireNonNull(descriptor);
        }

        public String key() { return owner + '#' + name + descriptor; }
    }

    public enum EdgeKind { CALL, EFFECT_APPLICATION, EFFECT_EVENT, EFFECT_CALLBACK, ENTITY_CREATION,
        ENTITY_CALLBACK, DAMAGE_CONSUMPTION, SYNCHRONOUS_CALLBACK }

    public record Edge(MethodId from, MethodId to, EdgeKind kind, String evidence) {}

    public record UnresolvedCall(MethodId caller, int instruction, MethodId symbolic,
                                 List<MethodId> candidateTargets, boolean candidateSetComplete, String reason) {
        public UnresolvedCall { candidateTargets = List.copyOf(candidateTargets); }
    }

    public record EffectLink(String registryOwner, String registryField, String effectClass,
                             Set<MethodId> applications, Set<MethodId> eventMethods,
                             Set<MethodId> relatedMethods, Set<String> spellClasses, boolean ownershipComplete) {
        public EffectLink {
            applications = Set.copyOf(applications);
            eventMethods = Set.copyOf(eventMethods);
            relatedMethods = Set.copyOf(relatedMethods);
            spellClasses = Set.copyOf(spellClasses);
        }
    }

    public record Analysis(String spellClass, Set<MethodId> methods, List<Edge> edges, List<EffectLink> effects,
                           Set<String> entities, List<String> diagnostics, List<UnresolvedCall> unresolvedCalls) {
        public Analysis {
            methods = Set.copyOf(methods);
            edges = List.copyOf(edges);
            effects = List.copyOf(effects);
            entities = Set.copyOf(entities);
            diagnostics = List.copyOf(diagnostics);
            unresolvedCalls = List.copyOf(unresolvedCalls);
        }
        public Analysis(String spellClass, Set<MethodId> methods, List<Edge> edges, List<EffectLink> effects,
                        Set<String> entities, List<String> diagnostics) {
            this(spellClass, methods, edges, effects, entities, diagnostics, List.of());
        }
    }

    public record SynchronousMethods(Set<MethodId> roots, Set<MethodId> methods, List<String> diagnostics) {
        public SynchronousMethods {
            roots = Set.copyOf(roots);
            methods = Set.copyOf(methods);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record SynchronousCallback(MethodId caller, MethodInsnNode call, InvokeDynamicInsnNode factory,
                                      MethodId implementation, FormulaDataFlow.Value implementationReceiver,
                                      List<FormulaDataFlow.Value> capturedArguments, List<FormulaDataFlow.Value> elements,
                                      boolean dynamicElements, Type elementType) {
        public SynchronousCallback {
            capturedArguments = List.copyOf(capturedArguments);
            elements = List.copyOf(elements);
        }

        public SynchronousCallback(MethodId caller, MethodInsnNode call, InvokeDynamicInsnNode factory,
                                   MethodId implementation, FormulaDataFlow.Value implementationReceiver,
                                   List<FormulaDataFlow.Value> capturedArguments, List<FormulaDataFlow.Value> elements) {
            this(caller, call, factory, implementation, implementationReceiver, capturedArguments, elements, false,
                    ((Type) factory.bsmArgs[2]).getArgumentTypes()[0]);
        }
    }

    private record FieldId(String owner, String name, String descriptor) {
        String key() { return owner + '.' + name; }
    }

    private record Registration(String effectClass, MethodId factory, MethodId constructor) {}
    private record Application(MethodId method, FieldId field) {}
    private record ExecutionMethod(MethodId method, String receiver) {}
    private record CallTarget(MethodId method, String receiver, boolean complete, List<MethodId> candidates, String reason) {}
    private record EffectFootprint(Set<FieldId> fields, boolean unbounded, List<String> diagnostics) {}

    private FormulaExecutionGraph() {}

    public static Index index(FormulaClassRepository repository) { return repository.executionGraph(); }

    static Index createIndex(FormulaClassRepository repository) { return new Index(repository); }

    public static Analysis analyze(FormulaClassRepository repository, String spellClass) {
        return index(repository).analyze(spellClass);
    }

    public static final class Index {
        private final FormulaClassRepository repository;
        private final List<ClassNode> sources;
        private final Set<String> sourceNames;
        private final List<String> sortedSourceNames;
        private final Map<String, Integer> spellCallbacks;
        private final Map<MethodId, Data> bodies = new HashMap<>();
        private final Map<MethodId, String> broken = new HashMap<>();
        private final Map<FieldId, Registration> registrations = new LinkedHashMap<>();
        private final Map<FieldId, String> registeredSpellReceivers = new HashMap<>();
        private final Map<FieldId, FieldId> aliases = new HashMap<>();
        private final Map<FieldId, Set<MethodId>> events = new LinkedHashMap<>();
        private final Map<FieldId, Set<MethodId>> unregisteredEvents = new LinkedHashMap<>();
        private final Map<String, List<Application>> applications = new LinkedHashMap<>();
        private final Map<FieldId, Set<String>> owners = new LinkedHashMap<>();
        private final Map<String, Analysis> analyses = new HashMap<>();
        private final Map<String, SynchronousMethods> synchronous = new HashMap<>();
        private final Map<String, SynchronousMethods> synchronousExecutions = new HashMap<>();
        private final Map<MethodId, List<SynchronousCallback>> callbackBodies = new HashMap<>();
        private final Map<String, List<SynchronousCallback>> callbacks = new HashMap<>();
        private final Map<String, Boolean> queryOverrides = new HashMap<>();
        private final Map<MethodId, Set<MethodId>> synchronousCalls = new HashMap<>();
        private final Map<MethodId, Set<MethodId>> synchronousCallers = new HashMap<>();
        private final Set<MethodId> asynchronousReferences = new HashSet<>();
        private boolean synchronousReferencesIndexed;
        private final Map<String, List<MethodId>> sourceMethods = new HashMap<>();
        private final Map<MethodId, List<MethodId>> sourceOverrides = new HashMap<>();
        private final Map<MethodId, Boolean> applicationPotential = new HashMap<>();
        private final Set<String> ownershipDiagnostics = new LinkedHashSet<>();
        private final FormulaEffectOwnership effectOwnership = new FormulaEffectOwnership();
        private final Map<MethodId, EffectFootprint> effectFootprints = new HashMap<>();
        private final Map<MethodId, Set<MethodId>> functionalImplementations = new HashMap<>();

        private Index(FormulaClassRepository repository) {
            this.repository = Objects.requireNonNull(repository);
            sources = repository.sourceClasses();
            sourceNames = sources.stream().map(value -> value.name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            sortedSourceNames = sourceNames.stream().sorted().toList();
            for (ClassNode source : sources)
                for (MethodNode method : source.methods)
                    if ((method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0)
                        sourceMethods.computeIfAbsent(method.name + method.desc, ignored -> new ArrayList<>()).add(id(source.name, method));
            spellCallbacks = spellCallbackContracts();
            indexFunctionalImplementations();
            indexRegistrations();
            indexEvents();
            for (String name : sortedSourceNames) {
                ClassNode source = repository.classNode(name);
                if (source == null || name.equals(SPELL) || (source.access & Opcodes.ACC_ABSTRACT) != 0
                        || !repository.isSubtype(name, SPELL)) continue;
                List<Application> values = findApplications(name);
                applications.put(name, values);
                for (Application application : values)
                    owners.computeIfAbsent(application.field(), ignored -> new LinkedHashSet<>()).add(name);
            }
        }

        public synchronized Analysis analyze(String spellClass) {
            String name = Objects.requireNonNull(spellClass).replace('.', '/');
            return analyses.computeIfAbsent(name, this::build);
        }

        public synchronized List<FormulaEffectOwnership.Uncertainty> ownershipUncertainties() {
            return effectOwnership.uncertainties();
        }

        public synchronized String registeredSpellReceiver(String owner, String name, String descriptor) {
            var field = new FieldId(owner.replace('.', '/'), name, descriptor);
            var declaration = repository.resolveField(field.owner(), field.name(), field.descriptor());
            if (declaration == null || (declaration.field().access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                    != (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) return null;
            return registeredSpellReceivers.get(canonical(field));
        }

        public synchronized SynchronousMethods synchronousHelpers(String spellClass) {
            String name = Objects.requireNonNull(spellClass).replace('.', '/');
            return synchronous.computeIfAbsent(name, value -> buildSynchronousHelpers(value, Set.of("onCast")));
        }

        public synchronized List<SynchronousCallback> synchronousCallbacks(String spellClass) {
            String spell = Objects.requireNonNull(spellClass).replace('.', '/');
            return callbacks.computeIfAbsent(spell, name -> {
                var synchronous = synchronousExecutionHelpers(name);
                Set<MethodId> allowed = new HashSet<>(synchronous.roots());
                allowed.addAll(synchronous.methods());
                return allowed.stream().sorted(Comparator.comparing(MethodId::key)).flatMap(method -> callbackBody(method).stream())
                        .filter(callback -> synchronous.methods().contains(callback.implementation())).toList();
            });
        }

        public synchronized List<SynchronousCallback> callbacks(MethodId caller) { return callbackBody(caller); }

        public synchronized SynchronousMethods synchronousExecutionHelpers(String spellClass) {
            String name = Objects.requireNonNull(spellClass).replace('.', '/');
            return synchronousExecutions.computeIfAbsent(name, value -> buildSynchronousHelpers(value,
                    Set.of("onCast", "onServerPreCast", "onServerCastTick", "onServerCastComplete", "checkPreCastConditions")));
        }

        private SynchronousMethods buildSynchronousHelpers(String spell, Set<String> rootNames) {
            Set<String> diagnostics = new LinkedHashSet<>();
            if (!sourceNames.contains(spell) || !repository.isSubtype(spell, SPELL))
                return new SynchronousMethods(Set.of(), Set.of(), List.of("无法确认同步法术入口：" + spell));
            Set<MethodId> roots = spellRoots(spell, rootNames);
            if (roots.isEmpty())
                return new SynchronousMethods(roots, Set.of(), List.of("没有匹配当前版本合同的同步 onCast 实现：" + spell));
            indexSynchronousReferences();
            Set<String> family = new HashSet<>();
            String owner = spell;
            while (owner != null && !owner.equals(SPELL) && sourceNames.contains(owner) && family.add(owner)) {
                ClassNode source = repository.classNode(owner);
                owner = source == null ? null : source.superName;
            }
            Set<MethodId> visited = new HashSet<>();
            Set<MethodId> helpers = new LinkedHashSet<>();
            ArrayDeque<MethodId> pending = new ArrayDeque<>(roots);
            while (!pending.isEmpty() && visited.size() < MAX_METHODS) {
                MethodId current = pending.removeFirst();
                if (!visited.add(current)) continue;
                for (MethodId target : synchronousCalls.getOrDefault(current, Set.of())) {
                    if (!family.contains(target.owner()) || roots.contains(target)) continue;
                    var resolved = repository.resolveMethod(target.owner(), target.name(), target.descriptor());
                    if (resolved == null || (resolved.method().access & Opcodes.ACC_PRIVATE) == 0
                            || (resolved.method().access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                            || target.name().equals("<init>") || target.name().equals("<clinit>")) continue;
                    if (helpers.add(target)) pending.add(target);
                }
            }
            if (pending.stream().anyMatch(value -> !visited.contains(value)))
                return new SynchronousMethods(roots, Set.of(), List.of("同步辅助方法图达到静态分析边界，保留原生行为：" + spell));
            boolean changed;
            do {
                changed = false;
                Set<MethodId> allowed = new HashSet<>(roots);
                allowed.addAll(helpers);
                for (MethodId root : roots) {
                    if (asynchronousReferences.contains(root)) {
                        diagnostics.add("onCast 存在方法句柄或动态常量引用，不扩展同步辅助方法：" + root.key());
                        return new SynchronousMethods(roots, Set.of(), diagnostics.stream().sorted().toList());
                    }
                    MethodId outside = synchronousCallers.getOrDefault(root, Set.of()).stream()
                            .filter(value -> !allowed.contains(value)).min(Comparator.comparing(MethodId::key)).orElse(null);
                    if (outside != null) {
                        diagnostics.add("onCast 另有施法闭包外显式调用，不扩展同步辅助方法：" + root.key() + " <- " + outside.key());
                        return new SynchronousMethods(roots, Set.of(), diagnostics.stream().sorted().toList());
                    }
                }
                for (MethodId helper : List.copyOf(helpers)) {
                    if (asynchronousReferences.contains(helper)) {
                        helpers.remove(helper);
                        diagnostics.add("同步辅助方法存在方法句柄或动态常量引用，保留原生行为：" + helper.key());
                        changed = true;
                        continue;
                    }
                    MethodId outside = synchronousCallers.getOrDefault(helper, Set.of()).stream()
                            .filter(value -> !allowed.contains(value)).min(Comparator.comparing(MethodId::key)).orElse(null);
                    if (outside != null) {
                        helpers.remove(helper);
                        diagnostics.add("同步辅助方法另有施法闭包外调用，保留原生行为：" + helper.key() + " <- " + outside.key());
                        changed = true;
                    }
                }
            } while (changed);
            return new SynchronousMethods(roots, helpers, diagnostics.stream().sorted().toList());
        }

        private void indexSynchronousReferences() {
            if (synchronousReferencesIndexed) return;
            for (String name : sortedSourceNames) {
                ClassNode source = repository.classNode(name);
                for (FieldNode field : source.fields) indexSynchronousConstant(field.value);
                for (MethodNode method : source.methods) {
                    MethodId caller = id(name, method);
                    Data reachableBody = body(caller);
                    if (reachableBody == null) {
                        asynchronousReferences.add(caller);
                        continue;
                    }
                    List<SynchronousCallback> callbacks = callbackBody(caller);
                    Set<InvokeDynamicInsnNode> factories = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                    for (var callback : callbacks) {
                        factories.add(callback.factory());
                        synchronousCalls.computeIfAbsent(caller, ignored -> new LinkedHashSet<>()).add(callback.implementation());
                        synchronousCallers.computeIfAbsent(callback.implementation(), ignored -> new LinkedHashSet<>()).add(caller);
                    }
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (!reachableBody.flow.reachable(instruction)) continue;
                        if (instruction instanceof MethodInsnNode call) {
                            MethodId target = synchronousTarget(call.owner, call.name, call.desc);
                            if (target == null) continue;
                            synchronousCalls.computeIfAbsent(caller, ignored -> new LinkedHashSet<>()).add(target);
                            synchronousCallers.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(caller);
                        } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                            if (factories.contains(dynamic)) continue;
                            indexSynchronousConstant(dynamic.bsm);
                            for (Object argument : dynamic.bsmArgs) indexSynchronousConstant(argument);
                        } else if (instruction instanceof LdcInsnNode literal) indexSynchronousConstant(literal.cst);
                    }
                }
            }
            synchronousReferencesIndexed = true;
        }

        private List<SynchronousCallback> callbackBody(MethodId caller) {
            return callbackBodies.computeIfAbsent(caller, this::findCallbacks);
        }

        private List<SynchronousCallback> findCallbacks(MethodId caller) {
            var resolved = repository.resolveMethod(caller.owner(), caller.name(), caller.descriptor());
            if (resolved == null || resolved.method().instructions.size() > 50000
                    || java.util.Arrays.stream(resolved.method().instructions.toArray()).noneMatch(value ->
                    value instanceof MethodInsnNode call && listForEach(call))) return List.of();
            Data body = body(caller);
            if (body == null) return List.of();
            FormulaDataFlow.Analysis flow = body.flow;
            List<SynchronousCallback> candidates = new ArrayList<>();
            for (var operation : flow.instructions()) {
                if (!(operation.instruction() instanceof MethodInsnNode call) || !listForEach(call) || !flow.reachable(call)) continue;
                var collection = flow.origin(flow.receiver(call));
                if (collection == null || !(collection.instruction() instanceof MethodInsnNode list)) continue;
                Type dynamicType = entityQueryType(body, list);
                boolean dynamicElements = dynamicType != null;
                List<FormulaDataFlow.Value> elements;
                if (dynamicElements) elements = List.of();
                else {
                    if (list.getOpcode() != Opcodes.INVOKESTATIC || !list.owner.equals("java/util/List") || !list.name.equals("of")
                            || !Type.getReturnType(list.desc).equals(Type.getObjectType("java/util/List"))) continue;
                    Type[] listTypes = Type.getArgumentTypes(list.desc);
                    if (listTypes.length == 0 || listTypes.length > 10 || java.util.Arrays.stream(listTypes)
                            .anyMatch(type -> !type.equals(Type.getObjectType("java/lang/Object")))) continue;
                    elements = flow.arguments(list);
                    if (elements.size() != listTypes.length) continue;
                }
                var source = flow.origin(flow.arguments(call).getFirst());
                if (source == null || !(source.instruction() instanceof InvokeDynamicInsnNode factory)
                        || factory.bsm.getTag() != Opcodes.H_INVOKESTATIC
                        || !factory.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                        || !factory.bsm.getName().equals("metafactory") || factory.bsmArgs.length != 3
                        || !factory.bsm.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")
                        || !factory.name.equals("accept")
                        || !Type.getReturnType(factory.desc).equals(Type.getObjectType("java/util/function/Consumer"))
                        || !(factory.bsmArgs[0] instanceof Type sam) || !sam.getDescriptor().equals("(Ljava/lang/Object;)V")
                        || !(factory.bsmArgs[1] instanceof Handle implementation)
                        || !(factory.bsmArgs[2] instanceof Type instantiated) || instantiated.getSort() != Type.METHOD
                        || !instantiated.getReturnType().equals(Type.VOID_TYPE)) continue;
                Type[] instantiatedArguments = instantiated.getArgumentTypes();
                if (instantiatedArguments.length != 1) continue;
                int tag = implementation.getTag();
                if (tag != Opcodes.H_INVOKESTATIC && tag != Opcodes.H_INVOKEVIRTUAL && tag != Opcodes.H_INVOKESPECIAL) continue;
                var target = repository.resolveMethod(implementation.getOwner(), implementation.getName(), implementation.getDesc());
                if (target == null || !sourceNames.contains(target.owner().name)
                        || (target.method().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != Opcodes.ACC_PRIVATE
                        || !Type.getReturnType(target.method().desc).equals(Type.VOID_TYPE)
                        || ((target.method().access & Opcodes.ACC_STATIC) != 0) != (tag == Opcodes.H_INVOKESTATIC)) continue;
                Type[] capturedTypes = Type.getArgumentTypes(factory.desc), parameters = Type.getArgumentTypes(target.method().desc);
                int offset = tag == Opcodes.H_INVOKESTATIC ? 0 : 1;
                var dynamic = flow.instruction(factory).call();
                if (dynamic == null || capturedTypes.length < offset || dynamic.arguments().size() != capturedTypes.length
                        || parameters.length != capturedTypes.length - offset + 1) continue;
                if (offset == 1 && !compatible(capturedTypes[0], Type.getObjectType(implementation.getOwner()))) continue;
                boolean compatible = true;
                for (int argument = offset; argument < capturedTypes.length; argument++)
                    if (!compatible(capturedTypes[argument], parameters[argument - offset])) compatible = false;
                Type elementType = instantiatedArguments[0];
                if (!compatible(elementType, parameters[parameters.length - 1]) || elementType.getSort() < Type.ARRAY) continue;
                if (dynamicElements && !compatible(elementType, dynamicType) && !compatible(dynamicType, elementType)) continue;
                for (var element : elements) if (!compatible(element.type(), elementType)) compatible = false;
                if (!compatible) continue;
                candidates.add(new SynchronousCallback(caller, call, factory, id(target.owner().name, target.method()),
                        offset == 1 ? dynamic.arguments().getFirst() : null,
                        dynamic.arguments().subList(offset, dynamic.arguments().size()), elements, dynamicElements, elementType));
            }
            if (candidates.isEmpty()) return List.of();
            Map<InvokeDynamicInsnNode, Set<MethodInsnNode>> uses = new IdentityHashMap<>();
            for (var callback : candidates) uses.computeIfAbsent(callback.factory(), ignored ->
                    java.util.Collections.newSetFromMap(new IdentityHashMap<>())).add(callback.call());
            Set<InvokeDynamicInsnNode> escaped = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (var operation : flow.instructions()) {
                int opcode = operation.instruction().getOpcode();
                if (opcode == Opcodes.ALOAD || opcode == Opcodes.ASTORE || opcode == Opcodes.CHECKCAST
                        || opcode >= Opcodes.DUP && opcode <= Opcodes.SWAP || opcode == Opcodes.POP) continue;
                for (var input : operation.operands()) for (var origin : flow.origins(input).nodes()) {
                    if (!(origin.instruction() instanceof InvokeDynamicInsnNode factory) || !uses.containsKey(factory)) continue;
                    if (!(operation.instruction() instanceof MethodInsnNode call) || !uses.get(factory).contains(call)
                            || !origin.equals(flow.origin(flow.arguments(call).getFirst()))) escaped.add(factory);
                }
            }
            return candidates.stream().filter(callback -> !escaped.contains(callback.factory())).toList();
        }

        private Type entityQueryType(Data body, MethodInsnNode call) {
            String type = ENTITY_QUERIES.get(call.name + call.desc);
            if (type == null || call.getOpcode() == Opcodes.INVOKESTATIC || !repository.isSubtype(call.owner, LEVEL)) return null;
            var implementation = repository.resolveMethod(call.owner, call.name, call.desc);
            if (implementation == null) return null;
            String exact = null;
            var receiver = body.flow.receiver(call);
            var origin = body.flow.origin(receiver);
            if (origin != null && origin.instruction() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW) {
                exact = allocation.desc;
                implementation = virtualMethod(exact, implementation);
            }
            if (implementation == null || !implementation.owner().name.startsWith("net/minecraft/")
                    || (implementation.method().access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || implementation.method().instructions.size() == 0) return null;
            String bound = receiver != null && receiver.type() != null && receiver.type().getSort() == Type.OBJECT
                    && repository.isSubtype(receiver.type().getInternalName(), LEVEL) ? receiver.type().getInternalName() : call.owner;
            String target = exact == null ? bound : exact;
            String key = (exact == null ? "subtypes:" : "exact:") + target;
            boolean knownOverride = queryOverrides.computeIfAbsent(key, ignored -> {
                for (ClassNode source : sources) {
                    boolean overrides = source.methods.stream().anyMatch(method -> (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0
                            && ENTITY_QUERIES.containsKey(method.name + method.desc));
                    if (!overrides) continue;
                    if (key.startsWith("exact:") ? repository.isSubtype(target, source.name) : repository.isSubtype(source.name, target)) return true;
                    if (!key.startsWith("exact:") && (source.access & Opcodes.ACC_INTERFACE) != 0)
                        for (ClassNode candidate : sources)
                            if ((candidate.access & Opcodes.ACC_INTERFACE) == 0 && repository.isSubtype(candidate.name, target)
                                    && repository.isSubtype(candidate.name, source.name)) return true;
                }
                return false;
            });
            return knownOverride ? null : Type.getObjectType(type);
        }

        private static boolean listForEach(MethodInsnNode call) {
            return call.getOpcode() == Opcodes.INVOKEINTERFACE && call.owner.equals("java/util/List")
                    && call.name.equals("forEach") && call.desc.equals("(Ljava/util/function/Consumer;)V");
        }

        private boolean compatible(Type actual, Type expected) {
            if (actual == null || expected == null) return false;
            if (actual.equals(expected)) return true;
            return actual.getSort() >= Type.ARRAY && expected.getSort() >= Type.ARRAY
                    && !actual.equals(org.objectweb.asm.tree.analysis.BasicInterpreter.NULL_TYPE)
                    && repository.isSubtype(actual.getInternalName(), expected.getInternalName());
        }

        private void indexSynchronousConstant(Object constant) {
            ArrayDeque<Object> pending = new ArrayDeque<>();
            if (constant != null) pending.add(constant);
            Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            while (!pending.isEmpty()) {
                Object value = pending.removeFirst();
                if (!visited.add(value)) continue;
                if (value instanceof Handle handle && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
                    MethodId target = synchronousTarget(handle.getOwner(), handle.getName(), handle.getDesc());
                    if (target != null) asynchronousReferences.add(target);
                } else if (value instanceof ConstantDynamic dynamic) {
                    pending.add(dynamic.getBootstrapMethod());
                    for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++)
                        pending.add(dynamic.getBootstrapMethodArgument(index));
                }
            }
        }

        private MethodId synchronousTarget(String owner, String name, String descriptor) {
            var resolved = repository.resolveMethod(owner, name, descriptor);
            return resolved == null || !sourceNames.contains(resolved.owner().name) ? null
                    : id(resolved.owner().name, resolved.method());
        }

        private Analysis build(String spell) {
            var graph = new Walk(spell);
            if (!sourceNames.contains(spell) || !repository.isSubtype(spell, SPELL)) {
                graph.diagnostics.add("无法确认法术执行图根节点：" + spell);
                return graph.result(spell, List.of());
            }
            Set<MethodId> roots = spellRoots(spell, EXECUTION_ROOTS);
            roots.addAll(spellRoots(spell, DESCRIPTION_ROOTS));
            graph.walk(roots);
            effectOwnership.uncertainties().stream().filter(value -> value.spellClass().equals(spell)).limit(3)
                    .map(FormulaEffectOwnership.Uncertainty::diagnostic).forEach(graph.diagnostics::add);
            List<EffectLink> links = new ArrayList<>();
            Map<FieldId, Set<MethodId>> grouped = new LinkedHashMap<>();
            for (Application value : applications.getOrDefault(spell, List.of()))
                grouped.computeIfAbsent(value.field(), ignored -> new LinkedHashSet<>()).add(value.method());
            for (var entry : grouped.entrySet()) {
                FieldId field = entry.getKey();
                Registration registration = registrations.get(field);
                Set<MethodId> handlers = events.getOrDefault(field, Set.of());
                for (MethodId candidate : unregisteredEvents.getOrDefault(field, Set.of()))
                    graph.diagnostics.add("效果事件方法缺少可确认的服务端事件注册，保留为未接入候选：" + candidate.key());
                Set<MethodId> related = new LinkedHashSet<>();
                if (registration == null) {
                    graph.diagnostics.add("药水注册字段无法还原具体效果类：" + field.key());
                } else {
                    for (MethodId application : entry.getValue()) {
                        graph.edge(application, registration.constructor(), EdgeKind.EFFECT_APPLICATION, field.key());
                        for (MethodId handler : handlers)
                            graph.edge(application, handler, EdgeKind.EFFECT_EVENT, field.key());
                    }
                    var effectWalk = new Walk(registration.effectClass());
                    effectWalk.walk(handlers);
                    for (MethodId callback : roots(registration.effectClass(), EFFECT_CALLBACKS)) {
                        for (MethodId application : entry.getValue())
                            effectWalk.edge(application, callback, EdgeKind.EFFECT_CALLBACK, field.key());
                        effectWalk.walk(Set.of(callback));
                    }
                    related.addAll(effectWalk.methods);
                    graph.merge(effectWalk);
                }
                Set<String> ownership = owners.getOrDefault(field, Set.of());
                if (ownership.size() > 1)
                    graph.diagnostics.add("效果被多支法术施加，消费归属存在歧义：" + field.key() + " "
                            + ownership.stream().sorted().toList());
                var decision = effectOwnership.decision(effectKey(field));
                for (var uncertainty : decision.uncertainties())
                    graph.diagnostics.add("效果施加归属尚未完整：" + field.key() + " <- " + uncertainty.diagnostic());
                links.add(new EffectLink(field.owner(), field.name(), registration == null ? "" : registration.effectClass(),
                        entry.getValue(), handlers, related, ownership, decision.complete()));
            }
            links.sort(Comparator.comparing(EffectLink::registryOwner).thenComparing(EffectLink::registryField));
            return graph.result(spell, links);
        }

        private Map<String, Integer> spellCallbackContracts() {
            Map<String, Integer> result = new HashMap<>();
            Set<String> seen = new HashSet<>();
            Set<String> signatures = new HashSet<>();
            for (String owner = SPELL; owner != null && seen.add(owner); ) {
                ClassNode source = repository.classNode(owner);
                if (source == null) break;
                for (MethodNode method : source.methods) {
                    if (!EXECUTION_ROOTS.contains(method.name) && !DESCRIPTION_ROOTS.contains(method.name)) continue;
                    String signature = method.name + method.desc;
                    if (!signatures.add(signature)
                            || (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != 0
                            || (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) continue;
                    result.put(signature, method.access);
                }
                owner = source.superName;
            }
            return Map.copyOf(result);
        }

        private Set<MethodId> spellRoots(String leaf, Set<String> names) {
            Set<MethodId> result = new LinkedHashSet<>();
            for (MethodId candidate : roots(leaf, names)) {
                Integer contract = spellCallbacks.get(candidate.name() + candidate.descriptor());
                if (contract == null) continue;
                var resolved = repository.resolveMethod(candidate.owner(), candidate.name(), candidate.descriptor());
                if (resolved == null) continue;
                int access = resolved.method().access;
                if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) continue;
                int visibility = (contract & Opcodes.ACC_PUBLIC) != 0 ? Opcodes.ACC_PUBLIC
                        : Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED;
                if ((access & visibility) != 0) result.add(candidate);
            }
            return result;
        }

        private Set<MethodId> roots(String leaf, Set<String> names) {
            Set<MethodId> result = new LinkedHashSet<>();
            Set<String> seen = new HashSet<>();
            Set<String> signatures = new HashSet<>();
            for (String owner = leaf; owner != null && sourceNames.contains(owner) && seen.add(owner); ) {
                ClassNode source = repository.classNode(owner);
                if (source == null || owner.equals(SPELL)) break;
                for (MethodNode method : source.methods)
                    if (names.contains(method.name) && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0
                            && (names == ENTITY_CALLBACKS || names == EFFECT_CALLBACKS ? callbackContract(source, method) : true)
                            && signatures.add(method.name + method.desc)) result.add(id(owner, method));
                owner = source.superName;
            }
            return result;
        }

        private boolean callbackContract(ClassNode owner, MethodNode method) {
            if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0 || owner.superName == null) return false;
            var parent = repository.resolveMethod(owner.superName, method.name, method.desc);
            if (parent == null || (parent.method().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != 0)
                return false;
            if ((parent.method().access & Opcodes.ACC_PUBLIC) != 0 && (method.access & Opcodes.ACC_PUBLIC) == 0) return false;
            return (parent.method().access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                    || samePackage(parent.owner().name, owner.name);
        }

        private void indexRegistrations() {
            Map<FieldId, Integer> writes = new HashMap<>();
            for (String name : sortedSourceNames) {
                ClassNode source = repository.classNode(name);
                for (MethodNode method : source.methods) {
                    if (!method.name.equals("<clinit>")) continue;
                    Data data = body(id(name, method));
                    if (data == null) continue;
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (!(instruction instanceof FieldInsnNode write) || write.getOpcode() != Opcodes.PUTSTATIC) continue;
                        FieldId destination = field(write);
                        writes.merge(destination, 1, Integer::sum);
                        AbstractInsnNode origin = data.origin(data.top(write));
                        if (origin instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC) {
                            aliases.put(destination, field(read));
                            continue;
                        }
                        if (origin instanceof MethodInsnNode call && registeredInstanceCall(call)) {
                            AbstractInsnNode instance = data.origin(data.argument(call, 0));
                            if (instance instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                                    && repository.isSubtype(allocation.desc, SPELL) && data.constructor(allocation) != null)
                                registeredSpellReceivers.put(destination, allocation.desc);
                        }
                        if (!(origin instanceof MethodInsnNode call) || !call.name.equals("register")
                                || !repository.isSubtype(call.owner, REGISTER)) continue;
                        Type[] arguments = Type.getArgumentTypes(call.desc);
                        Registration selected = null;
                        for (int argument = 0; argument < arguments.length; argument++) {
                            AbstractInsnNode supplier = data.origin(data.argument(call, argument));
                            if (!(supplier instanceof InvokeDynamicInsnNode dynamic)
                                    || !dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) continue;
                            for (Object value : dynamic.bsmArgs) if (value instanceof Handle handle) {
                                Registration candidate = factory(handle);
                                if (candidate != null) {
                                    if (selected != null && !selected.equals(candidate)) {
                                        selected = null;
                                        break;
                                    }
                                    selected = candidate;
                                }
                            }
                        }
                        if (selected != null) {
                            if (repository.isSubtype(selected.effectClass(), SPELL)) registeredSpellReceivers.put(destination, selected.effectClass());
                            else registrations.put(destination, selected);
                        }
                    }
                }
            }
            writes.forEach((field, count) -> { if (count > 1) registeredSpellReceivers.remove(field); });
            for (FieldId alias : aliases.keySet()) {
                FieldId canonical = canonical(alias);
                Registration registration = registrations.get(canonical);
                if (registration != null) registrations.put(alias, registration);
                String spell = registeredSpellReceivers.get(canonical);
                if (spell != null && writes.getOrDefault(alias, 0) <= 1) registeredSpellReceivers.put(alias, spell);
            }
        }

        private boolean registeredInstanceCall(MethodInsnNode call) {
            if (call.getOpcode() != Opcodes.INVOKESTATIC
                    || !List.of(Type.getArgumentTypes(call.desc)).equals(List.of(Type.getObjectType(SPELL)))) return false;
            var resolved = repository.resolveMethod(call.owner, call.name, call.desc);
            if (resolved == null || !sourceNames.contains(resolved.owner().name)) return false;
            List<AbstractInsnNode> code = new ArrayList<>();
            for (AbstractInsnNode instruction : resolved.method().instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
            if (code.size() != 7 || !(code.get(0) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                    || Type.getType(field.desc).getSort() != Type.OBJECT
                    || !repository.isSubtype(Type.getType(field.desc).getInternalName(), REGISTER)
                    || !(code.get(1) instanceof VarInsnNode first) || first.getOpcode() != Opcodes.ALOAD || first.var != 0
                    || !(code.get(2) instanceof MethodInsnNode name) || name.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !name.owner.equals(SPELL) || !name.name.equals("getSpellName") || !name.desc.equals("()Ljava/lang/String;")
                    || !(code.get(3) instanceof VarInsnNode capture) || capture.getOpcode() != Opcodes.ALOAD || capture.var != 0
                    || !(code.get(4) instanceof InvokeDynamicInsnNode supplier)
                    || !supplier.desc.equals("(L" + SPELL + ";)Ljava/util/function/Supplier;")
                    || !supplier.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || supplier.bsmArgs.length < 2
                    || !(supplier.bsmArgs[1] instanceof Handle implementation) || implementation.getTag() != Opcodes.H_INVOKESTATIC
                    || !implementation.getDesc().equals("(L" + SPELL + ";)L" + SPELL + ';')
                    || !(code.get(5) instanceof MethodInsnNode register) || register.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !repository.isSubtype(register.owner, REGISTER) || !register.name.equals("register")
                    || !List.of(Type.getArgumentTypes(register.desc)).equals(List.of(Type.getType(String.class), Type.getType(java.util.function.Supplier.class)))
                    || !compatibleRegisterReturn(register.desc, call.desc) || code.get(6).getOpcode() != Opcodes.ARETURN)
                return false;
            var target = repository.resolveMethod(implementation.getOwner(), implementation.getName(), implementation.getDesc());
            if (target == null) return false;
            List<AbstractInsnNode> identity = new ArrayList<>();
            for (AbstractInsnNode instruction : target.method().instructions) if (instruction.getOpcode() >= 0) identity.add(instruction);
            return identity.size() == 2 && identity.get(0) instanceof VarInsnNode input && input.getOpcode() == Opcodes.ALOAD
                    && input.var == 0 && identity.get(1).getOpcode() == Opcodes.ARETURN;
        }

        private boolean compatibleRegisterReturn(String actualMethod, String expectedMethod) {
            Type actual = Type.getReturnType(actualMethod), expected = Type.getReturnType(expectedMethod);
            return actual.equals(expected) || actual.getSort() == Type.OBJECT && expected.getSort() == Type.OBJECT
                    && repository.isSubtype(actual.getInternalName(), expected.getInternalName());
        }

        private Registration factory(Handle handle) {
            if (handle.getTag() == Opcodes.H_NEWINVOKESPECIAL && handle.getName().equals("<init>")
                    && (repository.isSubtype(handle.getOwner(), EFFECT) || repository.isSubtype(handle.getOwner(), SPELL))) {
                var constructor = new MethodId(handle.getOwner(), handle.getName(), handle.getDesc());
                return new Registration(handle.getOwner(), constructor, constructor);
            }
            var resolved = repository.resolveMethod(handle.getOwner(), handle.getName(), handle.getDesc());
            if (resolved == null || !sourceNames.contains(resolved.owner().name)) return null;
            MethodId factory = id(resolved.owner().name, resolved.method());
            Data data = body(factory);
            if (data == null) return null;
            Registration result = null;
            for (AbstractInsnNode instruction : resolved.method().instructions) {
                if (instruction.getOpcode() != Opcodes.ARETURN) continue;
                AbstractInsnNode origin = data.origin(data.top(instruction));
                List<MethodInsnNode> fluent = new ArrayList<>();
                Set<AbstractInsnNode> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                while (origin instanceof MethodInsnNode call && visited.add(origin)
                        && returnsReceiver(call, null, new HashSet<>(), 0)) {
                    fluent.add(call);
                    origin = data.origin(data.receiver(call));
                }
                if (!(origin instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                        || (!repository.isSubtype(allocation.desc, EFFECT) && !repository.isSubtype(allocation.desc, SPELL))) return null;
                if (fluent.stream().anyMatch(call -> !returnsReceiver(call, allocation.desc, new HashSet<>(), 0))) return null;
                MethodId constructor = data.constructor(allocation);
                if (constructor == null) return null;
                var candidate = new Registration(allocation.desc, factory, constructor);
                if (result != null && !result.equals(candidate)) return null;
                result = candidate;
            }
            return result;
        }

        private boolean returnsReceiver(MethodInsnNode call, String receiver, Set<String> visited, int depth) {
            if (depth > 8 || call.getOpcode() == Opcodes.INVOKESTATIC || call.name.equals("<init>")) return false;
            var resolved = receiver == null ? repository.resolveMethod(call.owner, call.name, call.desc)
                    : repository.resolveInvocation(call.owner, call, receiver).target();
            if (resolved == null || (resolved.method().access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || !visited.add(resolved.owner().name + '#' + resolved.method().name + resolved.method().desc)) return false;
            var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
            if (!flow.successful()) return false;
            boolean returned = false;
            for (var operation : flow.instructions()) {
                if (operation.instruction().getOpcode() != Opcodes.ARETURN || !flow.reachable(operation.instruction())) continue;
                if (operation.operands().size() != 1) return false;
                returned = true;
                var origin = flow.origin(operation.operands().get(0));
                Set<FormulaDataFlow.Node> seen = new HashSet<>();
                while (origin != null && seen.add(origin) && origin.instruction() instanceof MethodInsnNode forwarded
                        && returnsReceiver(forwarded, receiver, new HashSet<>(visited), depth + 1))
                    origin = flow.origin(flow.receiver(forwarded));
                if (origin == null || origin.kind() != FormulaDataFlow.NodeKind.PARAMETER || origin.parameterSlot() != 0) return false;
            }
            return returned;
        }

        private void indexEvents() {
            for (String name : sortedSourceNames) {
                ClassNode source = repository.classNode(name);
                for (MethodNode method : source.methods) {
                    if (!subscribed(method)) continue;
                    MethodId id = id(name, method);
                    Data data = body(id);
                    if (data == null) continue;
                    for (AbstractInsnNode instruction : method.instructions) {
                        if (!(instruction instanceof MethodInsnNode call)
                                || !Set.of("getEffect", "hasEffect").contains(call.name)
                                || !repository.isSubtype(call.owner, LIVING)
                                || Type.getArgumentTypes(call.desc).length != 1) continue;
                        FieldId field = data.holder(data.argument(call, 0));
                        if (field != null) {
                            var target = serverSubscriber(source) && (method.access & Opcodes.ACC_STATIC) != 0
                                    ? events : unregisteredEvents;
                            target.computeIfAbsent(canonical(field), ignored -> new LinkedHashSet<>()).add(id);
                        }
                    }
                }
            }
        }

        private static boolean subscribed(MethodNode method) {
            return hasSubscription(method.visibleAnnotations) || hasSubscription(method.invisibleAnnotations);
        }

        private static boolean serverSubscriber(ClassNode owner) {
            List<AnnotationNode> annotations = new ArrayList<>();
            if (owner.visibleAnnotations != null) annotations.addAll(owner.visibleAnnotations);
            if (owner.invisibleAnnotations != null) annotations.addAll(owner.invisibleAnnotations);
            for (AnnotationNode annotation : annotations) {
                if (!annotation.desc.equals("Lnet/neoforged/fml/common/EventBusSubscriber;")) continue;
                if (annotation.values == null) return true;
                for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
                    String key = annotation.values.get(index).toString();
                    Object value = annotation.values.get(index + 1);
                    if (key.equals("bus") && value instanceof String[] bus && bus.length == 2 && bus[1].equals("MOD"))
                        return false;
                    if (key.equals("value") && value instanceof List<?> sides
                            && sides.stream().noneMatch(side -> side instanceof String[] dist
                            && dist.length == 2 && dist[1].equals("DEDICATED_SERVER"))) return false;
                }
                return true;
            }
            return false;
        }

        private static boolean hasSubscription(List<AnnotationNode> annotations) {
            return annotations != null && annotations.stream().anyMatch(value ->
                    value.desc.equals("Lnet/neoforged/bus/api/SubscribeEvent;"));
        }

        private List<Application> findApplications(String spell) {
            List<Application> result = new ArrayList<>();
            ArrayDeque<ExecutionMethod> pending = new ArrayDeque<>();
            spellRoots(spell, EXECUTION_ROOTS).forEach(value -> pending.add(new ExecutionMethod(value, spell)));
            Set<ExecutionMethod> visited = new HashSet<>();
            while (!pending.isEmpty() && visited.size() < MAX_METHODS) {
                ExecutionMethod work = pending.removeFirst();
                MethodId id = work.method();
                if (!visited.add(work)) continue;
                Data data = body(id);
                if (data == null) {
                    if (applicationPotential.computeIfAbsent(id, this::mayApplyEffect))
                        ownershipUncertainty(spell, id, List.of(id), "效果施加归属索引不完整，法术方法无法分析：" + spell + " -> " + id.key());
                    continue;
                }
                    List<SynchronousCallback> callbacks = callbackBody(id);
                    for (var callback : callbacks)
                        if (expandable(callback.implementation())) pending.add(new ExecutionMethod(callback.implementation(),
                                callbackReceiver(callback, data, work.receiver())));
                Map<AbstractInsnNode, FieldId> constructed = new IdentityHashMap<>();
                Set<AbstractInsnNode> published = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                for (AbstractInsnNode instruction : data.method.instructions) {
                    if (!data.flow.reachable(instruction)) continue;
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    Type[] arguments = Type.getArgumentTypes(call.desc);
                    if (call.owner.equals(INSTANCE) && call.name.equals("<init>") && arguments.length > 1) {
                        FieldId field = data.holder(data.argument(call, 0));
                        AbstractInsnNode receiver = data.origin(data.receiver(call));
                        if (field != null && receiver != null) constructed.put(receiver, canonical(field));
                    } else if (Set.of("addEffect", "forceAddEffect").contains(call.name)
                            && arguments.length >= 1 && arguments[0].equals(Type.getObjectType(INSTANCE))
                            && repository.isSubtype(call.owner, LIVING)) {
                        AbstractInsnNode origin = data.origin(data.argument(call, 0));
                        if (origin != null) published.add(origin);
                    }
                    CallTarget resolution = resolved(call, data, work.receiver());
                    MethodId target = resolution.method();
                    MethodId symbolic = new MethodId(call.owner, call.name, call.desc);
                    if (!resolution.complete() && !accessor(call) && (resolution.candidates().isEmpty()
                            ? sourceOverrides.computeIfAbsent(symbolic, this::sourceOverrides).isEmpty()
                            || applicationPotential.computeIfAbsent(symbolic, this::mayApplyEffect)
                            : resolution.candidates().stream().filter(candidate -> sourceNames.contains(candidate.owner()))
                            .anyMatch(candidate -> applicationPotential.computeIfAbsent(candidate, this::mayApplyEffect))))
                        ownershipUncertainty(spell, id, resolution.candidates().isEmpty() ? List.of(symbolic) : resolution.candidates(),
                                "效果施加归属索引不完整，法术调用无法唯一解析：" + spell + " -> "
                                + id.key() + " -> " + call.owner + '#' + call.name + call.desc);
                    if (target != null && expandable(target)) pending.add(new ExecutionMethod(target, resolution.receiver()));
                }
                for (AbstractInsnNode object : published) {
                    FieldId field = constructed.get(object);
                    if (field != null) result.add(new Application(id, field));
                }
            }
            List<MethodId> remaining = pending.stream().filter(value -> !visited.contains(value)).map(ExecutionMethod::method).toList();
            if (!remaining.isEmpty()) ownershipUncertainty(spell, remaining.get(0), remaining,
                    "效果施加归属索引达到静态分析边界：" + spell + " -> " + remaining.get(0).key());
            return List.copyOf(result);
        }

        private FormulaEffectOwnership.Effect effectKey(FieldId field) {
            FieldId value = canonical(field);
            return new FormulaEffectOwnership.Effect(value.owner(), value.name());
        }

        private void indexFunctionalImplementations() {
            for (ClassNode source : sources) for (MethodNode method : source.methods)
                for (AbstractInsnNode instruction : method.instructions)
                    if (instruction instanceof InvokeDynamicInsnNode dynamic
                            && dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                            && dynamic.bsmArgs.length >= 2 && dynamic.bsmArgs[0] instanceof Type contract
                            && dynamic.bsmArgs[1] instanceof Handle implementation
                            && Type.getReturnType(dynamic.desc).getSort() == Type.OBJECT
                            && sourceNames.contains(implementation.getOwner())) {
                        MethodId key = new MethodId(Type.getReturnType(dynamic.desc).getInternalName(), dynamic.name, contract.getDescriptor());
                        functionalImplementations.computeIfAbsent(key, ignored -> new LinkedHashSet<>())
                                .add(new MethodId(implementation.getOwner(), implementation.getName(), implementation.getDesc()));
                    }
        }

        private void ownershipUncertainty(String spell, MethodId caller, List<MethodId> candidates, String diagnostic) {
            ownershipDiagnostics.add(diagnostic);
            Set<FormulaEffectOwnership.Effect> possible = new LinkedHashSet<>();
            Set<String> boundaries = new LinkedHashSet<>();
            boolean unbounded = false;
            Set<MethodId> domains = new LinkedHashSet<>();
            for (MethodId candidate : candidates) {
                var declaration = repository.resolveMethod(candidate.owner(), candidate.name(), candidate.descriptor());
                Set<MethodId> implementations = functionalImplementations.getOrDefault(candidate, Set.of());
                if (declaration != null && (declaration.method().access & Opcodes.ACC_ABSTRACT) != 0 && !implementations.isEmpty()) {
                    domains.addAll(implementations);
                    domains.addAll(sourceOverrides.computeIfAbsent(candidate, this::sourceOverrides));
                } else domains.add(candidate);
            }
            for (MethodId candidate : domains) {
                EffectFootprint footprint = effectFootprints.computeIfAbsent(candidate, this::effectFootprint);
                footprint.fields().stream().map(this::effectKey).forEach(possible::add);
                unbounded |= footprint.unbounded();
                boundaries.addAll(footprint.diagnostics());
            }
            effectOwnership.add(new FormulaEffectOwnership.Uncertainty(spell, caller, possible, unbounded,
                    boundaries.isEmpty() ? diagnostic : diagnostic + "，无法缩窄的效果来源：" + boundaries.stream().limit(3).toList()));
        }

        private EffectFootprint effectFootprint(MethodId root) {
            Set<FieldId> fields = new LinkedHashSet<>();
            Set<MethodId> visited = new HashSet<>();
            ArrayDeque<MethodId> pending = new ArrayDeque<>();
            pending.add(root);
            boolean unbounded = false;
            Set<String> diagnostics = new LinkedHashSet<>();
            while (!pending.isEmpty() && visited.size() < MAX_METHODS) {
                MethodId current = pending.removeFirst();
                if (!visited.add(current)) continue;
                var resolved = repository.resolveMethod(current.owner(), current.name(), current.descriptor());
                if (resolved == null) { unbounded = true; diagnostics.add("MISSING_METHOD " + current.key()); continue; }
                MethodNode method = resolved.method();
                if ((method.access & Opcodes.ACC_ABSTRACT) != 0) {
                    List<MethodId> overrides = sourceOverrides.computeIfAbsent(current, this::sourceOverrides);
                    Set<MethodId> functions = functionalImplementations.getOrDefault(current, Set.of());
                    pending.addAll(overrides);
                    pending.addAll(functions);
                    if (overrides.isEmpty() && functions.isEmpty() && Type.getReturnType(current.descriptor()).equals(Type.VOID_TYPE)) {
                        unbounded = true;
                        diagnostics.add("UNKNOWN_IMPLEMENTATION " + current.key());
                    }
                    continue;
                }
                if ((method.access & Opcodes.ACC_NATIVE) != 0) { unbounded = true; diagnostics.add("NATIVE_METHOD " + current.key()); continue; }
                Data data = body(current);
                Map<AbstractInsnNode, FieldId> constructed = new IdentityHashMap<>();
                if (data != null) for (AbstractInsnNode instruction : method.instructions) {
                    if (!data.flow.reachable(instruction) || !(instruction instanceof MethodInsnNode call)
                            || !call.owner.equals(INSTANCE) || !call.name.equals("<init>")) continue;
                    FieldId field = data.holder(data.argument(call, 0));
                    AbstractInsnNode allocation = data.origin(data.receiver(call));
                    if (field != null && allocation != null) constructed.put(allocation, canonical(field));
                }
                for (AbstractInsnNode instruction : method.instructions) {
                    if (data != null && !data.flow.reachable(instruction)) continue;
                    if (instruction instanceof InvokeDynamicInsnNode dynamic)
                        for (Object argument : dynamic.bsmArgs)
                            if (argument instanceof Handle handle && sourceNames.contains(handle.getOwner()))
                                pending.add(new MethodId(handle.getOwner(), handle.getName(), handle.getDesc()));
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    Type[] arguments = Type.getArgumentTypes(call.desc);
                    if (Set.of("addEffect", "forceAddEffect").contains(call.name)
                            && arguments.length > 0 && arguments[0].equals(Type.getObjectType(INSTANCE))
                            && repository.isSubtype(call.owner, LIVING)) {
                        FieldId field = data == null ? null : constructed.get(data.origin(data.argument(call, 0)));
                        if (field == null) { unbounded = true; diagnostics.add("DYNAMIC_EFFECT " + current.key()); }
                        else fields.add(field);
                    }
                    CallTarget resolution;
                    if (data != null) resolution = resolved(call, data, null);
                    else {
                        var target = repository.resolveInvocation(current.owner(), call, null);
                        resolution = new CallTarget(target.target() == null ? null : id(target.target().owner().name, target.target().method()),
                                null, target.target() != null, target.candidates().stream().map(value -> id(value.owner().name, value.method())).toList(), target.reason());
                    }
                    if (resolution.method() != null && expandable(resolution.method())) pending.add(resolution.method());
                    if (!resolution.complete()) for (MethodId candidate : resolution.candidates())
                        if (expandable(candidate)) pending.add(candidate);
                }
            }
            if (pending.stream().anyMatch(value -> !visited.contains(value))) { unbounded = true; diagnostics.add("METHOD_LIMIT " + root.key()); }
            return new EffectFootprint(Set.copyOf(fields), unbounded, List.copyOf(diagnostics));
        }

        private boolean expandable(MethodId method) {
            if (!sourceNames.contains(method.owner()) || method.owner().equals(SPELL)
                    || method.name().equals("<clinit>")) return false;
            return !method.owner().contains("/network/") && !method.owner().contains("/particle/")
                    && !method.owner().contains("/client/");
        }

        private boolean accessor(MethodInsnNode call) {
            var method = repository.resolveMethod(call.owner, call.name, call.desc);
            if (method == null || (method.method().access & Opcodes.ACC_ABSTRACT) == 0) return false;
            var annotations = method.method().visibleAnnotations;
            var invisible = method.method().invisibleAnnotations;
            return (annotations != null && annotations.stream().anyMatch(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")))
                    || (invisible != null && invisible.stream().anyMatch(value -> value.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")));
        }

        private String callbackReceiver(SynchronousCallback callback, Data body, String context) {
            if (callback.implementationReceiver() == null) return null;
            if (context != null && body.parameter(callback.implementationReceiver(), 0)) return context;
            AbstractInsnNode origin = body.origin(callback.implementationReceiver());
            return origin instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW ? allocation.desc : null;
        }

        private CallTarget resolved(MethodInsnNode call, Data data, String context) {
            String receiver = null;
            if (context != null && repository.isSubtype(context, data.owner)
                    && (data.method.access & Opcodes.ACC_STATIC) == 0 && data.parameter(data.receiver(call), 0)) receiver = context;
            else if (data.origin(data.receiver(call)) instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW)
                receiver = allocation.desc;
            else {
                FieldId field = data.holder(data.receiver(call));
                var declaration = field == null ? null : repository.resolveField(field.owner(), field.name(), field.descriptor());
                if (declaration != null && (declaration.field().access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                        == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) receiver = registeredSpellReceivers.get(canonical(field));
            }
            var resolution = repository.resolveInvocation(data.owner, call, receiver);
            List<MethodId> candidates = resolution.candidates().stream().map(value -> id(value.owner().name, value.method())).toList();
            var target = resolution.target();
            if (target != null) return new CallTarget(id(target.owner().name, target.method()), receiver, true, candidates, resolution.reason());
            boolean external = !sourceNames.contains(call.owner) && candidates.stream().noneMatch(value -> sourceNames.contains(value.owner()));
            return new CallTarget(null, receiver, external, candidates, resolution.reason());
        }

        private FormulaClassRepository.ResolvedMethod virtualMethod(String receiver, FormulaClassRepository.ResolvedMethod declared) {
            return FormulaMethodResolver.virtual(repository, receiver, declared);
        }

        private List<MethodId> sourceOverrides(MethodId method) {
            List<MethodId> result = new ArrayList<>();
            for (MethodId candidate : sourceMethods.getOrDefault(method.name() + method.descriptor(), List.of()))
                if (!candidate.owner().equals(method.owner()) && repository.isSubtype(candidate.owner(), method.owner()))
                    result.add(candidate);
            return List.copyOf(result);
        }

        private boolean mayApplyEffect(MethodId root) {
            ArrayDeque<MethodId> pending = new ArrayDeque<>();
            pending.add(root);
            pending.addAll(sourceOverrides.computeIfAbsent(root, this::sourceOverrides));
            Set<MethodId> visited = new HashSet<>();
            while (!pending.isEmpty() && visited.size() < MAX_METHODS) {
                MethodId current = pending.removeFirst();
                if (!visited.add(current)) continue;
                var source = repository.resolveMethod(current.owner(), current.name(), current.descriptor());
                if (source == null) return true;
                if ((source.method().access & Opcodes.ACC_NATIVE) != 0) return true;
                if ((source.method().access & Opcodes.ACC_ABSTRACT) != 0) continue;
                Data reachableBody = body(current);
                if (reachableBody == null) return true;
                for (AbstractInsnNode instruction : source.method().instructions) {
                    if (!reachableBody.flow.reachable(instruction)) continue;
                    if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                        for (Object argument : dynamic.bsmArgs)
                            if (argument instanceof Handle handle && sourceNames.contains(handle.getOwner()))
                                pending.add(new MethodId(handle.getOwner(), handle.getName(), handle.getDesc()));
                    }
                    if (!(instruction instanceof MethodInsnNode call)) continue;
                    Type[] arguments = Type.getArgumentTypes(call.desc);
                    if (Set.of("addEffect", "forceAddEffect").contains(call.name) && arguments.length > 0
                            && arguments[0].equals(Type.getObjectType(INSTANCE)) && repository.isSubtype(call.owner, LIVING))
                        return true;
                    var target = repository.resolveMethod(call.owner, call.name, call.desc);
                    if (target == null) continue;
                    MethodId targetId = id(target.owner().name, target.method());
                    if (sourceNames.contains(targetId.owner())) pending.add(targetId);
                    if (call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE)
                        pending.addAll(sourceOverrides.computeIfAbsent(targetId, this::sourceOverrides));
                }
            }
            return pending.stream().anyMatch(value -> !visited.contains(value));
        }

        private static boolean samePackage(String first, String second) {
            int a = first.lastIndexOf('/');
            int b = second.lastIndexOf('/');
            return a == b && first.regionMatches(0, second, 0, a + 1);
        }

        private Data body(MethodId id) {
            Data known = bodies.get(id);
            if (known != null || broken.containsKey(id)) return known;
            var resolved = repository.resolveMethod(id.owner(), id.name(), id.descriptor());
            if (resolved == null || (resolved.method().access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                broken.put(id, "没有可读取的方法体");
                return null;
            }
            try {
                Data result = new Data(repository, id.owner(), resolved.method());
                bodies.put(id, result);
                return result;
            } catch (AnalyzerException | RuntimeException failure) {
                broken.put(id, failure.getClass().getSimpleName() + ": " + failure.getMessage());
                return null;
            }
        }

        private FieldId canonical(FieldId field) {
            Set<FieldId> seen = new HashSet<>();
            while (seen.add(field) && aliases.containsKey(field)) field = aliases.get(field);
            return field;
        }

        private final class Walk {
            private final String spell;
            private final Set<MethodId> methods = new LinkedHashSet<>();
            private final Set<Edge> edges = new LinkedHashSet<>();
            private final Set<String> entities = new LinkedHashSet<>();
            private final Set<String> diagnostics = new LinkedHashSet<>();
            private final Set<UnresolvedCall> unresolvedCalls = new LinkedHashSet<>();
            private final Set<ExecutionMethod> expanded = new HashSet<>();

            private Walk(String spell) { this.spell = spell; }

            private void walk(Set<MethodId> roots) {
                ArrayDeque<ExecutionMethod> pending = new ArrayDeque<>();
                roots.stream().sorted(Comparator.comparing(MethodId::key))
                        .forEach(value -> pending.add(new ExecutionMethod(value, spell)));
                while (!pending.isEmpty() && expanded.size() < MAX_METHODS && edges.size() < MAX_EDGES) {
                    ExecutionMethod work = pending.removeFirst();
                    MethodId id = work.method();
                    if (!expanded.add(work)) continue;
                    methods.add(id);
                    Data data = body(id);
                    if (data == null) {
                        diagnostics.add("执行图方法无法分析：" + id.key() + " " + broken.get(id));
                        continue;
                    }
                    List<SynchronousCallback> callbacks = callbackBody(id);
                    Set<InvokeDynamicInsnNode> factories = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                    for (var callback : callbacks) {
                        factories.add(callback.factory());
                        edge(id, callback.implementation(), EdgeKind.SYNCHRONOUS_CALLBACK,
                                callback.dynamicElements() ? "platform_entity_query.forEach:dynamic"
                                        : "java/util/List.of.forEach:" + callback.elements().size());
                        if (expandable(callback.implementation())) pending.add(new ExecutionMethod(callback.implementation(),
                                callbackReceiver(callback, data, work.receiver())));
                    }
                    for (AbstractInsnNode instruction : data.method.instructions) {
                        if (!data.flow.reachable(instruction)) continue;
                        if (instruction instanceof InvokeDynamicInsnNode dynamic
                                && dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && !factories.contains(dynamic))
                            diagnostics.add("执行图存在尚未证明调用时机的回调：" + id.key());
                        if (!(instruction instanceof MethodInsnNode call)) continue;
                        CallTarget resolution = resolved(call, data, work.receiver());
                        MethodId target = resolution.method();
                        if (!resolution.complete()) {
                            diagnostics.add("执行图存在尚未唯一解析的虚调用：" + id.key()
                                    + " -> " + call.owner + '#' + call.name + call.desc + " candidates=" + resolution.candidates());
                            unresolvedCalls.add(new UnresolvedCall(id, data.instructionIndex(call),
                                    new MethodId(call.owner, call.name, call.desc), resolution.candidates(), false, resolution.reason()));
                        }
                        if (target != null) {
                            var implementation = repository.resolveMethod(target.owner(), target.name(), target.descriptor());
                            if (FormulaConsumptionModel.classify(repository, call, implementation).stream().anyMatch(value ->
                                    value.kind() == FormulaAutomaticSites.Kind.DAMAGE && value.proof() == FormulaConsumptionModel.Proof.TERMINAL))
                                edge(id, target, EdgeKind.DAMAGE_CONSUMPTION, call.name + ":damage");
                            if (sourceNames.contains(target.owner())) {
                                edge(id, target, EdgeKind.CALL, call.owner + '#' + call.name + call.desc);
                                if (expandable(target)) pending.add(new ExecutionMethod(target, resolution.receiver()));
                            }
                        }
                        if (call.name.equals("addFreshEntity") && Type.getArgumentTypes(call.desc).length == 1
                                && repository.isSubtype(call.owner, LEVEL)) {
                            AbstractInsnNode origin = data.allocation(data.argument(call, 0));
                            if (origin instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                                    && repository.isSubtype(allocation.desc, ENTITY)) {
                                entities.add(allocation.desc);
                                MethodId constructor = data.constructor(allocation);
                                if (constructor != null) edge(id, constructor, EdgeKind.ENTITY_CREATION, allocation.desc);
                                for (MethodId callback : roots(allocation.desc, ENTITY_CALLBACKS)) {
                                    edge(id, callback, EdgeKind.ENTITY_CALLBACK, allocation.desc);
                                    pending.add(new ExecutionMethod(callback, allocation.desc));
                                }
                            } else diagnostics.add("实体发布来源无法唯一还原：" + id.key());
                        }
                    }
                }
                if (!pending.isEmpty()) diagnostics.add("执行图达到静态分析边界，存在未展开节点");
            }

            private void edge(MethodId from, MethodId to, EdgeKind kind, String evidence) {
                if (edges.size() < MAX_EDGES) edges.add(new Edge(from, to, kind, evidence));
            }

            private void merge(Walk other) {
                methods.addAll(other.methods);
                edges.addAll(other.edges);
                entities.addAll(other.entities);
                diagnostics.addAll(other.diagnostics);
                unresolvedCalls.addAll(other.unresolvedCalls);
            }

            private Analysis result(String spell, List<EffectLink> links) {
                return new Analysis(spell, methods, edges.stream().sorted(Comparator.comparing((Edge value) -> value.from().key())
                        .thenComparing(value -> value.to().key()).thenComparing(value -> value.kind().name())).toList(),
                        links, entities, diagnostics.stream().sorted().toList(), unresolvedCalls.stream()
                        .sorted(Comparator.comparing((UnresolvedCall value) -> value.caller().key()).thenComparingInt(UnresolvedCall::instruction)).toList());
            }
        }
    }

    private static MethodId id(String owner, MethodNode method) { return new MethodId(owner, method.name, method.desc); }
    private static FieldId field(FieldInsnNode field) { return new FieldId(field.owner, field.name, field.desc); }

    private static final class Data {
        private final FormulaClassRepository repository;
        private final String owner;
        private final MethodNode method;
        private final FormulaDataFlow.Analysis flow;
        private FormulaMethodSnapshot snapshot;
        private Map<TypeInsnNode, MethodId> constructors;

        private Data(FormulaClassRepository repository, String owner, MethodNode method) throws AnalyzerException {
            this.repository = repository;
            this.owner = owner;
            this.method = method;
            flow = FormulaDataFlow.analyze(repository, owner, method);
            if (!flow.successful()) throw new AnalyzerException(null, flow.diagnostics().toString());
        }

        private SourceValue top(AbstractInsnNode instruction) {
            FormulaDataFlow.FrameState frame = flow.frameBefore(instruction);
            return frame == null || frame.stack().isEmpty() ? null : frame.stack().getLast();
        }

        private int instructionIndex(AbstractInsnNode instruction) {
            if (snapshot == null) snapshot = new FormulaMethodSnapshot(method);
            return snapshot.indexOf(instruction);
        }

        private SourceValue argument(MethodInsnNode call, int argument) {
            List<FormulaDataFlow.Value> values = flow.arguments(call);
            return argument < 0 || argument >= values.size() ? null : values.get(argument);
        }

        private SourceValue receiver(MethodInsnNode call) {
            return flow.receiver(call);
        }

        private AbstractInsnNode origin(SourceValue value) {
            FormulaDataFlow.Node source = flow.origin(value);
            return source == null ? null : source.instruction();
        }

        private AbstractInsnNode allocation(SourceValue value) {
            AbstractInsnNode direct = origin(value);
            if (direct instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW) return direct;
            var object = FormulaInterproceduralFlow.object(repository,
                    FormulaInterproceduralFlow.value(FormulaInterproceduralFlow.root(flow), value)).exact();
            if (object == null || object.kind() != FormulaInterproceduralFlow.IdentityKind.ALLOCATION
                    || !object.method().equals(flow.method()) || !object.context().isEmpty()
                    || object.instruction() < 0 || object.instruction() >= method.instructions.size()) return null;
            AbstractInsnNode source = method.instructions.get(object.instruction());
            return source instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW ? source : null;
        }

        private boolean parameter(SourceValue value, int slot) {
            return flow.parameter(value, slot);
        }

        private FieldId holder(SourceValue value) {
            AbstractInsnNode origin = origin(value);
            Set<AbstractInsnNode> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            while (origin instanceof MethodInsnNode call && seen.add(origin)
                    && Set.of("get", "value").contains(call.name) && Type.getArgumentTypes(call.desc).length == 0
                    && (call.owner.equals("net/neoforged/neoforge/registries/DeferredHolder")
                    || call.owner.equals("net/minecraft/core/Holder"))) origin = origin(receiver(call));
            return origin instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC ? field(read) : null;
        }

        private MethodId constructor(TypeInsnNode allocation) {
            if (constructors == null) {
                Map<TypeInsnNode, MethodId> indexed = new IdentityHashMap<>();
                for (AbstractInsnNode instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                            && call.name.equals("<init>") && origin(receiver(call)) instanceof TypeInsnNode created
                            && created.getOpcode() == Opcodes.NEW && call.owner.equals(created.desc))
                        indexed.put(created, indexed.containsKey(created) ? null : new MethodId(call.owner, call.name, call.desc));
                constructors = indexed;
            }
            return constructors.get(allocation);
        }
    }
}
