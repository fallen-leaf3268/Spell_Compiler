package com.example.spell_compiler.formula;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class FormulaCatalogEntries {
    private FormulaCatalogEntries() {}

    static JsonObject numeric(FormulaNumericSites.Definition site) {
        JsonObject formula = new JsonObject();
        formula.addProperty("alias", site.alias());
        formula.addProperty("name", site.helperMethod());
        formula.addProperty("descriptor", site.descriptor());
        formula.addProperty("editable", true);
        String original = java.math.BigDecimal.valueOf(site.defaultValue().doubleValue()).stripTrailingZeros().toPlainString();
        formula.addProperty("strictExpression", "double(" + original + ')');
        formula.addProperty("readableExpression", original);
        JsonObject variables = new JsonObject();
        if (FormulaEntitySites.find(site.dispatchKey()) == null) variables.addProperty("native_value", "D");
        variables.addProperty("level", "I");
        variables.addProperty("spell_power", "D");
        variables.addProperty("school_spell_power", "D");
        variables.addProperty("power_multiplier", "D");
        formula.add("variables", variables);
        formula.addProperty("declaringClass", site.declaringClass());
        return formula;
    }

    static JsonObject method(String alias, ClassNode declaringOwner, MethodNode method,
                                          AutomaticFormulaExtractor.Formula extracted, String castType) {
        JsonObject formula = new JsonObject();
        formula.addProperty("alias", alias);
        formula.addProperty("name", method.name);
        formula.addProperty("descriptor", method.desc);
        String key = method.name + method.desc;
        boolean editable = !key.equals("getCastTime(I)I") && !key.equals("getSpellCooldown()I");
        if (key.equals("getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I"))
            editable = castType.equals("LONG") || castType.equals("CONTINUOUS");
        formula.addProperty("editable", editable);
        formula.addProperty("strictExpression", extracted.expression());
        formula.addProperty("readableExpression", extracted.readableExpression());
        JsonObject variables = new JsonObject();
        extracted.variables().forEach((name, variable) -> variables.addProperty(name, variable.type().getDescriptor()));
        formula.add("variables", variables);
        formula.addProperty("declaringClass", declaringOwner.name.replace('/', '.'));
        return formula;
    }

    static JsonObject automatic(FormulaAutomaticSites.Site site) {
        JsonObject formula = numeric(site.definition());
        formula.addProperty("strictExpression", site.nativeExpression());
        formula.addProperty("readableExpression", site.defaultExpression());
        JsonObject inputs = new JsonObject();
        FormulaUnifiedAnalysis.inputTypes(site).forEach(inputs::addProperty);
        formula.add("variables", inputs);
        formula.addProperty("consumptionFingerprint", FormulaBindingIdentities.identity(site)
                + (site.inputs().isEmpty() ? "" : ':' + FormulaAutomaticSites.captureLayout(site)));
        return formula;
    }

    static JsonObject execution(FormulaClassRepository repository, String spellClass,
                                FormulaExecutionGraph.Analysis graph, FormulaAutomaticSites.Scan automatic,
                                Set<FormulaDataFlow.MethodId> exposedMethods) {
        JsonObject execution = new JsonObject();
        execution.addProperty("analysis", graph.diagnostics().isEmpty() && graph.unresolvedCalls().isEmpty()
                ? "recognized_roots_analyzed" : "partial_static_graph");
        execution.addProperty("analysisScope", "recognized_roots_only");
        execution.addProperty("methodAnalysis", "unified_control_and_data_flow");
        execution.addProperty("methods", graph.methods().size());
        execution.addProperty("edges", graph.edges().size());
        execution.addProperty("knownConsumptionCountingUnit", "reachable_instruction");
        execution.add("unresolvedCalls", unresolvedCalls(graph));
        execution.addProperty("synchronousHelpers",
                FormulaExecutionGraph.index(repository).synchronousHelpers(spellClass).methods().size());
        execution.add("effects", effects(graph));
        JsonArray entities = new JsonArray();
        graph.entities().stream().sorted().forEach(entities::add);
        execution.add("entities", entities);
        JsonArray diagnostics = new JsonArray();
        graph.diagnostics().forEach(diagnostics::add);
        automatic.diagnostics().forEach(diagnostics::add);
        execution.add("diagnostics", diagnostics);
        execution.add("restorations", restorations(repository, automatic));
        execution.add("consumptions", consumptions(repository, graph, automatic, exposedMethods));
        return execution;
    }

    private static JsonArray unresolvedCalls(FormulaExecutionGraph.Analysis graph) {
        JsonArray unresolvedCalls = new JsonArray();
        for (var unresolved : graph.unresolvedCalls()) {
            JsonObject call = new JsonObject();
            call.addProperty("caller", unresolved.caller().key());
            call.addProperty("instruction", unresolved.instruction());
            call.addProperty("symbolic", unresolved.symbolic().key());
            call.addProperty("candidateSetComplete", unresolved.candidateSetComplete());
            call.addProperty("reason", unresolved.reason());
            JsonArray candidates = new JsonArray();
            unresolved.candidateTargets().stream().map(FormulaExecutionGraph.MethodId::key).sorted().forEach(candidates::add);
            call.add("candidateTargets", candidates);
            unresolvedCalls.add(call);
        }
        return unresolvedCalls;
    }

    private static JsonArray effects(FormulaExecutionGraph.Analysis graph) {
        JsonArray effects = new JsonArray();
        for (var effect : graph.effects()) {
            JsonObject linked = new JsonObject();
            linked.addProperty("registryOwner", effect.registryOwner());
            linked.addProperty("registryField", effect.registryField());
            linked.addProperty("effectClass", effect.effectClass());
            linked.addProperty("eventMethods", effect.eventMethods().size());
            linked.addProperty("sharingSpells", effect.spellClasses().size());
            linked.addProperty("ownershipComplete", effect.ownershipComplete());
            effects.add(linked);
        }
        return effects;
    }

    private static JsonArray restorations(FormulaClassRepository repository, FormulaAutomaticSites.Scan automatic) {
        JsonArray restorations = new JsonArray();
        for (var restored : automatic.restorations()) {
            JsonObject detail = new JsonObject();
            detail.addProperty("alias", restored.alias());
            detail.addProperty("owner", restored.owner());
            detail.addProperty("method", restored.method());
            detail.addProperty("instruction", restored.instruction());
            detail.addProperty("argument", restored.argument());
            detail.addProperty("status", restored.completeness().name().toLowerCase(Locale.ROOT));
            detail.addProperty("expression", restored.expression());
            JsonArray capturedInputs = new JsonArray();
            var source = repository.resolveMethod(restored.owner().replace('.', '/'),
                    restored.method().substring(0, restored.method().indexOf('(')),
                    restored.method().substring(restored.method().indexOf('(')));
            var instructions = source == null ? List.<AbstractInsnNode>of()
                    : FormulaAutomaticInjector.executable(source.method());
            for (var input : restored.inputs()) {
                JsonObject captured = new JsonObject();
                captured.addProperty("name", input.name());
                captured.addProperty("type", input.descriptor());
                captured.addProperty("producer", input.producerIndex());
                captured.addProperty("local", input.localSlot());
                captured.addProperty("nativePower", input.nativePower());
                captured.addProperty("powerStageUnknown", input.powerUnknown());
                if (input.producerIndex() >= 0 && input.producerIndex() < instructions.size()) {
                    var producer = instructions.get(input.producerIndex());
                    captured.addProperty("opcode", producer.getOpcode());
                    if (producer instanceof MethodInsnNode call)
                        captured.addProperty("source", call.owner + '#' + call.name + call.desc);
                    else if (producer instanceof FieldInsnNode field)
                        captured.addProperty("source", field.owner + '#' + field.name + ':' + field.desc);
                }
                capturedInputs.add(captured);
            }
            detail.add("inputs", capturedInputs);
            JsonArray reasons = new JsonArray();
            restored.diagnostics().forEach(reasons::add);
            detail.add("diagnostics", reasons);
            restorations.add(detail);
        }
        return restorations;
    }

    private static JsonArray consumptions(FormulaClassRepository repository, FormulaExecutionGraph.Analysis graph,
                                               FormulaAutomaticSites.Scan automatic, Set<FormulaDataFlow.MethodId> exposedMethods) {
        JsonArray consumptions = new JsonArray();
        for (var consumer : FormulaUnifiedAnalysis.consumptions(repository, graph, automatic, exposedMethods)) {
            JsonObject detail = new JsonObject();
            detail.addProperty("owner", consumer.owner());
            detail.addProperty("method", consumer.method());
            detail.addProperty("instruction", consumer.instruction());
            detail.addProperty("argument", consumer.argument());
            detail.addProperty("kind", consumer.kind());
            detail.addProperty("proof", consumer.proof());
            detail.addProperty("lowering", consumer.completeness().name().toLowerCase(Locale.ROOT));
            detail.addProperty("expression", consumer.expression());
            JsonArray linked = new JsonArray();
            consumer.bindings().forEach(linked::add);
            detail.add("directAutomaticBindings", linked);
            JsonArray upstream = new JsonArray();
            consumer.upstreamBindings().forEach(upstream::add);
            detail.add("upstreamMethodBindings", upstream);
            JsonArray evidence = new JsonArray();
            consumer.upstreamEvidence().forEach(evidence::add);
            detail.add("upstreamEvidence", evidence);
            detail.addProperty("bindingStatus", !consumer.bindings().isEmpty() ? "bound_direct"
                    : !consumer.upstreamBindings().isEmpty() ? "bound_upstream" : "unresolved");
            JsonArray reasons = new JsonArray();
            consumer.diagnostics().forEach(reasons::add);
            detail.add("diagnostics", reasons);
            consumptions.add(detail);
        }
        return consumptions;
    }
}
