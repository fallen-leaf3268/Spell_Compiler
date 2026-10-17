package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.FormulaCatalogGenerator;
import com.example.spell_compiler.formula.SpellFormulaCatalog;
import com.example.spell_compiler.formula.SpellFormulaFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaUnifiedExportTest {
    private static SpellFormulaCatalog catalog;

    @BeforeAll
    static void generateNativeCatalog() throws Exception {
        catalog = SpellFormulaCatalog.parse(FormulaCatalogGenerator.generate(Path.of("libs/ironsspells-3.16.1.jar")));
    }

    @Test
    void everyDefaultExportRoundTripsWithoutInstallingOverrides() throws Exception {
        assertEquals(109, catalog.spells().size());
        Map<String, JsonElement> resources = new LinkedHashMap<>();
        for (var spell : catalog.spells()) {
            var exported = SpellFormulaFiles.preview(spell);
            assertTrue(SpellFormulaFiles.parse(spell, exported.toString()).isEmpty(), spell.id());
            String namespace = spell.id().substring(0, spell.id().indexOf(':'));
            String name = spell.id().substring(spell.id().indexOf(':') + 1);
            String school = spell.school().substring(spell.school().indexOf(':') + 1);
            resources.put(namespace + ':' + school + '/' + name, exported);
        }
        var snapshot = SpellFormulaFiles.parseResources(resources, catalog);
        assertTrue(snapshot.diagnostics().isEmpty(), () -> snapshot.diagnostics().toString());
        assertTrue(snapshot.byClass().isEmpty(), () -> snapshot.byClass().keySet().toString());
    }

    @Test
    void exportsOnlyEditableNoninstantCastFormulasWithTypeSelectionAndFinalRoundingHidden() {
        int castFormulas = 0;
        int publicFormulas = 0;
        for (var spell : catalog.spells()) {
            var exported = SpellFormulaFiles.preview(spell);
            var formulas = exported.getAsJsonObject("formulas");
            publicFormulas += formulas.size();
            assertFalse(formulas.has("effective_cast_time"), spell.id());
            assertFalse(formulas.has("base_cast_time"), spell.id());
            assertFalse(formulas.has("cooldown_ticks"), spell.id());
            assertFalse(formulas.toString().contains("is_instant"), spell.id());
            assertFalse(formulas.toString().contains("is_continuous"), spell.id());
            if (formulas.has("cast_time")) {
                castFormulas++;
                assertTrue(exported.getAsJsonObject("parameters").has("castTime"), spell.id());
                assertFalse(outerFunction(formulas.get("cast_time").getAsString(), "round"), spell.id());
            } else assertFalse(exported.getAsJsonObject("parameters").has("castTime"), spell.id());
        }
        assertEquals(62, castFormulas);
        assertEquals(437, publicFormulas);
    }

    @Test
    void finalIntegerConversionsAreHiddenForManaAndTickDurations() {
        for (var spell : catalog.spells()) {
            var formulas = SpellFormulaFiles.preview(spell).getAsJsonObject("formulas");
            for (String alias : new String[]{"mana_cost", "duration_ticks"}) {
                if (formulas.has(alias)) {
                    String expression = formulas.get(alias).getAsString();
                    assertFalse(outerFunction(expression, "int"), spell.id() + '/' + alias + ": " + expression);
                }
            }
        }
    }

    @Test
    void thunderstormExportsTheSavedAmplifierUnderItsGeneratorName() {
        var spell = catalog.spells().stream().filter(value -> value.id().equals("irons_spellbooks:thunderstorm"))
                .findFirst().orElseThrow();
        JsonObject formulas = SpellFormulaFiles.preview(spell).getAsJsonObject("formulas");
        assertEquals("8 + (level - 1) * power_multiplier * spell_power * school_spell_power",
                formulas.get("effect_level").getAsString());
        assertEquals("(effect_level - 7) * power_multiplier * spell_power * school_spell_power + 7",
                formulas.get("effect_damage").getAsString());
        assertFalse(Pattern.compile("\\bint\\s*\\(").matcher(formulas.get("effect_level").getAsString()).find());
        var damage = spell.formulas().stream().filter(method -> method.alias().equals("effect_damage"))
                .findFirst().orElseThrow();
        assertTrue(damage.variables().containsKey("effect_level"));
        assertFalse(damage.variables().containsKey("amplifier"));
    }

    private static boolean outerFunction(String expression, String function) {
        String text = expression.trim();
        while (text.startsWith("(") && closingParenthesis(text, 0) == text.length() - 1)
            text = text.substring(1, text.length() - 1).trim();
        var matcher = Pattern.compile("^" + Pattern.quote(function) + "\\s*\\(").matcher(text);
        if (!matcher.find()) return false;
        return closingParenthesis(text, matcher.end() - 1) == text.length() - 1;
    }

    private static int closingParenthesis(String expression, int opening) {
        int depth = 0;
        for (int index = opening; index < expression.length(); index++) {
            char character = expression.charAt(index);
            if (character == '(') depth++;
            if (character == ')' && --depth == 0) return index;
        }
        return -1;
    }
}
