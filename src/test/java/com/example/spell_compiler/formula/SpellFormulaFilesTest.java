package com.example.spell_compiler.formula;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpellFormulaFilesTest {
    private static final String STRICT = "(float(5.0) + (float(5.0) * float((((double((baseSpellPower + (spellPowerPerLevel * (level - int(1))))) * double(float(spell_power))) * school_spell_power) * double(power_multiplier)))))";
    private static final String READABLE = "5 + 5 * (baseSpellPower + spellPowerPerLevel * (level - 1)) * spell_power * school_spell_power * power_multiplier";

    @Test
    void cleanPreviewIsAnExactNoChangeConfiguration() throws Exception {
        var preview = SpellFormulaFiles.preview(spell());
        assertEquals(List.of("advanced", "parameters", "formulas"), List.copyOf(preview.keySet()));
        assertTrue(SpellFormulaFiles.parse(spell(), preview.toString()).isEmpty());
        assertFalse(preview.toString().contains("_说明"));
        assertTrue(preview.toString().chars().allMatch(value -> value < 128));
    }

    @Test
    void omittedValuesAndNumericFormattingDoNotOverrideNativeSettings() throws Exception {
        assertTrue(SpellFormulaFiles.parse(spell(), "{}").isEmpty());
        assertTrue(SpellFormulaFiles.parse(spell(), "{\"parameters\":{\"power_multiplier\":1.000,\"baseManaCost\":60.0}}").isEmpty());
        assertTrue(SpellFormulaFiles.parse(spell(), "{\"formulas\":{\"damage\":\" ( " + READABLE + " ) \"}}").isEmpty());
        assertTrue(SpellFormulaFiles.parse(spell(), "{\"formulas\":{\"damage\":" + new Gson().toJson(STRICT) + "}}").isEmpty());
    }

    @Test
    void changedFieldsAndRealPlayerFormulaTextAreRetained() throws Exception {
        String formula = "min(" + READABLE + ", 16)";
        String text = "{\"advanced\":{\"enabled\":false},\"parameters\":{\"baseManaCost\":70,\"power_multiplier\":1},\"formulas\":{\"damage\":" + new Gson().toJson(formula) + "}}";
        var result = SpellFormulaFiles.parse(spell(), text);
        assertEquals(Map.of("enabled", JsonParser.parseString("false")), result.advanced());
        assertEquals(Map.of("baseManaCost", JsonParser.parseString("70")), result.parameters());
        assertEquals(Map.of("getDamage(ILnet/minecraft/world/entity/LivingEntity;)F", formula), result.formulas());
    }

    @Test
    void unknownFieldsAndFormulaAliasesRejectTheEntireConfiguration() {
        for (String json : List.of("{\"advnaced\":{}}", "{\"parameters\":{\"baseManaCosts\":70}}", "{\"formulas\":{\"dammage\":\"10\"}}"))
            assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell(), json), json);
    }

    @Test
    void wrongTypesAndInvalidNativeParameterValuesAreRejected() {
        for (String json : List.of(
                "{\"advanced\":{\"enabled\":\"false\"}}",
                "{\"advanced\":{\"max_level\":0}}",
                "{\"advanced\":{\"min_rarity\":\"ancient\"}}",
                "{\"advanced\":{\"school\":\"fire\"}}",
                "{\"parameters\":{\"baseManaCost\":2.5}}",
                "{\"parameters\":{\"baseManaCost\":2147483648}}",
                "{\"parameters\":{\"castTime\":-1}}",
                "{\"parameters\":{\"power_multiplier\":1e100}}",
                "{\"parameters\":{\"mana_cost_multiplier\":1e400}}",
                "{\"parameters\":null}",
                "{\"_说明\":false}"))
            assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell(), json), json);
    }

    @Test
    void invalidFormulaSyntaxUnknownVariablesAndFunctionsAreRejected() {
        for (String formula : List.of("", "5 +", "le vel", "missing + 1", "execute(level)", "min(level)", "clamp(level,1)", "sqrt(level,2)", "min(level,)")) {
            String json = "{\"formulas\":{\"damage\":" + new Gson().toJson(formula) + "}}";
            assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell(), json), formula);
        }
    }

    @Test
    void parameterCombinationsRejectNativeIntegerOverflowBeforeTheyAreApplied() {
        for (String json : List.of(
                "{\"parameters\":{\"baseManaCost\":2147483647,\"manaCostPerLevel\":1}}",
                "{\"parameters\":{\"baseManaCost\":0,\"manaCostPerLevel\":1073741824,\"mana_cost_multiplier\":0}}",
                "{\"parameters\":{\"baseSpellPower\":9223372036854775807,\"spellPowerPerLevel\":1}}",
                "{\"parameters\":{\"baseSpellPower\":-9223372036854775808,\"spellPowerPerLevel\":9223372036854775807}}",
                "{\"parameters\":{\"mana_cost_multiplier\":1e200}}",
                "{\"parameters\":{\"cooldown_in_seconds\":1e200}}",
                "{\"advanced\":{\"max_level\":2147483647}}"))
            assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.parse(spell(), json), json);
    }

    @Test
    void validParameterBoundaryAndLevelChangesKeepTheirConfiguredValues() throws Exception {
        for (String json : List.of(
                "{\"parameters\":{\"baseManaCost\":2147483647,\"manaCostPerLevel\":0}}",
                "{\"parameters\":{\"baseManaCost\":2147483643,\"manaCostPerLevel\":1}}",
                "{\"advanced\":{\"max_level\":2},\"parameters\":{\"baseManaCost\":2147483646,\"manaCostPerLevel\":1}}",
                "{\"parameters\":{\"baseSpellPower\":-10,\"spellPowerPerLevel\":1}}",
                "{\"parameters\":{\"cooldown_in_seconds\":0,\"mana_cost_multiplier\":0}}"))
            assertFalse(SpellFormulaFiles.parse(spell(), json).isEmpty(), json);
    }

    @Test
    void duplicateKeysAndTrailingJsonAreRejected() {
        for (String json : List.of("{\"parameters\":{},\"parameters\":{}}", "{\"parameters\":{\"castTime\":20,\"castTime\":40}}", "{} {}"))
            assertThrows(Exception.class, () -> SpellFormulaFiles.parse(spell(), json), json);
    }

    @Test
    void exporterCreatesOnlyMissingFilesAndPreservesPlayerEdits() throws Exception {
        Path root = directory();
        var catalog = new SpellFormulaCatalog(List.of(spell()));
        var first = SpellFormulaFiles.exportAndLoad(root, catalog);
        assertTrue(first.byClass().isEmpty());
        assertTrue(first.diagnostics().isEmpty());
        Path file = root.resolve("data/irons_spellbooks/spells/fire/fireball.json");
        String edited = "{\"parameters\":{\"baseManaCost\":70}}";
        Files.writeString(file, edited);
        var second = SpellFormulaFiles.exportAndLoad(root, catalog);
        assertEquals(edited, Files.readString(file));
        assertEquals(70, second.byClass().get(spell().className()).parameters().get("baseManaCost").getAsInt());
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void unknownFilesAndInvalidFilesHaveExplicitDiagnosticsWithoutPartialApplication() throws Exception {
        Path root = directory();
        var catalog = new SpellFormulaCatalog(List.of(spell()));
        SpellFormulaFiles.exportAndLoad(root, catalog);
        Path spells = root.resolve("data/irons_spellbooks/spells/fire");
        Files.writeString(spells.resolve("fireball.json"), "{\"parameters\":{\"baseManaCost\":70},\"formulas\":{\"damage\":\"unknown\"}}");
        Files.writeString(spells.resolve("unknown.json"), "{}");
        var result = SpellFormulaFiles.exportAndLoad(root, catalog);
        assertTrue(result.byClass().isEmpty());
        assertEquals(2, result.diagnostics().size());
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.message().contains("未知或尚不支持的法术")));
        assertTrue(result.diagnostics().stream().allMatch(value -> value.cause() instanceof IllegalArgumentException));
    }

    @Test
    void exportAndReadDiagnosticsKeepTheirOriginalCauses() throws Exception {
        Path root = directory();
        Files.createDirectory(root.resolve("pack.mcmeta"));
        var failures = SpellFormulaFiles.exportPack(root, new SpellFormulaCatalog(List.of(spell())));
        assertEquals(1, failures.size());
        assertInstanceOf(java.io.IOException.class, failures.get(0).cause());
        Files.writeString(root.resolve("data/irons_spellbooks/spells/fire/fireball.json"), "{invalid");
        var loaded = SpellFormulaFiles.exportAndLoad(root, new SpellFormulaCatalog(List.of(spell())));
        assertEquals(2, loaded.diagnostics().size());
        assertTrue(loaded.diagnostics().stream().allMatch(value -> value.cause() != null));
    }

    @Test
    void deeplyNestedFormulaIsDiagnosedWithoutAnErrorEscapingResourceLoading() {
        JsonObject formulas = new JsonObject();
        formulas.addProperty("damage", "(".repeat(3000) + "level" + ")".repeat(3000));
        JsonObject json = new JsonObject();
        json.add("formulas", formulas);
        var result = SpellFormulaFiles.parseResources(Map.of("irons_spellbooks:fire/fireball", json),
                new SpellFormulaCatalog(List.of(spell())));
        assertTrue(result.byClass().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertInstanceOf(IllegalArgumentException.class, result.diagnostics().get(0).cause());
    }

    @Test
    void fingerprintIgnoresOrderingAndEquivalentNumbers() throws Exception {
        var first = SpellFormulaFiles.parse(spell(), "{\"parameters\":{\"baseManaCost\":70,\"power_multiplier\":1.25},\"advanced\":{\"enabled\":false}}");
        var second = SpellFormulaFiles.parse(spell(), "{\"advanced\":{\"enabled\":false},\"parameters\":{\"power_multiplier\":1.2500,\"baseManaCost\":70.0}}");
        assertEquals(SpellFormulaFiles.fingerprint(Map.of(spell().className(), first)), SpellFormulaFiles.fingerprint(Map.of(spell().className(), second)));
        var third = SpellFormulaFiles.parse(spell(), "{\"parameters\":{\"baseManaCost\":71}}");
        assertNotEquals(SpellFormulaFiles.fingerprint(Map.of(spell().className(), first)), SpellFormulaFiles.fingerprint(Map.of(spell().className(), third)));
    }

    @Test
    void signedZeroAndVerySmallExponentsRemainFiniteAndFingerprintable() throws Exception {
        var zero = SpellFormulaFiles.parse(spell(), "{\"parameters\":{\"power_multiplier\":0}}");
        var negativeZero = SpellFormulaFiles.parse(spell(), "{\"parameters\":{\"power_multiplier\":-0.0}}");
        assertNotEquals(SpellFormulaFiles.fingerprint(Map.of(spell().className(), zero)), SpellFormulaFiles.fingerprint(Map.of(spell().className(), negativeZero)));
        var tiny = SpellFormulaFiles.parse(spell(), "{\"parameters\":{\"power_multiplier\":1e-999999}}");
        assertEquals(64, SpellFormulaFiles.fingerprint(Map.of(spell().className(), tiny)).length());
    }

    @Test
    void unsafeIdsAndDuplicateCatalogEntriesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SpellFormulaCatalog(List.of(spell(), spell())));
        for (String id : List.of("irons_spellbooks:../escape", "irons_spellbooks:/escape", "irons_spellbooks:a/./b", "irons_spellbooks:a//b"))
            assertThrows(IllegalArgumentException.class, () -> new SpellFormulaCatalog.Spell(id, spell().className(), spell().school(), spell().advanced(), spell().parameters(), spell().formulas()));
    }

    @Test
    void packagedCatalogDefaultsAllRoundTripWithoutOverrides() throws Exception {
        var catalog = SpellFormulaCatalog.load(getClass().getClassLoader());
        assertEquals(109, catalog.spells().size());
        assertFalse(catalog.spells().stream().anyMatch(spell -> List.of("AbstractEldritchSpell",
                "GravityFissureSpell", "SoulfireRaySpell", "ThunderStepSpell")
                .stream().anyMatch(name -> spell.className().endsWith('.' + name))));
        int methods = 0;
        for (var spell : catalog.spells()) {
            assertTrue(SpellFormulaFiles.parse(spell, SpellFormulaFiles.preview(spell).toString()).isEmpty(), spell.id());
            methods += spell.formulas().size();
        }
        assertEquals(716, methods);
    }

    @Test
    void exportedPackHasStandardMetadataAndKeepsUserMetadata() throws Exception {
        Path root = directory();
        assertTrue(SpellFormulaFiles.export(root, new SpellFormulaCatalog(List.of(spell()))).isEmpty());
        Path metadata = root.resolve("pack.mcmeta");
        assertEquals(15, JsonParser.parseString(Files.readString(metadata)).getAsJsonObject().getAsJsonObject("pack").get("pack_format").getAsInt());
        assertTrue(Files.isRegularFile(root.resolve("data/irons_spellbooks/spells/fire/fireball.json")));
        String custom = "{\"pack\":{\"pack_format\":15,\"description\":\"custom\"}}";
        Files.writeString(metadata, custom);
        SpellFormulaFiles.export(root, new SpellFormulaCatalog(List.of(spell())));
        assertEquals(custom, Files.readString(metadata));
    }

    @Test
    void resourceSnapshotRoundTripsOnlyEffectiveChangesForSynchronization() throws Exception {
        var override = SpellFormulaFiles.parse(spell(), "{\"advanced\":{\"enabled\":false},\"parameters\":{\"baseManaCost\":70},\"formulas\":{\"damage\":\"min(" + READABLE + ", 16)\"}}");
        var snapshot = SpellFormulaFiles.snapshot(Map.of(spell().className(), override), List.of());
        var resources = SpellFormulaFiles.resourcesForSnapshot(snapshot);
        assertEquals(1, resources.size());
        var decoded = SpellFormulaFiles.parseResources(resources, new SpellFormulaCatalog(List.of(spell())));
        assertTrue(decoded.diagnostics().isEmpty());
        assertEquals(snapshot.fingerprint(), decoded.fingerprint());
        assertEquals(override.formulas(), decoded.byClass().get(spell().className()).formulas());
        assertFalse(resources.toString().contains("_说明"));
    }

    @Test
    void twoResourcePathsForOneSpellAreRejectedWithoutPickingAnArbitraryWinner() {
        var resources = Map.of("irons_spellbooks:fire/fireball", JsonParser.parseString("{\"parameters\":{\"baseManaCost\":70}}"),
                "irons_spellbooks:other/fireball", JsonParser.parseString("{\"parameters\":{\"baseManaCost\":80}}"));
        var result = SpellFormulaFiles.parseResources(resources, new SpellFormulaCatalog(List.of(spell())));
        assertTrue(result.byClass().isEmpty());
        assertEquals(1, result.diagnostics().size());
    }

    @Test
    void unreadableDuplicatePathsRejectTheSpellRegardlessOfResourceOrdering() throws Exception {
        for (String folder : List.of("a", "z")) {
            for (String failureKind : List.of("syntax", "size", "io")) {
                Path root = directory();
                var catalog = new SpellFormulaCatalog(List.of(spell()));
                SpellFormulaFiles.exportPack(root, catalog);
                Path valid = root.resolve("data/irons_spellbooks/spells/fire/fireball.json");
                Files.writeString(valid, "{\"parameters\":{\"baseManaCost\":70}}");
                Path invalid = root.resolve("data/irons_spellbooks/spells/" + folder + "/fireball.json");
                Files.createDirectories(invalid.getParent());
                if (failureKind.equals("io")) Files.createDirectory(invalid);
                else Files.writeString(invalid, failureKind.equals("size") ? " ".repeat(262_145) : "{invalid");

                var result = SpellFormulaFiles.exportAndLoad(root, catalog);
                String scenario = folder + '/' + failureKind;
                assertTrue(result.byClass().isEmpty(), scenario);
                assertEquals(2, result.diagnostics().size(), scenario);
                assertEquals(1, result.diagnostics().stream()
                        .filter(value -> value.message().contains("同一法术存在多个数据路径")).count(), scenario);
                assertTrue(result.diagnostics().stream().allMatch(value -> value.cause() != null), scenario);
                assertEquals("{\"parameters\":{\"baseManaCost\":70}}", Files.readString(valid), scenario);
            }
        }
    }

    @Test
    void anActualNullJsonRootRemainsInvalidWithoutReadingFailureMetadata() {
        var result = SpellFormulaFiles.parseResources(Map.of("irons_spellbooks:fire/fireball",
                JsonParser.parseString("null")), new SpellFormulaCatalog(List.of(spell())));
        assertTrue(result.byClass().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertTrue(result.diagnostics().get(0).message().contains("配置根节点必须是对象"));
        assertInstanceOf(IllegalArgumentException.class, result.diagnostics().get(0).cause());
    }

    private static Path directory() throws Exception {
        Path root = Path.of("build/formula-runtime-test").toAbsolutePath();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "files-");
    }

    private static SpellFormulaCatalog.Spell spell() {
        JsonObject advanced = JsonParser.parseString("{\"enabled\":true,\"max_level\":5,\"min_rarity\":\"rare\",\"allow_crafting\":true,\"school\":\"irons_spellbooks:fire\"}").getAsJsonObject();
        JsonObject parameters = JsonParser.parseString("{\"baseManaCost\":60,\"manaCostPerLevel\":15,\"baseSpellPower\":1,\"spellPowerPerLevel\":1,\"castTime\":40,\"cooldown_in_seconds\":25,\"power_multiplier\":1,\"mana_cost_multiplier\":1}").getAsJsonObject();
        var method = new SpellFormulaCatalog.Method("damage", "getDamage", "(ILnet/minecraft/world/entity/LivingEntity;)F", STRICT, READABLE,
                Map.of("baseSpellPower", "I", "spellPowerPerLevel", "I", "level", "I", "spell_power", "D", "school_spell_power", "D", "power_multiplier", "F"),
                "io.redspace.ironsspellbooks.spells.fire.FireballSpell");
        return new SpellFormulaCatalog.Spell("irons_spellbooks:fireball", "io.redspace.ironsspellbooks.spells.fire.FireballSpell", "irons_spellbooks:fire", advanced, parameters, List.of(method));
    }
}
