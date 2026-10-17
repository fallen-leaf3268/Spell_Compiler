package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaUsageTest {
    private static final String POWER = "getSpellPower(ILnet/minecraft/world/entity/Entity;)F";
    private static SpellFormulaCatalog catalog;

    @BeforeAll
    static void generateCatalog() throws Exception {
        catalog = SpellFormulaCatalog.parse(FormulaCatalogGenerator.generate(Path.of("libs/ironsspells-3.16.1.jar")));
    }

    @Test
    void thunderstormHidesUnusedPowerFormulaAndParametersButKeepsAttributeInputs() {
        var storm = spell("thunderstorm");
        var exported = SpellFormulaFiles.preview(storm);
        var formulas = exported.getAsJsonObject("formulas");
        var parameters = exported.getAsJsonObject("parameters");
        assertFalse(formulas.has("spell_power"));
        assertFalse(parameters.has("baseSpellPower"));
        assertFalse(parameters.has("spellPowerPerLevel"));
        assertFalse(method(storm, "spell_power").editable());
        assertEquals(POWER, method(storm, "spell_power").key());
        assertEquals(8, storm.parameters().get("baseSpellPower").getAsInt());
        assertEquals(1, storm.parameters().get("spellPowerPerLevel").getAsInt());
        for (String alias : List.of("effect_level", "duration_ticks", "effect_damage")) {
            assertTrue(formulas.has(alias), alias);
            assertTrue(method(storm, alias).variables().containsKey("spell_power"), alias);
            assertTrue(method(storm, alias).variables().containsKey("school_spell_power"), alias);
        }
    }

    @Test
    void sculkTentaclesKeepTheDirectBasePowerInputWithoutExposingUnusedGrowth() {
        var tentacles = spell("sculk_tentacles");
        var exported = SpellFormulaFiles.preview(tentacles);
        assertFalse(exported.getAsJsonObject("formulas").has("spell_power"));
        assertTrue(exported.getAsJsonObject("formulas").has("damage"));
        assertTrue(exported.getAsJsonObject("parameters").has("baseSpellPower"));
        assertFalse(exported.getAsJsonObject("parameters").has("spellPowerPerLevel"));
        assertTrue(method(tentacles, "damage").variables().containsKey("baseSpellPower"));
        assertEquals(POWER, method(tentacles, "spell_power").key());
        assertEquals(3, tentacles.parameters().get("spellPowerPerLevel").getAsInt());
    }

    @Test
    void spellsWithDirectOrNestedPowerCallsRetainTheirEditablePowerAndParameters() {
        for (String name : List.of("fireball", "lightning_lance", "magma_bomb", "raise_dead")) {
            var spell = spell(name);
            var exported = SpellFormulaFiles.preview(spell);
            assertTrue(exported.getAsJsonObject("formulas").has("spell_power"), name);
            assertTrue(exported.getAsJsonObject("parameters").has("baseSpellPower"), name);
            assertTrue(exported.getAsJsonObject("parameters").has("spellPowerPerLevel"), name);
            assertTrue(method(spell, "spell_power").editable(), name);
            assertEquals(POWER, method(spell, "spell_power").key(), name);
        }
        assertTrue(method(spell("fireball"), "damage").variables().containsKey("baseSpellPower"));
        assertTrue(method(spell("magma_bomb"), "aoe_damage").variables().containsKey("spellPowerPerLevel"));
    }

    @Test
    void frameworkEntriesAndMultipliersRemainAvailableWhileInternalBindingsStayComplete() {
        assertEquals(109, catalog.spells().size());
        assertEquals(716, catalog.spells().stream().mapToInt(spell -> spell.formulas().size()).sum());
        var hiddenPower = new HashSet<String>();
        int publicFormulas = 0;
        int castTimes = 0;
        for (var spell : catalog.spells()) {
            var exported = SpellFormulaFiles.preview(spell);
            var formulas = exported.getAsJsonObject("formulas");
            var parameters = exported.getAsJsonObject("parameters");
            for (String key : List.of(POWER, "getManaCost(I)I", "getSpellCooldown()I", "getCastTime(I)I",
                    "getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I"))
                assertTrue(spell.formulas().stream().anyMatch(method -> method.key().equals(key)), spell.id() + '/' + key);
            assertTrue(formulas.has("mana_cost"), spell.id());
            assertFalse(formulas.has("cooldown_ticks"), spell.id());
            for (String parameter : List.of("baseManaCost", "manaCostPerLevel", "cooldown_in_seconds",
                    "power_multiplier", "mana_cost_multiplier"))
                assertTrue(parameters.has(parameter), spell.id() + '/' + parameter);
            assertEquals(formulas.has("cast_time"), parameters.has("castTime"), spell.id());
            if (formulas.has("cast_time")) castTimes++;
            if (!formulas.has("spell_power")) hiddenPower.add(spell.id());
            publicFormulas += formulas.size();
        }
        assertEquals(62, castTimes);
        assertEquals(Set.of("irons_spellbooks:pocket_dimension", "irons_spellbooks:sculk_tentacles",
                "irons_spellbooks:telekinesis", "irons_spellbooks:counterspell", "irons_spellbooks:recall",
                "irons_spellbooks:summon_ender_chest", "irons_spellbooks:summon_vex", "irons_spellbooks:wololo",
                "irons_spellbooks:cleanse", "irons_spellbooks:greater_heal", "irons_spellbooks:blizzard",
                "irons_spellbooks:ice_tomb", "irons_spellbooks:snowball", "irons_spellbooks:thunderstorm"), hiddenPower);
        assertEquals(437, publicFormulas);
    }

    @Test
    void everyFilteredDefaultRoundTripsWithoutInstallingOverrides() {
        for (var spell : catalog.spells())
            assertTrue(SpellFormulaFiles.parse(spell, SpellFormulaFiles.preview(spell)).isEmpty(), spell.id());
    }

    @Test
    void hiddenLegacyFieldsAreRejectedWithoutBlockingTheRemainingDirectBaseParameter() {
        for (String name : List.of("thunderstorm", "sculk_tentacles")) {
            var spell = spell(name);
            assertRejected(spell, "formulas", "spell_power", new JsonPrimitive("100"));
            assertRejected(spell, "parameters", "spellPowerPerLevel", new JsonPrimitive(2));
        }
        assertRejected(spell("thunderstorm"), "parameters", "baseSpellPower", new JsonPrimitive(9));
        var root = new JsonObject();
        var parameters = new JsonObject();
        parameters.addProperty("baseSpellPower", 9);
        root.add("parameters", parameters);
        assertEquals(9, SpellFormulaFiles.parse(spell("sculk_tentacles"), root).parameters().get("baseSpellPower").getAsInt());
    }

    private static void assertRejected(SpellFormulaCatalog.Spell spell, String group, String name, JsonPrimitive value) {
        var root = new JsonObject();
        var fields = new JsonObject();
        fields.add(name, value);
        root.add(group, fields);
        var failure = assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell, root));
        assertEquals("未知字段：" + group + '.' + name, failure.getMessage());
    }

    private static SpellFormulaCatalog.Method method(SpellFormulaCatalog.Spell spell, String alias) {
        return spell.formulas().stream().filter(method -> method.alias().equals(alias)).findFirst().orElseThrow();
    }

    private static SpellFormulaCatalog.Spell spell(String name) {
        return catalog.spells().stream().filter(spell -> spell.id().equals("irons_spellbooks:" + name)).findFirst().orElseThrow();
    }
}
