package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormulaCastSafetyTest {
    @Test
    void declaredNativeCastParametersRejectOverflowAcrossNormalLevels() throws Exception {
        var catalog = SpellFormulaCatalog.load(getClass().getClassLoader());
        for (String id : List.of("irons_spellbooks:blaze_storm", "irons_spellbooks:telekinesis")) {
            var spell = catalog.spells().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell,
                    "{\"parameters\":{\"castTime\":2147483647}}"), id);
        }
    }

    @Test
    void validDeclaredMaximumCastParametersRemainAccepted() throws Exception {
        var catalog = SpellFormulaCatalog.load(getClass().getClassLoader());
        for (String id : List.of("irons_spellbooks:blaze_storm", "irons_spellbooks:telekinesis")) {
            var spell = catalog.spells().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            int value = id.endsWith(":blaze_storm") ? Integer.MAX_VALUE - 5 : Integer.MAX_VALUE;
            var result = SpellFormulaFiles.parse(spell,
                    "{\"advanced\":{\"max_level\":1},\"parameters\":{\"castTime\":" + value + "}}");
            assertEquals(value, result.parameters().get("castTime").getAsInt(), id);
        }
    }

    @Test
    void wideningTheLevelRangeValidatesNativeCastTimeWithoutAnArbitraryLevelCeiling() {
        var spell = spell("(castTime + (int(20) * (level - int(1))))");
        assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell,
                "{\"advanced\":{\"max_level\":2147483647}}"));
    }

    @Test
    void interiorIntegerOverflowIsRejectedEvenWhenBothFinalEndpointResultsFit() {
        var spell = spell("(castTime + (level * (int(2147483647) - level)))");
        assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell,
                "{\"advanced\":{\"max_level\":2147483647}}"));
    }

    @Test
    void unchangedDefaultsAndUnrelatedEditsKeepTheirNativeBoundaryBehavior() {
        var spell = spell("(castTime + (int(20) * (level - int(1))))");
        assertDoesNotThrow(() -> SpellFormulaFiles.parse(spell, "{}"));
        assertDoesNotThrow(() -> SpellFormulaFiles.parse(spell, "{\"advanced\":{\"enabled\":false}}"));
    }

    private static SpellFormulaCatalog.Spell spell(String expression) {
        var advanced = new JsonObject();
        advanced.addProperty("max_level", 1);
        advanced.addProperty("enabled", true);
        var parameters = new JsonObject();
        parameters.addProperty("castTime", 1);
        var method = new SpellFormulaCatalog.Method("cast_time", "getCastTime", "(I)I", expression,
                expression, Map.of("level", "I", "castTime", "I"), "test.spells.NativeCastSpell");
        return new SpellFormulaCatalog.Spell("test:native_cast", "test.spells.NativeCastSpell", "test:school",
                advanced, parameters, List.of(method));
    }
}
