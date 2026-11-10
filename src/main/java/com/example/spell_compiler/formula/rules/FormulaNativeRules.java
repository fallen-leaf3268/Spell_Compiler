package com.example.spell_compiler.formula.rules;

import com.example.spell_compiler.formula.*;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class FormulaNativeRules {
    private FormulaNativeRules() {}
    private static volatile java.util.Map<String, FormulaBindingPlan.Decision> runtimeDecisions = java.util.Map.of();
    private static volatile Set<String> runtimeTargets = Set.of();
    private static final Set<String> checkedTargets = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static synchronized void install(FormulaBindingPlan plan) {
        var decisions = new java.util.LinkedHashMap<String, FormulaBindingPlan.Decision>();
        plan.decisions().forEach(decision -> decisions.put(decision.rule(), decision));
        runtimeDecisions = java.util.Map.copyOf(decisions);
        runtimeTargets = plan.targets();
    }

    public static synchronized void observe(FormulaBindingPlan.Decision decision) {
        var previous = runtimeDecisions.get(decision.rule());
        if (previous != null && !previous.available() && decision.available()) return;
        var updated = new java.util.LinkedHashMap<>(runtimeDecisions);
        updated.put(decision.rule(), decision);
        runtimeDecisions = java.util.Map.copyOf(updated);
    }

    public static String unavailableReason(String dispatchKey) {
        var decisions = runtimeDecisions;
        for (String rule : FormulaNativeBindingRequirements.rulesFor(dispatchKey)) {
            var decision = decisions.get(rule);
            if (decision != null && !decision.available()) return "公式消费规则未接入：" + rule + "：" + decision.reason();
        }
        return null;
    }

    public static void initializeRuntime() {
        for (String target : runtimeTargets) {
            if (!checkedTargets.add(target)) continue;
            try { Class.forName(target, false, FormulaNativeRules.class.getClassLoader()); }
            catch (ClassNotFoundException | LinkageError failure) {
                for (var rule : RULES) if (rule.targets().contains(target))
                    observe(new FormulaBindingPlan.Decision(rule.id(), false, failure.toString()));
            }
        }
    }

    private record Family(String id, Set<String> targets, Consumer<FormulaClassRepository> validate,
                          BiConsumer<String, ClassNode> patch) {}

    private static final List<Family> FAMILIES = List.of(
            new Family("native.helper_values", FormulaSites.targetClasses(), FormulaSites::validate, FormulaSites::apply),
            new Family("native.effect_consumers", FormulaConsumerSites.targetClasses(), FormulaConsumerSites::validate, FormulaConsumerSites::apply),
            new Family("native.numeric_values", FormulaNumericSites.targetClasses(), FormulaNumericSites::validate, FormulaNumericSites::apply),
            new Family("native.entity_values", FormulaEntitySites.targetClasses(), FormulaEntitySites::validate, FormulaEntitySites::apply));

    private static final FormulaBindingPlan.Rule ACID_RANGE =
            new FormulaBindingPlan.Rule("compat.acid_orb_range", FormulaPublishedCorrections.targetClasses(), false,
                    FormulaPublishedCorrections::validate, FormulaPublishedCorrections::applyIfCompatible);

    private static final List<FormulaBindingPlan.Rule> RULES = java.util.stream.Stream.concat(FAMILIES.stream().flatMap(family ->
            family.targets().stream().sorted().map(owner -> new FormulaBindingPlan.Rule(family.id() + ':' + owner,
                    Set.of(owner), true, repository -> {}, (name, target) -> { family.patch().accept(name, target); return true; }))), java.util.stream.Stream.of(ACID_RANGE,
            new FormulaBindingPlan.Rule("compat.continuous_completion", FormulaLegacyContinuousCompatibility.targetClasses(), false,
                    FormulaLegacyContinuousCompatibility::validate, FormulaLegacyContinuousCompatibility::applyIfCompatible))).toList();

    public static FormulaBindingPlan prepare(FormulaClassRepository repository) {
        return FormulaBindingPlan.prepare(repository, RULES);
    }

    public static Set<String> baselineTargets() {
        return java.util.stream.Stream.concat(FAMILIES.stream().flatMap(family -> family.targets().stream()),
                ACID_RANGE.targets().stream()).collect(Collectors.toUnmodifiableSet());
    }

    public static void validateBaseline(FormulaClassRepository repository) {
        for (var family : FAMILIES) family.validate().accept(repository);
        ACID_RANGE.validate().accept(repository);
    }

    public static void applyBaseline(String owner, ClassNode target) {
        for (var family : FAMILIES)
            if (family.targets().contains(owner.replace('/', '.'))) family.patch().accept(owner, target);
        FormulaPublishedCorrections.apply(owner, target);
    }
}
