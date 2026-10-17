package com.example.spell_compiler.audit.formula;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FormulaJarProbeTest {
    @Test
    void realJarFormulasAreExportedRecompiledAndChangedByOneTransformer() throws Exception {
        Path source = Path.of("libs", "ironsspells-3.16.1.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(source));
        Path report = Path.of("build", "formula-probe", "ironsspells-3.16.1-formulas.json");
        FormulaJarProbe.main(new String[]{source.toString(), report.toString()});
        var data = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        assertTrue(data.get("spell_class_files").getAsInt() > 0);
        assertEquals(data.get("numeric_methods_examined").getAsInt(),
                data.get("automatically_supported_methods").getAsInt() + data.get("rejected_methods").getAsInt());
        assertEquals(data.get("spell_class_files").getAsInt(), data.get("unchanged_class_bytes_verified").getAsInt());
        assertEquals(data.get("spell_class_files").getAsInt(), data.get("readable_unchanged_class_bytes_verified").getAsInt());
        assertEquals(data.get("automatically_supported_methods").getAsInt(), data.get("readable_formulas_exported").getAsInt());
        assertTrue(data.get("execution_comparisons").getAsInt() >= 31104);
        assertTrue(data.get("readable_execution_comparisons").getAsInt() >= 51840);
        assertTrue(data.get("readable_edit_comparisons").getAsInt() >= 216);
        assertTrue(data.get("readable_radius_limited_cases").getAsInt() > 0);
        assertTrue(data.get("readable_radius_unchanged_cases").getAsInt() > 0);
        assertTrue(data.get("readable_coefficient_baseline").getAsString().contains("original Fireball.getDamage bytecode"));
        assertTrue(data.get("independent_factor_comparisons").getAsInt() >= 144);
        assertTrue(data.get("baseline").getAsString().contains("native JAR method bytecode"));
        var examples = data.getAsJsonArray("execution_examples");
        assertTrue(examples.size() >= 6);
        for (var value : examples) {
            var example = value.getAsJsonObject();
            assertEquals(example.get("original").getAsDouble(), example.get("recompiled_default").getAsDouble());
            assertEquals(example.get("original").getAsDouble() + 7, example.get("modified").getAsDouble(), 0.00001);
            assertEquals(example.get("original").getAsDouble(), example.get("readable_recompiled_default").getAsDouble());
            assertEquals(example.get("modified").getAsDouble(), example.get("readable_modified").getAsDouble());
        }
        for (String caster : new String[]{"PRESENT", "MISSING_SCHOOL_ATTRIBUTE", "NULL"}) {
            assertTrue(examples.asList().stream().anyMatch(value -> value.getAsJsonObject().get("caster").getAsString().equals(caster)));
        }
        assertTrue(examples.asList().stream().anyMatch(value -> value.getAsJsonObject().get("class").getAsString().endsWith("EarthquakeSpell")
                && value.getAsJsonObject().get("method").getAsString().equals("getSlownessAmplifier")));
        var supported = data.getAsJsonArray("supported");
        for (var value : supported) {
            String readable = value.getAsJsonObject().get("readable_expression").getAsString();
            assertFalse(readable.isBlank());
            assertFalse(readable.contains("float("), readable);
            assertFalse(readable.contains("double("), readable);
        }
        var fireball = supported.asList().stream().map(value -> value.getAsJsonObject())
                .filter(item -> item.get("class").getAsString().endsWith("FireballSpell")
                        && item.get("method").getAsString().equals("getDamage"))
                .findFirst().orElseThrow();
        assertTrue(fireball.getAsJsonObject("variables").has("baseSpellPower"));
        assertTrue(fireball.getAsJsonObject("variables").has("spellPowerPerLevel"));
        assertTrue(fireball.getAsJsonObject("variables").has("school_spell_power"));
        assertTrue(fireball.getAsJsonObject("variables").has("power_multiplier"));
        assertEquals("double", fireball.getAsJsonObject("variables").get("spell_power").getAsString());
        assertEquals("double", fireball.getAsJsonObject("variables").get("school_spell_power").getAsString());
        assertEquals("float", fireball.getAsJsonObject("variables").get("power_multiplier").getAsString());
        assertFalse(fireball.get("expression").getAsString().contains("power_scale"));
        assertTrue(data.get("execution_methods").getAsInt() >= 16);
        assertTrue(supported.asList().stream().anyMatch(value -> {
            var item = value.getAsJsonObject();
            return item.get("class").getAsString().endsWith("FireballSpell")
                    && item.get("method").getAsString().equals("getDamage")
                    && item.getAsJsonObject("variables").has("spell_power");
        }));
        assertTrue(supported.asList().stream().noneMatch(value ->
                value.getAsJsonObject().getAsJsonObject("variables").has("power")));
    }
}
