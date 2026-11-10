package com.example.spell_compiler.formula.rules;

import com.example.spell_compiler.formula.FormulaClassRepository;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class FormulaBindingPlan {
    @FunctionalInterface
    public interface Patch { boolean apply(String owner, ClassNode candidate); }

    public record Rule(String id, Set<String> targets, boolean baselineOnly,
                       Consumer<FormulaClassRepository> validate, Patch patch) {
        public Rule { targets = Set.copyOf(targets); }
    }

    public record Decision(String rule, boolean available, String reason) {}

    private final Map<String, List<Rule>> bindings;
    private final List<Decision> decisions;

    private FormulaBindingPlan(Map<String, List<Rule>> bindings, List<Decision> decisions) {
        var immutable = new LinkedHashMap<String, List<Rule>>();
        bindings.forEach((owner, rules) -> immutable.put(owner, List.copyOf(rules)));
        this.bindings = Map.copyOf(immutable);
        this.decisions = List.copyOf(decisions);
    }

    public static FormulaBindingPlan prepare(FormulaClassRepository repository, List<Rule> rules) {
        Map<String, List<Rule>> bindings = new LinkedHashMap<>();
        List<Decision> decisions = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        for (Rule rule : rules) {
            if (!ids.add(rule.id())) throw new IllegalArgumentException("重复的公式规则：" + rule.id());
            if (rule.baselineOnly() && !repository.baselineCoverage()) continue;
            try {
                rule.validate().accept(repository);
                for (String owner : rule.targets()) {
                    ClassNode source = repository.classNode(owner.replace('.', '/'));
                    if (source == null || !rule.patch().apply(owner, copy(source)))
                        throw new IllegalArgumentException("规则无法绑定：" + owner);
                }
                rule.targets().forEach(owner -> bindings.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(rule));
                decisions.add(new Decision(rule.id(), true, ""));
            } catch (RuntimeException | LinkageError failure) {
                decisions.add(new Decision(rule.id(), false, failure.toString()));
            }
        }
        return new FormulaBindingPlan(bindings, decisions);
    }

    public Set<String> targets() { return bindings.keySet(); }

    public List<Decision> decisions() { return decisions; }

    public List<Decision> apply(String owner, ClassNode target) {
        String normalized = owner.replace('/', '.');
        if (!target.name.equals(normalized.replace('.', '/')))
            throw new IllegalArgumentException("公式规则目标类名不匹配：" + owner);
        List<Decision> results = new ArrayList<>();
        for (Rule rule : bindings.getOrDefault(normalized, List.of())) {
            ClassNode candidate = copy(target);
            try {
                if (!rule.patch().apply(normalized, candidate))
                    throw new IllegalArgumentException("规则消费结构已改变");
                target.methods = candidate.methods;
                results.add(new Decision(rule.id(), true, ""));
            } catch (RuntimeException | LinkageError failure) {
                results.add(new Decision(rule.id(), false, failure.toString()));
            }
        }
        return List.copyOf(results);
    }

    private static ClassNode copy(ClassNode source) {
        var result = new ClassNode();
        source.accept(result);
        return result;
    }
}
