package com.example.spell_compiler.formula;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormulaLongPowerFilesTest {
    @Test
    void playerFiveHundredMillionGrowthLoadsThroughResourceParsing() throws Exception {
        var catalog = catalog();
        var spell = spell(catalog);
        var json = SpellFormulaFiles.parseJson("{\"advanced\":{\"max_level\":10},\"parameters\":{\"baseSpellPower\":500000000,\"spellPowerPerLevel\":500000000}}");
        var snapshot = SpellFormulaFiles.parseResources(Map.of("irons_spellbooks:lightning/lightning_lance", json), catalog);
        assertTrue(snapshot.diagnostics().isEmpty(), snapshot.diagnostics().toString());
        var override = snapshot.byClass().get(spell.className());
        assertNotNull(override);
        assertEquals(500_000_000L, override.parameters().get("baseSpellPower").getAsLong());
        assertEquals(500_000_000L, override.parameters().get("spellPowerPerLevel").getAsLong());
    }

    @Test
    void bothPowerParametersAcceptValuesBeyondTheNativeIntegerRange() throws Exception {
        var spell = spell(catalog());
        var override = SpellFormulaFiles.parse(spell,
                "{\"parameters\":{\"baseSpellPower\":3000000000,\"spellPowerPerLevel\":4000000000}}");
        assertEquals(3_000_000_000L, override.parameters().get("baseSpellPower").getAsLong());
        assertEquals(4_000_000_000L, override.parameters().get("spellPowerPerLevel").getAsLong());
    }

    @Test
    void valuesWithTheSameLowThirtyTwoBitsRemainEffectiveChanges() throws Exception {
        var spell = spell(catalog());
        long changed = (1L << 32) + spell.parameters().get("baseSpellPower").getAsLong();
        var override = SpellFormulaFiles.parse(spell,
                "{\"parameters\":{\"baseSpellPower\":" + changed + ",\"spellPowerPerLevel\":0}}");
        assertTrue(override.parameters().containsKey("baseSpellPower"));
        assertEquals(changed, override.parameters().get("baseSpellPower").getAsLong());
    }

    @Test
    void signedLongEndpointsAreValidWithZeroGrowth() throws Exception {
        var spell = spell(catalog());
        for (long endpoint : List.of(Long.MIN_VALUE, Long.MAX_VALUE)) {
            var override = SpellFormulaFiles.parse(spell,
                    "{\"parameters\":{\"baseSpellPower\":" + endpoint + ",\"spellPowerPerLevel\":0}}");
            assertEquals(endpoint, override.parameters().get("baseSpellPower").getAsLong());
        }
    }

    @Test
    void fractionalOutOfLongRangeAndOverflowingGrowthAreRejected() throws Exception {
        var spell = spell(catalog());
        for (String parameters : List.of(
                "\"baseSpellPower\":9223372036854775808",
                "\"baseSpellPower\":-9223372036854775809",
                "\"baseSpellPower\":3000000000.5",
                "\"spellPowerPerLevel\":9223372036854775808",
                "\"spellPowerPerLevel\":-9223372036854775809",
                "\"spellPowerPerLevel\":0.25",
                "\"baseSpellPower\":9223372036854775807,\"spellPowerPerLevel\":1",
                "\"baseSpellPower\":-9223372036854775808,\"spellPowerPerLevel\":-1",
                "\"baseSpellPower\":0,\"spellPowerPerLevel\":9223372036854775807")) {
            assertThrows(IllegalArgumentException.class,
                    () -> SpellFormulaFiles.parse(spell, "{\"parameters\":{" + parameters + "}}"), parameters);
        }
    }

    @Test
    void manaCastTimeAndMaximumLevelKeepTheirNativeIntegerLimits() throws Exception {
        var spell = spell(catalog());
        for (String json : List.of(
                "{\"parameters\":{\"baseManaCost\":2147483648}}",
                "{\"parameters\":{\"manaCostPerLevel\":2147483648}}",
                "{\"parameters\":{\"castTime\":2147483648}}",
                "{\"advanced\":{\"max_level\":2147483648}}")) {
            assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell, json), json);
        }
    }

    @Test
    void floatingPowerFormulasAcceptLongLiteralsWhileIntegerFormulasRetainTheirLimits() throws Exception {
        var spell = spell(catalog());
        var override = SpellFormulaFiles.parse(spell, "{\"formulas\":{\"spell_power\":\"5000000000\"}}");
        var key = spell.formulas().stream().filter(value -> value.alias().equals("spell_power")).findFirst().orElseThrow().key();
        assertEquals("5000000000", override.formulas().get(key));
        assertThrows(IllegalArgumentException.class,
                () -> SpellFormulaFiles.parse(spell, "{\"formulas\":{\"cast_time\":\"5000000000\"}}"));
    }

    @Test
    void cleanExportsAndEquivalentIntegerFormattingRemainUnmodified() throws Exception {
        var spell = spell(catalog());
        assertTrue(SpellFormulaFiles.parse(spell, SpellFormulaFiles.preview(spell)).isEmpty());
        assertTrue(SpellFormulaFiles.parse(spell, "{\"parameters\":{\"baseSpellPower\":"
                + spell.parameters().get("baseSpellPower").getAsLong() + ".0,\"spellPowerPerLevel\":"
                + spell.parameters().get("spellPowerPerLevel").getAsLong() + ".000}}").isEmpty());
    }

    @Test
    void packetAndSnapshotRoundTripPreserveAllLongDigitsAndFingerprints() throws Exception {
        var catalog = catalog();
        var spell = spell(catalog);
        var first = SpellFormulaFiles.parse(spell,
                "{\"parameters\":{\"baseSpellPower\":9223372036854775807,\"spellPowerPerLevel\":0}}");
        var second = SpellFormulaFiles.parse(spell,
                "{\"parameters\":{\"baseSpellPower\":9223372036854775806,\"spellPowerPerLevel\":0}}");
        var snapshot = SpellFormulaFiles.snapshot(Map.of(spell.className(), first), List.of());
        assertNotEquals(snapshot.fingerprint(), SpellFormulaFiles.snapshot(Map.of(spell.className(), second), List.of()).fingerprint());
        var packet = new SpellFormulaNetwork.SnapshotPacket(SpellFormulaFiles.resourcesForSnapshot(snapshot));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            var decoded = SpellFormulaNetwork.SnapshotPacket.decode(buffer);
            var received = SpellFormulaFiles.parseResources(decoded.resources(), catalog);
            assertTrue(received.diagnostics().isEmpty(), received.diagnostics().toString());
            assertEquals(snapshot.fingerprint(), received.fingerprint());
            assertEquals(Long.MAX_VALUE, received.byClass().get(spell.className()).parameters().get("baseSpellPower").getAsLong());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static SpellFormulaCatalog catalog() throws Exception {
        return SpellFormulaCatalog.load(FormulaLongPowerFilesTest.class.getClassLoader());
    }

    private static SpellFormulaCatalog.Spell spell(SpellFormulaCatalog catalog) {
        return catalog.spells().stream().filter(value -> value.id().equals("irons_spellbooks:lightning_lance"))
                .findFirst().orElseThrow();
    }
}
