package com.example.spell_compiler.formula;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class FormulaNumericIndex {
    private final Map<String, FormulaNumericSites.Definition> byKey;
    private final Map<String, List<FormulaNumericSites.Definition>> byOwner;

    FormulaNumericIndex(Collection<FormulaNumericSites.Definition> definitions) {
        Map<String, FormulaNumericSites.Definition> keys = new LinkedHashMap<>();
        Map<String, LinkedHashSet<FormulaNumericSites.Definition>> owners = new LinkedHashMap<>();
        for (var definition : definitions) {
            keys.putIfAbsent(definition.dispatchKey(), definition);
            owners.computeIfAbsent(definition.spellClassName(), ignored -> new LinkedHashSet<>()).add(definition);
        }
        Map<String, List<FormulaNumericSites.Definition>> lists = new LinkedHashMap<>();
        owners.forEach((owner, values) -> lists.put(owner, List.copyOf(values)));
        byKey = Map.copyOf(keys);
        byOwner = Map.copyOf(lists);
    }

    FormulaNumericSites.Definition find(String key) { return key == null ? null : byKey.get(key); }
    List<FormulaNumericSites.Definition> definitions(String owner) { return owner == null ? List.of() : byOwner.getOrDefault(owner, List.of()); }
    Map<String, List<FormulaNumericSites.Definition>> owners() { return byOwner; }
}
