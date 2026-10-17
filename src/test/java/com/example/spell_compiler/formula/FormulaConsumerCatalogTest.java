package com.example.spell_compiler.formula;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaConsumerCatalogTest {
    @Test
    void nativeGettersWithoutAnEffectConsumerAreNotExportedAsEditableEffects() throws Exception {
        var catalog = FormulaCatalogGenerator.generate(Path.of("libs", "ironsspells-3.16.1.jar"));
        var removed = Set.of("irons_spellbooks:wisp#distance", "irons_spellbooks:wisp#duration_ticks",
                "irons_spellbooks:sunbeam#duration_ticks", "irons_spellbooks:gust#damage");
        for (var entry : catalog.getAsJsonArray("spells")) {
            var spell = entry.getAsJsonObject();
            for (var formula : spell.getAsJsonArray("formulas"))
                assertFalse(removed.contains(spell.get("id").getAsString() + '#'
                        + formula.getAsJsonObject().get("alias").getAsString()),
                        "A native getter without a real effect consumer must not be exported as an editable effect");
        }
    }
}
