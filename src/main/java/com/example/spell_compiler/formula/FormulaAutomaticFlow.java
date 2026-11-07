package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FormulaAutomaticFlow {
    private static final String ABSTRACT = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    private static final String CAST_SOURCE = "io/redspace/ironsspellbooks/api/spells/CastSource";
    private static final String SUMMON = "io/redspace/ironsspellbooks/capabilities/magic/SummonManager";
    private static final String RECAST = "io/redspace/ironsspellbooks/capabilities/magic/RecastInstance";
    private static final String RECASTS = "io/redspace/ironsspellbooks/capabilities/magic/PlayerRecasts";
    private static final String AOE = "io/redspace/ironsspellbooks/entity/spells/AoeEntity";
    private static final String PROJECTILE = "io/redspace/ironsspellbooks/entity/spells/AbstractMagicProjectile";
    private static final String VECTOR = "net/minecraft/world/phys/Vec3";
    private static final String BOX = "net/minecraft/world/phys/AABB";
    private static final String FANG = "net/minecraft/world/entity/projectile/EvokerFangs";
    private static final String RECAST_CONSTRUCTOR = "(Ljava/lang/String;IIIL" + CAST_SOURCE
            + ";Lio/redspace/ironsspellbooks/api/spells/ICastDataSerializable;)V";

    private record MethodId(String owner, String name, String descriptor) {
        String key() { return owner + '#' + name + descriptor; }
    }

    private record Context(int caster, int level, int spell, Set<Integer> protectedValues, boolean scoped) {
        Context {
            protectedValues = Set.copyOf(protectedValues);
        }
        Context(int caster, int level, int spell, Set<Integer> protectedValues) {
            this(caster, level, spell, protectedValues, false);
        }
        boolean complete() { return scoped || caster >= 0 && level >= 0 && spell >= 0; }
    }
    private record Work(FormulaClassRepository.ResolvedMethod resolved, Context context) {
        MethodId id() { return new MethodId(resolved.owner().name, resolved.method().name, resolved.method().desc); }
    }
    private record Origin(AbstractInsnNode producer, int parameter) {
        static Origin unknown() { return new Origin(null, -1); }
    }
    private record FieldId(String owner, String name, String descriptor) {}
    private record Sink(FormulaAutomaticSites.Kind kind, int argument) {}
    private record Use(MethodInsnNode call, int argument, FormulaAutomaticSites.Kind kind) {}
    private record Candidate(Work work, Data data, Origin origin, Type type, List<Use> uses) {}
    private record ArgumentCandidate(Work work, Data data, Use use) {}
    private record ConsumerId(MethodId method, int instruction, int argument) {}
    private record ReadWork(FormulaClassRepository.ResolvedMethod resolved, int self) {}
    private record ProducerId(String method, String descriptor, int instruction) {}
    private record ManualCoverage(Set<ProducerId> producers, boolean uncertain) {}

    private final FormulaClassRepository repository;
    private final String spell;
    private final Set<String> reserved;
    private final Set<String> family = new LinkedHashSet<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final Map<MethodId, Data> data = new HashMap<>();
    private final Set<MethodId> broken = new HashSet<>();
    private final Map<String, List<ReadWork>> entityCalls = new HashMap<>();
    private final Map<String, ManualCoverage> manualCoverage = new HashMap<>();
    private final Map<MethodNode, FormulaMethodSnapshot> methodSnapshots = new IdentityHashMap<>();
    private final Map<MethodId, Boolean> extractableGetters = new HashMap<>();

    private FormulaAutomaticFlow(FormulaClassRepository repository, String spell, Set<String> reserved) {
        this.repository = repository;
        this.spell = spell.replace('.', '/');
        this.reserved = Set.copyOf(reserved);
    }

    static FormulaAutomaticSites.Scan scan(FormulaClassRepository repository, String spell, Set<String> reserved) {
        return new FormulaAutomaticFlow(repository, spell, reserved).scan();
    }

    private FormulaAutomaticSites.Scan scan() {
        ClassNode owner = repository.classNode(spell);
        if (owner == null || !repository.isSubtype(spell, ABSTRACT)) return result(List.of(), "不是可确认的法术继承类");
        String current = spell;
        while (current != null && family.add(current)) {
            ClassNode source = repository.classNode(current);
            if (source == null || current.equals(ABSTRACT)) break;
            current = source.superName;
        }
        var synchronous = FormulaExecutionGraph.index(repository).synchronousExecutionHelpers(spell);
        var synchronousCallbacks = FormulaExecutionGraph.index(repository).synchronousCallbacks(spell);
        Map<MethodId, List<FormulaExecutionGraph.SynchronousCallback>> callbacks = new HashMap<>();
        for (var callback : synchronousCallbacks)
            callbacks.computeIfAbsent(methodId(callback.caller()), ignored -> new ArrayList<>()).add(callback);
        diagnostics.addAll(synchronous.diagnostics());
        Set<MethodId> helpers = new HashSet<>();
        for (var method : synchronous.methods()) helpers.add(methodId(method));
        ArrayDeque<Work> pending = new ArrayDeque<>();
        for (var root : synchronous.roots()) addRoot(pending, root);
        Map<MethodId, Set<MethodId>> calls = helperCalls(synchronous, helpers);
        for (var callback : synchronousCallbacks)
            calls.computeIfAbsent(methodId(callback.caller()), ignored -> new HashSet<>()).add(methodId(callback.implementation()));
        Map<MethodId, Context> contexts = new HashMap<>();
        Set<MethodId> invalid = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        List<ArgumentCandidate> argumentCandidates = new ArrayList<>();
        while (!pending.isEmpty()) {
            Work work = pending.removeFirst();
            Context previous = contexts.putIfAbsent(work.id(), work.context());
            if (previous != null) {
                if (!previous.equals(work.context()) && invalid.add(work.id()))
                    diagnostics.add("同一方法存在不同施法上下文，跳过自动消费入口：" + work.id().key());
                continue;
            }
            if (contexts.size() > 128) {
                diagnostics.add("自动消费调用图超过安全范围，保留原生行为");
                return new FormulaAutomaticSites.Scan(spell, List.of(), diagnostics);
            }
            Data body = body(work.resolved());
            if (body == null) {
                invalid.add(work.id());
                continue;
            }
            if (!work.context().complete()) {
                invalid.add(work.id());
                diagnostics.add("同步helper没有唯一完整的原施法上下文，保留原生值：" + work.id().key());
                continue;
            }
            if (!FormulaAutomaticInjector.contextIntact(work.resolved().method(), work.context().caster(),
                    work.context().level(), work.context().spell())) {
                invalid.add(work.id());
                diagnostics.add("原参数槽被复用，无法保持施法上下文：" + work.id().key());
                continue;
            }
            Map<AbstractInsnNode, Candidate> grouped = new IdentityHashMap<>();
            for (AbstractInsnNode instruction : work.resolved().method().instructions) {
                if (instruction instanceof InvokeDynamicInsnNode dynamic
                        && dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                        && callbacks.getOrDefault(work.id(), List.of()).stream().noneMatch(callback -> callback.factory() == dynamic))
                    diagnostics.add("lambda创建不能证明一次性执行，保留其原生消费：" + work.id().key());
                if (!(instruction instanceof MethodInsnNode call)) continue;
                var callee = helper(work.resolved().owner().name, call, helpers);
                if (callee != null) pending.add(new Work(callee, mapContext(work, body, call, callee.method())));
                for (var callback : callbacks.getOrDefault(work.id(), List.of())) {
                    if (callback.call() != call) continue;
                    var target = callback.implementation();
                    var implementation = repository.resolveMethod(target.owner(), target.name(), target.descriptor());
                    if (implementation == null) continue;
                    if (callback.dynamicElements()) {
                        List<SourceValue> arguments = new ArrayList<>(callback.capturedArguments());
                        arguments.add(null);
                        var context = mapContext(work, body, callback.implementationReceiver(), arguments, implementation.method(), true);
                        pending.add(new Work(implementation, context));
                    }
                    for (var element : callback.elements()) {
                        List<SourceValue> arguments = new ArrayList<>(callback.capturedArguments());
                        arguments.add(element);
                        pending.add(new Work(implementation, mapContext(work, body, callback.implementationReceiver(),
                                arguments, implementation.method())));
                    }
                }
                var consumptions = FormulaConsumptionModel.classify(repository, call);
                Sink sink = sink(work, body, call, consumptions);
                List<Sink> sinks = new ArrayList<>();
                if (sink != null) sinks.add(sink);
                sinks.addAll(additionalSinks(work, body, call, consumptions));
                for (Sink argument : sinks) {
                    SourceValue value = body.argument(call, argument.argument());
                    boolean terminalComponent = (movement(argument.kind()) || argument.kind() == FormulaAutomaticSites.Kind.DAMAGE)
                            && consumptions.stream()
                            .anyMatch(consumption -> consumption.proof() == FormulaConsumptionModel.Proof.TERMINAL
                                    && consumption.kind() == argument.kind() && consumption.argument() == argument.argument());
                    if (!terminalComponent && (protectedInput(work.context(), body, value, new HashSet<>())
                            || existingGetter(work, body, value, new HashSet<>(), movement(argument.kind()))
                            || manualInput(work, body, value))) continue;
                    argumentCandidates.add(new ArgumentCandidate(work, body, new Use(call, argument.argument(), argument.kind())));
                }
                if (sink == null) continue;
                SourceValue value = body.argument(call, sink.argument());
                Origin origin = body.origin(value);
                if (origin.producer() == null) {
                    diagnostics.add("消费值没有唯一可替换的生产点：" + work.id().key());
                    continue;
                }
                Type actualType = FormulaAutomaticInjector.resultType(origin.producer());
                Type expectedType = Type.getArgumentTypes(call.desc)[sink.argument()];
                if (!expectedType.equals(actualType) || !supported(actualType)) continue;
                if (protectedInput(work.context(), body, value, new HashSet<>())) {
                    diagnostics.add("同步helper消费依赖已有数值入口，保留原生值：" + work.id().key());
                    continue;
                }
                if (existingGetter(work, body, value, new HashSet<>())) continue;
                if (manuallyCovered(work, body, origin.producer())) continue;
                Candidate candidate = grouped.computeIfAbsent(origin.producer(), ignored ->
                        new Candidate(work, body, origin, actualType, new ArrayList<>()));
                candidate.uses().add(new Use(call, sink.argument(), sink.kind()));
            }
            candidates.addAll(grouped.values());
        }
        ArrayDeque<MethodId> invalidated = new ArrayDeque<>(invalid);
        while (!invalidated.isEmpty()) {
            MethodId caller = invalidated.removeFirst();
            for (MethodId callee : calls.getOrDefault(caller, Set.of()))
                if (invalid.add(callee)) {
                    diagnostics.add("上游施法上下文无法确认，保留同步helper原生值：" + callee.key());
                    invalidated.add(callee);
                }
        }
        List<FormulaAutomaticSites.Site> sites = new ArrayList<>();
        Set<ConsumerId> coveredConsumers = new HashSet<>();
        Map<FormulaAutomaticSites.Site, List<ConsumerId>> oldConsumers = new HashMap<>();
        Map<String, List<FormulaAutomaticSites.Site>> aliases = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (invalid.contains(candidate.work().id())) continue;
            if (candidate.work().context().scoped()) continue;
            FormulaAutomaticSites.Kind kind = mergedKind(candidate.uses());
            if (kind == null || unsafeUse(candidate)) {
                diagnostics.add("同源数值存在其他用途，保留原生值：" + candidate.work().id().key());
                continue;
            }
            String alias = alias(kind);
            if (reserved.contains(alias)) {
                diagnostics.add("自动消费公式与既有公开入口重名，保留原入口：" + alias);
                continue;
            }
            FormulaNumericSites.ValueType type = valueType(candidate.type());
            Number constant = constant(candidate.data(), candidate.origin().producer(), new HashSet<>());
            if (constant != null) constant = convert(constant, type);
            double minimum = kind == FormulaAutomaticSites.Kind.HIT_INTERVAL ? 1 : 0;
            double maximum = type == FormulaNumericSites.ValueType.INT ? Integer.MAX_VALUE
                    : type == FormulaNumericSites.ValueType.FLOAT ? Math.sqrt(Float.MAX_VALUE) : Math.sqrt(Double.MAX_VALUE);
            if (kind == FormulaAutomaticSites.Kind.RADIUS) maximum = 32;
            if (constant != null && (!Double.isFinite(constant.doubleValue())
                    || constant.doubleValue() < minimum || constant.doubleValue() > maximum)) {
                diagnostics.add("原生消费常数不在可安全配置范围，保留原值：" + candidate.work().id().key());
                continue;
            }
            Number defaultValue = constant == null ? convert(minimum, type) : constant;
            String expression = constant == null ? "native_value" : number(constant);
            var definition = new FormulaNumericSites.Definition(spell.replace('/', '.'), alias, defaultValue, type, minimum, maximum);
            var method = candidate.work().resolved().method();
            var context = candidate.work().context();
            var site = new FormulaAutomaticSites.Site(definition, candidate.work().resolved().owner().name,
                    method.name, method.desc, snapshot(method).indexOf(candidate.origin().producer()),
                    snapshot(method).fingerprint(), context.caster(), context.level(), context.spell(), kind, expression);
            aliases.computeIfAbsent(alias, ignored -> new ArrayList<>()).add(site);
            oldConsumers.put(site, candidate.uses().stream().map(use -> consumer(candidate.work(), use)).toList());
        }
        aliases.forEach((alias, selected) -> {
            if (selected.size() == 1) {
                sites.add(selected.get(0));
                coveredConsumers.addAll(oldConsumers.get(selected.get(0)));
            }
            else diagnostics.add("同一消费语义存在多个不同生产点，无法唯一命名，保留原生行为：" + alias);
        });
        Set<String> usedAliases = new HashSet<>(reserved);
        sites.forEach(site -> usedAliases.add(site.definition().alias()));
        argumentCandidates.sort(java.util.Comparator.comparing((ArgumentCandidate value) -> value.work().resolved().owner().name)
                .thenComparing(value -> value.work().resolved().method().name)
                .thenComparing(value -> value.work().resolved().method().desc)
                .thenComparingInt(value -> snapshot(value.work().resolved().method()).indexOf(value.use().call()))
                .thenComparingInt(value -> value.use().argument()));
        for (ArgumentCandidate candidate : argumentCandidates) {
            if (invalid.contains(candidate.work().id()) || !coveredConsumers.add(consumer(candidate.work(), candidate.use()))) continue;
            Use use = candidate.use();
            FormulaNumericSites.ValueType type = valueType(Type.getArgumentTypes(use.call().desc)[use.argument()]);
            Origin origin = candidate.data().origin(candidate.data().argument(use.call(), use.argument()));
            Number constant = constant(candidate.data(), origin.producer(), new HashSet<>());
            if (constant != null) constant = convert(constant, type);
            double maximum = type == FormulaNumericSites.ValueType.INT ? Integer.MAX_VALUE
                    : type == FormulaNumericSites.ValueType.FLOAT ? Math.sqrt(Float.MAX_VALUE) : Math.sqrt(Double.MAX_VALUE);
            double minimum = movement(use.kind()) ? -maximum : use.kind() == FormulaAutomaticSites.Kind.HIT_INTERVAL ? 1 : 0;
            if (query(use.kind())) maximum = 32;
            if (constant != null && (!Double.isFinite(constant.doubleValue()) || constant.doubleValue() < minimum || constant.doubleValue() > maximum)) continue;
            String baseAlias = alias(use.kind());
            String alias = baseAlias;
            for (int suffix = 2; !usedAliases.add(alias); suffix++) alias = baseAlias + '_' + suffix;
            Number defaultValue = constant == null ? convert(Math.max(0, minimum), type) : constant;
            String expression = constant == null ? "native_value" : number(constant);
            var definition = new FormulaNumericSites.Definition(spell, alias, defaultValue, type, minimum, maximum);
            MethodNode method = candidate.work().resolved().method();
            Context context = candidate.work().context();
            sites.add(new FormulaAutomaticSites.Site(definition, candidate.work().resolved().owner().name, method.name,
                    method.desc, snapshot(method).indexOf(use.call()), snapshot(method).fingerprint(),
                    context.caster(), context.level(), context.spell(), use.kind(), expression, use.argument(),
                    context.scoped() ? FormulaAutomaticSites.ContextMode.SCOPED : FormulaAutomaticSites.ContextMode.DIRECT, -1));
        }
        sites.sort(java.util.Comparator.comparing(FormulaAutomaticSites.Site::owner)
                .thenComparing(FormulaAutomaticSites.Site::methodName).thenComparing(FormulaAutomaticSites.Site::methodDescriptor)
                .thenComparingInt(FormulaAutomaticSites.Site::producerIndex).thenComparingInt(FormulaAutomaticSites.Site::argumentIndex));
        Set<MethodId> scopedMethods = new HashSet<>();
        for (var site : sites) if (site.contextMode() == FormulaAutomaticSites.ContextMode.SCOPED)
            scopedMethods.add(new MethodId(site.owner().replace('.', '/'), site.methodName(), site.methodDescriptor()));
        List<FormulaCastContext.Root> scopes = new ArrayList<>();
        for (var root : synchronous.roots().stream().sorted(java.util.Comparator.comparing(FormulaExecutionGraph.MethodId::key)).toList()) {
            MethodId id = methodId(root);
            if (!reaches(id, scopedMethods, calls, new HashSet<>())) continue;
            Context context = contexts.get(id);
            if (context == null || invalid.contains(id)) continue;
            var method = repository.resolveMethod(root.owner(), root.name(), root.descriptor());
            if (method == null) continue;
            scopes.add(new FormulaCastContext.Root(method.owner().name, method.method().name, method.method().desc,
                    snapshot(method.method()).fingerprint(), context.caster(), context.level(), context.spell()));
        }
        return new FormulaAutomaticSites.Scan(spell, sites, diagnostics, List.of(), scopes);
    }

    private static boolean reaches(MethodId method, Set<MethodId> targets, Map<MethodId, Set<MethodId>> calls, Set<MethodId> visited) {
        if (targets.contains(method)) return true;
        if (!visited.add(method)) return false;
        for (var callee : calls.getOrDefault(method, Set.of())) if (reaches(callee, targets, calls, visited)) return true;
        return false;
    }

    private FormulaMethodSnapshot snapshot(MethodNode method) {
        return methodSnapshots.computeIfAbsent(method, FormulaMethodSnapshot::new);
    }

    private ConsumerId consumer(Work work, Use use) {
        return new ConsumerId(work.id(), snapshot(work.resolved().method()).indexOf(use.call()), use.argument());
    }

    private FormulaAutomaticSites.Scan result(List<FormulaAutomaticSites.Site> sites, String diagnostic) {
        return new FormulaAutomaticSites.Scan(spell, sites, List.of(diagnostic));
    }

    private boolean manuallyCovered(Work work, Data body, AbstractInsnNode producer) {
        var owner = work.resolved().owner();
        var coverage = manualCoverage.computeIfAbsent(owner.name, ignored -> manualCoverage(owner));
        if (coverage.uncertain()) return true;
        var method = work.resolved().method();
        var snapshot = snapshot(method);
        var covered = coveredDependency(method, body, producer, coverage.producers(), snapshot,
                java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        if (covered == null) return false;
        int index = snapshot.indexOf(producer);
        if (covered.instruction() == index)
            diagnostics.add("该数值生产点已有人工消费挂钩，继续扫描其他点：" + work.id().key() + ':' + index);
        else diagnostics.add("自动候选依赖已有人工消费数值点，保留原值：" + work.id().key() + ':' + index
                + " <- " + covered.instruction());
        return true;
    }

    private static ProducerId coveredDependency(MethodNode method, Data body, AbstractInsnNode producer,
                                                Set<ProducerId> covered, FormulaMethodSnapshot snapshot,
                                                Set<AbstractInsnNode> visited) {
        if (!visited.add(producer)) return null;
        var point = new ProducerId(method.name, method.desc, snapshot.indexOf(producer));
        if (covered.contains(point)) return point;
        for (var value : body.inputs.getOrDefault(producer, List.of()))
            for (var input : value.insns) {
                var dependency = coveredDependency(method, body, input, covered, snapshot, visited);
                if (dependency != null) return dependency;
            }
        return null;
    }

    private ManualCoverage manualCoverage(ClassNode owner) {
        String name = owner.name.replace('/', '.');
        List<java.util.function.BiConsumer<String, ClassNode>> mutators = new ArrayList<>();
        if (repository.baselineCoverage() && FormulaNumericSites.targetClasses().contains(name)) mutators.add(FormulaNumericSites::apply);
        if (FormulaAddonNumericSites.targetClasses().contains(name))
            mutators.add((target, source) -> FormulaAddonNumericSites.apply(target, source));
        if (repository.baselineCoverage()) {
            if (FormulaSites.targetClasses().contains(name)) mutators.add(FormulaSites::apply);
            if (FormulaEntitySites.targetClasses().contains(name)) mutators.add(FormulaEntitySites::apply);
            if (FormulaConsumerSites.targetClasses().contains(name)) mutators.add(FormulaConsumerSites::apply);
            if (FormulaPublishedCorrections.targetClasses().contains(name)) mutators.add(FormulaPublishedCorrections::apply);
        }
        if (mutators.isEmpty()) return new ManualCoverage(Set.of(), false);
        Set<ProducerId> producers = new HashSet<>();
        try {
            for (var mutator : mutators) {
                var probe = new ClassNode();
                owner.accept(probe);
                for (var method : probe.methods) {
                    var instructions = FormulaAutomaticInjector.executable(method);
                    for (int index = 0; index < instructions.size(); index++) {
                        var marker = new LabelNode();
                        method.instructions.insertBefore(instructions.get(index), marker);
                        method.instructions.insert(marker, new LineNumberNode(-index - 1, marker));
                    }
                }
                mutator.accept(name, probe);
                for (var method : probe.methods) {
                    var numericHooks = FormulaAutomaticInjector.executable(method).stream()
                            .filter(instruction -> instruction instanceof MethodInsnNode call && numericHook(call))
                            .map(MethodInsnNode.class::cast).toList();
                    if (numericHooks.isEmpty()) continue;
                    var body = new Data(repository, probe.name, method);
                    for (var hook : numericHooks) {
                        var original = body.origin(body.argument(hook, 0)).producer();
                        int index = original == null ? -1 : markedIndex(original);
                        if (index < 0) throw new IllegalArgumentException("人工数值挂钩没有唯一原始生产点："
                                + method.name + method.desc + '#' + hook.name);
                        producers.add(new ProducerId(method.name, method.desc, index));
                    }
                }
            }
            return new ManualCoverage(Set.copyOf(producers), false);
        } catch (AnalyzerException | RuntimeException | LinkageError failure) {
            diagnostics.add("无法确认人工消费覆盖范围，保留该类候选的原生值：" + name + ": " + failure.getMessage());
            return new ManualCoverage(Set.of(), true);
        }
    }

    private static boolean numericHook(MethodInsnNode call) {
        if (!Set.of("com/example/spell_compiler/formula/FormulaNumericHooks",
                "com/example/spell_compiler/formula/FormulaSiteHooks",
                "com/example/spell_compiler/formula/FormulaEntityHooks",
                "com/example/spell_compiler/formula/FormulaConsumerHooks",
                "com/example/spell_compiler/formula/FormulaPublishedCorrectionsHooks").contains(call.owner)) return false;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        return arguments.length > 0 && supported(arguments[0]) && supported(Type.getReturnType(call.desc));
    }

    private static int markedIndex(AbstractInsnNode original) {
        for (var previous = original.getPrevious(); previous != null; previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) return -1;
            if (previous instanceof LineNumberNode line && line.line < 0) return -line.line - 1;
        }
        return -1;
    }

    private static MethodId methodId(FormulaExecutionGraph.MethodId method) {
        return new MethodId(method.owner(), method.name(), method.descriptor());
    }

    private FormulaClassRepository.ResolvedMethod helper(String caller, MethodInsnNode call, Set<MethodId> allowed) {
        if (!family.contains(call.owner) || call.name.startsWith("<")) return null;
        var resolved = resolve(caller, call, spell);
        if (resolved == null || (resolved.method().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE))
                != Opcodes.ACC_PRIVATE) return null;
        return allowed.contains(new MethodId(resolved.owner().name, resolved.method().name, resolved.method().desc)) ? resolved : null;
    }

    private Map<MethodId, Set<MethodId>> helperCalls(FormulaExecutionGraph.SynchronousMethods synchronous, Set<MethodId> helpers) {
        Set<FormulaExecutionGraph.MethodId> methods = new HashSet<>(synchronous.roots());
        methods.addAll(synchronous.methods());
        Map<MethodId, Set<MethodId>> result = new HashMap<>();
        for (var method : methods) {
            var resolved = repository.resolveMethod(method.owner(), method.name(), method.descriptor());
            if (resolved == null) continue;
            var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
            if (!flow.successful()) continue;
            for (var instruction : resolved.method().instructions)
                if (instruction instanceof MethodInsnNode call && flow.reachable(call)) {
                    var callee = helper(resolved.owner().name, call, helpers);
                    if (callee != null) result.computeIfAbsent(methodId(method), ignored -> new HashSet<>())
                            .add(new MethodId(callee.owner().name, callee.method().name, callee.method().desc));
                }
        }
        return result;
    }

    private Context mapContext(Work caller, Data body, MethodInsnNode call, MethodNode callee) {
        return mapContext(caller, body, body.receiver(call), body.arguments(call), callee);
    }

    private Context mapContext(Work caller, Data body, SourceValue receiver, List<SourceValue> arguments, MethodNode callee) {
        return mapContext(caller, body, receiver, arguments, callee, false);
    }

    private Context mapContext(Work caller, Data body, SourceValue receiver, List<SourceValue> arguments,
                               MethodNode callee, boolean unknownLastElement) {
        Set<Integer> casters = new HashSet<>();
        Set<Integer> levels = new HashSet<>();
        Set<Integer> spells = new HashSet<>();
        Set<Integer> protectedValues = new HashSet<>();
        int slot = (callee.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        if (slot == 1) {
            if (!body.parameter(receiver, caller.context().spell()))
                return new Context(-1, -1, -1, Set.of());
            spells.add(0);
        }
        Type[] types = Type.getArgumentTypes(callee.desc);
        for (int index = 0; index < types.length; index++) {
            SourceValue argument = index < arguments.size() ? arguments.get(index) : null;
            if (types[index].equals(Type.INT_TYPE) && body.parameter(argument, caller.context().level())) levels.add(slot);
            if (types[index].getSort() == Type.OBJECT || types[index].getSort() == Type.ARRAY) {
                if (body.parameter(argument, caller.context().caster())) casters.add(slot);
                if (body.parameter(argument, caller.context().spell())) spells.add(slot);
            }
            if (supported(types[index]) && (protectedInput(caller.context(), body, argument, new HashSet<>())
                    || existingGetter(caller, body, argument, new HashSet<>()))) protectedValues.add(slot);
            else if (supported(types[index]) && manualInput(caller, body, argument)) protectedValues.add(slot);
            slot += types[index].getSize();
        }
        int caster = unique(casters), level = unique(levels), spell = unique(spells);
        if (caster >= 0 && level >= 0 && spell >= 0 && !caller.context().scoped())
            return new Context(caster, level, spell, protectedValues);
        boolean levelPresent = !levels.isEmpty() || java.util.Arrays.stream(types).noneMatch(type -> type.equals(Type.INT_TYPE));
        int capturedCount = types.length - (unknownLastElement ? 1 : 0);
        boolean casterPresent = !casters.isEmpty() || java.util.Arrays.stream(types, 0, capturedCount).noneMatch(type -> type.getSort() == Type.OBJECT
                && repository.isSubtype(type.getInternalName(), ENTITY));
        boolean spellPresent = !spells.isEmpty() || java.util.Arrays.stream(types, 0, capturedCount).noneMatch(type -> type.getSort() == Type.OBJECT
                && repository.isSubtype(type.getInternalName(), ABSTRACT));
        if (levelPresent && casterPresent && spellPresent)
            return new Context(caster, level, spell, protectedValues, true);
        return new Context(caster, level, spell, protectedValues);
    }

    private static int unique(Set<Integer> slots) {
        return slots.size() == 1 ? slots.iterator().next() : -1;
    }

    private boolean manualInput(Work caller, Data body, SourceValue value) {
        if (value == null) return false;
        for (var producer : value.insns)
            if (manuallyCovered(caller, body, producer)) return true;
        return false;
    }

    private static boolean protectedInput(Context context, Data body, SourceValue value, Set<SourceValue> visited) {
        if (value == null) return false;
        if (value instanceof FormulaDataFlow.Value typed)
            for (var source : typed.sources()) if (source.kind() == FormulaDataFlow.NodeKind.PARAMETER
                    && context.protectedValues().contains(source.parameterSlot())) return true;
        if (!visited.add(value)) return false;
        if (!context.protectedValues().isEmpty())
            for (var instruction : body.method.instructions)
                if (instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode
                        || instruction instanceof LookupSwitchInsnNode) return true;
        for (var producer : value.insns)
            for (var input : body.inputs.getOrDefault(producer, List.of()))
                if (protectedInput(context, body, input, visited)) return true;
        return false;
    }

    private void addRoot(ArrayDeque<Work> pending, FormulaExecutionGraph.MethodId root) {
        Type[] parameters = Type.getArgumentTypes(root.descriptor());
        if (parameters.length < 3 || !parameters[0].equals(Type.getObjectType(LEVEL))
                || !parameters[1].equals(Type.INT_TYPE) || !parameters[2].equals(Type.getObjectType(LIVING))) return;
        var resolved = repository.resolveMethod(root.owner(), root.name(), root.descriptor());
        if (resolved != null && !resolved.owner().name.equals(ABSTRACT)
                && (resolved.method().access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)
            pending.add(new Work(resolved, new Context(3, 2, 0, Set.of())));
    }

    private List<Sink> additionalSinks(Work work, Data body, MethodInsnNode call,
                                       List<FormulaConsumptionModel.Consumption> consumptions) {
        List<Sink> platform = new ArrayList<>();
        for (var consumption : consumptions)
            if (consumption.proof() == FormulaConsumptionModel.Proof.TERMINAL
                    || consumption.kind() == FormulaAutomaticSites.Kind.EFFECT_LEVEL
                    && published(body, body.origin(body.receiver(call)), "effect"))
                platform.add(new Sink(consumption.kind(), consumption.argument()));
        if (!platform.isEmpty()) return platform;
        if (call.owner.equals(VECTOR)) {
            return FormulaGeometryConsumption.classify(repository, body.analysis, call).stream()
                    .map(value -> new Sink(value.kind(), value.argument())).toList();
        }
        if (call.owner.equals(BOX) && call.name.equals("inflate") && reachesQuery(body, call)) {
            if (!isolatedQuery(body, call)) {
                diagnostics.add("查询范围对象存在其他未证明的用途，保留原生值：" + work.id().key());
                return List.of();
            }
            if (call.desc.equals("(D)L" + BOX + ';')) return List.of(new Sink(FormulaAutomaticSites.Kind.QUERY_RADIUS, 0));
            if (call.desc.equals("(DDD)L" + BOX + ';')) return axes(FormulaAutomaticSites.Kind.QUERY_X_RANGE,
                    FormulaAutomaticSites.Kind.QUERY_Y_RANGE, FormulaAutomaticSites.Kind.QUERY_Z_RANGE);
        }
        if (call.name.equals("<init>") && repository.isSubtype(call.owner, FANG)
                && published(body, body.origin(body.receiver(call)), "entity")) {
            int parameter = fangWarmup(call.owner, call.desc, new HashSet<>());
            if (parameter >= 0) return List.of(new Sink(FormulaAutomaticSites.Kind.ACTIVATION_DELAY, parameter));
        }
        return List.of();
    }

    private static List<Sink> axes(FormulaAutomaticSites.Kind x, FormulaAutomaticSites.Kind y, FormulaAutomaticSites.Kind z) {
        return List.of(new Sink(x, 0), new Sink(y, 1), new Sink(z, 2));
    }

    private boolean isolatedQuery(Data body, AbstractInsnNode output) {
        for (var entry : body.inputs.entrySet()) {
            if (entry.getValue().stream().noneMatch(value -> depends(body, value, output))) continue;
            AbstractInsnNode consumer = entry.getKey();
            if (copy(consumer) || consumer.getOpcode() == Opcodes.POP || consumer.getOpcode() == Opcodes.POP2) continue;
            if (consumer instanceof MethodInsnNode call) {
                if (call.owner.equals(BOX)) continue;
                if (repository.isSubtype(call.owner, LEVEL)
                        && Set.of("getEntities", "getEntitiesOfClass", "getNearbyEntities", "m_45976_", "m_6443_", "m_45933_").contains(call.name)
                        && java.util.Arrays.stream(Type.getArgumentTypes(call.desc)).anyMatch(type -> type.equals(Type.getObjectType(BOX)))) continue;
                if (Set.of("java/lang/Math", "java/lang/StrictMath", "net/minecraft/util/Mth").contains(call.owner)
                        && supported(Type.getReturnType(call.desc))
                        && java.util.Arrays.stream(Type.getArgumentTypes(call.desc)).allMatch(FormulaAutomaticFlow::supported)) continue;
                return false;
            }
            if (consumer instanceof FieldInsnNode field) {
                if (field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(BOX)) continue;
                return false;
            }
            if (consumer instanceof JumpInsnNode || consumer instanceof TableSwitchInsnNode || consumer instanceof LookupSwitchInsnNode)
                return false;
            if (FormulaAutomaticInjector.resultType(consumer) == null) return false;
        }
        return true;
    }

    private boolean reachesQuery(Data body, AbstractInsnNode output) {
        for (AbstractInsnNode instruction : body.method.instructions)
            if (instruction instanceof MethodInsnNode call && repository.isSubtype(call.owner, LEVEL)
                    && Set.of("getEntities", "getEntitiesOfClass", "getNearbyEntities", "m_45976_", "m_6443_", "m_45933_").contains(call.name)) {
                Type[] arguments = Type.getArgumentTypes(call.desc);
                for (int index = 0; index < arguments.length; index++)
                    if (arguments[index].equals(Type.getObjectType(BOX))
                            && depends(body, body.argument(call, index), output)) return true;
            }
        return false;
    }

    private static boolean depends(Data body, SourceValue value, AbstractInsnNode output) {
        if (!(value instanceof FormulaDataFlow.Value typed)) return false;
        ArrayDeque<FormulaDataFlow.Value> pending = new ArrayDeque<>();
        Set<FormulaDataFlow.Node> visited = new HashSet<>();
        pending.add(typed);
        while (!pending.isEmpty()) {
            for (var source : pending.removeFirst().sources()) {
                if (source.instruction() == output) return true;
                if (!visited.add(source)) continue;
                if (source.instruction() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                        && allocation.desc.equals(VECTOR))
                    for (var argument : body.vectorArguments.getOrDefault(allocation, List.of()))
                        if (argument instanceof FormulaDataFlow.Value input) pending.add(input);
                pending.addAll(body.analysis.dependencies(source));
            }
        }
        return false;
    }

    private int fangWarmup(String owner, String descriptor, Set<String> visited) {
        if (!visited.add(owner + descriptor)) return -1;
        Type[] arguments = Type.getArgumentTypes(descriptor);
        String nativeDescriptor = "(L" + LEVEL + ";DDDFIL" + LIVING + ";)V";
        if (owner.equals(FANG)) return descriptor.equals(nativeDescriptor) ? 5 : -1;
        var constructor = repository.resolveMethod(owner, "<init>", descriptor);
        if (constructor == null || !constructor.owner().name.equals(owner)) return -1;
        Data body = body(constructor);
        if (body == null) return -1;
        int selected = -1;
        for (AbstractInsnNode instruction : constructor.method().instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("<init>")
                    || !repository.isSubtype(call.owner, FANG) || !body.origin(body.receiver(call)).equals(new Origin(null, 0))) continue;
            int argument = fangWarmup(call.owner, call.desc, visited);
            if (argument < 0) continue;
            Origin source = body.origin(body.argument(call, argument));
            if (source.parameter() < 0) return -1;
            int slot = 1;
            for (int index = 0; index < arguments.length; index++) {
                if (slot == source.parameter() && arguments[index].equals(Type.INT_TYPE)) {
                    if (selected >= 0 && selected != index) return -1;
                    selected = index;
                }
                slot += arguments[index].getSize();
            }
        }
        return selected;
    }

    private static boolean movement(FormulaAutomaticSites.Kind kind) { return kind.name().startsWith("MOVEMENT_"); }
    private static boolean query(FormulaAutomaticSites.Kind kind) { return kind.name().startsWith("QUERY_"); }

    private Data body(FormulaClassRepository.ResolvedMethod method) {
        MethodId id = new MethodId(method.owner().name, method.method().name, method.method().desc);
        if (broken.contains(id)) return null;
        Data known = data.get(id);
        if (known != null) return known;
        if ((method.method().access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return null;
        try {
            Data created = new Data(repository, method.owner().name, method.method());
            data.put(id, created);
            return created;
        } catch (AnalyzerException | RuntimeException failure) {
            broken.add(id);
            diagnostics.add("无法证明方法数值流，保留原方法：" + id.key() + ": " + failure.getMessage());
            return null;
        }
    }

    private Sink sink(Work work, Data body, MethodInsnNode call,
                      List<FormulaConsumptionModel.Consumption> consumptions) {
        Type[] arguments = Type.getArgumentTypes(call.desc);
        for (var consumption : consumptions)
            if (consumption.proof() == FormulaConsumptionModel.Proof.EFFECT_PUBLICATION
                    && consumption.kind() == FormulaAutomaticSites.Kind.DURATION
                    && published(body, body.origin(body.receiver(call)), "effect"))
                return new Sink(consumption.kind(), consumption.argument());
        if (call.owner.equals(RECAST) && call.name.equals("<init>") && call.desc.equals(RECAST_CONSTRUCTOR)
                && published(body, body.origin(body.receiver(call)), "recast"))
            return new Sink(FormulaAutomaticSites.Kind.RECAST_WINDOW, 3);
        if (call.owner.equals(SUMMON) && call.name.equals("initSummon") && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.desc.equals("(L" + ENTITY + ";L" + ENTITY + ";ILio/redspace/ironsspellbooks/capabilities/magic/SummonedEntitiesCastData;)V"))
            return new Sink(FormulaAutomaticSites.Kind.SUMMON_DURATION, 2);
        if (arguments.length != 1 || !Type.getReturnType(call.desc).equals(Type.VOID_TYPE)) return null;
        String base;
        FormulaAutomaticSites.Kind kind;
        if (repository.isSubtype(call.owner, AOE)) {
            base = AOE;
            kind = switch (call.name + call.desc) {
                case "setDuration(I)V" -> FormulaAutomaticSites.Kind.DURATION;
                case "setReapplicationDelay(I)V" -> FormulaAutomaticSites.Kind.HIT_INTERVAL;
                case "setRadius(F)V" -> FormulaAutomaticSites.Kind.RADIUS;
                case "setDamage(F)V" -> FormulaAutomaticSites.Kind.DAMAGE;
                default -> null;
            };
        } else if (repository.isSubtype(call.owner, PROJECTILE)) {
            base = PROJECTILE;
            kind = switch (call.name + call.desc) {
                case "setDamage(F)V" -> FormulaAutomaticSites.Kind.DAMAGE;
                case "setExplosionRadius(F)V" -> FormulaAutomaticSites.Kind.RADIUS;
                default -> null;
            };
        } else return null;
        if (kind == null) return null;
        String target = publishedEntityType(body, body.receiver(call));
        if (target == null || !repository.isSubtype(target, base)) return null;
        var setter = resolve(work.resolved().owner().name, call, target);
        if (setter == null || !setter.owner().name.equals(base)) return null;
        if (!consumed(target, base, kind, setter)) {
            diagnostics.add("实体setter数值没有可证明的实际消费，保留原生值：" + target + '#' + call.name + call.desc);
            return null;
        }
        return new Sink(kind, 0);
    }

    private String publishedEntityType(Data body, SourceValue value) {
        Origin origin = body.origin(value);
        if (origin.producer() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                && published(body, origin, "entity")) return allocation.desc;
        return null;
    }

    private boolean published(Data body, Origin object, String kind) {
        if (!(object.producer() instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW) return false;
        for (AbstractInsnNode instruction : body.method.instructions) if (instruction instanceof MethodInsnNode call) {
            Type[] arguments = Type.getArgumentTypes(call.desc);
            if (arguments.length == 0 || !body.origin(body.argument(call, 0)).equals(object)) continue;
            if (kind.equals("effect") && Set.of("addEffect", "m_7292_", "m_147207_").contains(call.name)
                    && repository.isSubtype(call.owner, LIVING) && arguments[0].equals(Type.getObjectType(INSTANCE))
                    && Type.getReturnType(call.desc).equals(Type.BOOLEAN_TYPE)) return true;
            if (kind.equals("recast") && call.owner.equals(RECASTS) && call.name.equals("addRecast")
                    && arguments[0].equals(Type.getObjectType(RECAST))) return true;
            if (kind.equals("entity") && Set.of("addFreshEntity", "m_7967_").contains(call.name)
                    && repository.isSubtype(call.owner, LEVEL) && arguments.length == 1
                    && arguments[0].equals(Type.getObjectType(ENTITY)) && Type.getReturnType(call.desc).equals(Type.BOOLEAN_TYPE)) return true;
        }
        return false;
    }

    private boolean consumed(String leaf, String base, FormulaAutomaticSites.Kind kind,
                             FormulaClassRepository.ResolvedMethod setter) {
        if (kind == FormulaAutomaticSites.Kind.RADIUS) {
            if (!base.equals(AOE)) return false;
            return nativeAoe(leaf, kind, setter, Set.of());
        }
        Set<FieldId> writes = setterFields(setter);
        if (writes.isEmpty()) return false;
        if (kind != FormulaAutomaticSites.Kind.DAMAGE) return base.equals(AOE) && nativeAoe(leaf, kind, setter, writes);
        for (ReadWork work : entityCalls.computeIfAbsent(leaf, this::entityCalls)) {
            Data source = body(work.resolved());
            if (source == null) continue;
            for (AbstractInsnNode instruction : work.resolved().method().instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                var implementation = source.parameter(source.receiver(call), work.self())
                        ? FormulaInterproceduralFlow.resolveCall(repository, source.analysis.method().owner(), call, leaf)
                        : repository.resolveMethod(call.owner, call.name, call.desc);
                for (var consumption : FormulaConsumptionModel.classify(repository, call, implementation))
                    if (consumption.kind() == FormulaAutomaticSites.Kind.DAMAGE
                            && consumption.proof() == FormulaConsumptionModel.Proof.TERMINAL
                            && numericFields(source, source.argument(call, consumption.argument()), leaf, work.self(), new HashSet<>(),
                            java.util.Collections.newSetFromMap(new IdentityHashMap<>()), true).stream().anyMatch(writes::contains)) return true;
            }
        }
        return false;
    }

    private Set<FieldId> setterFields(FormulaClassRepository.ResolvedMethod setter) {
        Data source = body(setter);
        if (source == null) return Set.of();
        Set<FieldId> result = new HashSet<>();
        for (AbstractInsnNode instruction : setter.method().instructions)
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
                List<SourceValue> inputs = source.inputs.getOrDefault(field, List.of());
                if (inputs.size() == 2 && source.parameter(inputs.get(0), 0) && source.parameter(inputs.get(1), 1))
                    result.add(field(field));
            }
        return result;
    }

    private List<ReadWork> entityCalls(String leaf) {
        List<ReadWork> result = new ArrayList<>();
        ArrayDeque<ReadWork> pending = new ArrayDeque<>();
        var ticking = callback(leaf, "()V", List.of("tick", "m_8119_"));
        if (ticking != null) pending.add(new ReadWork(ticking, 0));
        for (var entry : Map.of("net/minecraft/world/phys/HitResult", List.of("onHit", "m_6532_"),
                "net/minecraft/world/phys/EntityHitResult", List.of("onHitEntity", "m_5790_"),
                "net/minecraft/world/phys/BlockHitResult", List.of("onHitBlock", "m_8060_")).entrySet())
            {
                var root = callback(leaf, "(L" + entry.getKey() + ";)V", entry.getValue());
                if (root != null) pending.add(new ReadWork(root, 0));
            }
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty() && visited.size() < 128) {
            ReadWork work = pending.removeFirst();
            String id = work.resolved().owner().name + '#' + work.resolved().method().name + work.resolved().method().desc + '#' + work.self();
            if (!visited.add(id) || work.resolved().owner().name.startsWith("net/minecraft/")) continue;
            Data source = body(work.resolved());
            if (source == null) continue;
            result.add(work);
            for (AbstractInsnNode instruction : work.resolved().method().instructions) {
                if (instruction instanceof MethodInsnNode call && !call.name.equals("<init>")) {
                    if (call.getOpcode() != Opcodes.INVOKESTATIC && source.parameter(source.receiver(call), work.self())
                            && repository.isSubtype(leaf, call.owner)) {
                        var resolved = resolve(work.resolved().owner().name, call, leaf);
                        if (resolved != null) pending.add(new ReadWork(resolved, 0));
                    } else if (call.getOpcode() == Opcodes.INVOKESTATIC) {
                        var resolved = repository.resolveMethod(call.owner, call.name, call.desc);
                        if (resolved == null || !repository.isSubtype(leaf, call.owner)) continue;
                        int slot = 0;
                        List<SourceValue> arguments = source.arguments(call);
                        Type[] types = Type.getArgumentTypes(call.desc);
                        for (int index = 0; index < arguments.size(); index++) {
                            if (source.parameter(arguments.get(index), work.self())) pending.add(new ReadWork(resolved, slot));
                            slot += types[index].getSize();
                        }
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private Set<FieldId> numericFields(Data source, SourceValue value, String leaf, int self,
                                       Set<String> methods, Set<SourceValue> visited) {
        return numericFields(source, value, leaf, self, methods, visited, false);
    }

    private Set<FieldId> numericFields(Data source, SourceValue value, String leaf, int self,
                                       Set<String> methods, Set<SourceValue> visited, boolean direct) {
        if (value == null || !visited.add(value)) return Set.of();
        Set<FieldId> result = new HashSet<>();
        for (AbstractInsnNode producer : value.insns) {
            if (producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
                List<SourceValue> inputs = source.inputs.getOrDefault(field, List.of());
                if (inputs.size() == 1 && source.parameter(inputs.get(0), self)) result.add(field(field));
            } else if (producer instanceof MethodInsnNode call) {
                if (supported(Type.getReturnType(call.desc)) && source.parameter(source.receiver(call), self)
                        && repository.isSubtype(leaf, call.owner)) {
                    var resolved = resolve(source.analysis.method().owner(), call, leaf);
                    if (resolved == null) continue;
                    String id = resolved.owner().name + '#' + call.name + call.desc;
                    if (!methods.add(id)) continue;
                    Data callee = body(resolved);
                    if (callee != null) for (AbstractInsnNode instruction : resolved.method().instructions)
                        if (instruction.getOpcode() == Opcodes.IRETURN || instruction.getOpcode() == Opcodes.FRETURN
                                || instruction.getOpcode() == Opcodes.DRETURN)
                            for (SourceValue returned : callee.inputs.getOrDefault(instruction, List.of()))
                                result.addAll(numericFields(callee, returned, leaf, 0, methods, visited, direct));
                    methods.remove(id);
                } else if (!direct && numericTransform(call)) {
                    for (SourceValue input : source.inputs.getOrDefault(producer, List.of()))
                        result.addAll(numericFields(source, input, leaf, self, methods, visited, false));
                }
            } else if ((!direct && numericOperation(producer)) || simpleCopy(producer)
                    || producer.getOpcode() >= Opcodes.I2L && producer.getOpcode() <= Opcodes.I2S) {
                for (SourceValue input : source.inputs.getOrDefault(producer, List.of()))
                    result.addAll(numericFields(source, input, leaf, self, methods, visited, direct));
            }
        }
        return result;
    }

    private static boolean numericOperation(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return opcode >= Opcodes.IADD && opcode <= Opcodes.LXOR || opcode >= Opcodes.I2L && opcode <= Opcodes.DCMPG;
    }

    private static boolean numericTransform(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC
                && Set.of("java/lang/Math", "java/lang/StrictMath", "net/minecraft/util/Mth").contains(call.owner)
                && Set.of("min", "max", "clamp", "m_14036_", "m_14008_", "m_14045_").contains(call.name);
    }

    private FormulaClassRepository.ResolvedMethod nativeMethod(String leaf, String descriptor, String... names) {
        var resolved = callback(leaf, descriptor, List.of(names));
        return resolved != null && resolved.owner().name.equals(AOE) ? resolved : null;
    }

    private FormulaClassRepository.ResolvedMethod callback(String leaf, String descriptor, List<String> names) {
        Set<String> visited = new HashSet<>();
        for (String current = leaf; current != null && visited.add(current); ) {
            ClassNode owner = repository.classNode(current);
            if (owner == null) return null;
            for (MethodNode method : owner.methods)
                if (names.contains(method.name) && method.desc.equals(descriptor)
                        && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0)
                    return new FormulaClassRepository.ResolvedMethod(owner, method);
            current = owner.superName;
        }
        return null;
    }

    private boolean nativeAoe(String leaf, FormulaAutomaticSites.Kind kind,
                              FormulaClassRepository.ResolvedMethod setter, Set<FieldId> writes) {
        var tick = nativeMethod(leaf, "()V", "tick", "m_8119_");
        if (tick == null) return false;
        Data source = body(tick);
        if (source == null) return false;
        if (kind == FormulaAutomaticSites.Kind.DURATION) {
            for (AbstractInsnNode instruction : tick.method().instructions)
                if (instruction instanceof MethodInsnNode call && Set.of("discard", "m_146870_").contains(call.name)
                        && call.desc.equals("()V") && source.parameter(source.receiver(call), 0)
                        && serverControlled(source, call) && fieldControls(source, leaf, writes, call, false)) return true;
            return false;
        }
        var hits = nativeMethod(leaf, "()V", "checkHits");
        if (hits == null) return false;
        boolean dispatch = false;
        for (AbstractInsnNode instruction : tick.method().instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals("checkHits") && call.desc.equals("()V")
                    && source.parameter(source.receiver(call), 0) && serverControlled(source, call)) {
                if (kind == FormulaAutomaticSites.Kind.HIT_INTERVAL)
                    return fieldControls(source, leaf, writes, call, true);
                dispatch = true;
            }
        if (!dispatch || kind != FormulaAutomaticSites.Kind.RADIUS) return false;
        var getter = nativeMethod(leaf, "()F", "getRadius");
        var dimensions = nativeMethod(leaf, "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;",
                "getDimensions", "m_6972_");
        var synced = nativeMethod(leaf, "(Lnet/minecraft/network/syncher/EntityDataAccessor;)V", "onSyncedDataUpdated", "m_7350_");
        var refresh = nativeMethod(leaf, "()V", "refreshDimensions", "m_6210_");
        if (getter == null || dimensions == null || synced == null || refresh == null || !radiusStorage(setter, getter)) return false;
        Data size = body(dimensions);
        if (size == null || !hasSelfCall(size, "getRadius", "()F")) return false;
        Data query = body(hits);
        if (query == null) return false;
        boolean realQuery = false;
        boolean realRadius = false;
        for (AbstractInsnNode instruction : hits.method().instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (Set.of("getEntitiesOfClass", "m_45976_").contains(call.name)
                    && call.desc.equals("(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;")
                    && repository.isSubtype(call.owner, LEVEL) && serverControlled(query, call)
                    && containsCall(query, query.argument(call, 1), Set.of("getBoundingBox", "m_20191_"),
                    "()Lnet/minecraft/world/phys/AABB;", true, new HashSet<>())) realQuery = true;
            if (call.name.equals("applyEffect") && call.desc.equals("(L" + LIVING + ";)V")
                    && query.parameter(query.receiver(call), 0)) {
                for (AbstractInsnNode branch : hits.method().instructions)
                    if (branch instanceof JumpInsnNode jump && conditional(jump)
                            && selects(query.method, jump, call)) {
                        List<SourceValue> inputs = query.inputs.getOrDefault(jump, List.of());
                        boolean radius = inputs.stream().anyMatch(value -> containsCall(query, value, Set.of("getRadius"),
                                "()F", true, new HashSet<>()));
                        boolean distance = inputs.stream().anyMatch(value -> containsCall(query, value,
                                Set.of("distanceToSqr", "m_20280_"), "(L" + ENTITY + ";)D", false, new HashSet<>()));
                        if (radius && distance) realRadius = true;
                    }
            }
        }
        return realQuery && realRadius;
    }

    private boolean fieldControls(Data source, String leaf, Set<FieldId> fields, MethodInsnNode target, boolean remainder) {
        if (fields.isEmpty()) return false;
        for (AbstractInsnNode instruction : source.method.instructions)
            if (instruction instanceof JumpInsnNode jump && conditional(jump) && selects(source.method, jump, target)) {
                List<SourceValue> values = source.inputs.getOrDefault(jump, List.of());
                boolean read = values.stream().anyMatch(value -> numericFields(source, value, leaf, 0, new HashSet<>(),
                        java.util.Collections.newSetFromMap(new IdentityHashMap<>())).stream().anyMatch(fields::contains));
                if (read && (!remainder || values.stream().anyMatch(value -> containsOpcode(source, value, Opcodes.IREM, new HashSet<>()))))
                    return true;
            }
        return false;
    }

    private boolean serverControlled(Data source, MethodInsnNode target) {
        for (AbstractInsnNode instruction : source.method.instructions)
            if (instruction instanceof JumpInsnNode jump && conditional(jump) && selects(source.method, jump, target))
                for (SourceValue value : source.inputs.getOrDefault(jump, List.of()))
                    if (clientSide(source, value, new HashSet<>())) return true;
        return false;
    }

    private boolean clientSide(Data source, SourceValue value, Set<SourceValue> visited) {
        if (value == null || !visited.add(value)) return false;
        for (AbstractInsnNode producer : value.insns) {
            if (producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.desc.equals("Z")
                    && Set.of("isClientSide", "f_46443_").contains(field.name) && repository.isSubtype(field.owner, LEVEL)) return true;
            if (simpleCopy(producer)) for (SourceValue input : source.inputs.getOrDefault(producer, List.of()))
                if (clientSide(source, input, visited)) return true;
        }
        return false;
    }

    private boolean radiusStorage(FormulaClassRepository.ResolvedMethod setter, FormulaClassRepository.ResolvedMethod getter) {
        Data write = body(setter);
        Data read = body(getter);
        if (write == null || read == null) return false;
        Set<FieldId> keys = new HashSet<>();
        for (AbstractInsnNode instruction : setter.method().instructions)
            if (instruction instanceof MethodInsnNode call && dataAccess(call, true)
                    && entityData(write, write.receiver(call)) && radiusInput(write, write.argument(call, 1), new HashSet<>())) {
                Origin origin = write.origin(write.argument(call, 0));
                if (origin.producer() instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) keys.add(field(field));
            }
        if (keys.isEmpty()) return false;
        for (AbstractInsnNode instruction : getter.method().instructions)
            if (instruction.getOpcode() == Opcodes.FRETURN)
                for (SourceValue value : read.inputs.getOrDefault(instruction, List.of()))
                    if (radiusOutput(read, value, keys, new HashSet<>())) return true;
        return false;
    }

    private static boolean dataAccess(MethodInsnNode call, boolean write) {
        return call.owner.equals("net/minecraft/network/syncher/SynchedEntityData")
                && (write ? Set.of("set", "m_135381_").contains(call.name)
                && call.desc.equals("(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V")
                : Set.of("get", "m_135370_").contains(call.name)
                && call.desc.equals("(Lnet/minecraft/network/syncher/EntityDataAccessor;)Ljava/lang/Object;"));
    }

    private static boolean entityData(Data source, SourceValue value) {
        Origin origin = source.origin(value);
        return origin.producer() instanceof MethodInsnNode call
                && Set.of("getEntityData", "m_20088_").contains(call.name)
                && call.desc.equals("()Lnet/minecraft/network/syncher/SynchedEntityData;")
                && source.parameter(source.receiver(call), 0);
    }

    private static boolean radiusInput(Data source, SourceValue value, Set<SourceValue> visited) {
        if (value == null || !visited.add(value)) return false;
        Origin origin = source.origin(value);
        if (origin.producer() instanceof MethodInsnNode call) {
            if (call.owner.equals("java/lang/Float") && call.name.equals("valueOf") && call.desc.equals("(F)Ljava/lang/Float;"))
                return radiusInput(source, source.argument(call, 0), visited);
            if (call.owner.equals("net/minecraft/util/Mth") && Set.of("clamp", "m_14036_").contains(call.name)
                    && call.desc.equals("(FFF)F") && source.parameter(source.argument(call, 0), 1)) {
                Number min = constant(source, source.origin(source.argument(call, 1)).producer(), new HashSet<>());
                Number max = constant(source, source.origin(source.argument(call, 2)).producer(), new HashSet<>());
                return min != null && min.floatValue() == 0 && max != null && max.floatValue() == 32;
            }
        }
        return false;
    }

    private boolean radiusOutput(Data source, SourceValue value, Set<FieldId> keys, Set<SourceValue> visited) {
        if (value == null || !visited.add(value)) return false;
        Origin origin = source.origin(value);
        if (origin.producer() instanceof MethodInsnNode call) {
            if (dataAccess(call, false) && entityData(source, source.receiver(call))) {
                Origin key = source.origin(source.argument(call, 0));
                return key.producer() instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC && keys.contains(field(field));
            }
            if (call.owner.equals("java/lang/Float") && call.name.equals("floatValue") && call.desc.equals("()F"))
                return radiusOutput(source, source.receiver(call), keys, visited);
        }
        return false;
    }

    private static boolean hasSelfCall(Data source, String name, String descriptor) {
        for (AbstractInsnNode instruction : source.method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals(name) && call.desc.equals(descriptor)
                    && source.parameter(source.receiver(call), 0)) return true;
        return false;
    }

    private static boolean containsCall(Data source, SourceValue value, Set<String> names, String descriptor,
                                        boolean self, Set<SourceValue> visited) {
        if (value == null || !visited.add(value)) return false;
        for (AbstractInsnNode producer : value.insns) {
            if (producer instanceof MethodInsnNode call && names.contains(call.name) && call.desc.equals(descriptor)
                    && (!self || source.parameter(source.receiver(call), 0))) return true;
            if (simpleCopy(producer) || numericOperation(producer) || producer instanceof MethodInsnNode)
                for (SourceValue input : source.inputs.getOrDefault(producer, List.of()))
                    if (containsCall(source, input, names, descriptor, self, visited)) return true;
        }
        return false;
    }

    private static boolean containsOpcode(Data source, SourceValue value, int opcode, Set<SourceValue> visited) {
        if (value == null || !visited.add(value)) return false;
        for (AbstractInsnNode producer : value.insns) {
            if (producer.getOpcode() == opcode) return true;
            if (simpleCopy(producer) || numericOperation(producer))
                for (SourceValue input : source.inputs.getOrDefault(producer, List.of()))
                    if (containsOpcode(source, input, opcode, visited)) return true;
        }
        return false;
    }

    private static boolean conditional(JumpInsnNode jump) {
        return jump.getOpcode() != Opcodes.GOTO && jump.getOpcode() != Opcodes.JSR;
    }

    private static boolean selects(MethodNode method, JumpInsnNode branch, AbstractInsnNode target) {
        return reachable(method, branch.label, target, branch) != reachable(method, branch.getNext(), target, branch);
    }

    private static boolean reachable(MethodNode method, AbstractInsnNode start, AbstractInsnNode target, AbstractInsnNode stop) {
        ArrayDeque<AbstractInsnNode> pending = new ArrayDeque<>();
        Set<AbstractInsnNode> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (start != null) pending.add(start);
        while (!pending.isEmpty()) {
            AbstractInsnNode instruction = pending.removeFirst();
            if (instruction == stop || !visited.add(instruction)) continue;
            if (instruction == target) return true;
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) continue;
            if (instruction instanceof JumpInsnNode jump) {
                if (method.instructions.indexOf(jump.label) > method.instructions.indexOf(instruction)) pending.add(jump.label);
                if (!conditional(jump)) continue;
            } else if (instruction instanceof TableSwitchInsnNode table) {
                if (method.instructions.indexOf(table.dflt) > method.instructions.indexOf(instruction)) pending.add(table.dflt);
                for (LabelNode label : table.labels)
                    if (method.instructions.indexOf(label) > method.instructions.indexOf(instruction)) pending.add(label);
                continue;
            } else if (instruction instanceof LookupSwitchInsnNode table) {
                if (method.instructions.indexOf(table.dflt) > method.instructions.indexOf(instruction)) pending.add(table.dflt);
                for (LabelNode label : table.labels)
                    if (method.instructions.indexOf(label) > method.instructions.indexOf(instruction)) pending.add(label);
                continue;
            }
            if (instruction.getNext() != null) pending.add(instruction.getNext());
        }
        return false;
    }

    private FieldId field(FieldInsnNode field) {
        var resolved = repository.resolveField(field.owner, field.name, field.desc);
        return new FieldId(resolved == null ? field.owner : resolved.owner().name, field.name, field.desc);
    }

    private FormulaClassRepository.ResolvedMethod resolve(String caller, MethodInsnNode call, String receiver) {
        return repository.resolveInvocation(caller, call, receiver).target();
    }

    private boolean existingGetter(Work work, Data body, SourceValue value, Set<AbstractInsnNode> visited) {
        return existingGetter(work, body, value, visited, false);
    }

    private boolean existingGetter(Work work, Data body, SourceValue value, Set<AbstractInsnNode> visited, boolean powerInput) {
        if (value == null) return false;
        for (AbstractInsnNode producer : value.insns) {
            if (!visited.add(producer)) continue;
            if (producer instanceof MethodInsnNode call && supported(Type.getReturnType(call.desc)) && family.contains(call.owner)) {
                if (powerInput && call.name.equals("getSpellPower") && call.desc.equals("(IL" + ENTITY + ";)F")) continue;
                var resolved = resolve(work.resolved().owner().name, call, spell);
                if (resolved != null && (resolved.owner().name.equals(spell)
                        || (resolved.method().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0)) {
                    try {
                        var id = new MethodId(resolved.owner().name, resolved.method().name, resolved.method().desc);
                        if (extractableGetters.computeIfAbsent(id, ignored ->
                                AutomaticFormulaExtractor.analyze(resolved.owner(), resolved.method(), repository, spell).supported())) return true;
                    } catch (RuntimeException failure) {
                        diagnostics.add("无法确认既有数值入口，跳过潜在重复消费：" + work.id().key() + ": " + failure.getMessage());
                        return true;
                    }
                }
            }
            for (SourceValue input : body.inputs.getOrDefault(producer, List.of()))
                if (existingGetter(work, body, input, visited, powerInput)) return true;
        }
        return false;
    }

    private boolean unsafeUse(Candidate candidate) {
        Data body = candidate.data();
        for (var entry : body.inputs.entrySet()) {
            AbstractInsnNode consumer = entry.getKey();
            if (simpleCopy(consumer) || consumer.getOpcode() == Opcodes.POP || consumer.getOpcode() == Opcodes.POP2) continue;
            if (consumer instanceof MethodInsnNode call) {
                List<SourceValue> arguments = body.arguments(call);
                for (int index = 0; index < arguments.size(); index++) {
                    if (!body.origin(arguments.get(index)).equals(candidate.origin())) continue;
                    int argument = index;
                    if (candidate.uses().stream().noneMatch(use -> use.call() == call && use.argument() == argument)) return true;
                }
            } else for (SourceValue value : entry.getValue())
                if (body.origin(value).equals(candidate.origin())) return true;
        }
        return false;
    }

    private static FormulaAutomaticSites.Kind mergedKind(List<Use> uses) {
        Set<FormulaAutomaticSites.Kind> kinds = new HashSet<>();
        uses.forEach(use -> kinds.add(use.kind()));
        if (kinds.size() == 1) return kinds.iterator().next();
        if (kinds.equals(Set.of(FormulaAutomaticSites.Kind.SUMMON_DURATION, FormulaAutomaticSites.Kind.RECAST_WINDOW)))
            return FormulaAutomaticSites.Kind.SUMMON_DURATION;
        if (kinds.equals(Set.of(FormulaAutomaticSites.Kind.DURATION, FormulaAutomaticSites.Kind.RECAST_WINDOW)))
            return FormulaAutomaticSites.Kind.DURATION;
        return null;
    }

    static String alias(FormulaAutomaticSites.Kind kind) {
        return switch (kind) {
            case DURATION -> "duration_ticks";
            case SUMMON_DURATION -> "summon_duration_ticks";
            case RECAST_WINDOW -> "recast_window_ticks";
            case RADIUS -> "radius";
            case DAMAGE -> "damage";
            case EVENT_DAMAGE -> "effect_damage";
            case EVENT_HEALING -> "effect_healing";
            case HEALING -> "healing";
            case DAMAGE_RATIO -> "effect_damage_ratio";
            case HIT_INTERVAL -> "hit_interval_ticks";
            case EFFECT_LEVEL -> "effect_level";
            case ACTIVATION_DELAY -> "activation_delay_ticks";
            case MOVEMENT_SCALE -> "movement_scale";
            case MOVEMENT_X_SCALE -> "movement_x_scale";
            case MOVEMENT_Y_SCALE -> "movement_y_scale";
            case MOVEMENT_Z_SCALE -> "movement_z_scale";
            case MOVEMENT_X_OFFSET -> "movement_x_offset";
            case MOVEMENT_Y_OFFSET -> "movement_y_offset";
            case MOVEMENT_Z_OFFSET -> "movement_z_offset";
            case MOVEMENT_X -> "movement_x";
            case MOVEMENT_Y -> "movement_y";
            case MOVEMENT_Z -> "movement_z";
            case MOVEMENT_KNOCKBACK_STRENGTH -> "knockback_strength";
            case MOVEMENT_KNOCKBACK_X_DIRECTION -> "knockback_x_direction";
            case MOVEMENT_KNOCKBACK_Z_DIRECTION -> "knockback_z_direction";
            case QUERY_RADIUS -> "query_radius";
            case QUERY_X_RANGE -> "query_x_range";
            case QUERY_Y_RANGE -> "query_y_range";
            case QUERY_Z_RANGE -> "query_z_range";
        };
    }

    private static boolean supported(Type type) {
        return type != null && (type.equals(Type.INT_TYPE) || type.equals(Type.FLOAT_TYPE) || type.equals(Type.DOUBLE_TYPE));
    }

    private static FormulaNumericSites.ValueType valueType(Type type) {
        if (type.equals(Type.INT_TYPE)) return FormulaNumericSites.ValueType.INT;
        if (type.equals(Type.FLOAT_TYPE)) return FormulaNumericSites.ValueType.FLOAT;
        return FormulaNumericSites.ValueType.DOUBLE;
    }

    private static Number convert(Number value, FormulaNumericSites.ValueType type) {
        if (type == FormulaNumericSites.ValueType.INT) return Integer.valueOf(value.intValue());
        if (type == FormulaNumericSites.ValueType.FLOAT) return Float.valueOf(value.floatValue());
        return Double.valueOf(value.doubleValue());
    }

    private static String number(Number value) {
        if (value.doubleValue() == 0 && Double.doubleToRawLongBits(value.doubleValue()) < 0) return "-0.0";
        return BigDecimal.valueOf(value.doubleValue()).stripTrailingZeros().toPlainString();
    }

    private static Number constant(Data body, AbstractInsnNode producer, Set<AbstractInsnNode> visited) {
        if (producer == null || !visited.add(producer)) return null;
        if (producer instanceof LdcInsnNode literal && literal.cst instanceof Number number) return number;
        int opcode = producer.getOpcode();
        if (producer instanceof IntInsnNode literal && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return literal.operand;
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return (float) (opcode - Opcodes.FCONST_0);
        if (opcode >= Opcodes.DCONST_0 && opcode <= Opcodes.DCONST_1) return (double) (opcode - Opcodes.DCONST_0);
        List<SourceValue> inputs = body.inputs.getOrDefault(producer, List.of());
        if (inputs.size() == 1) {
            Origin origin = body.origin(inputs.get(0));
            if (origin.producer() == null) return null;
            Number value = constant(body, origin.producer(), visited);
            if (value == null) return null;
            return switch (opcode) {
                case Opcodes.INEG -> -value.intValue();
                case Opcodes.FNEG -> -value.floatValue();
                case Opcodes.DNEG -> -value.doubleValue();
                case Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> Float.valueOf(value.floatValue());
                case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> Double.valueOf(value.doubleValue());
                case Opcodes.L2I, Opcodes.F2I, Opcodes.D2I -> Integer.valueOf(value.intValue());
                case Opcodes.I2B -> Integer.valueOf((byte) value.intValue());
                case Opcodes.I2C -> Integer.valueOf((char) value.intValue());
                case Opcodes.I2S -> Integer.valueOf((short) value.intValue());
                default -> null;
            };
        }
        return null;
    }

    private static boolean copy(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return instruction instanceof VarInsnNode || opcode == Opcodes.DUP || opcode == Opcodes.DUP_X1
                || opcode == Opcodes.DUP_X2 || opcode == Opcodes.DUP2 || opcode == Opcodes.DUP2_X1
                || opcode == Opcodes.DUP2_X2 || opcode == Opcodes.SWAP || opcode == Opcodes.CHECKCAST;
    }

    private static boolean simpleCopy(AbstractInsnNode instruction) {
        return instruction instanceof VarInsnNode || instruction.getOpcode() == Opcodes.DUP
                || instruction.getOpcode() == Opcodes.CHECKCAST;
    }

    private static final class Data {
        final MethodNode method;
        final FormulaDataFlow.Analysis analysis;
        final Map<AbstractInsnNode, List<SourceValue>> inputs;
        final Map<AbstractInsnNode, List<SourceValue>> vectorArguments = new IdentityHashMap<>();

        Data(FormulaClassRepository repository, String owner, MethodNode method) throws AnalyzerException {
            this.method = method;
            analysis = FormulaDataFlow.analyze(repository, owner, method);
            if (!analysis.successful()) throw new AnalyzerException(null, analysis.diagnostics().toString());
            inputs = analysis.sourceInputs();
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode constructor && constructor.owner.equals(VECTOR)
                        && constructor.name.equals("<init>")) {
                    Origin receiver = origin(receiver(constructor));
                    if (receiver.producer() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW)
                        vectorArguments.put(allocation, List.copyOf(arguments(constructor)));
                }
        }

        Origin origin(SourceValue value) {
            var source = analysis.origin(value);
            if (source == null) return Origin.unknown();
            if (source.kind() == FormulaDataFlow.NodeKind.PARAMETER) return new Origin(null, source.parameterSlot());
            return source.instruction() == null ? Origin.unknown() : new Origin(source.instruction(), -1);
        }

        boolean parameter(SourceValue value, int slot) { return slot >= 0 && analysis.parameter(value, slot); }

        SourceValue argument(MethodInsnNode call, int index) {
            List<SourceValue> values = arguments(call);
            return index >= 0 && index < values.size() ? values.get(index) : null;
        }

        SourceValue receiver(MethodInsnNode call) {
            List<SourceValue> values = inputs.getOrDefault(call, List.of());
            return call.getOpcode() == Opcodes.INVOKESTATIC || values.isEmpty() ? null : values.get(0);
        }

        List<SourceValue> arguments(MethodInsnNode call) {
            List<SourceValue> values = inputs.getOrDefault(call, List.of());
            int offset = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
            return values.size() < offset ? List.of() : values.subList(offset, values.size());
        }
    }
}
