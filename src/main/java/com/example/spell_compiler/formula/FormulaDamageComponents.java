package com.example.spell_compiler.formula;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FormulaDamageComponents {
    private record Trace(Set<String> aliases, Set<String> methods, Set<String> evidence) {
        Trace() { this(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>()); }
    }

    private record Terminal(FormulaAutomaticSites.Site site, Trace trace) {}
    private enum ControlUse { NONE, UNRESOLVED, PROVEN }
    private record EffectSource(String registryOwner, String registryField, FormulaInterproceduralFlow.ValueRef amplifier,
                                List<String> aliases) {}
    private final FormulaClassRepository repository;
    private final FormulaAutomaticSites.Scan scan;
    private final Map<String, JsonObject> formulas = new LinkedHashMap<>();
    private final Map<String, String> methodAliases = new LinkedHashMap<>();
    private final Map<String, FormulaDataFlow.Analysis> flows = new LinkedHashMap<>();
    private final Map<String, List<AbstractInsnNode>> instructions = new LinkedHashMap<>();
    private final List<FormulaInterproceduralFlow.State> states = new ArrayList<>();
    private final List<EffectSource> effectSources = new ArrayList<>();
    private final Map<String, List<EffectSource>> effectCallbacks = new LinkedHashMap<>();
    private final Map<String, Map<Integer, FormulaInterproceduralFlow.ValueRef>> callbackArguments = new LinkedHashMap<>();
    private final Map<String, Set<String>> independentEvidence = new LinkedHashMap<>();
    private final Map<String, Set<String>> unresolvedEvidence = new LinkedHashMap<>();
    private final List<FormulaSites.BindingPoint> manualBindings;

    private FormulaDamageComponents(FormulaClassRepository repository, FormulaAutomaticSites.Scan scan, JsonArray formulas) {
        this.repository = repository;
        this.scan = scan;
        this.manualBindings = FormulaSites.bindingPoints(repository, scan.spellClassName());
        for (var value : formulas) {
            JsonObject formula = value.getAsJsonObject();
            String alias = formula.get("alias").getAsString();
            this.formulas.put(alias, formula);
            methodAliases.put(formula.get("declaringClass").getAsString().replace('.', '/') + '#'
                    + key(formula), alias);
        }
        for (var capture : scan.captures()) {
            var flow = flow(capture.owner(), capture.methodName(), capture.methodDescriptor());
            if (flow == null) continue;
            var executable = instructions.get(flow.method().key());
            if (capture.producerIndex() < 0 || capture.producerIndex() >= executable.size()
                    || !(executable.get(capture.producerIndex()) instanceof MethodInsnNode publication)) continue;
            var state = FormulaInterproceduralFlow.inspectPublishedObject(repository, flow, publication);
            if (state.object() != null) states.add(state);
            if ((capture.mode() == FormulaEntityFlow.CaptureMode.EFFECT_SOURCE
                    || capture.mode() == FormulaEntityFlow.CaptureMode.EFFECT_SCOPED) && state.object() != null) {
                var context = FormulaInterproceduralFlow.root(flow);
                for (var instruction : flow.instructions()) {
                    if (!(instruction.instruction() instanceof MethodInsnNode call) || !call.name.equals("<init>")) continue;
                    if (FormulaConsumptionModel.classify(repository, call).stream()
                            .noneMatch(consumer -> consumer.kind() == FormulaAutomaticSites.Kind.EFFECT_LEVEL)) continue;
                    var receiver = FormulaInterproceduralFlow.object(repository,
                            FormulaInterproceduralFlow.value(context, flow.receiver(call))).exact();
                    if (state.object().equals(receiver)) addEffectSource(capture.registryOwner(), capture.registryField(), flow, call);
                }
            }
        }
    }

    static List<SpellFormulaCatalog.DamageComponent> analyze(FormulaClassRepository repository,
            FormulaExecutionGraph.Analysis execution, FormulaAutomaticSites.Scan scan, JsonArray formulas) {
        return new FormulaDamageComponents(repository, scan, formulas).analyze(execution);
    }

    private List<SpellFormulaCatalog.DamageComponent> analyze(FormulaExecutionGraph.Analysis execution) {
        discoverEffectSources(execution);
        Set<FormulaExecutionGraph.MethodId> callbackOwners = new LinkedHashSet<>(execution.methods());
        scan.captures().forEach(capture -> callbackOwners.add(new FormulaExecutionGraph.MethodId(capture.owner(), capture.methodName(), capture.methodDescriptor())));
        for (var method : callbackOwners) {
            var flow = flow(method.owner(), method.name(), method.descriptor());
            if (flow == null) continue;
            var context = FormulaInterproceduralFlow.root(flow);
            for (var instruction : flow.instructions()) {
                if (!(instruction.instruction() instanceof MethodInsnNode call) || !flow.reachable(call)) continue;
                var target = repository.resolveMethod(call.owner, call.name, call.desc);
                if (target == null || (target.method().access & Opcodes.ACC_PRIVATE) == 0 || call.name.startsWith("<")) continue;
                var callee = invocation(context, call);
                if (callee != null) callbackArguments.merge(callee.analysis().method().key(), callee.parameterBindings(),
                        (previous, next) -> previous.equals(next) ? previous : Map.of());
            }
            for (var callback : FormulaExecutionGraph.index(repository).callbacks(method)) {
                var implementation = repository.resolveMethod(callback.implementation().owner(), callback.implementation().name(), callback.implementation().descriptor());
                if (implementation == null) continue;
                Map<Integer, FormulaInterproceduralFlow.ValueRef> bindings = new LinkedHashMap<>();
                int slot = 0;
                if ((implementation.method().access & Opcodes.ACC_STATIC) == 0)
                    bindings.put(slot++, FormulaInterproceduralFlow.value(context, callback.implementationReceiver()));
                Type[] types = Type.getArgumentTypes(implementation.method().desc);
                for (int index = 0; index < Math.min(types.length, callback.capturedArguments().size()); index++) {
                    bindings.put(slot, FormulaInterproceduralFlow.value(context, callback.capturedArguments().get(index)));
                    slot += types[index].getSize();
                }
                if (!bindings.containsValue(null)) callbackArguments.merge(callback.implementation().key(), bindings,
                        (previous, next) -> previous.equals(next) ? previous : Map.of());
            }
        }
        for (var effect : execution.effects()) {
            if (!effect.ownershipComplete() || !effect.spellClasses().equals(Set.of(execution.spellClass()))) continue;
            for (var edge : execution.edges()) {
                if (edge.kind() != FormulaExecutionGraph.EdgeKind.EFFECT_CALLBACK || !effect.applications().contains(edge.from())
                        || !edge.to().owner().equals(effect.effectClass().replace('.', '/'))
                        || !FormulaMinecraftNames.method(edge.to().name()).equals("applyEffectTick")
                        || !edge.to().descriptor().equals("(Lnet/minecraft/world/entity/LivingEntity;I)V")) continue;
                effectCallbacks.put(edge.to().key(), effectSources.stream().filter(source -> source.registryOwner().equals(effect.registryOwner())
                        && source.registryField().equals(effect.registryField())).toList());
            }
        }
        List<Terminal> terminals = new ArrayList<>();
        Set<String> terminalAliases = new LinkedHashSet<>();
        for (var site : scan.sites()) {
            if (!damage(site.kind()) || site.argumentIndex() < 0) continue;
            var flow = flow(site.owner(), site.methodName(), site.methodDescriptor());
            if (flow == null) continue;
            var executable = instructions.get(flow.method().key());
            if (site.producerIndex() >= executable.size()
                    || !(executable.get(site.producerIndex()) instanceof MethodInsnNode call)) continue;
            if (FormulaConsumptionModel.classify(repository, call).stream().noneMatch(consumer -> damage(consumer.kind())
                    && consumer.argument() == site.argumentIndex()
                    && consumer.proof() != FormulaConsumptionModel.Proof.EFFECT_PUBLICATION)) continue;
            Trace trace = argument(flow, call, site.argumentIndex());
            trace.aliases().remove(site.definition().alias());
            trace.evidence().add("TERMINAL_ARGUMENT: " + flow.method().key() + '@' + site.producerIndex() + ':' + site.argumentIndex());
            terminals.add(new Terminal(site, trace));
            terminalAliases.add(site.definition().alias());
        }
        if (terminals.isEmpty()) return List.of();
        Map<String, Trace> returns = new LinkedHashMap<>();
        for (var entry : formulas.entrySet()) {
            JsonObject formula = entry.getValue();
            var flow = flow(formula.get("declaringClass").getAsString(), formula.get("name").getAsString(),
                    formula.get("descriptor").getAsString());
            if (flow != null) returns.put(entry.getKey(), returns(FormulaInterproceduralFlow.root(flow)));
        }
        Set<String> candidates = new LinkedHashSet<>();
        terminals.forEach(terminal -> candidates.addAll(terminal.trace().aliases()));
        for (var entry : returns.entrySet()) {
            if (!FormulaDisplayControls.onlyDescription(repository, scan.spellClassName(), formulas.get(entry.getKey()))) continue;
            Set<String> helpers = new LinkedHashSet<>(entry.getValue().methods());
            helpers.removeAll(methodAliases.keySet());
            helpers.removeIf(method -> method.startsWith("java/") || method.startsWith("net/minecraft/")
                    || method.startsWith("io/redspace/ironsspellbooks/api/"));
            for (Terminal terminal : terminals) {
                Set<String> shared = new LinkedHashSet<>(helpers);
                shared.retainAll(terminal.trace().methods());
                if (!shared.isEmpty()) {
                    terminal.trace().aliases().add(entry.getKey());
                    terminal.trace().evidence().add("PREVIEW_SHARED_NUMERIC_HELPER: " + entry.getKey() + " -> " + String.join(",", shared.stream().sorted().toList()));
                    candidates.add(entry.getKey());
                }
            }
        }
        candidates.removeAll(terminalAliases);
        Set<String> independent = independentUses(execution);
        for (Terminal terminal : terminals) for (String alias : terminal.trace().aliases()) {
            terminal.trace().evidence().addAll(independentEvidence.getOrDefault(alias, Set.of()));
            terminal.trace().evidence().addAll(unresolvedEvidence.getOrDefault(alias, Set.of()));
        }
        var attributeControls = new FormulaEffectAttributeControls.Query(repository, scan.spellClassName());
        for (var effect : execution.effects()) if (FormulaAttributeBridgeProof.hasNativeModifiers(repository, effect)
                && !attributeControls.fullyControlsEffect(effect.registryOwner(), effect.registryField()))
            for (EffectSource source : effectSources) {
                if (!source.registryOwner().equals(effect.registryOwner()) || !source.registryField().equals(effect.registryField())) continue;
                Trace trace = new Trace();
                trace.aliases().addAll(source.aliases());
                trace(source.amplifier(), trace, new IdentityHashMap<>(), new HashSet<>(), 0);
                independent.addAll(trace.aliases());
            }
        boolean changed;
        do {
            changed = false;
            for (var entry : returns.entrySet()) {
                if (!candidates.contains(entry.getKey()) || independent.contains(entry.getKey()))
                    changed |= independent.addAll(entry.getValue().aliases());
            }
        } while (changed);
        candidates.removeAll(independent);
        for (String alias : candidates) {
            formulas.get(alias).addProperty("editable", false);
            if (unresolvedEvidence.containsKey(alias)) {
                var destinations = terminals.stream().filter(terminal -> terminal.trace().aliases().contains(alias))
                        .map(terminal -> terminal.site().definition().alias()).distinct().sorted().toList();
                formulas.get(alias).addProperty("readOnlyReason", "该上游还参与未解析的条件调用，无法证明其为独立非伤害功能；原生条件和调用保留，请修改最终伤害组件："
                        + String.join(", ", destinations));
            }
        }
        List<SpellFormulaCatalog.DamageComponent> result = new ArrayList<>();
        for (Terminal terminal : terminals) {
            var site = terminal.site();
            List<String> upstream = terminal.trace().aliases().stream().filter(candidates::contains).sorted().toList();
            Set<String> previewMethods = new LinkedHashSet<>(upstream.stream().map(formulas::get)
                    .filter(formula -> !formula.get("name").getAsString().startsWith("numeric$"))
                    .map(FormulaDamageComponents::key).toList());
            manualBindings.stream().filter(point -> point.replacesReturn() && upstream.contains(point.alias()))
                    .map(point -> point.owner() + '#' + point.method() + point.descriptor()).forEach(previewMethods::add);
            String alias = site.definition().alias();
            result.add(new SpellFormulaCatalog.DamageComponent(alias, alias, List.of(key(formulas.get(alias))),
                    upstream, previewMethods.stream().sorted().toList(), terminal.trace().evidence().stream().sorted().toList()));
        }
        for (var effect : execution.effects()) {
            if (!attributeControls.fullyControlsEffect(effect.registryOwner(), effect.registryField())) continue;
            List<String> attributes = attributeControls.controlledAliases(effect.registryOwner(), effect.registryField()).stream().sorted().toList();
            if (attributes.isEmpty()) continue;
            Set<String> shared = new LinkedHashSet<>();
            for (EffectSource source : effectSources) {
                if (!source.registryOwner().equals(effect.registryOwner()) || !source.registryField().equals(effect.registryField())) continue;
                Trace trace = new Trace();
                trace.aliases().addAll(source.aliases());
                trace(source.amplifier(), trace, new IdentityHashMap<>(), new HashSet<>(), 0);
                shared.addAll(trace.aliases());
            }
            shared.retainAll(candidates);
            for (String alias : shared) {
                Set<String> destinations = new LinkedHashSet<>(attributes);
                result.stream().filter(component -> component.upstreamAliases().contains(alias))
                        .map(SpellFormulaCatalog.DamageComponent::alias).forEach(destinations::add);
                formulas.get(alias).addProperty("readOnlyReason", "该共享效果数值已细分为独立的最终伤害和效果属性组件，请分别修改："
                        + String.join(", ", destinations.stream().sorted().toList()));
            }
        }
        return List.copyOf(result);
    }

    private Set<String> independentUses(FormulaExecutionGraph.Analysis execution) {
        Set<String> result = new LinkedHashSet<>();
        for (var site : scan.sites()) {
            if (damage(site.kind()) || site.kind() == FormulaAutomaticSites.Kind.DAMAGE_RATIO
                    || site.kind() == FormulaAutomaticSites.Kind.EFFECT_LEVEL) continue;
            var flow = flow(site.owner(), site.methodName(), site.methodDescriptor());
            if (flow == null) continue;
            var executable = instructions.get(flow.method().key());
            if (site.producerIndex() < 0 || site.producerIndex() >= executable.size()) continue;
            var instruction = executable.get(site.producerIndex());
            if (site.argumentIndex() >= 0 && instruction instanceof MethodInsnNode call)
                result.addAll(argument(flow, call, site.argumentIndex()).aliases());
            else if (!flow.instruction(instruction).results().isEmpty())
                result.addAll(value(flow, flow.instruction(instruction).results().get(0)).aliases());
        }
        Set<FormulaExecutionGraph.MethodId> methods = new LinkedHashSet<>(execution.methods());
        scan.sites().forEach(site -> methods.add(new FormulaExecutionGraph.MethodId(site.owner(), site.methodName(), site.methodDescriptor())));
        for (var method : methods) {
            var flow = flow(method.owner(), method.name(), method.descriptor());
            if (flow == null) continue;
            result.addAll(independentControlUses(flow));
            for (var instruction : flow.instructions()) {
                if (!(instruction.instruction() instanceof MethodInsnNode call) || !flow.reachable(call)) continue;
                var consumers = FormulaConsumptionModel.classify(repository, call);
                for (var consumer : consumers)
                    if (!damage(consumer.kind()) && consumer.kind() != FormulaAutomaticSites.Kind.EFFECT_LEVEL)
                        result.addAll(argument(flow, call, consumer.argument()).aliases());
                if (call.owner.startsWith("net/minecraft/world/phys/") || call.owner.equals("net/minecraft/core/BlockPos")) {
                    Type[] arguments = Type.getArgumentTypes(call.desc);
                    for (int index = 0; index < arguments.length; index++)
                        if (numeric(arguments[index])) result.addAll(argument(flow, call, index).aliases());
                }
            }
        }
        return result;
    }

    private Set<String> independentControlUses(FormulaDataFlow.Analysis flow) {
        JsonObject declaration = new JsonObject();
        declaration.addProperty("declaringClass", flow.method().owner());
        declaration.addProperty("name", flow.method().name());
        declaration.addProperty("descriptor", flow.method().descriptor());
        if (FormulaDisplayControls.onlyDescription(repository, scan.spellClassName(), declaration)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        Map<Integer, Set<Integer>> controlledPoints = new LinkedHashMap<>();
        Map<Integer, Map<String, Set<Integer>>> conditionAliases = new LinkedHashMap<>();
        for (var branch : flow.instructions()) {
            var operation = branch.instruction();
            if (!flow.reachable(operation) || !(operation instanceof JumpInsnNode && operation.getOpcode() != Opcodes.GOTO
                    && operation.getOpcode() != Opcodes.JSR || operation instanceof TableSwitchInsnNode || operation instanceof LookupSwitchInsnNode)) continue;
            var targets = flow.successors(operation).stream().filter(edge -> edge.kind() == FormulaDataFlow.EdgeKind.NORMAL)
                    .map(FormulaDataFlow.Edge::to).distinct().toList();
            if (targets.size() < 2) continue;
            Set<String> aliases = new LinkedHashSet<>();
            branch.operands().forEach(operand -> aliases.addAll(value(flow, operand).aliases()));
            Map<String, Set<Integer>> origins = new LinkedHashMap<>();
            aliases.forEach(alias -> origins.put(alias, new LinkedHashSet<>(Set.of(branch.index()))));
            conditionAliases.put(branch.index(), origins);
            Map<Integer, Integer> reached = new LinkedHashMap<>();
            for (int target : targets) {
                Set<Integer> visited = new HashSet<>();
                var pending = new ArrayDeque<Integer>();
                pending.add(target);
                while (!pending.isEmpty()) {
                    int point = pending.removeFirst();
                    if (point == branch.index() || !visited.add(point)) continue;
                    reached.merge(point, 1, Integer::sum);
                    for (var edge : flow.successors(flow.instructions().get(point).instruction()))
                        if (edge.kind() == FormulaDataFlow.EdgeKind.NORMAL) pending.add(edge.to());
                }
            }
            Set<Integer> points = new LinkedHashSet<>();
            reached.forEach((point, count) -> { if (count != targets.size()) points.add(point); });
            controlledPoints.put(branch.index(), points);
        }
        boolean changed;
        do {
            changed = false;
            for (var branch : controlledPoints.entrySet()) for (int point : branch.getValue()) {
                var dependent = conditionAliases.get(point);
                if (dependent == null) continue;
                for (var alias : conditionAliases.get(branch.getKey()).entrySet())
                    changed |= dependent.computeIfAbsent(alias.getKey(), ignored -> new LinkedHashSet<>()).addAll(alias.getValue());
            }
        } while (changed);
        var context = FormulaInterproceduralFlow.root(flow);
        Map<Integer, ControlUse> uses = new LinkedHashMap<>();
        for (var branch : controlledPoints.entrySet()) {
            var aliases = conditionAliases.get(branch.getKey());
            if (aliases.isEmpty()) continue;
            for (int point : branch.getValue()) {
                var controlled = flow.instructions().get(point);
                var use = uses.computeIfAbsent(point, ignored -> uncoveredEffect(context, controlled, new HashSet<>(), 0));
                if (use == ControlUse.NONE) continue;
                for (var alias : aliases.entrySet()) {
                    if (use == ControlUse.PROVEN) result.add(alias.getKey());
                    (use == ControlUse.PROVEN ? independentEvidence : unresolvedEvidence)
                            .computeIfAbsent(alias.getKey(), ignored -> new LinkedHashSet<>()).add((use == ControlUse.PROVEN
                            ? "INDEPENDENT_CONTROL_USE: " : "UNRESOLVED_CONTROL_USE: ")
                            + alias.getKey() + " " + flow.method().key() + '@' + alias.getValue().stream().sorted().toList()
                            + " -> branch " + branch.getKey() + " -> " + controlled.index()
                            + (controlled.instruction() instanceof MethodInsnNode call ? " " + call.owner + '#' + call.name + call.desc
                            : " opcode=" + controlled.instruction().getOpcode()));
                }
            }
        }
        return result;
    }

    private ControlUse uncoveredEffect(FormulaInterproceduralFlow.Context context, FormulaDataFlow.Instruction instruction,
                                       Set<String> visiting, int depth) {
        var flow = context.analysis();
        int opcode = instruction.instruction().getOpcode();
        if (instruction.field() != null && instruction.field().write())
            return instruction.field().isStatic() || externalReference(context, instruction.field().receiver())
                    ? ControlUse.PROVEN : ControlUse.UNRESOLVED;
        if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE)
            return !instruction.operands().isEmpty() && externalReference(context, instruction.operands().get(0))
                    ? ControlUse.PROVEN : ControlUse.UNRESOLVED;
        if (opcode == Opcodes.ATHROW || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) return ControlUse.UNRESOLVED;
        if (instruction.call() == null) return ControlUse.NONE;
        if (!(instruction.instruction() instanceof MethodInsnNode call) || call.name.startsWith("<")) return ControlUse.UNRESOLVED;
        var consumers = FormulaConsumptionModel.classify(repository, call);
        if (!consumers.isEmpty()) {
            if (consumers.stream().anyMatch(consumer -> !damage(consumer.kind()))) return ControlUse.PROVEN;
            var executable = instructions.computeIfAbsent(flow.method().key(), ignored -> flow.instructions().stream()
                    .map(FormulaDataFlow.Instruction::instruction).filter(operation -> operation.getOpcode() >= 0).toList());
            int point = executable.indexOf(call);
            return consumers.stream().anyMatch(consumer -> scan.sites().stream().noneMatch(site -> sameMethod(site, flow)
                    && damage(site.kind()) && site.argumentIndex() == consumer.argument()
                    && site.producerIndex() == point)) ? ControlUse.UNRESOLVED : ControlUse.NONE;
        }
        if (numericIntrinsic(call)) return ControlUse.NONE;
        if (depth >= 8) return ControlUse.UNRESOLVED;
        var callee = invocation(context, call);
        if (callee == null) {
            if (Type.getReturnType(call.desc).getSort() != Type.VOID) return ControlUse.UNRESOLVED;
            var resolved = repository.resolveMethod(call.owner, call.name, call.desc);
            var body = resolved == null ? null : flow(resolved.owner().name, resolved.method().name, resolved.method().desc);
            if (body == null) return ControlUse.UNRESOLVED;
            Map<Integer, FormulaInterproceduralFlow.ValueRef> bindings = new LinkedHashMap<>();
            int slot = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
            if (slot == 1) bindings.put(0, FormulaInterproceduralFlow.value(context, flow.receiver(call)));
            var types = Type.getArgumentTypes(call.desc);
            for (int index = 0; index < types.length; index++) {
                bindings.put(slot, FormulaInterproceduralFlow.value(context, flow.arguments(call).get(index)));
                slot += types[index].getSize();
            }
            var observed = new FormulaInterproceduralFlow.Context(body, bindings, context.callPath());
            return body.instructions().stream().filter(operation -> body.reachable(operation.instruction()))
                    .anyMatch(operation -> operation.field() != null && operation.field().write()
                            && (operation.field().isStatic() || externalReference(observed, operation.field().receiver())))
                    ? ControlUse.PROVEN : ControlUse.UNRESOLVED;
        }
        if (!visiting.add(callee.analysis().method().key())) return ControlUse.UNRESOLVED;
        try {
            ControlUse result = ControlUse.NONE;
            for (var operation : callee.analysis().instructions()) if (callee.analysis().reachable(operation.instruction())) {
                var nested = uncoveredEffect(callee, operation, visiting, depth + 1);
                if (nested == ControlUse.PROVEN) return nested;
                if (nested == ControlUse.UNRESOLVED) result = nested;
            }
            return result;
        } finally { visiting.remove(callee.analysis().method().key()); }
    }

    private boolean externalReference(FormulaInterproceduralFlow.Context context, FormulaDataFlow.Value value) {
        if (value == null) return false;
        var identity = FormulaInterproceduralFlow.object(repository, FormulaInterproceduralFlow.value(context, value)).exact();
        if (identity == null || identity.kind() != FormulaInterproceduralFlow.IdentityKind.PARAMETER) return false;
        var method = repository.resolveMethod(identity.method().owner(), identity.method().name(), identity.method().descriptor());
        return method != null && (identity.parameterSlot() != 0 || (method.method().access & Opcodes.ACC_STATIC) != 0);
    }

    private Trace returns(FormulaInterproceduralFlow.Context context) {
        Trace trace = new Trace();
        for (var instruction : context.analysis().instructions()) {
            int opcode = instruction.instruction().getOpcode();
            if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.DRETURN && !instruction.operands().isEmpty())
                trace(FormulaInterproceduralFlow.value(context, instruction.operands().get(0)), trace,
                        new IdentityHashMap<>(), new HashSet<>(), 0);
        }
        return trace;
    }

    private Trace argument(FormulaDataFlow.Analysis flow, MethodInsnNode call, int argument) {
        Trace result = value(flow, flow.arguments(call).get(argument));
        int point = -2;
        for (var site : scan.sites()) if (sameMethod(site, flow) && site.argumentIndex() == argument) {
            if (point == -2) point = instructions.get(flow.method().key()).indexOf(call);
            if (site.producerIndex() == point) result.aliases().add(site.definition().alias());
        }
        return result;
    }

    private Trace value(FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value) {
        Trace result = new Trace();
        trace(FormulaInterproceduralFlow.value(FormulaInterproceduralFlow.root(flow), value), result,
                new IdentityHashMap<>(), new HashSet<>(), 0);
        for (var state : states) {
            var context = state.callback(flow);
            if (context != null) trace(FormulaInterproceduralFlow.value(context, value), result,
                    new IdentityHashMap<>(), new HashSet<>(), 0);
        }
        return result;
    }

    private void trace(FormulaInterproceduralFlow.ValueRef reference, Trace result,
                       Map<FormulaInterproceduralFlow.Context, Set<FormulaDataFlow.Node>> visited,
                       Set<String> calls, int depth) {
        if (reference == null || depth > 64) return;
        var context = reference.context();
        var flow = context.analysis();
        Set<FormulaDataFlow.Node> seen = visited.computeIfAbsent(context, ignored -> new HashSet<>());
        for (var node : reference.value().sources()) {
            if (!seen.add(node)) continue;
            if (node.kind() == FormulaDataFlow.NodeKind.PARAMETER) {
                argumentBinding(context, node.parameterSlot(), result);
                trace(context.parameterBindings().get(node.parameterSlot()), result, visited, calls, depth + 1);
                if (!context.parameterBindings().containsKey(node.parameterSlot()))
                    trace(callbackArguments.getOrDefault(flow.method().key(), Map.of()).get(node.parameterSlot()), result, visited, calls, depth + 1);
                if (!context.parameterBindings().containsKey(node.parameterSlot()) && node.parameterSlot() == 2)
                    for (EffectSource source : effectCallbacks.getOrDefault(flow.method().key(), List.of())) {
                        result.evidence().add("OWNED_EFFECT_CALLBACK_AMPLIFIER: " + source.registryOwner() + '#' + source.registryField());
                        result.aliases().addAll(source.aliases());
                        trace(source.amplifier(), result, visited, calls, depth + 1);
                    }
                continue;
            }
            var instruction = node.instruction();
            if (instruction == null) continue;
            var executable = instructions.computeIfAbsent(flow.method().key(), ignored -> {
                var resolved = repository.resolveMethod(flow.method().owner(), flow.method().name(), flow.method().descriptor());
                return resolved == null ? List.of() : FormulaAutomaticInjector.executable(resolved.method());
            });
            int instructionIndex = -2;
            for (var site : scan.sites()) if (sameMethod(site, flow) && site.argumentIndex() < 0) {
                if (instructionIndex == -2) instructionIndex = executable.indexOf(instruction);
                if (site.producerIndex() == instructionIndex) result.aliases().add(site.definition().alias());
            }
            for (var point : manualBindings) if (!point.replacesReturn() && point.owner().equals(flow.method().owner())
                    && point.method().equals(flow.method().name()) && point.descriptor().equals(flow.method().descriptor())) {
                if (instructionIndex == -2) instructionIndex = executable.indexOf(instruction);
                if (point.instruction() == instructionIndex) result.aliases().add(point.alias());
            }
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
                for (var state : states) {
                    var resolution = state.resolveRead(context, field);
                    if (resolution.status() == FormulaInterproceduralFlow.Status.UNPROVEN_RECEIVER
                            && context.parameterBindings().isEmpty() && flow.parameter(flow.instruction(field).field().receiver(), 0))
                        resolution = state.resolveRead(state.callback(flow), field);
                    var source = resolution.source();
                    if (source == null && resolution.status() == FormulaInterproceduralFlow.Status.ESCAPED
                            && resolution.writes().size() == 1 && resolution.writes().get(0).receiverProven())
                        source = resolution.writes().get(0).value();
                    if (source != null) {
                        result.evidence().add("PUBLISHED_FIELD_DEPENDENCY: " + field.owner + '#' + field.name + field.desc
                                + ":" + resolution.status());
                        trace(source, result, visited, calls, depth + 1);
                    }
                }
                continue;
            }
            if (instruction instanceof MethodInsnNode call) {
                if (numericIntrinsic(call)) {
                    for (var argument : flow.arguments(call))
                        trace(FormulaInterproceduralFlow.value(context, argument), result, visited, calls, depth + 1);
                    continue;
                }
                if (call.owner.equals("net/minecraft/world/effect/MobEffectInstance") && FormulaMinecraftNames.method(call.name).equals("getAmplifier") && call.desc.equals("()I")) {
                    var receiver = flow.origin(flow.receiver(call));
                    if (receiver != null && receiver.instruction() instanceof MethodInsnNode get
                            && FormulaMinecraftNames.method(get.name).equals("getEffect") && get.desc.equals("(Lnet/minecraft/world/effect/MobEffect;)Lnet/minecraft/world/effect/MobEffectInstance;")) {
                        var holder = flow.registryOrigin(flow.arguments(get).get(0));
                        if (holder != null && holder.instruction() instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC)
                            for (EffectSource source : effectSources) if (source.registryOwner().equals(field.owner) && source.registryField().equals(field.name)) {
                                result.evidence().add("OWNED_EFFECT_INSTANCE_AMPLIFIER: " + field.owner + '#' + field.name);
                                result.aliases().addAll(source.aliases());
                                trace(source.amplifier(), result, visited, calls, depth + 1);
                            }
                    }
                }
                var callee = invocation(context, call);
                if (callee == null) continue;
                String method = callee.analysis().method().key();
                for (var point : manualBindings) if (point.replacesReturn()
                        && (point.owner() + '#' + point.method() + point.descriptor()).equals(method)) {
                    result.aliases().add(point.alias());
                    result.evidence().add("VERIFIED_MANUAL_RETURN_BINDING: " + method + '@' + point.instruction());
                }
                String alias = methodAliases.get(method);
                if (alias != null) {
                    result.aliases().add(alias);
                    result.evidence().add("NUMERIC_RETURN_DATAFLOW: " + method);
                }
                result.methods().add(method);
                if (!calls.add(method)) continue;
                for (var returned : callee.analysis().instructions()) {
                    int opcode = returned.instruction().getOpcode();
                    if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.DRETURN && !returned.operands().isEmpty())
                        trace(FormulaInterproceduralFlow.value(callee, returned.operands().get(0)), result, visited, calls, depth + 1);
                }
                calls.remove(method);
                continue;
            }
            for (var dependency : flow.dependencies(node))
                trace(FormulaInterproceduralFlow.value(context, dependency), result, visited, calls, depth + 1);
        }
    }

    private void discoverEffectSources(FormulaExecutionGraph.Analysis execution) {
        for (var effect : execution.effects()) {
            if (!effect.ownershipComplete() || !effect.spellClasses().equals(Set.of(execution.spellClass()))) continue;
            for (var application : effect.applications()) {
                var flow = flow(application.owner(), application.name(), application.descriptor());
                if (flow == null) continue;
                for (var instruction : flow.instructions()) {
                    if (!(instruction.instruction() instanceof MethodInsnNode constructor) || !flow.reachable(constructor)
                            || FormulaConsumptionModel.classify(repository, constructor).stream()
                            .noneMatch(consumer -> consumer.kind() == FormulaAutomaticSites.Kind.EFFECT_LEVEL)) continue;
                    var holder = flow.origin(flow.arguments(constructor).get(0));
                    var allocation = flow.origin(flow.receiver(constructor));
                    if (holder == null || !(holder.instruction() instanceof FieldInsnNode field)
                            || field.getOpcode() != Opcodes.GETSTATIC || !field.owner.equals(effect.registryOwner())
                            || !field.name.equals(effect.registryField()) || allocation == null
                            || !(allocation.instruction() instanceof org.objectweb.asm.tree.TypeInsnNode created)
                            || created.getOpcode() != Opcodes.NEW) continue;
                    boolean published = flow.instructions().stream().anyMatch(operation -> operation.instruction() instanceof MethodInsnNode call
                            && FormulaMinecraftNames.method(call.name).equals("addEffect") && call.desc.startsWith("(Lnet/minecraft/world/effect/MobEffectInstance;")
                            && repository.isSubtype(call.owner, "net/minecraft/world/entity/LivingEntity")
                            && !flow.arguments(call).isEmpty() && allocation.equals(flow.origin(flow.arguments(call).get(0)))
                            && flow.resultDominates(constructor, call));
                    if (published) addEffectSource(effect.registryOwner(), effect.registryField(), flow, constructor);
                }
            }
        }
    }

    private void addEffectSource(String registryOwner, String registryField, FormulaDataFlow.Analysis flow, MethodInsnNode constructor) {
        var value = FormulaInterproceduralFlow.value(FormulaInterproceduralFlow.root(flow), flow.arguments(constructor).get(2));
        if (effectSources.stream().anyMatch(source -> source.registryOwner().equals(registryOwner)
                && source.registryField().equals(registryField) && source.amplifier().equals(value))) return;
        int point = instructions.get(flow.method().key()).indexOf(constructor);
        List<String> aliases = scan.sites().stream().filter(site -> sameMethod(site, flow)
                        && site.argumentIndex() == 2 && site.producerIndex() == point)
                .map(site -> site.definition().alias()).sorted().toList();
        effectSources.add(new EffectSource(registryOwner, registryField, value, aliases));
    }

    private void argumentBinding(FormulaInterproceduralFlow.Context context, int parameter, Trace result) {
        var bound = context.parameterBindings().get(parameter);
        if (bound == null || context.callPath().isEmpty()) return;
        var point = context.callPath().get(context.callPath().size() - 1);
        var caller = bound.context().analysis();
        if (!point.method().equals(caller.method()) || point.instruction() < 0 || point.instruction() >= caller.instructions().size()
                || !(caller.instructions().get(point.instruction()).instruction() instanceof MethodInsnNode call)) return;
        int slot = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        for (int index = 0; index < arguments.length; index++) {
            if (slot == parameter) {
                var executable = instructions.get(caller.method().key());
                if (executable == null) return;
                int instructionIndex = -2;
                for (var site : scan.sites()) if (sameMethod(site, caller) && site.argumentIndex() == index) {
                    if (instructionIndex == -2) instructionIndex = executable.indexOf(call);
                    if (site.producerIndex() == instructionIndex) {
                        result.aliases().add(site.definition().alias());
                        result.evidence().add("INTERMEDIATE_ARGUMENT_BINDING: " + caller.method().key() + '@' + site.producerIndex() + ':' + index);
                    }
                }
                return;
            }
            slot += arguments[index].getSize();
        }
    }

    private FormulaInterproceduralFlow.Context invocation(FormulaInterproceduralFlow.Context context, MethodInsnNode call) {
        var direct = FormulaInterproceduralFlow.invocation(repository, context, call);
        if (direct != null) return direct;
        if (call.getOpcode() == Opcodes.INVOKESTATIC) return null;
        String spell = scan.spellClassName().replace('.', '/');
        var receiver = FormulaInterproceduralFlow.value(context, context.analysis().receiver(call));
        var identity = FormulaInterproceduralFlow.object(repository, receiver).exact();
        if (identity == null || identity.kind() != FormulaInterproceduralFlow.IdentityKind.PARAMETER
                || identity.parameterSlot() != 0 || !repository.isSubtype(spell, identity.method().owner())
                || !repository.isSubtype(spell, call.owner)) return null;
        var resolution = repository.resolveInvocation(context.analysis().method().owner(), call, spell);
        if (!resolution.complete() || resolution.target() == null) return null;
        var selected = resolution.target();
        var flow = flow(selected.owner().name, selected.method().name, selected.method().desc);
        if (flow == null) return null;
        Map<Integer, FormulaInterproceduralFlow.ValueRef> bindings = new LinkedHashMap<>();
        bindings.put(0, receiver);
        int slot = 1;
        Type[] types = Type.getArgumentTypes(call.desc);
        for (int index = 0; index < types.length; index++) {
            bindings.put(slot, FormulaInterproceduralFlow.value(context, context.analysis().arguments(call).get(index)));
            slot += types[index].getSize();
        }
        var path = new ArrayList<>(context.callPath());
        path.add(new FormulaInterproceduralFlow.CallSite(context.analysis().method(), context.analysis().instruction(call).index()));
        return new FormulaInterproceduralFlow.Context(flow, bindings, path);
    }

    private FormulaDataFlow.Analysis flow(String owner, String name, String descriptor) {
        String key = owner.replace('.', '/') + '#' + name + descriptor;
        if (flows.containsKey(key)) return flows.get(key);
        var resolved = repository.resolveMethod(owner.replace('.', '/'), name, descriptor);
        if (resolved == null) { flows.put(key, null); return null; }
        var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
        flows.put(key, flow.successful() ? flow : null);
        instructions.put(flow.method().key(), FormulaAutomaticInjector.executable(resolved.method()));
        return flow.successful() ? flow : null;
    }

    private static boolean sameMethod(FormulaAutomaticSites.Site site, FormulaDataFlow.Analysis flow) {
        return site.owner().replace('.', '/').equals(flow.method().owner()) && site.methodName().equals(flow.method().name())
                && site.methodDescriptor().equals(flow.method().descriptor());
    }

    private static boolean damage(FormulaAutomaticSites.Kind kind) {
        return kind == FormulaAutomaticSites.Kind.DAMAGE || kind == FormulaAutomaticSites.Kind.EVENT_DAMAGE;
    }

    private static boolean numeric(Type type) {
        return type.getSort() >= Type.INT && type.getSort() <= Type.DOUBLE;
    }

    private static boolean numericIntrinsic(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC || !numeric(Type.getReturnType(call.desc))
                || java.util.Arrays.stream(Type.getArgumentTypes(call.desc)).anyMatch(type -> !numeric(type))) return false;
        if (call.owner.equals("java/lang/Math") || call.owner.equals("java/lang/StrictMath"))
            return Set.of("min", "max", "abs", "sqrt", "cbrt", "floor", "ceil", "round", "pow", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "exp", "log", "log10", "signum", "copySign").contains(call.name);
        return call.owner.equals("net/minecraft/util/Mth")
                && Set.of("clamp", "m_14045_", "m_14036_", "m_14008_", "floor", "ceil", "sqrt", "sin", "cos", "abs", "lerp").contains(call.name);
    }

    private static String key(JsonObject formula) {
        return formula.get("name").getAsString() + formula.get("descriptor").getAsString();
    }
}
