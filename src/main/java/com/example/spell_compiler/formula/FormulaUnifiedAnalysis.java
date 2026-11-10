package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;

public final class FormulaUnifiedAnalysis {
    private static final Map<String, FormulaExecutionGraph.EffectLink> EVENT_SOURCES = new java.util.concurrent.ConcurrentHashMap<>();
    public record Restoration(String alias, String owner, String method, int instruction, int argument,
                              FormulaBackwardSlice.Completeness completeness, String expression,
                              List<FormulaCapturedInput> inputs, List<String> diagnostics) {
        public Restoration { inputs = List.copyOf(inputs); diagnostics = List.copyOf(diagnostics); }
    }

    public record Consumption(String owner, String method, int instruction, int argument, String kind,
                              String proof, List<String> bindings, FormulaBackwardSlice.Completeness completeness,
                              String expression, List<String> diagnostics, List<String> upstreamBindings,
                              List<String> upstreamEvidence) {
        public Consumption {
            bindings = List.copyOf(bindings);
            diagnostics = List.copyOf(diagnostics);
            upstreamBindings = List.copyOf(upstreamBindings);
            upstreamEvidence = List.copyOf(upstreamEvidence);
        }
        public Consumption(String owner, String method, int instruction, int argument, String kind, String proof,
                           List<String> bindings, FormulaBackwardSlice.Completeness completeness,
                           String expression, List<String> diagnostics) {
            this(owner, method, instruction, argument, kind, proof, bindings, completeness, expression, diagnostics, List.of(), List.of());
        }
    }

    private FormulaUnifiedAnalysis() {}

    static FormulaAutomaticSites.Scan effectEvents(FormulaClassRepository repository, FormulaAutomaticSites.Scan scan,
                                                   java.util.Set<String> reserved) {
        var graph = FormulaExecutionGraph.analyze(repository, scan.spellClassName());
        var sites = new ArrayList<>(scan.sites());
        var diagnostics = new ArrayList<>(scan.diagnostics());
        var aliases = new LinkedHashSet<>(reserved);
        sites.forEach(site -> aliases.add(site.definition().alias()));
        var visited = new LinkedHashSet<String>();
        for (var effect : graph.effects()) {
            if (!effect.ownershipComplete() || !effect.spellClasses().equals(java.util.Set.of(graph.spellClass()))) continue;
            for (var method : effect.eventMethods().stream()
                    .sorted(java.util.Comparator.comparing(FormulaExecutionGraph.MethodId::key)).toList()) {
                var resolved = repository.resolveMethod(method.owner(), method.name(), method.descriptor());
                if (resolved == null || (resolved.method().access & Opcodes.ACC_STATIC) == 0
                        || Type.getArgumentTypes(method.descriptor()).length != 1) continue;
                String owner = resolved.owner().name.replace('/', '.');
                if (repository.baselineCoverage() && (FormulaConsumerSites.targetClasses().contains(owner) || FormulaSites.targetClasses().contains(owner)
                        || FormulaNumericSites.targetClasses().contains(owner) || FormulaEntitySites.targetClasses().contains(owner))) {
                    diagnostics.add("EFFECT_EVENT_LEGACY_COVERAGE: " + method.key());
                    continue;
                }
                var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
                if (!flow.successful()) continue;
                var executable = FormulaAutomaticInjector.executable(resolved.method());
                for (var instruction : flow.instructions()) {
                    if (!(instruction.instruction() instanceof MethodInsnNode call) || !flow.reachable(call)
                            || !flow.parameter(flow.receiver(call), 0)) continue;
                    for (var consumer : FormulaConsumptionModel.classify(repository, call)) {
                        if (consumer.proof() != FormulaConsumptionModel.Proof.EFFECT_EVENT) continue;
                        int sourceSlot = eventEffectInput(repository, flow, flow.arguments(call).get(consumer.argument()), call, effect, diagnostics);
                        if (sourceSlot < 0) continue;
                        int position = executable.indexOf(call);
                        if (!visited.add(method.key() + ':' + position + ':' + consumer.argument())) continue;
                        String base = consumer.kind() == FormulaAutomaticSites.Kind.EVENT_DAMAGE ? "effect_damage" : "effect_healing";
                        String alias = base;
                        for (int suffix = 2; !aliases.add(alias); suffix++) alias = base + '_' + suffix;
                        var definition = new FormulaNumericSites.Definition(scan.spellClassName(), alias, 0f,
                                FormulaNumericSites.ValueType.FLOAT, -Math.sqrt(Float.MAX_VALUE), Math.sqrt(Float.MAX_VALUE));
                        var site = new FormulaAutomaticSites.Site(definition, owner, method.name(), method.descriptor(),
                                position, FormulaAutomaticInjector.fingerprint(resolved.method()), -1, -1, -1,
                                consumer.kind(), "native_value", consumer.argument(),
                                FormulaAutomaticSites.ContextMode.ENTITY, sourceSlot);
                        EVENT_SOURCES.put(eventSourceKey(site), effect);
                        sites.add(site);
                    }
                }
            }
        }
        return new FormulaAutomaticSites.Scan(scan.spellClassName(), sites, diagnostics, scan.captures(), scan.scopes());
    }

    private static int eventEffectInput(FormulaClassRepository repository, FormulaDataFlow.Analysis flow,
                                           FormulaDataFlow.Value value, MethodInsnNode consumer,
                                           FormulaExecutionGraph.EffectLink effect, List<String> diagnostics) {
        var pending = new java.util.ArrayDeque<FormulaDataFlow.Node>(value.sources());
        var visited = new java.util.HashSet<FormulaDataFlow.Node>();
        boolean found = false;
        FormulaDataFlow.Value receiver = null;
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (!visited.add(node)) continue;
            if (node.instruction() instanceof MethodInsnNode amplifier
                    && amplifier.owner.equals("net/minecraft/world/effect/MobEffectInstance")
                    && FormulaMinecraftNames.method(amplifier.name).equals("getAmplifier") && amplifier.desc.equals("()I")) {
                var instance = flow.registryOrigin(flow.receiver(amplifier));
                if (instance == null || !(instance.instruction() instanceof MethodInsnNode get)
                        || !FormulaMinecraftNames.method(get.name).equals("getEffect")
                        || !get.desc.equals("(Lnet/minecraft/world/effect/MobEffect;)Lnet/minecraft/world/effect/MobEffectInstance;")
                        || !repository.isSubtype(get.owner, "net/minecraft/world/entity/LivingEntity")) return -1;
                var holder = flow.registryOrigin(flow.arguments(get).get(0));
                if (holder == null || !(holder.instruction() instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETSTATIC || !field.owner.equals(effect.registryOwner())
                        || !field.name.equals(effect.registryField())) return -1;
                if (!flow.resultDominates(amplifier, consumer)) {
                    diagnostics.add("EFFECT_EVENT_UNGUARDED_MERGE: " + flow.method().key() + ':' + flow.instruction(consumer).index());
                    return -1;
                }
                FormulaDataFlow.Value selected = flow.receiver(get);
                if (flow.origin(selected) == null || receiver != null && !flow.origin(receiver).equals(flow.origin(selected))) return -1;
                receiver = selected;
                found = true;
            }
            for (var input : flow.dependencies(node)) pending.addAll(input.sources());
        }
        if (!found || receiver == null) return -1;
        var frame = flow.frameBefore(consumer);
        if (frame == null) return -1;
        var candidates = new ArrayList<Integer>();
        for (int slot = 0; slot < frame.locals().size(); slot++) {
            var local = frame.locals().get(slot);
            if (local != null && local.type() != null && local.type().getSort() == Type.OBJECT
                    && repository.isSubtype(local.type().getInternalName(), "net/minecraft/world/entity/LivingEntity")
                    && flow.origin(receiver).equals(flow.origin(local))) candidates.add(slot);
        }
        if (candidates.size() == 1) return candidates.get(0);
        if (!candidates.isEmpty()) return -1;
        var origin = flow.origin(receiver);
        if (origin.instruction() instanceof MethodInsnNode entity
                && FormulaMinecraftNames.method(entity.name).equals("getEntity")
                && entity.desc.equals("()Lnet/minecraft/world/entity/LivingEntity;")
                && flow.parameter(flow.receiver(entity), 0)) return 0;
        diagnostics.add("EFFECT_EVENT_SOURCE_UNAVAILABLE: " + flow.method().key());
        return -1;
    }

    static boolean verifiedEventContext(FormulaClassRepository repository, String owner, org.objectweb.asm.tree.MethodNode method,
                                        FormulaAutomaticSites.Site site) {
        if (site.contextMode() != FormulaAutomaticSites.ContextMode.ENTITY
                || site.kind() != FormulaAutomaticSites.Kind.EVENT_DAMAGE && site.kind() != FormulaAutomaticSites.Kind.EVENT_HEALING
                || !owner.replace('/', '.').equals(site.owner()) || !method.name.equals(site.methodName())
                || !method.desc.equals(site.methodDescriptor()) || !FormulaAutomaticInjector.fingerprint(method).equals(site.methodFingerprint())) return false;
        var effect = EVENT_SOURCES.get(eventSourceKey(site));
        if (effect == null) return false;
        var code = FormulaAutomaticInjector.executable(method);
        if (site.producerIndex() < 0 || site.producerIndex() >= code.size()
                || !(code.get(site.producerIndex()) instanceof MethodInsnNode consumer)) return false;
        boolean actualConsumer = FormulaConsumptionModel.classify(repository, consumer).stream().anyMatch(point ->
                point.proof() == FormulaConsumptionModel.Proof.EFFECT_EVENT && point.kind() == site.kind() && point.argument() == site.argumentIndex());
        if (!actualConsumer) return false;
        var flow = FormulaDataFlow.analyze(repository, owner, method);
        return flow.successful() && flow.reachable(consumer) && site.argumentIndex() < flow.arguments(consumer).size()
                && eventEffectInput(repository, flow, flow.arguments(consumer).get(site.argumentIndex()), consumer, effect, new ArrayList<>()) == site.contextSlot();
    }

    private static String eventSourceKey(FormulaAutomaticSites.Site site) {
        return site.definition().spellClassName() + '#' + site.owner() + '#' + site.methodName() + site.methodDescriptor()
                + ':' + site.producerIndex() + ':' + site.methodFingerprint() + ':' + site.argumentIndex() + ':' + site.kind() + ':' + site.contextSlot();
    }

    static Map<String, String> inputTypes(FormulaAutomaticSites.Site site) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("native_value", "D");
        boolean recovered = site.contextMode() != FormulaAutomaticSites.ContextMode.DIRECT;
        if (recovered || site.levelSlot() >= 0) result.put("level", "I");
        if (recovered || site.spellSlot() >= 0) {
            result.put("power_multiplier", "D");
            if (recovered || site.casterSlot() >= 0) {
                result.put("spell_power", "D");
                result.put("school_spell_power", "D");
            }
        }
        site.inputs().forEach(input -> result.put(input.name(), input.descriptor()));
        return Map.copyOf(result);
    }

    static List<Consumption> consumptions(FormulaClassRepository repository, FormulaExecutionGraph.Analysis execution,
                                         FormulaAutomaticSites.Scan scan) {
        return consumptions(repository, execution, scan, java.util.Set.of());
    }

    static List<Consumption> consumptions(FormulaClassRepository repository, FormulaExecutionGraph.Analysis execution,
                                         FormulaAutomaticSites.Scan scan,
                                         java.util.Set<FormulaDataFlow.MethodId> exposedMethods) {
        var methods = new LinkedHashSet<>(execution.methods());
        for (var site : scan.sites()) methods.add(new FormulaExecutionGraph.MethodId(site.owner(), site.methodName(), site.methodDescriptor()));
        List<Consumption> result = new ArrayList<>();
        for (var method : methods.stream().sorted(java.util.Comparator.comparing(FormulaExecutionGraph.MethodId::key)).toList()) {
            if (method.owner().startsWith("net/minecraft/") || method.owner().startsWith("java/")) continue;
            var resolved = repository.resolveMethod(method.owner(), method.name(), method.descriptor());
            if (resolved == null) continue;
            var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
            if (!flow.successful()) continue;
            var executable = FormulaAutomaticInjector.executable(resolved.method());
            for (var operation : flow.instructions()) {
                if (!(operation.instruction() instanceof MethodInsnNode call) || !flow.reachable(call)) continue;
                for (var consumer : FormulaConsumptionModel.classify(repository, call)) {
                    int position = executable.indexOf(call);
                    var bindings = scan.sites().stream().filter(site -> site.owner().replace('.', '/').equals(resolved.owner().name)
                                    && site.methodName().equals(method.name()) && site.methodDescriptor().equals(method.descriptor())
                                    && site.producerIndex() == position && site.argumentIndex() == consumer.argument())
                            .map(site -> site.definition().alias()).sorted().toList();
                    var slice = FormulaBackwardSlice.argument(repository, flow, call, consumer.argument(), Map.of(), exposedMethods);
                    var upstream = upstreamBinding(repository, flow, call, consumer.argument(), scan.spellClassName(), exposedMethods, executable);
                    result.add(new Consumption(resolved.owner().name.replace('/', '.'), method.name() + method.descriptor(),
                            position, consumer.argument(), consumer.kind().name().toLowerCase(java.util.Locale.ROOT),
                            consumer.proof().name().toLowerCase(java.util.Locale.ROOT), bindings, slice.completeness(),
                            slice.expression(), slice.diagnostics(), upstream == null ? List.of() : List.of(upstream.method()),
                            upstream == null ? List.of() : List.of(upstream.evidence())));
                }
            }
        }
        return List.copyOf(result);
    }

    private record UpstreamBinding(String method, String evidence) {}

    private static UpstreamBinding upstreamBinding(FormulaClassRepository repository, FormulaDataFlow.Analysis flow,
                                                   MethodInsnNode consumer, int argument, String spellClass,
                                                   java.util.Set<FormulaDataFlow.MethodId> exposedMethods,
                                                   List<org.objectweb.asm.tree.AbstractInsnNode> executable) {
        if (exposedMethods.isEmpty()) return null;
        var origin = flow.registryOrigin(flow.arguments(consumer).get(argument));
        if (origin == null || !(origin.instruction() instanceof MethodInsnNode getter)
                || !flow.resultDominates(getter, consumer)) return null;
        String receiver = null;
        if (getter.getOpcode() != Opcodes.INVOKESTATIC) {
            String spell = spellClass.replace('.', '/');
            var current = repository.resolveMethod(flow.method().owner(), flow.method().name(), flow.method().descriptor());
            if (current != null && (current.method().access & Opcodes.ACC_STATIC) == 0
                    && repository.isSubtype(spell, flow.method().owner()) && flow.parameter(flow.receiver(getter), 0))
                receiver = spell;
            else {
                var object = flow.registryOrigin(flow.receiver(getter));
                if (object != null && object.instruction() instanceof org.objectweb.asm.tree.TypeInsnNode allocation
                        && allocation.getOpcode() == Opcodes.NEW && allocation.desc.equals(spell)) receiver = spell;
            }
            if (receiver == null) return null;
        }
        var resolution = repository.resolveInvocation(flow.method().owner(), getter, receiver);
        var target = resolution.target();
        if (!resolution.complete() || target == null) return null;
        var method = new FormulaDataFlow.MethodId(target.owner().name, target.method().name, target.method().desc);
        if (!exposedMethods.contains(method)) return null;
        return new UpstreamBinding(method.key(), "UNIQUE_VALUE_ORIGIN_AND_NORMAL_COMPLETION_DOMINANCE: "
                + flow.method().key() + '@' + executable.indexOf(getter) + " -> "
                + flow.method().key() + '@' + executable.indexOf(consumer) + ':' + argument);
    }

    static FormulaAutomaticSites.Scan finish(FormulaClassRepository repository, FormulaAutomaticSites.Scan scan) {
        return finish(repository, scan, java.util.Set.of());
    }

    static FormulaAutomaticSites.Scan finish(FormulaClassRepository repository, FormulaAutomaticSites.Scan scan,
                                              java.util.Set<FormulaDataFlow.MethodId> exposedMethods) {
        List<FormulaAutomaticSites.Site> sites = new ArrayList<>();
        List<Restoration> restorations = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>(scan.diagnostics());
        var identities = new FormulaBindingIdentities(repository, scan.spellClassName());
        for (var site : scan.sites()) {
            FormulaBackwardSlice.Result result = restore(repository, site, exposedMethods);
            String alias = identities.alias(site);
            result = identities.inputs(site, result);
            var original = site.definition();
            var definition = new FormulaNumericSites.Definition(original.spellClassName(), alias, original.defaultValue(),
                    original.valueType(), original.minimum(), original.maximum());
            String expression = site.defaultExpression();
            var inputs = site.inputs();
            String nativeExpression = site.nativeExpression();
            if (result.completeness() != FormulaBackwardSlice.Completeness.UNSUPPORTED) {
                nativeExpression = "double(" + result.expression() + ')';
                expression = ReadableFormula.display(nativeExpression);
                inputs = result.inputs();
            }
            site = new FormulaAutomaticSites.Site(definition, site.owner(), site.methodName(), site.methodDescriptor(),
                    site.producerIndex(), site.methodFingerprint(), site.casterSlot(), site.levelSlot(), site.spellSlot(),
                    site.kind(), expression, site.argumentIndex(), site.contextMode(), site.contextSlot(), inputs, nativeExpression);
            sites.add(site);
            restorations.add(new Restoration(site.definition().alias(), site.owner(), site.methodName() + site.methodDescriptor(),
                    site.producerIndex(), site.argumentIndex(), result.completeness(), result.expression(), result.inputs(), result.diagnostics()));
            for (String message : result.diagnostics()) diagnostics.add(site.definition().alias() + ": " + message);
        }
        return new FormulaAutomaticSites.Scan(scan.spellClassName(), sites, diagnostics, scan.captures(), scan.scopes(), restorations);
    }

    private static FormulaBackwardSlice.Result restore(FormulaClassRepository repository, FormulaAutomaticSites.Site site,
                                                        java.util.Set<FormulaDataFlow.MethodId> exposedMethods) {
        var resolved = repository.resolveMethod(site.owner().replace('.', '/'), site.methodName(), site.methodDescriptor());
        if (resolved == null) return unsupported("UNAVAILABLE_METHOD: " + site.owner() + '#' + site.methodName());
        var method = resolved.method();
        var graph = FormulaDataFlow.analyze(repository, resolved.owner().name, method);
        if (!graph.successful()) return unsupported("IR_FAILURE: " + graph.diagnostics());
        var executable = FormulaAutomaticInjector.executable(method);
        if (site.producerIndex() < 0 || site.producerIndex() >= executable.size()) return unsupported("UNAVAILABLE_CONSUMPTION_POINT");
        var instruction = executable.get(site.producerIndex());
        Map<Integer, String> bindings = new LinkedHashMap<>();
        if (site.levelSlot() >= 0) bindings.put(site.levelSlot(), "level");
        FormulaBackwardSlice.Result result;
        if (site.argumentIndex() >= 0 && instruction instanceof MethodInsnNode call)
            result = FormulaBackwardSlice.argument(repository, graph, call, site.argumentIndex(), bindings, exposedMethods);
        else {
            var value = graph.instruction(instruction);
            if (value == null || value.results().size() != 1) return unsupported("NO_UNIQUE_NUMERIC_RESULT");
            result = FormulaBackwardSlice.value(repository, graph, value.results().get(0), instruction, bindings, exposedMethods);
        }
        if (result.expression() != null && result.expression().length() > 8100)
            return unsupported("EXPRESSION_BOUND: " + result.expression().length());
        return result;
    }

    private static FormulaBackwardSlice.Result unsupported(String message) {
        return new FormulaBackwardSlice.Result("native_value", List.of(), FormulaBackwardSlice.Completeness.UNSUPPORTED,
                List.of(message), false);
    }
}
