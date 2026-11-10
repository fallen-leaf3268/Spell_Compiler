package com.example.spell_compiler.formula;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class FormulaEffectOwnership {
    public record Effect(String registryOwner, String registryField) {
        public Effect {
            registryOwner = Objects.requireNonNull(registryOwner).replace('.', '/');
            Objects.requireNonNull(registryField);
        }
    }

    public record Uncertainty(String spellClass, FormulaExecutionGraph.MethodId caller,
                              Set<Effect> possibleEffects, boolean unbounded, String diagnostic) {
        public Uncertainty {
            spellClass = Objects.requireNonNull(spellClass).replace('.', '/');
            Objects.requireNonNull(caller);
            possibleEffects = Set.copyOf(possibleEffects);
            Objects.requireNonNull(diagnostic);
        }
    }

    public record Decision(boolean complete, List<Uncertainty> uncertainties) {
        public Decision { uncertainties = List.copyOf(uncertainties); }
    }

    private final List<Uncertainty> uncertainties = new ArrayList<>();

    void add(Uncertainty value) {
        if (!uncertainties.contains(value)) uncertainties.add(value);
    }

    public List<Uncertainty> uncertainties() { return List.copyOf(uncertainties); }

    public Decision decision(Effect effect) {
        List<Uncertainty> affected = uncertainties.stream()
                .filter(value -> value.unbounded() || value.possibleEffects().contains(effect)).toList();
        return new Decision(affected.isEmpty(), affected);
    }
}
