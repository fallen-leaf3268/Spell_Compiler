package com.example.spell_compiler.formula;

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
import java.util.Set;

public final class FormulaEntityFlow {
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SPELL = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    private static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String CONTEXT = "com/example/spell_compiler/formula/FormulaEntityContext";
    private static final Set<String> CONSTRUCTORS = Set.of("(" + HOLDER + "I)V", "(" + HOLDER + "II)V",
            "(" + HOLDER + "IIZZ)V", "(" + HOLDER + "IIZZZ)V",
            "(" + HOLDER + "IIZZZL" + INSTANCE + ";Ljava/util/Optional;)V");
    private static final Set<String> CALLBACKS = Set.of("onHit(Lnet/minecraft/world/phys/HitResult;)V",
            "onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V",
            "onHitBlock(Lnet/minecraft/world/phys/BlockHitResult;)V", "tick()V", "customServerAiStep()V",
            "applyEffect(L" + LIVING + ";)V", "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            "m_6469_(Lnet/minecraft/world/damagesource/DamageSource;F)Z");
    private static final Set<String> NUMERIC_HOOKS = Set.of(
            "com/example/spell_compiler/formula/FormulaNumericHooks",
            "com/example/spell_compiler/formula/FormulaSiteHooks",
            "com/example/spell_compiler/formula/FormulaEntityHooks",
            "com/example/spell_compiler/formula/FormulaConsumerHooks",
            "com/example/spell_compiler/formula/FormulaPublishedCorrectionsHooks", CONTEXT);
    private static final Map<String, List<Capture>> CAPTURES = new LinkedHashMap<>();
    private static final Map<String, Set<Capture>> VERIFIED = new LinkedHashMap<>();

    public enum CaptureMode { ENTITY, EFFECT_SOURCE, EFFECT_SCOPED, EFFECT_EVENT, EFFECT_CALLBACK }

    public record Capture(String owner, String methodName, String methodDescriptor, int producerIndex,
                          String methodFingerprint, int casterSlot, int levelSlot, int spellSlot,
                          String entityClassName, int allocationOrdinal, int publicationOrdinal, int parentSlot,
                          CaptureMode mode, String registryOwner, String registryField, int effectReadOrdinal,
                          String registryDescriptor) {
        public Capture {
            owner = owner.replace('/', '.');
            entityClassName = entityClassName.replace('/', '.');
            registryOwner = registryOwner.replace('.', '/');
        }

        public Capture(String owner, String methodName, String methodDescriptor, int producerIndex,
                       String methodFingerprint, int casterSlot, int levelSlot, int spellSlot,
                       String entityClassName, int allocationOrdinal, int publicationOrdinal, int parentSlot,
                       CaptureMode mode, String registryOwner, String registryField, int effectReadOrdinal) {
            this(owner, methodName, methodDescriptor, producerIndex, methodFingerprint, casterSlot, levelSlot, spellSlot,
                    entityClassName, allocationOrdinal, publicationOrdinal, parentSlot, mode, registryOwner,
                    registryField, effectReadOrdinal, "");
        }

        public Capture(String owner, String methodName, String methodDescriptor, int producerIndex,
                       String methodFingerprint, int casterSlot, int levelSlot, int spellSlot, String entityClassName,
                       int allocationOrdinal, int publicationOrdinal, int parentSlot) {
            this(owner, methodName, methodDescriptor, producerIndex, methodFingerprint, casterSlot, levelSlot, spellSlot,
                    entityClassName, allocationOrdinal, publicationOrdinal, parentSlot, CaptureMode.ENTITY, "", "", -1);
        }

        public Capture(String owner, String methodName, String methodDescriptor, int producerIndex,
                       String methodFingerprint, int casterSlot, int levelSlot, int spellSlot, String entityClassName,
                       int allocationOrdinal, int publicationOrdinal) {
            this(owner, methodName, methodDescriptor, producerIndex, methodFingerprint, casterSlot, levelSlot, spellSlot,
                    entityClassName, allocationOrdinal, publicationOrdinal, -1);
        }

        public Capture(String owner, String methodName, String methodDescriptor, int producerIndex,
                       String methodFingerprint, int casterSlot, int levelSlot, int spellSlot, String entityClassName) {
            this(owner, methodName, methodDescriptor, producerIndex, methodFingerprint,
                    casterSlot, levelSlot, spellSlot, entityClassName, -1, -1);
        }
    }

    public record Result(List<FormulaAutomaticSites.Site> sites, List<Capture> captures, List<String> diagnostics) {
        public Result {
            sites = List.copyOf(sites);
            captures = List.copyOf(captures);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record Parameter(int slot) {}
    private record Body(String owner, MethodNode method) {}
    private record Consumer(Body body, MethodInsnNode call, int argument, FormulaAutomaticSites.Kind kind, Number constant) {}
    private record Publication(Body body, MethodInsnNode call, TypeInsnNode allocation) {}
    private record EntityNode(String type, List<Consumer> consumers, List<Publication> publications) {}
    private record EntityWork(String type, String source, java.util.function.Supplier<FormulaInterproceduralFlow.State> provenance) {}
    private record Descendants(List<Consumer> consumers, List<Capture> captures) {}
    private record Prepared(MethodNode method, MethodInsnNode call, Capture capture) {}
    private record EffectRead(MethodInsnNode call, int slot) {}
    private final FormulaClassRepository repository;
    private final String spell;
    private final Set<String> reserved;
    private final List<FormulaAutomaticSites.Site> coveredSites;
    private final List<String> diagnostics = new ArrayList<>();
    private final Map<String, Data> bodies = new HashMap<>();
    private final Set<String> broken = new HashSet<>();
    private final Map<String, Boolean> manual = new HashMap<>();
    private final Map<MethodNode, FormulaMethodSnapshot> methodSnapshots = new IdentityHashMap<>();
    private final Map<FormulaDataFlow.MethodId, Boolean> extractableSources = new HashMap<>();

    private FormulaEntityFlow(FormulaClassRepository repository, String spell, Set<String> reserved,
                              List<FormulaAutomaticSites.Site> coveredSites) {
        this.repository = repository;
        this.spell = spell.replace('/', '.');
        this.reserved = Set.copyOf(reserved);
        this.coveredSites = List.copyOf(coveredSites);
    }

    public static Result scan(FormulaClassRepository repository, String spell, Set<String> reserved) {
        return scan(repository, spell, reserved, List.of());
    }

    public static Result scan(FormulaClassRepository repository, String spell, Set<String> reserved,
                              List<FormulaAutomaticSites.Site> coveredSites) {
        return new FormulaEntityFlow(repository, spell, reserved, coveredSites).scan();
    }

    private Result scan() {
        if (!repository.isSubtype(spell.replace('.', '/'), SPELL)) return new Result(List.of(), List.of(), List.of());
        List<Capture> proposed = new ArrayList<>();
        Map<String, Consumer> consumers = new LinkedHashMap<>();
        var roots = FormulaExecutionGraph.index(repository).synchronousExecutionHelpers(spell).roots().stream()
                .sorted(Comparator.comparing(FormulaExecutionGraph.MethodId::key)).toList();
        for (var root : roots) {
            var resolved = repository.resolveMethod(root.owner(), root.name(), root.descriptor());
            if (resolved == null || !rootContext(resolved.method())) continue;
            Body body = new Body(resolved.owner().name, resolved.method());
            Data data = data(body);
            if (data == null) continue;
            for (var instruction : body.method().instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !spawn(call)
                        || !repository.isSubtype(call.owner, LEVEL)) continue;
                Object origin = data.origin(data.argument(call, 0));
                if (!(origin instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                        || !repository.isSubtype(allocation.desc, ENTITY)) {
                    diagnostics.add("实体发布参数不是唯一的新实体，保留原生回调：" + key(body));
                    continue;
                }
                FormulaInterproceduralFlow.State[] provenance = new FormulaInterproceduralFlow.State[1];
                var selected = descendants(allocation.desc, () -> {
                    if (provenance[0] == null)
                        provenance[0] = FormulaInterproceduralFlow.inspectPublishedObject(repository, data.analysis, call);
                    return provenance[0];
                });
                if (selected.consumers().isEmpty()) continue;
                for (Consumer consumer : selected.consumers()) consumers.putIfAbsent(key(consumer), consumer);
                proposed.addAll(selected.captures());
                proposed.add(new Capture(body.owner(), body.method().name, body.method().desc,
                        snapshot(body.method()).indexOf(call),
                        snapshot(body.method()).fingerprint(), 3, 2, 0, allocation.desc,
                        allocationOrdinal(body.method(), allocation), publicationOrdinal(body.method(), call)));
            }
        }
        effectEntitySources(roots, proposed, consumers);
        List<Consumer> sorted = consumers.values().stream().sorted(Comparator
                .comparing((Consumer consumer) -> consumer.body().owner())
                .thenComparing(consumer -> consumer.body().method().name)
                .thenComparing(consumer -> consumer.body().method().desc)
                .thenComparingInt(consumer -> snapshot(consumer.body().method()).indexOf(consumer.call()))
                .thenComparingInt(Consumer::argument)).toList();
        List<FormulaAutomaticSites.Site> sites = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        Set<String> used = new HashSet<>(reserved);
        for (Consumer consumer : sorted) {
            String base = switch (consumer.kind()) {
                case DURATION -> "impact_effect_duration_ticks";
                case EFFECT_LEVEL -> "impact_effect_level";
                default -> "impact_" + FormulaAutomaticFlow.alias(consumer.kind());
            };
            if (reserved.contains(base)) {
                diagnostics.add("实体回调语义已有同名入口，保留原生消费：" + base);
                continue;
            }
            int ordinal = counts.merge(base, 1, Integer::sum);
            String alias = ordinal == 1 ? base : base + '_' + ordinal;
            while (!used.add(alias)) alias = base + '_' + counts.merge(base, 1, Integer::sum);
            Number constant = consumer.constant();
            boolean movement = consumer.kind().name().startsWith("MOVEMENT_");
            if (constant != null && (!Double.isFinite(constant.doubleValue()) || !movement && constant.doubleValue() < 0)) {
                diagnostics.add("实体回调包含负数或无限期原生值，保留原生消费：" + key(consumer));
                continue;
            }
            Type type = Type.getArgumentTypes(consumer.call().desc)[consumer.argument()];
            FormulaNumericSites.ValueType valueType = type.equals(Type.INT_TYPE) ? FormulaNumericSites.ValueType.INT
                    : type.equals(Type.FLOAT_TYPE) ? FormulaNumericSites.ValueType.FLOAT : FormulaNumericSites.ValueType.DOUBLE;
            Number zero = switch (valueType) { case INT -> (Number) 0; case FLOAT -> (Number) 0f; case DOUBLE -> (Number) 0d; };
            double maximum = valueType == FormulaNumericSites.ValueType.INT ? Integer.MAX_VALUE
                    : valueType == FormulaNumericSites.ValueType.FLOAT ? Math.sqrt(Float.MAX_VALUE) : Math.sqrt(Double.MAX_VALUE);
            double minimum = movement ? -maximum : 0;
            if (constant != null && (constant.doubleValue() < minimum || constant.doubleValue() > maximum)) continue;
            var definition = new FormulaNumericSites.Definition(spell, alias, constant == null ? zero : constant, valueType, minimum, maximum);
            Body body = consumer.body();
            sites.add(new FormulaAutomaticSites.Site(definition, body.owner(), body.method().name, body.method().desc,
                    snapshot(body.method()).indexOf(consumer.call()),
                    snapshot(body.method()).fingerprint(), -1, -1, -1,
                    consumer.kind(), constant == null ? "native_value" : constant.toString(), consumer.argument(),
                    FormulaAutomaticSites.ContextMode.ENTITY, 0));
        }
        boolean attributeSources = !repository.baselineCoverage() && FormulaConsumerSites.hasAttributeControls(repository, spell)
                || FormulaEffectAttributeControls.hasControls(repository, spell);
        return new Result(sites, sites.isEmpty() && !attributeSources ? List.of() : proposed.stream().distinct().toList(), diagnostics);
    }

    private void effectEntitySources(List<FormulaExecutionGraph.MethodId> roots, List<Capture> proposed,
                                     Map<String, Consumer> consumers) {
        var graph = FormulaExecutionGraph.analyze(repository, spell);
        for (var effect : graph.effects()) {
            if (!effect.ownershipComplete() || !effect.spellClasses().equals(Set.of(graph.spellClass()))) continue;
            List<Capture> sources = effectSources(effect, Set.copyOf(roots));
            if (sources.isEmpty()) continue;
            List<Capture> eventCaptures = new ArrayList<>();
            Map<String, Consumer> selected = new LinkedHashMap<>();
            for (var event : effect.eventMethods().stream().sorted(Comparator.comparing(FormulaExecutionGraph.MethodId::key)).toList()) {
                var method = repository.resolveMethod(event.owner(), event.name(), event.descriptor());
                if (method == null) continue;
                Body body = new Body(method.owner().name, method.method());
                Data data = data(body);
                if (data == null) continue;
                for (var instruction : body.method().instructions) {
                    if (!(instruction instanceof MethodInsnNode publication) || !spawn(publication)
                            || !repository.isSubtype(publication.owner, LEVEL)) continue;
                    Object origin = data.origin(data.argument(publication, 0));
                    if (!(origin instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                            || !repository.isSubtype(allocation.desc, ENTITY)) continue;
                    List<EffectRead> reads = eventEffectReads(data, allocation, publication, effect.registryOwner(), effect.registryField());
                    if (reads.size() != 1) {
                        diagnostics.add("效果事件实体没有唯一且仍存活的效果实例来源，保留原生消费：" + key(body));
                        continue;
                    }
                    var read = reads.getFirst();
                    FormulaInterproceduralFlow.State[] state = new FormulaInterproceduralFlow.State[1];
                    var descendants = descendants(allocation.desc, () -> {
                        if (state[0] == null) state[0] = FormulaInterproceduralFlow.inspectPublishedObject(repository, data.analysis, publication);
                        return state[0];
                    });
                    if (descendants.consumers().isEmpty()) continue;
                    for (var consumer : descendants.consumers()) selected.putIfAbsent(key(consumer), consumer);
                    eventCaptures.addAll(descendants.captures());
                    eventCaptures.add(new Capture(body.owner(), body.method().name, body.method().desc,
                            snapshot(body.method()).indexOf(publication), snapshot(body.method()).fingerprint(),
                            -1, -1, -1, allocation.desc, allocationOrdinal(body.method(), allocation), publicationOrdinal(body.method(), publication),
                            read.slot(), CaptureMode.EFFECT_EVENT, effect.registryOwner(), effect.registryField(), effectReadOrdinal(body.method(), read.call())));
                }
            }
            for (var callback : FormulaEffectCallbackSources.scan(repository, graph, effect)) {
                var resolved = repository.resolveMethod(callback.method().owner(), callback.method().name(), callback.method().descriptor());
                if (resolved == null) continue;
                Body body = new Body(resolved.owner().name, resolved.method());
                Data data = data(body);
                if (data == null) continue;
                for (var instruction : body.method().instructions) {
                    if (!(instruction instanceof MethodInsnNode publication) || !data.analysis.reachable(publication)
                            || !spawn(publication) || !repository.isSubtype(publication.owner, LEVEL)) continue;
                    Object origin = data.origin(data.argument(publication, 0));
                    if (!(origin instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                            || !repository.isSubtype(allocation.desc, ENTITY)) continue;
                    FormulaInterproceduralFlow.State[] state = new FormulaInterproceduralFlow.State[1];
                    var children = descendants(allocation.desc, () -> {
                        if (state[0] == null) state[0] = FormulaInterproceduralFlow.inspectPublishedObject(repository, data.analysis, publication);
                        return state[0];
                    });
                    if (children.consumers().isEmpty()) continue;
                    for (var consumer : children.consumers()) selected.putIfAbsent(key(consumer), consumer);
                    eventCaptures.addAll(children.captures());
                    eventCaptures.add(new Capture(body.owner(), body.method().name, body.method().desc,
                            snapshot(body.method()).indexOf(publication), snapshot(body.method()).fingerprint(),
                            -1, -1, -1, allocation.desc, allocationOrdinal(body.method(), allocation), publicationOrdinal(body.method(), publication),
                            callback.hostSlot(), CaptureMode.EFFECT_CALLBACK, effect.registryOwner(), effect.registryField(), -1,
                            callback.holderDescriptor()));
                }
            }
            boolean attributeSource = !repository.baselineCoverage() && FormulaConsumerSites.hasAttributeControls(repository, spell)
                    || FormulaEffectAttributeControls.controlsEffect(repository, spell, effect.registryOwner(), effect.registryField());
            if (selected.isEmpty() && !attributeSource) continue;
            selected.forEach(consumers::putIfAbsent);
            proposed.addAll(sources);
            proposed.addAll(eventCaptures);
        }
    }

    private List<Capture> effectSources(FormulaExecutionGraph.EffectLink effect, Set<FormulaExecutionGraph.MethodId> roots) {
        List<Capture> sources = new ArrayList<>();
        var helpers = FormulaExecutionGraph.index(repository).synchronousExecutionHelpers(spell).methods();
        for (var application : effect.applications()) {
            var method = repository.resolveMethod(application.owner(), application.name(), application.descriptor());
            boolean direct = method != null && roots.contains(application) && rootContext(method.method());
            boolean scoped = method != null && helpers.contains(application);
            if (!direct && !scoped) {
                diagnostics.add("效果施加缺少可证明的原始施法上下文，保留事件实体原生消费：" + application.key());
                return List.of();
            }
            Body body = new Body(method.owner().name, method.method());
            Data data = data(body);
            if (data == null) return List.of();
            boolean found = false;
            for (var instruction : body.method().instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !effectPublication(call)
                        || !repository.isSubtype(call.owner, LIVING)) continue;
                Object origin = data.origin(data.argument(call, 0));
                if (!(origin instanceof TypeInsnNode allocation) || !allocation.desc.equals(INSTANCE)) continue;
                var holder = allocatedEffectHolder(data, allocation);
                if (!holderMatches(holder, effect.registryOwner(), effect.registryField())) continue;
                if (!simpleEffectPublication(call)) {
                    diagnostics.add("效果施加包含尚未接入的发布参数，保留事件实体原生消费：" + application.key());
                    return List.of();
                }
                found = true;
                sources.add(new Capture(body.owner(), body.method().name, body.method().desc,
                        snapshot(body.method()).indexOf(call), snapshot(body.method()).fingerprint(),
                        direct ? 3 : -1, direct ? 2 : -1, direct ? 0 : -1, INSTANCE,
                        allocationOrdinal(body.method(), allocation), effectPublicationOrdinal(body.method(), call),
                        -1, direct ? CaptureMode.EFFECT_SOURCE : CaptureMode.EFFECT_SCOPED,
                        effect.registryOwner(), effect.registryField(), -1));
            }
            if (!found) return List.of();
        }
        return List.copyOf(sources);
    }

    static List<FormulaCastContext.Root> effectScopes(FormulaClassRepository repository, String spell, List<Capture> captures) {
        if (captures.stream().noneMatch(capture -> capture.mode() == CaptureMode.EFFECT_SCOPED)) return List.of();
        List<FormulaCastContext.Root> result = new ArrayList<>();
        for (var root : FormulaExecutionGraph.index(repository).synchronousExecutionHelpers(spell).roots().stream()
                .sorted(Comparator.comparing(FormulaExecutionGraph.MethodId::key)).toList()) {
            var method = repository.resolveMethod(root.owner(), root.name(), root.descriptor());
            if (method == null || !rootContext(method.method())) return List.of();
            result.add(new FormulaCastContext.Root(method.owner().name, method.method().name, method.method().desc,
                    FormulaAutomaticInjector.fingerprint(method.method()), 3, 2, 0));
        }
        return List.copyOf(result);
    }

    private static List<EffectRead> eventEffectReads(Data data, TypeInsnNode allocation, MethodInsnNode publication,
                                                    String registryOwner, String registryField) {
        List<EffectRead> result = new ArrayList<>();
        var frame = data.analysis.frameBefore(publication);
        if (frame == null) return List.of();
        Set<MethodInsnNode> found = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int slot = 0; slot < frame.locals().size(); slot++) {
            var origin = data.analysis.origin(frame.locals().get(slot));
            if (origin == null || !(origin.instruction() instanceof MethodInsnNode get) || !effectRead(get)
                    || !data.repository.isSubtype(get.owner, LIVING) || !data.analysis.resultDominates(get, publication)
                    || !holderMatches(data.origin(data.argument(get, 0)), registryOwner, registryField)
                    || !entityDependsOnEffect(data, allocation, publication, get) || !found.add(get)) continue;
            result.add(new EffectRead(get, slot));
        }
        return result;
    }

    private static boolean entityDependsOnEffect(Data data, TypeInsnNode allocation, MethodInsnNode publication, MethodInsnNode effect) {
        var state = FormulaInterproceduralFlow.inspectPublishedObject(data.repository, data.analysis, publication);
        if (state.object() == null || state.object().kind() != FormulaInterproceduralFlow.IdentityKind.ALLOCATION
                || !state.object().method().equals(data.analysis.method())
                || state.object().instruction() != data.analysis.instruction(allocation).index()) return false;
        for (var write : state.writes()) {
            if (!write.receiverProven() || !writeBeforePublication(data.analysis, publication, write.path())) continue;
            if (FormulaInterproceduralFlow.dependsOnCall(write.value(), amplifier -> amplifier.owner.equals(INSTANCE)
                    && amplifier.name.equals("getAmplifier") && amplifier.desc.equals("()I")
                    && data.analysis.instruction(amplifier) != null && data.origin(data.receiver(amplifier)) == effect
                    && data.analysis.resultDominates(amplifier, publication))) return true;
        }
        return false;
    }

    private static boolean writeBeforePublication(FormulaDataFlow.Analysis root, MethodInsnNode publication,
                                                   List<FormulaInterproceduralFlow.Location> path) {
        if (path.isEmpty() || path.getFirst().analysis() != root
                || !root.resultDominates(path.getFirst().instruction(), publication)) return false;
        for (int index = 1; index < path.size(); index++) {
            var location = path.get(index);
            boolean returns = false;
            for (var operation : location.analysis().instructions()) {
                int opcode = operation.instruction().getOpcode();
                if (opcode < Opcodes.IRETURN || opcode > Opcodes.RETURN || !location.analysis().reachable(operation.instruction())) continue;
                returns = true;
                if (!location.analysis().resultDominates(location.instruction(), operation.instruction())) return false;
            }
            if (!returns) return false;
        }
        return true;
    }

    private static Object allocatedEffectHolder(Data data, TypeInsnNode allocation) {
        Object holder = null;
        for (var instruction : data.body.method().instructions)
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals(INSTANCE) && call.name.equals("<init>") && CONSTRUCTORS.contains(call.desc)
                    && data.origin(data.receiver(call)) == allocation) {
                if (holder != null) return null;
                holder = data.origin(data.argument(call, 0));
            }
        return holder;
    }

    private static boolean holderMatches(Object holder, String owner, String field) {
        return holder instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETSTATIC
                && read.owner.equals(owner) && read.name.equals(field);
    }

    private static boolean effectRead(MethodInsnNode call) {
        return call.getOpcode() != Opcodes.INVOKESTATIC && call.name.equals("getEffect")
                && call.desc.equals("(" + HOLDER + ")L" + INSTANCE + ';');
    }

    private static int effectReadOrdinal(MethodNode method, MethodInsnNode target) {
        int ordinal = 0;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && effectRead(call)) {
            if (call == target) return ordinal;
            ordinal++;
        }
        return -1;
    }

    private static boolean effectPublication(MethodInsnNode call) {
        var arguments = Type.getArgumentTypes(call.desc);
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL && Set.of("addEffect", "forceAddEffect").contains(call.name)
                && arguments.length > 0 && arguments[0].equals(Type.getObjectType(INSTANCE));
    }

    private static boolean simpleEffectPublication(MethodInsnNode call) {
        return effectPublication(call) && call.desc.equals("(L" + INSTANCE + ";)Z");
    }

    private static int effectPublicationOrdinal(MethodNode method, MethodInsnNode target) {
        int ordinal = 0;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && effectPublication(call)) {
            if (call == target) return ordinal;
            ordinal++;
        }
        return -1;
    }

    private Descendants descendants(String entity, java.util.function.Supplier<FormulaInterproceduralFlow.State> provenance) {
        Map<String, EntityNode> nodes = new LinkedHashMap<>();
        Set<String> sources = new HashSet<>();
        ArrayDeque<EntityWork> pending = new ArrayDeque<>();
        pending.add(new EntityWork(entity, "root", provenance));
        while (!pending.isEmpty()) {
            var work = pending.removeFirst();
            if (!sources.add(work.type() + ':' + work.source())) continue;
            if (sources.size() > 4096 || !nodes.containsKey(work.type()) && nodes.size() >= 128) {
                diagnostics.add("实体后代发布图超过安全范围，保留未证明的后代消费：" + entity);
                break;
            }
            var node = consumers(work.type(), work.provenance());
            EntityNode previous = nodes.get(work.type());
            if (previous == null) nodes.put(work.type(), node);
            else {
                List<Consumer> combined = new ArrayList<>(previous.consumers());
                combined.addAll(node.consumers());
                List<Publication> publications = new ArrayList<>(previous.publications());
                publications.addAll(node.publications());
                nodes.put(work.type(), new EntityNode(work.type(), combined.stream().distinct().toList(), publications.stream().distinct().toList()));
            }
            for (var publication : node.publications()) {
                FormulaInterproceduralFlow.State[] state = new FormulaInterproceduralFlow.State[1];
                String point = key(publication.body()) + ':' + snapshot(publication.body().method()).indexOf(publication.call());
                pending.add(new EntityWork(publication.allocation().desc, point, () -> {
                    if (state[0] == null) state[0] = FormulaInterproceduralFlow.inspectPublishedObject(repository,
                            data(publication.body()).analysis, publication.call());
                    return state[0];
                }));
            }
        }
        Set<String> productive = new LinkedHashSet<>();
        for (var node : nodes.values()) if (!node.consumers().isEmpty()) productive.add(node.type());
        boolean changed;
        do {
            changed = false;
            for (var node : nodes.values())
                if (node.publications().stream().anyMatch(publication -> productive.contains(publication.allocation().desc)))
                    changed |= productive.add(node.type());
        } while (changed);
        if (!productive.contains(entity)) return new Descendants(List.of(), List.of());
        List<Consumer> consumers = new ArrayList<>();
        List<Capture> captures = new ArrayList<>();
        for (var node : nodes.values()) {
            if (!productive.contains(node.type())) continue;
            consumers.addAll(node.consumers());
            for (var publication : node.publications()) {
                if (!productive.contains(publication.allocation().desc)) continue;
                Body body = publication.body();
                captures.add(new Capture(body.owner(), body.method().name, body.method().desc,
                        snapshot(body.method()).indexOf(publication.call()),
                        snapshot(body.method()).fingerprint(), -1, -1, -1, publication.allocation().desc,
                        allocationOrdinal(body.method(), publication.allocation()), publicationOrdinal(body.method(), publication.call()), 0));
            }
        }
        return new Descendants(consumers, captures);
    }

    private EntityNode consumers(String entity, java.util.function.Supplier<FormulaInterproceduralFlow.State> provenance) {
        ArrayDeque<Body> pending = new ArrayDeque<>();
        Set<String> signatures = new HashSet<>();
        Set<String> hierarchy = new LinkedHashSet<>();
        for (String owner = entity; owner != null && hierarchy.add(owner) && !owner.equals(ENTITY); ) {
            if (owner.startsWith("net/minecraft/")) {
                hierarchy.remove(owner);
                break;
            }
            ClassNode node = repository.classNode(owner);
            if (node == null) break;
            for (MethodNode method : node.methods) {
                String signature = method.name + method.desc;
                if (CALLBACKS.contains(signature) && signatures.add(signature)
                        && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
                    pending.add(new Body(owner, method));
            }
            owner = node.superName;
        }
        List<Consumer> result = new ArrayList<>();
        List<Publication> publications = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            Body body = pending.removeFirst();
            if (!visited.add(key(body))) continue;
            if (visited.size() > 128) {
                diagnostics.add("实体实例回调调用图超过安全范围，保留未证明的消费：" + entity);
                break;
            }
            if (!FormulaAutomaticInjector.contextIntact(body.method(), 0)) {
                diagnostics.add("实体回调改写了来源接收对象，保留原生消费：" + key(body));
                continue;
            }
            Data data = data(body);
            if (data == null) continue;
            for (var callback : FormulaExecutionGraph.index(repository).callbacks(
                    new FormulaExecutionGraph.MethodId(body.owner(), body.method().name, body.method().desc))) {
                var target = callback.implementation();
                var resolved = repository.resolveMethod(target.owner(), target.name(), target.descriptor());
                if (resolved == null || !hierarchy.contains(resolved.owner().name)) continue;
                boolean instanceSource = (resolved.method().access & Opcodes.ACC_STATIC) == 0
                        && data.analysis.parameter(callback.implementationReceiver(), 0);
                boolean staticSource = (resolved.method().access & Opcodes.ACC_STATIC) != 0
                        && !callback.capturedArguments().isEmpty() && data.analysis.parameter(callback.capturedArguments().getFirst(), 0);
                if (instanceSource || staticSource) pending.add(new Body(resolved.owner().name, resolved.method()));
                else diagnostics.add("实体同步回调没有保持原来源对象槽，保留原生消费：" + target.key());
            }
            for (var instruction : body.method().instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !data.analysis.reachable(call)) continue;
                if (spawn(call) && repository.isSubtype(call.owner, LEVEL)) {
                    Object origin = data.origin(data.argument(call, 0));
                    if (origin instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                            && repository.isSubtype(allocation.desc, ENTITY)) publications.add(new Publication(body, call, allocation));
                    else diagnostics.add("实体后代发布参数不是唯一的新实体，保留原生来源：" + key(body));
                }
                Object receiver = data.origin(data.receiver(call));
                FormulaClassRepository.ResolvedMethod implementation;
                if (receiver instanceof Parameter parameter && parameter.slot() == 0)
                    implementation = resolveHelper(body.owner(), call, entity);
                else if (receiver instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW)
                    implementation = resolveHelper(body.owner(), call, allocation.desc);
                else implementation = repository.resolveMethod(call.owner, call.name, call.desc);
                var consumptions = new ArrayList<>(FormulaConsumptionModel.classify(repository, call, implementation));
                consumptions.addAll(FormulaGeometryConsumption.classify(repository, data.analysis, call, candidate -> {
                    if (candidate == call) return implementation;
                    Object candidateReceiver = data.origin(data.receiver(candidate));
                    return candidateReceiver instanceof Parameter parameter && parameter.slot() == 0 ? resolveHelper(body.owner(), candidate, entity)
                            : candidateReceiver instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                            ? resolveHelper(body.owner(), candidate, allocation.desc) : repository.resolveMethod(candidate.owner, candidate.name, candidate.desc);
                }));
                for (var consumption : consumptions) {
                    if (consumption.proof() == FormulaConsumptionModel.Proof.EFFECT_EVENT) continue;
                    if (forwardedIncomingHurt(body, data, call, consumption)) continue;
                    if (consumption.proof() == FormulaConsumptionModel.Proof.EFFECT_PUBLICATION && !publishedEffect(data, call)) continue;
                    int argument = consumption.argument();
                    boolean finalDamage = consumption.kind() == FormulaAutomaticSites.Kind.DAMAGE
                            && consumption.proof() == FormulaConsumptionModel.Proof.TERMINAL;
                    if (!finalDamage && manuallyCovered(body, call, argument)) continue;
                    SourceValue value = data.argument(call, argument);
                    if (!finalDamage && protectedInput(data, value, provenance, consumption.kind())) {
                        diagnostics.add("实体消费实参依赖已有公式入口，保留原生值：" + key(body));
                        continue;
                    }
                    result.add(new Consumer(body, call, argument, consumption.kind(), data.constant(value)));
                }
                if (call.getOpcode() == Opcodes.INVOKESTATIC || call.name.startsWith("<")
                        || !(receiver instanceof Parameter parameter) || parameter.slot() != 0) continue;
                if (implementation != null && hierarchy.contains(implementation.owner().name)
                        && !implementation.owner().name.equals(ENTITY)
                        && (implementation.method().access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
                    pending.add(new Body(implementation.owner().name, implementation.method()));
            }
        }
        return new EntityNode(entity, result, publications);
    }

    private static boolean forwardedIncomingHurt(Body body, Data data, MethodInsnNode call,
                                                 FormulaConsumptionModel.Consumption consumption) {
        String descriptor = "(Lnet/minecraft/world/damagesource/DamageSource;F)Z";
        return consumption.kind() == FormulaAutomaticSites.Kind.DAMAGE && Set.of("hurt", "m_6469_").contains(body.method().name)
                && body.method().desc.equals(descriptor) && call.getOpcode() == Opcodes.INVOKESPECIAL
                && Set.of("hurt", "m_6469_").contains(call.name) && call.desc.equals(descriptor)
                && data.analysis.parameter(data.receiver(call), 0) && data.analysis.parameter(data.argument(call, 0), 1)
                && data.analysis.parameter(data.argument(call, 1), 2);
    }

    private FormulaClassRepository.ResolvedMethod resolveHelper(String caller, MethodInsnNode call, String entity) {
        return FormulaInterproceduralFlow.resolveCall(repository, caller, call, entity);
    }

    private boolean protectedInput(Data data, SourceValue value,
                                   java.util.function.Supplier<FormulaInterproceduralFlow.State> provenance,
                                   FormulaAutomaticSites.Kind kind) {
        if (data.protectedNumericValue(value, new HashSet<>())) return true;
        var direct = FormulaInterproceduralFlow.forwarded(FormulaInterproceduralFlow.value(FormulaInterproceduralFlow.root(data.analysis), value));
        var initial = direct == null ? null : direct.context().analysis().origin(direct.value());
        if (initial == null || !(initial.instruction() instanceof FieldInsnNode)
                && !(initial.instruction() instanceof MethodInsnNode call && Type.getArgumentTypes(call.desc).length == 0)) return false;
        var state = provenance.get();
        if (state.object() == null) return false;
        var context = state.callback(data.analysis);
        var read = FormulaInterproceduralFlow.forwardedField(repository, FormulaInterproceduralFlow.value(context, value));
        if (read == null || !state.object().equals(read.receiver())) return false;
        var resolution = state.resolveRead(read.context(), read.instruction());
        var source = FormulaInterproceduralFlow.forwardedCall(resolution.source());
        if (source != null && (numericHook(source.call()) || existingSourceInput(source, state))) return true;
        if (resolution.status() != FormulaInterproceduralFlow.Status.SINGLE_WRITE) return false;
        var write = resolution.writes().getFirst();
        var original = FormulaInterproceduralFlow.forwarded(write.value());
        if (original == null) return false;
        var origin = original.context().analysis().origin(original.value());
        for (var covered : coveredSites) {
            if (covered.kind() != kind || !covered.definition().spellClassName().equals(spell)) continue;
            if (covered.argumentIndex() < 0) {
                if (origin != null && siteInstruction(covered, original.context().analysis()) == origin.instruction()) return true;
                continue;
            }
            var cursor = state.rootContext();
            for (var location : write.path()) {
                if (cursor == null || cursor.analysis() != location.analysis()
                        || !(location.instruction() instanceof MethodInsnNode call)) break;
                if (siteInstruction(covered, cursor.analysis()) == call) {
                    var arguments = cursor.analysis().arguments(call);
                    if (covered.argumentIndex() < arguments.size()
                            && state.object().equals(FormulaInterproceduralFlow.object(repository,
                            FormulaInterproceduralFlow.value(cursor, cursor.analysis().receiver(call))).exact())
                            && sameValue(original, FormulaInterproceduralFlow.value(cursor, arguments.get(covered.argumentIndex())))) return true;
                }
                cursor = FormulaInterproceduralFlow.invocation(repository, cursor, call);
            }
        }
        return false;
    }

    private static boolean sameValue(FormulaInterproceduralFlow.ValueRef first, FormulaInterproceduralFlow.ValueRef second) {
        first = FormulaInterproceduralFlow.forwarded(first);
        second = FormulaInterproceduralFlow.forwarded(second);
        if (first == null || second == null || first.context().analysis() != second.context().analysis()) return false;
        var origin = first.context().analysis().origin(first.value());
        return origin != null && origin.equals(second.context().analysis().origin(second.value()));
    }

    private AbstractInsnNode siteInstruction(FormulaAutomaticSites.Site site, FormulaDataFlow.Analysis analysis) {
        var method = analysis.method();
        if (!site.owner().replace('.', '/').equals(method.owner()) || !site.methodName().equals(method.name())
                || !site.methodDescriptor().equals(method.descriptor())) return null;
        var declared = repository.resolveMethod(method.owner(), method.name(), method.descriptor());
        if (declared == null || !site.methodFingerprint().equals(snapshot(declared.method()).fingerprint())) return null;
        List<AbstractInsnNode> executable = analysis.instructions().stream().map(FormulaDataFlow.Instruction::instruction)
                .filter(instruction -> instruction.getOpcode() >= 0).toList();
        if (site.producerIndex() < 0 || site.producerIndex() >= executable.size()) return null;
        return executable.get(site.producerIndex());
    }

    private boolean existingSourceInput(FormulaInterproceduralFlow.CallRef source, FormulaInterproceduralFlow.State state) {
        MethodInsnNode call = source.call();
        Type result = Type.getReturnType(call.desc);
        if (!Set.of(Type.INT_TYPE, Type.FLOAT_TYPE, Type.DOUBLE_TYPE).contains(result)
                || !repository.isSubtype(spell.replace('.', '/'), call.owner)) return false;
        var receiver = FormulaInterproceduralFlow.forwarded(FormulaInterproceduralFlow.value(source.context(), source.context().analysis().receiver(call)));
        if (receiver == null || receiver.context().analysis() != state.rootContext().analysis()) return false;
        var origin = receiver.context().analysis().origin(receiver.value());
        if (origin == null || origin.kind() != FormulaDataFlow.NodeKind.PARAMETER || origin.parameterSlot() != 0) return false;
        var resolved = FormulaInterproceduralFlow.resolveCall(repository, source.context().analysis().method().owner(), call, spell);
        if (resolved == null || (resolved.method().access & Opcodes.ACC_STATIC) != 0) return false;
        try {
            var method = new FormulaDataFlow.MethodId(resolved.owner().name, resolved.method().name, resolved.method().desc);
            return extractableSources.computeIfAbsent(method, ignored ->
                    AutomaticFormulaExtractor.analyze(resolved.owner(), resolved.method(), repository, spell).supported());
        } catch (RuntimeException | LinkageError failure) {
            diagnostics.add("无法确认字段来源是否已有公式入口，保留实际消费：" + resolved.owner().name + '#' + resolved.method().name);
            return false;
        }
    }

    private boolean manuallyCovered(Body body, MethodInsnNode constructor, int argument) {
        if (!repository.baselineCoverage()) return false;
        String point = key(body) + ':' + snapshot(body.method()).indexOf(constructor) + ':' + argument;
        return manual.computeIfAbsent(point, ignored -> {
            String owner = body.owner().replace('/', '.');
            List<java.util.function.BiConsumer<String, ClassNode>> mutators = new ArrayList<>();
            if (FormulaNumericSites.targetClasses().contains(owner)) mutators.add(FormulaNumericSites::apply);
            if (FormulaEntitySites.targetClasses().contains(owner)) mutators.add(FormulaEntitySites::apply);
            if (FormulaConsumerSites.targetClasses().contains(owner)) mutators.add(FormulaConsumerSites::apply);
            if (FormulaSites.targetClasses().contains(owner)) mutators.add(FormulaSites::apply);
            if (FormulaPublishedCorrections.targetClasses().contains(owner)) mutators.add(FormulaPublishedCorrections::apply);
            for (var mutator : mutators) {
                var copy = new ClassNode();
                repository.classNode(body.owner()).accept(copy);
                try {
                    mutator.accept(owner, copy);
                    MethodNode changed = copy.methods.stream().filter(method -> method.name.equals(body.method().name)
                            && method.desc.equals(body.method().desc)).findFirst().orElseThrow();
                    List<MethodInsnNode> originalCalls = matchingCalls(body.method(), constructor);
                    List<MethodInsnNode> changedCalls = matchingCalls(changed, constructor);
                    int ordinal = originalCalls.indexOf(constructor);
                    if (ordinal < 0 || originalCalls.size() != changedCalls.size())
                        throw new IllegalArgumentException("人工变换改变了效果消费调用集合");
                    Data transformed = new Data(new Body(copy.name, changed), repository);
                    if (transformed.protectedNumericValue(transformed.argument(changedCalls.get(ordinal), argument), new HashSet<>())) {
                        diagnostics.add("实体效果实参已有人工数值消费，保留既有入口：" + point);
                        return true;
                    }
                } catch (AnalyzerException | RuntimeException | LinkageError failure) {
                    diagnostics.add("无法核对实体回调人工覆盖，保留原生效果参数：" + key(body) + ": " + failure.getMessage());
                    return true;
                }
            }
            return false;
        });
    }

    private static List<MethodInsnNode> matchingCalls(MethodNode method, MethodInsnNode target) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(target.owner)
                    && call.name.equals(target.name) && call.desc.equals(target.desc)) result.add(call);
        return result;
    }

    private boolean publishedEffect(Data data, MethodInsnNode constructor) {
        Object allocation = data.origin(data.receiver(constructor));
        if (!(allocation instanceof TypeInsnNode creation) || creation.getOpcode() != Opcodes.NEW
                || !creation.desc.equals(INSTANCE)) return false;
        for (var instruction : data.body.method().instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals("addEffect")
                    && (call.desc.equals("(L" + INSTANCE + ";)Z") || call.desc.equals("(L" + INSTANCE + ";L" + ENTITY + ";)Z"))
                    && repository.isSubtype(call.owner, LIVING) && data.origin(data.argument(call, 0)) == allocation) return true;
        return false;
    }

    private Data data(Body body) {
        String key = key(body);
        if (broken.contains(key)) return null;
        Data known = bodies.get(key);
        if (known != null) return known;
        try {
            Data created = new Data(body, repository);
            bodies.put(key, created);
            return created;
        } catch (AnalyzerException | RuntimeException failure) {
            broken.add(key);
            diagnostics.add("实体来源或消费参数无法分析，保留原生行为：" + key + ": " + failure.getMessage());
            return null;
        }
    }

    private static boolean rootContext(MethodNode method) {
        Type[] types = Type.getArgumentTypes(method.desc);
        return (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                && types.length >= 3 && types[0].getDescriptor().equals("L" + LEVEL + ';')
                && types[1].equals(Type.INT_TYPE) && types[2].getDescriptor().equals("L" + LIVING + ';')
                && FormulaAutomaticInjector.contextIntact(method, 0, 2, 3);
    }

    private static boolean spawn(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL && Set.of("addFreshEntity", "m_7967_").contains(call.name)
                && call.desc.equals("(L" + ENTITY + ";)Z");
    }

    private static boolean numericHook(MethodInsnNode call) {
        Type[] types = Type.getArgumentTypes(call.desc);
        return NUMERIC_HOOKS.contains(call.owner) && types.length > 0
                && Set.of(Type.INT_TYPE, Type.FLOAT_TYPE, Type.DOUBLE_TYPE).contains(types[0])
                && Set.of(Type.INT_TYPE, Type.FLOAT_TYPE, Type.DOUBLE_TYPE).contains(Type.getReturnType(call.desc));
    }

    public static synchronized void register(String spell, List<Capture> captures) {
        String owner = spell.replace('/', '.');
        List<Capture> selected = captures.stream().distinct().sorted(Comparator.comparing(Capture::owner)
                .thenComparing(Capture::methodName).thenComparing(Capture::methodDescriptor)
                .thenComparingInt(Capture::producerIndex).thenComparing(Capture::methodFingerprint)
                .thenComparingInt(Capture::spellSlot).thenComparingInt(Capture::levelSlot)
                .thenComparingInt(Capture::casterSlot).thenComparing(Capture::entityClassName)
                .thenComparingInt(Capture::allocationOrdinal).thenComparingInt(Capture::publicationOrdinal)
                .thenComparingInt(Capture::parentSlot).thenComparing(Capture::mode)
                .thenComparing(Capture::registryOwner).thenComparing(Capture::registryField)
                .thenComparingInt(Capture::effectReadOrdinal)).toList();
        List<Capture> previous = CAPTURES.put(owner, selected);
        if (!selected.equals(previous)) VERIFIED.remove(owner);
    }

    public static synchronized boolean verifyCaptureHooks(String owner, ClassNode target) {
        String selected = owner.replace('/', '.');
        boolean matched = target != null && selected.equals(target.name.replace('/', '.'));
        boolean complete = matched;
        for (var source : CAPTURES.entrySet()) {
            List<Capture> captures = source.getValue().stream().filter(capture -> capture.owner().equals(selected)).toList();
            if (captures.isEmpty()) continue;
            Set<Capture> verified = new LinkedHashSet<>(VERIFIED.getOrDefault(source.getKey(), Set.of()));
            verified.removeAll(captures);
            Map<String, List<Capture>> methods = new LinkedHashMap<>();
            for (Capture capture : captures) methods.computeIfAbsent(capture.methodName() + capture.methodDescriptor(),
                    ignored -> new ArrayList<>()).add(capture);
            for (List<Capture> expected : methods.values()) {
                Capture first = expected.getFirst();
                List<MethodNode> candidates = matched ? target.methods.stream().filter(method -> method.name.equals(first.methodName())
                        && method.desc.equals(first.methodDescriptor())).toList() : List.of();
                if (candidates.size() == 1 && verifiedMethod(candidates.getFirst(), expected)) verified.addAll(expected);
                else complete = false;
            }
            VERIFIED.put(source.getKey(), Set.copyOf(verified));
        }
        return complete;
    }

    public static synchronized boolean hasVerifiedSource(String spellClassName) {
        String owner = spellClassName.replace('/', '.');
        List<Capture> captures = CAPTURES.get(owner);
        return captures != null && !captures.isEmpty() && VERIFIED.getOrDefault(owner, Set.of()).containsAll(captures)
                && (captures.stream().noneMatch(capture -> capture.mode() == CaptureMode.EFFECT_SCOPED)
                || FormulaCastContext.hasVerifiedSource(owner));
    }

    public static synchronized boolean hasVerifiedEffectSource(String spellClassName, String registryOwner, String registryField) {
        String owner = spellClassName.replace('/', '.');
        var captures = CAPTURES.getOrDefault(owner, List.of()).stream()
                .filter(capture -> (capture.mode() == CaptureMode.EFFECT_SOURCE || capture.mode() == CaptureMode.EFFECT_SCOPED)
                        && capture.registryOwner().equals(registryOwner.replace('.', '/')) && capture.registryField().equals(registryField)).toList();
        return !captures.isEmpty() && VERIFIED.getOrDefault(owner, Set.of()).containsAll(captures)
                && (captures.stream().noneMatch(capture -> capture.mode() == CaptureMode.EFFECT_SCOPED)
                || FormulaCastContext.hasVerifiedSource(owner));
    }

    private static boolean verifiedMethod(MethodNode method, List<Capture> expected) {
        if (expected.stream().anyMatch(capture -> !captureContext(method, capture))) return false;
        Data data;
        try {
            data = new Data(new Body(expected.getFirst().owner().replace('.', '/'), method), null);
        } catch (AnalyzerException | RuntimeException failure) {
            return false;
        }
        Map<String, Integer> counts = new HashMap<>();
        Map<Integer, String> points = new HashMap<>();
        for (Capture capture : expected) {
            if (!capture.methodFingerprint().equals(expected.getFirst().methodFingerprint())
                    || capture.allocationOrdinal() < 0 || capture.publicationOrdinal() < 0) return false;
            String context = captureKey(capture);
            String previous = points.putIfAbsent(capture.producerIndex(), context);
            if (previous != null && !previous.equals(context)) return false;
            if (previous == null) counts.merge(context, 1, Integer::sum);
        }
        List<AbstractInsnNode> instructions = FormulaAutomaticInjector.executable(method);
        for (int index = 0; index < instructions.size(); index++) {
            if (!(instructions.get(index) instanceof MethodInsnNode hook)
                    || !Set.of(CONTEXT, "com/example/spell_compiler/formula/FormulaEffectCallbackHooks").contains(hook.owner)
                    || !Set.of("capture", "captureScoped", "inherit").contains(hook.name)) continue;
            int spellSlot = -1, levelSlot = -1, casterSlot = -1, parentSlot = -1;
            FieldInsnNode callbackHolder = null;
            String scopedOwner = null, scopedField = null;
            MethodInsnNode publication;
            if (hook.owner.equals("com/example/spell_compiler/formula/FormulaEffectCallbackHooks")) {
                if (hook.getOpcode() != Opcodes.INVOKESTATIC || !hook.name.equals("inherit")
                        || !hook.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V")
                        || index < 3 || instructions.get(index - 3).getOpcode() != Opcodes.DUP
                        || !(instructions.get(index - 2) instanceof VarInsnNode parent) || parent.getOpcode() != Opcodes.ALOAD
                        || !(instructions.get(index - 1) instanceof FieldInsnNode holder) || holder.getOpcode() != Opcodes.GETSTATIC) return false;
                parentSlot = parent.var;
                callbackHolder = holder;
                publication = publicationAfterInheritance(instructions, index, parentSlot);
            } else if (hook.name.equals("captureScoped")) {
                if (!hook.owner.equals(CONTEXT) || hook.getOpcode() != Opcodes.INVOKESTATIC
                        || !hook.desc.equals("(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V") || index < 3
                        || instructions.get(index - 3).getOpcode() != Opcodes.DUP
                        || !(instructions.get(index - 2) instanceof LdcInsnNode owner) || !(owner.cst instanceof String)
                        || !(instructions.get(index - 1) instanceof LdcInsnNode field) || !(field.cst instanceof String)) return false;
                scopedOwner = (String) owner.cst;
                scopedField = (String) field.cst;
                publication = index + 1 < instructions.size() && instructions.get(index + 1) instanceof MethodInsnNode call
                        && simpleEffectPublication(call) ? call : null;
            } else if (hook.name.equals("inherit")) {
                if (hook.getOpcode() != Opcodes.INVOKESTATIC || !hook.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)V")
                        || index < 2 || instructions.get(index - 2).getOpcode() != Opcodes.DUP
                        || !(instructions.get(index - 1) instanceof VarInsnNode parent) || parent.getOpcode() != Opcodes.ALOAD) return false;
                parentSlot = parent.var;
                publication = publicationAfterInheritance(instructions, index, parentSlot);
            } else {
                if (hook.getOpcode() != Opcodes.INVOKESTATIC || !hook.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;)V")
                        || index < 4 || instructions.get(index - 4).getOpcode() != Opcodes.DUP
                        || !(instructions.get(index - 3) instanceof VarInsnNode spell) || spell.getOpcode() != Opcodes.ALOAD
                        || !(instructions.get(index - 2) instanceof VarInsnNode level) || level.getOpcode() != Opcodes.ILOAD
                        || !(instructions.get(index - 1) instanceof VarInsnNode caster) || caster.getOpcode() != Opcodes.ALOAD) return false;
                spellSlot = spell.var;
                levelSlot = level.var;
                casterSlot = caster.var;
                publication = publicationAfterCapture(instructions, index, spellSlot, levelSlot, casterSlot);
            }
            if (publication == null) return false;
            int actualSpell = spellSlot, actualLevel = levelSlot, actualCaster = casterSlot, actualParent = parentSlot;
            FieldInsnNode actualHolder = callbackHolder;
            String actualScopedOwner = scopedOwner, actualScopedField = scopedField;
            var matches = expected.stream().filter(capture -> capture.spellSlot() == actualSpell && capture.levelSlot() == actualLevel
                    && capture.casterSlot() == actualCaster && capture.parentSlot() == actualParent
                    && (capture.mode() == CaptureMode.EFFECT_SCOPED) == hook.name.equals("captureScoped")
                    && (actualScopedOwner == null || capture.registryOwner().equals(actualScopedOwner)
                    && capture.registryField().equals(actualScopedField))
                    && (actualHolder == null ? capture.mode() != CaptureMode.EFFECT_CALLBACK
                    : capture.mode() == CaptureMode.EFFECT_CALLBACK && capture.registryOwner().equals(actualHolder.owner)
                    && capture.registryField().equals(actualHolder.name) && capture.registryDescriptor().equals(actualHolder.desc))
                    && captureMatches(data, publication, capture)).toList();
            if (matches.isEmpty()) return false;
            String context = captureKey(matches.getFirst());
            if (matches.stream().anyMatch(capture -> !captureKey(capture).equals(context))) return false;
            int count = counts.getOrDefault(context, 0);
            if (count <= 0) return false;
            counts.put(context, count - 1);
        }
        return counts.values().stream().allMatch(count -> count == 0);
    }

    private static String captureKey(Capture capture) {
        return capture.spellSlot() + ":" + capture.levelSlot() + ":" + capture.casterSlot() + ':' + capture.parentSlot() + ':'
                + capture.entityClassName() + ':' + capture.allocationOrdinal() + ':' + capture.publicationOrdinal() + ':'
                + capture.mode() + ':' + capture.registryOwner() + ':' + capture.registryField() + ':' + capture.effectReadOrdinal()
                + ':' + capture.registryDescriptor();
    }

    private static boolean captureContext(MethodNode method, Capture capture) {
        if (capture.mode() == CaptureMode.EFFECT_SCOPED)
            return capture.spellSlot() == -1 && capture.levelSlot() == -1 && capture.casterSlot() == -1
                    && capture.parentSlot() == -1 && capture.effectReadOrdinal() == -1
                    && capture.entityClassName().equals(INSTANCE.replace('/', '.'))
                    && !capture.registryOwner().isBlank() && !capture.registryField().isBlank()
                    && (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == Opcodes.ACC_PRIVATE
                    && !method.name.startsWith("<");
        if (capture.mode() == CaptureMode.EFFECT_CALLBACK)
            return FormulaEffectCallbackSources.hostParameter(method, capture.parentSlot())
                    && FormulaAutomaticInjector.contextIntact(method, capture.parentSlot())
                    && capture.spellSlot() == -1 && capture.levelSlot() == -1 && capture.casterSlot() == -1
                    && !capture.registryOwner().isBlank() && !capture.registryField().isBlank()
                    && !capture.registryDescriptor().isBlank() && Type.getType(capture.registryDescriptor()).getSort() == Type.OBJECT
                    && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0 && !method.name.startsWith("<");
        if (capture.mode() == CaptureMode.EFFECT_EVENT)
            return capture.parentSlot() >= 0 && capture.spellSlot() == -1 && capture.levelSlot() == -1 && capture.casterSlot() == -1
                    && capture.effectReadOrdinal() >= 0 && !capture.registryOwner().isBlank() && !capture.registryField().isBlank()
                    && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0 && !method.name.startsWith("<");
        if (capture.mode() == CaptureMode.EFFECT_SOURCE && (capture.parentSlot() != -1 || capture.effectReadOrdinal() != -1
                || !capture.entityClassName().equals(INSTANCE.replace('/', '.'))
                || capture.registryOwner().isBlank() || capture.registryField().isBlank())) return false;
        if (capture.parentSlot() < 0)
            return rootContext(method) && capture.spellSlot() == 0 && capture.levelSlot() == 2 && capture.casterSlot() == 3;
        return capture.parentSlot() == 0 && capture.spellSlot() == -1 && capture.levelSlot() == -1 && capture.casterSlot() == -1
                && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                && !method.name.startsWith("<") && FormulaAutomaticInjector.contextIntact(method, capture.parentSlot());
    }

    private static MethodInsnNode publicationAfterCapture(List<AbstractInsnNode> instructions, int index,
                                                          int spellSlot, int levelSlot, int casterSlot) {
        int next = index + 1;
        while (next + 4 < instructions.size() && instructions.get(next).getOpcode() == Opcodes.DUP
                && instructions.get(next + 1) instanceof VarInsnNode spell && spell.getOpcode() == Opcodes.ALOAD && spell.var == spellSlot
                && instructions.get(next + 2) instanceof VarInsnNode level && level.getOpcode() == Opcodes.ILOAD && level.var == levelSlot
                && instructions.get(next + 3) instanceof VarInsnNode caster && caster.getOpcode() == Opcodes.ALOAD && caster.var == casterSlot
                && instructions.get(next + 4) instanceof MethodInsnNode snapshot && snapshot.getOpcode() == Opcodes.INVOKESTATIC
                && snapshot.owner.equals("com/example/spell_compiler/formula/FormulaEntityHooks") && snapshot.name.equals("capture")
                && snapshot.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;)V")) next += 5;
        return next < instructions.size() && instructions.get(next) instanceof MethodInsnNode publication
                && (spawn(publication) || simpleEffectPublication(publication))
                ? publication : null;
    }

    private static MethodInsnNode publicationAfterInheritance(List<AbstractInsnNode> instructions, int index, int parentSlot) {
        int next = index + 1;
        while (next + 2 < instructions.size() && instructions.get(next).getOpcode() == Opcodes.DUP
                && instructions.get(next + 1) instanceof VarInsnNode parent && parent.getOpcode() == Opcodes.ALOAD && parent.var == parentSlot
                && instructions.get(next + 2) instanceof MethodInsnNode snapshot && snapshot.getOpcode() == Opcodes.INVOKESTATIC
                && snapshot.owner.equals("com/example/spell_compiler/formula/FormulaEntityHooks") && snapshot.name.equals("inherit")
                && snapshot.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)V")) next += 3;
        return next < instructions.size() && instructions.get(next) instanceof MethodInsnNode publication && spawn(publication)
                ? publication : null;
    }

    public static synchronized boolean hasCaptures(String owner) {
        String selected = owner.replace('/', '.');
        return CAPTURES.values().stream().flatMap(List::stream).anyMatch(capture -> capture.owner().equals(selected));
    }

    public static synchronized Set<String> captureTargets() {
        return CAPTURES.values().stream().flatMap(List::stream).map(Capture::owner)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static synchronized Runnable prepareCaptures(String owner, List<MethodNode> copies) {
        String selected = owner.replace('/', '.');
        Map<String, Capture> captures = new LinkedHashMap<>();
        for (var values : CAPTURES.values()) for (Capture capture : values) {
            if (!capture.owner().equals(selected)) continue;
            String key = capture.methodName() + capture.methodDescriptor() + '#' + capture.producerIndex();
            Capture previous = captures.putIfAbsent(key, capture);
            if (previous != null && !previous.equals(capture))
                throw new IllegalArgumentException("实体来源捕获上下文冲突：" + selected + '#' + key);
        }
        List<Prepared> prepared = new ArrayList<>();
        Map<String, List<MethodNode>> methodsBySignature = new HashMap<>();
        Map<MethodNode, FormulaMethodSnapshot> snapshots = new IdentityHashMap<>();
        Map<MethodNode, Data> analyses = new IdentityHashMap<>();
        for (Capture capture : captures.values()) {
            List<MethodNode> methods = methodsBySignature.computeIfAbsent(capture.methodName() + capture.methodDescriptor(),
                    ignored -> copies.stream().filter(method -> method.name.equals(capture.methodName())
                            && method.desc.equals(capture.methodDescriptor())).toList());
            if (methods.size() != 1) throw mismatch(capture, "目标方法数量不符");
            MethodNode method = methods.getFirst();
            var snapshot = snapshots.computeIfAbsent(method, FormulaMethodSnapshot::new);
            if (!snapshot.fingerprint().equals(capture.methodFingerprint()) || !captureContext(method, capture))
                throw mismatch(capture, "原始施法方法或上下文已经变化");
            List<AbstractInsnNode> instructions = snapshot.instructions();
            if (capture.producerIndex() < 0 || capture.producerIndex() >= instructions.size()
                    || !(instructions.get(capture.producerIndex()) instanceof MethodInsnNode call)
                    || !(capture.mode() == CaptureMode.EFFECT_SOURCE || capture.mode() == CaptureMode.EFFECT_SCOPED
                    ? simpleEffectPublication(call) : spawn(call)))
                throw mismatch(capture, "实体发布消费锚点已经变化");
            try {
                Data data = analyses.get(method);
                if (data == null) {
                    data = new Data(new Body(capture.owner().replace('.', '/'), method), null);
                    analyses.put(method, data);
                }
                if (!captureMatches(data, call, capture))
                    throw mismatch(capture, "新实体分配或发布来源已经变化");
            } catch (AnalyzerException failure) {
                throw mismatch(capture, "新实体分配来源无法分析");
            }
            prepared.add(new Prepared(method, call, capture));
        }
        return () -> {
            for (Prepared entry : prepared) {
                Capture capture = entry.capture();
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.DUP));
                if (capture.mode() == CaptureMode.EFFECT_SCOPED) {
                    code.add(new LdcInsnNode(capture.registryOwner()));
                    code.add(new LdcInsnNode(capture.registryField()));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "captureScoped",
                            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V", false));
                } else if (capture.mode() == CaptureMode.EFFECT_CALLBACK) {
                    code.add(new VarInsnNode(Opcodes.ALOAD, capture.parentSlot()));
                    code.add(new FieldInsnNode(Opcodes.GETSTATIC, capture.registryOwner(), capture.registryField(), capture.registryDescriptor()));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "com/example/spell_compiler/formula/FormulaEffectCallbackHooks", "inherit",
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
                } else if (capture.parentSlot() >= 0) {
                    code.add(new VarInsnNode(Opcodes.ALOAD, capture.parentSlot()));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "inherit", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                } else {
                    code.add(new VarInsnNode(Opcodes.ALOAD, capture.spellSlot()));
                    code.add(new VarInsnNode(Opcodes.ILOAD, capture.levelSlot()));
                    code.add(new VarInsnNode(Opcodes.ALOAD, capture.casterSlot()));
                    code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, "capture",
                            "(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;)V", false));
                }
                entry.method().instructions.insertBefore(entry.call(), code);
                entry.method().maxStack += capture.mode() == CaptureMode.EFFECT_CALLBACK ? 3 : capture.parentSlot() >= 0 ? 2 : 4;
            }
        };
    }

    private static boolean captureMatches(Data data, MethodInsnNode publication, Capture capture) {
        Object origin = data.origin(data.argument(publication, 0));
        if (!(origin instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                || !allocation.desc.replace('/', '.').equals(capture.entityClassName())
                || allocationOrdinal(data.body.method(), allocation) != capture.allocationOrdinal()) return false;
        if (capture.mode() == CaptureMode.EFFECT_SOURCE || capture.mode() == CaptureMode.EFFECT_SCOPED)
            return simpleEffectPublication(publication) && data.repository.isSubtype(publication.owner, LIVING)
                    && effectPublicationOrdinal(data.body.method(), publication) == capture.publicationOrdinal()
                    && holderMatches(allocatedEffectHolder(data, allocation), capture.registryOwner(), capture.registryField());
        if (!spawn(publication) || !data.repository.isSubtype(publication.owner, LEVEL)
                || publicationOrdinal(data.body.method(), publication) != capture.publicationOrdinal()) return false;
        if (capture.mode() == CaptureMode.EFFECT_EVENT)
            return eventEffectReads(data, allocation, publication, capture.registryOwner(), capture.registryField()).stream()
                    .anyMatch(read -> read.slot() == capture.parentSlot()
                            && effectReadOrdinal(data.body.method(), read.call()) == capture.effectReadOrdinal());
        return true;
    }

    private static IllegalArgumentException mismatch(Capture capture, String detail) {
        return new IllegalArgumentException("实体来源捕获锚点不匹配：" + capture.owner() + '#' + capture.methodName()
                + capture.methodDescriptor() + ": " + detail);
    }

    private static String key(Body body) { return body.owner() + '#' + body.method().name + body.method().desc; }

    private FormulaMethodSnapshot snapshot(MethodNode method) {
        return methodSnapshots.computeIfAbsent(method, FormulaMethodSnapshot::new);
    }

    private String key(Consumer consumer) {
        return key(consumer.body()) + '#' + snapshot(consumer.body().method()).indexOf(consumer.call())
                + ':' + consumer.argument();
    }

    private static int allocationOrdinal(MethodNode method, AbstractInsnNode allocation) {
        int ordinal = 0;
        for (var instruction : method.instructions)
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                if (instruction == allocation) return ordinal;
                ordinal++;
            }
        return -1;
    }

    private static int publicationOrdinal(MethodNode method, MethodInsnNode publication) {
        int ordinal = 0;
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && spawn(call)) {
                if (call == publication) return ordinal;
                ordinal++;
            }
        return -1;
    }

    private static final class Data {
        private final Body body;
        private final FormulaClassRepository repository;
        private final FormulaDataFlow.Analysis analysis;

        private Data(Body body, FormulaClassRepository repository) throws AnalyzerException {
            this.body = body;
            FormulaClassRepository selected = repository;
            if (selected == null) {
                ClassNode owner = new ClassNode();
                owner.version = Opcodes.V21;
                owner.name = body.owner();
                owner.superName = "java/lang/Object";
                owner.methods.add(body.method());
                selected = FormulaClassRepository.of(owner);
            }
            this.repository = selected;
            analysis = FormulaDataFlow.analyze(selected, body.owner(), body.method());
            if (!analysis.successful()) throw new AnalyzerException(null, analysis.diagnostics().toString());
        }

        private SourceValue argument(MethodInsnNode call, int argument) {
            var arguments = analysis.arguments(call);
            return argument < 0 || argument >= arguments.size() ? null : arguments.get(argument);
        }

        private SourceValue receiver(MethodInsnNode call) {
            return analysis.receiver(call);
        }

        private Object origin(SourceValue value) {
            var origin = analysis.origin(value);
            Object direct = origin == null ? null : origin.kind() == FormulaDataFlow.NodeKind.PARAMETER
                    ? new Parameter(origin.parameterSlot()) : origin.instruction();
            if (direct instanceof Parameter || direct instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    || !(value instanceof FormulaDataFlow.Value typed) || typed.type() == null || typed.type().getSort() < Type.ARRAY)
                return direct;
            var identity = FormulaInterproceduralFlow.object(repository,
                    FormulaInterproceduralFlow.value(FormulaInterproceduralFlow.root(analysis), value)).exact();
            if (identity != null && identity.kind() == FormulaInterproceduralFlow.IdentityKind.ALLOCATION
                    && identity.method().equals(analysis.method()) && identity.context().isEmpty()
                    && identity.instruction() >= 0 && identity.instruction() < analysis.instructions().size()) {
                var instruction = analysis.instructions().get(identity.instruction()).instruction();
                if (instruction instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW) return allocation;
            }
            return direct;
        }

        private Number constant(SourceValue value) {
            Object selected = origin(value);
            if (!(selected instanceof AbstractInsnNode instruction)) return null;
            if (instruction instanceof LdcInsnNode constant && (constant.cst instanceof Integer
                    || constant.cst instanceof Float || constant.cst instanceof Double)) return (Number) constant.cst;
            if (instruction instanceof IntInsnNode constant && (constant.getOpcode() == Opcodes.BIPUSH || constant.getOpcode() == Opcodes.SIPUSH))
                return constant.operand;
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return (float) (opcode - Opcodes.FCONST_0);
            if (opcode >= Opcodes.DCONST_0 && opcode <= Opcodes.DCONST_1) return (double) (opcode - Opcodes.DCONST_0);
            return opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5 ? opcode - Opcodes.ICONST_0 : null;
        }

        private boolean protectedNumericValue(SourceValue value, Set<SourceValue> seen) {
            if (value == null || !seen.add(value)) return false;
            for (AbstractInsnNode instruction : value.insns) {
                if (instruction instanceof MethodInsnNode call && numericHook(call)) return true;
                for (SourceValue input : analysis.operands(instruction)) if (protectedNumericValue(input, seen)) return true;
            }
            return false;
        }
    }
}
