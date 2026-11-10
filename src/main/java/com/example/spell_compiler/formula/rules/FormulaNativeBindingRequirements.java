package com.example.spell_compiler.formula.rules;

import com.example.spell_compiler.formula.FormulaConsumerSites;
import com.example.spell_compiler.formula.FormulaEntitySites;
import com.example.spell_compiler.formula.FormulaNumericSites;
import com.example.spell_compiler.formula.FormulaSites;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public final class FormulaNativeBindingRequirements {
    public record Requirement(String binding, String rule) {}
    private record Family(String id, Function<String, Set<String>> owners) {}

    private static final List<Family> FAMILIES = List.of(
            new Family("native.helper_values", FormulaSites::requiredRuleOwners),
            new Family("native.effect_consumers", FormulaConsumerSites::requiredRuleOwners),
            new Family("native.numeric_values", FormulaNumericSites::requiredRuleOwners),
            new Family("native.entity_values", FormulaEntitySites::requiredRuleOwners));
    private static final List<Requirement> COMPATIBILITY = List.of(new Requirement(
            "io.redspace.ironsspellbooks.spells.nature.AcidOrbSpell#getRadius(ILnet/minecraft/world/entity/LivingEntity;)F",
            "compat.acid_orb_range"));

    private FormulaNativeBindingRequirements() {}

    public static Set<String> rulesFor(String dispatchKey) {
        if (dispatchKey == null) return Set.of();
        int separator = dispatchKey.indexOf('#');
        if (separator <= 0) return Set.of();
        String key = dispatchKey.substring(0, separator).replace('/', '.') + dispatchKey.substring(separator);
        Set<String> result = new LinkedHashSet<>();
        for (Family family : FAMILIES) for (String owner : family.owners().apply(key))
            result.add(family.id() + ':' + owner.replace('/', '.'));
        for (Requirement requirement : COMPATIBILITY) if (requirement.binding().equals(key)) result.add(requirement.rule());
        return Set.copyOf(result);
    }
}
