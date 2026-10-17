package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class InheritedSpellFormulaTest {
    @Test
    void allNativeSpellsKeepManaEditableAndCooldownInternal() throws Exception {
        var catalog = SpellFormulaCatalog.parse(FormulaCatalogGenerator.generate(Path.of("libs", "ironsspells-3.16.1.jar")));
        var expected = Map.of("mana_cost", "getManaCost(I)I", "cooldown_ticks", "getSpellCooldown()I");
        assertEquals(109, catalog.spells().size());
        for (var spell : catalog.spells()) {
            assertTrue(SpellFormulaFiles.parse(spell, SpellFormulaFiles.preview(spell)).isEmpty(), spell.id());
            for (var entry : expected.entrySet()) {
                var method = spell.formulas().stream().filter(value -> value.alias().equals(entry.getKey()))
                        .findFirst().orElseThrow(() -> new AssertionError(spell.id() + " is missing " + entry.getKey()));
                assertEquals(entry.getValue(), method.key());
                assertEquals("io.redspace.ironsspellbooks.api.spells.AbstractSpell", method.declaringClass());
                var changed = new JsonObject();
                var formulas = new JsonObject();
                formulas.addProperty(entry.getKey(), '(' + method.readableExpression() + ") + 5");
                changed.add("formulas", formulas);
                if (!method.editable()) {
                    assertFalse(SpellFormulaFiles.preview(spell).getAsJsonObject("formulas").has(entry.getKey()));
                    assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell, changed));
                    continue;
                }
                var parsed = SpellFormulaFiles.parse(spell, changed);
                assertEquals(1, parsed.formulas().size());
                assertTrue(parsed.formulas().containsKey(method.key()));
            }
        }
    }

    @Test
    void lightningLanceExportsAndAcceptsItsInheritedPowerFormula() throws Exception {
        var catalog = SpellFormulaCatalog.parse(FormulaCatalogGenerator.generate(Path.of("libs", "ironsspells-3.16.1.jar")));
        var lance = catalog.spells().stream().filter(spell -> spell.id().equals("irons_spellbooks:lightning_lance"))
                .findFirst().orElseThrow();
        var power = lance.formulas().stream().filter(method -> method.alias().equals("spell_power")).findFirst();
        assertTrue(power.isPresent(), "Lightning Lance must expose the inherited formula used by its preview and projectile");
        assertEquals("getSpellPower(ILnet/minecraft/world/entity/Entity;)F", power.orElseThrow().key());
        assertEquals("io.redspace.ironsspellbooks.api.spells.AbstractSpell", power.orElseThrow().declaringClass());
        var original = SpellFormulaFiles.preview(lance);
        assertTrue(SpellFormulaFiles.parse(lance, original).isEmpty());
        var changed = new JsonObject();
        var formulas = new JsonObject();
        formulas.addProperty("spell_power", '(' + power.orElseThrow().readableExpression() + ") + 5");
        changed.add("formulas", formulas);
        var parsed = SpellFormulaFiles.parse(lance, changed);
        assertEquals(1, parsed.formulas().size());
        assertTrue(parsed.formulas().containsKey(power.orElseThrow().key()));
    }
}
