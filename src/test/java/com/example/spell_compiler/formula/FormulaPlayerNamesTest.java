package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaPlayerNamesTest {
    private static SpellFormulaCatalog catalog;

    @BeforeAll
    static void generateCatalog() throws Exception {
        catalog = SpellFormulaCatalog.parse(FormulaCatalogGenerator.generate(Path.of("libs/ironsspells-3.16.1.jar")));
    }

    @Test
    void allEffectsUseOneLevelNameAndRetainNativeMethodBindings() {
        int effectLevels = 0;
        for (var spell : catalog.spells()) {
            for (var method : spell.editableFormulas()) {
                if (Set.of("getAmplifier", "getAmplifierForLevel", "getRendAmplifier", "getSlownessAmplifier")
                        .contains(method.name())) {
                    assertEquals("effect_level", method.alias(), spell.id());
                    effectLevels++;
                }
            }
        }
        assertEquals(9, effectLevels);
        var storm = spell("thunderstorm");
        var damage = storm.formulas().stream().filter(method -> method.alias().equals("effect_damage")).findFirst().orElseThrow();
        assertTrue(damage.variables().containsKey("effect_level"));
        assertTrue(damage.variables().containsKey("spell_power"));
        assertTrue(damage.variables().containsKey("school_spell_power"));
    }

    @Test
    void timeAndCooldownVisibilityRetainInternalBindingsAndDefaultRoundTrips() throws Exception {
        int castTimes = 0;
        for (var spell : catalog.spells()) {
            JsonObject json = SpellFormulaFiles.preview(spell);
            var formulas = json.getAsJsonObject("formulas");
            boolean castTime = formulas.has("cast_time");
            assertFalse(formulas.has("cooldown_ticks"), spell.id());
            assertEquals(castTime, json.getAsJsonObject("parameters").has("castTime"), spell.id());
            assertTrue(spell.formulas().stream().anyMatch(method -> method.key().equals("getCastTime(I)I")), spell.id());
            assertTrue(spell.formulas().stream().anyMatch(method -> method.key().equals("getSpellCooldown()I")), spell.id());
            assertTrue(SpellFormulaFiles.parse(spell, json.toString()).isEmpty(), spell.id());
            if (castTime) castTimes++;
        }
        assertEquals(62, castTimes);
        assertEquals(437, catalog.spells().stream().mapToInt(spell -> spell.editableFormulas().size()).sum());
        assertFalse(SpellFormulaFiles.preview(spell("firebolt")).getAsJsonObject("formulas").has("cast_time"));
        assertTrue(SpellFormulaFiles.preview(spell("fireball")).getAsJsonObject("formulas").has("cast_time"));
        assertTrue(SpellFormulaFiles.preview(spell("electrocute")).getAsJsonObject("formulas").has("cast_time"));
        assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell("firebolt"),
                "{\"parameters\":{\"castTime\":1}}"));
    }

    @Test
    void canonicalInputsRemainSeparateFromFormulaOutputNames() throws Exception {
        var fireball = spell("fireball");
        var power = fireball.formulas().stream().filter(method -> method.alias().equals("spell_power")).findFirst().orElseThrow();
        assertTrue(power.variables().containsKey("spell_power"));
        assertTrue(power.variables().containsKey("school_spell_power"));
        var json = SpellFormulaFiles.preview(fireball);
        json.getAsJsonObject("formulas").addProperty("spell_power", "spell_power * 2");
        assertTrue(SpellFormulaFiles.parse(fireball, json.toString()).formulas().containsKey(power.key()));
        var mana = fireball.formulas().stream().filter(method -> method.alias().equals("mana_cost")).findFirst().orElseThrow();
        assertTrue(mana.variables().containsKey("mana_cost_multiplier"));
        assertFalse(mana.variables().containsKey("mana_multiplier"));
        var time = fireball.formulas().stream().filter(method -> method.alias().equals("cast_time")).findFirst().orElseThrow();
        assertTrue(time.variables().containsKey("castTime"));
        assertFalse(time.variables().containsKey("native_cast_time"));
    }

    @Test
    void namesDistinguishHealthAmountsRatiosDamageAndTickWindows() {
        assertAlias("devour", "getHpBonus", "health_bonus");
        assertAlias("summon_swords", "getHealthBonus", "health_bonus_ratio");
        assertAlias("acid_orb", "getRendDuration", "rend_duration_ticks");
        assertAlias("portal", "getPortalDuration", "portal_duration_ticks");
        for (var spell : catalog.spells()) {
            for (var method : spell.editableFormulas()) {
                assertFalse(Set.of("aoedamage", "shield_hp", "hp_bonus", "duration", "effect_duration",
                        "portal_duration", "rend_duration", "chain_duration", "recast_duration", "freeze_time",
                        "summon_health_bonus").contains(method.alias()), spell.id() + '/' + method.alias());
            }
        }
    }

    private static void assertAlias(String spellName, String nativeMethod, String alias) {
        var method = spell(spellName).formulas().stream().filter(value -> value.name().equals(nativeMethod)).findFirst().orElseThrow();
        assertEquals(alias, method.alias());
    }

    private static SpellFormulaCatalog.Spell spell(String name) {
        return catalog.spells().stream().filter(spell -> spell.id().equals("irons_spellbooks:" + name)).findFirst().orElseThrow();
    }
}
