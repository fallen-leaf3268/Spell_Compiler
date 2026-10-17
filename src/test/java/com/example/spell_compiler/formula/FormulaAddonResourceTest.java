package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaAddonResourceTest {
    @Test
    void completeNestedSpellIdIsPreservedWhenAddressedDirectly() {
        var spell = spell("fixture:branch/bolt", "fixture.addon.Bolt");
        var parsed = SpellFormulaFiles.parseResources(Map.of(spell.id(), changed()), new SpellFormulaCatalog(List.of(spell)));
        assertTrue(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
        assertEquals(12, parsed.byClass().get(spell.className()).parameters().get("cooldown_in_seconds").getAsInt());
    }

    @Test
    void snapshotResourcesKeepTheCompleteExportPathAndNestedId() {
        var spell = spell("fixture:branch/bolt", "fixture.addon.Bolt");
        var override = SpellFormulaFiles.parse(spell, changed());
        var resources = SpellFormulaFiles.resourcesForSnapshot(SpellFormulaFiles.snapshot(Map.of(spell.className(), override), List.of()));
        assertEquals(java.util.Set.of("fixture:spells/fire/branch/bolt"), resources.keySet());
        var parsed = SpellFormulaFiles.parseResources(resources, new SpellFormulaCatalog(List.of(spell)));
        assertTrue(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
        assertEquals(override.parameters(), parsed.byClass().get(spell.className()).parameters());
    }

    @Test
    void nestedIdsWithTheSameLastSegmentStaySeparate() {
        var left = spell("fixture:left/bolt", "fixture.addon.LeftBolt");
        var right = spell("fixture:right/bolt", "fixture.addon.RightBolt");
        var parsed = SpellFormulaFiles.parseResources(Map.of("fixture:spells/fire/left/bolt", changed(),
                "fixture:spells/fire/right/bolt", changed()), new SpellFormulaCatalog(List.of(left, right)));
        assertTrue(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
        assertEquals(java.util.Set.of(left.className(), right.className()), parsed.byClass().keySet());
    }

    @Test
    void duplicateDirectAndExportPathsRejectTheSameCompleteId() {
        var spell = spell("fixture:branch/bolt", "fixture.addon.Bolt");
        var parsed = SpellFormulaFiles.parseResources(Map.of(spell.id(), changed(),
                "fixture:spells/fire/branch/bolt", changed()), new SpellFormulaCatalog(List.of(spell)));
        assertTrue(parsed.byClass().isEmpty());
        assertEquals(1, parsed.diagnostics().size());
        assertTrue(parsed.diagnostics().get(0).message().contains("同一法术存在多个数据路径"));
    }

    @Test
    void catalogFingerprintIsStableAcrossOrderAndChangesWithDefinitions() {
        var left = spell("fixture:left/bolt", "fixture.addon.LeftBolt");
        var right = spell("fixture:right/bolt", "fixture.addon.RightBolt");
        var original = new SpellFormulaCatalog(List.of(left, right)).fingerprint();
        assertEquals(original, new SpellFormulaCatalog(List.of(right, left)).fingerprint());
        assertNotEquals(original, new SpellFormulaCatalog(List.of(left)).fingerprint());
        var changed = right.parameters().deepCopy();
        changed.addProperty("cooldown_in_seconds", 11.0);
        var replacement = new SpellFormulaCatalog.Spell(right.id(), right.className(), right.school(),
                right.advanced(), changed, right.formulas());
        assertNotEquals(original, new SpellFormulaCatalog(List.of(left, replacement)).fingerprint());
    }

    @Test
    void catalogFingerprintIgnoresJsonMemberInsertionOrder() {
        var original = spell("fixture:bolt", "fixture.addon.Bolt");
        var first = original.parameters().deepCopy();
        first.addProperty("power_multiplier", 1.0);
        var second = new JsonObject();
        second.addProperty("power_multiplier", 1);
        second.addProperty("cooldown_in_seconds", 10);
        var left = new SpellFormulaCatalog.Spell(original.id(), original.className(), original.school(),
                original.advanced(), first, original.formulas());
        var right = new SpellFormulaCatalog.Spell(original.id(), original.className(), original.school(),
                original.advanced(), second, original.formulas());
        assertEquals(new SpellFormulaCatalog(List.of(left)).fingerprint(), new SpellFormulaCatalog(List.of(right)).fingerprint());
    }

    @Test
    void unknownNestedResourceCannotSelectAnAddonWithTheSameLastSegment() {
        var flat = spell("fixture:bolt", "fixture.addon.Bolt");
        var result = SpellFormulaFiles.parseResources(Map.of("fixture:spells/fire/branch/bolt", changed()),
                new SpellFormulaCatalog(List.of(flat)));
        assertTrue(result.byClass().isEmpty());
        assertEquals(1, result.diagnostics().size());
    }

    @Test
    void aDirectIdStartingWithSpellsCanBeAddressedWithoutDiscardingItsPath() {
        var spell = spell("fixture:spells/fire/bolt", "fixture.addon.Bolt");
        var parsed = SpellFormulaFiles.parseResources(Map.of(spell.id(), changed()), new SpellFormulaCatalog(List.of(spell)));
        assertTrue(parsed.diagnostics().isEmpty(), parsed.diagnostics().toString());
        assertTrue(parsed.byClass().containsKey(spell.className()));
    }

    @Test
    void canonicalAndDirectIdentitiesForDifferentSpellsCannotSelectAnArbitraryTarget() {
        var left = spell("fixture:bolt", "fixture.addon.Left");
        var right = spell("fixture:spells/fire/bolt", "fixture.addon.Right");
        var catalog = new SpellFormulaCatalog(List.of(left, right));
        var parsed = SpellFormulaFiles.parseResources(Map.of(left.id(), changed(), right.id(), changed(),
                SpellFormulaFiles.resourceKey(right), changed()), catalog);
        assertTrue(parsed.byClass().isEmpty());
        assertEquals(3, parsed.diagnostics().size());
        var snapshot = SpellFormulaFiles.snapshot(Map.of(left.className(), SpellFormulaFiles.parse(left, changed()),
                right.className(), SpellFormulaFiles.parse(right, changed())), List.of());
        assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.resourcesForSnapshot(snapshot));
    }

    @Test
    void collidingExportPathsAreRejectedWithoutWritingEitherSpell() throws Exception {
        var left = spell("fixture:a/b", "fixture.addon.Left");
        var right = new SpellFormulaCatalog.Spell("fixture:b", "fixture.addon.Right", "fixture:fire/a",
                left.advanced(), left.parameters(), left.formulas());
        var catalog = new SpellFormulaCatalog(List.of(left, right));
        var directory = java.nio.file.Path.of("build/formula-runtime-test").toAbsolutePath();
        java.nio.file.Files.createDirectories(directory);
        var root = java.nio.file.Files.createTempDirectory(directory, "addon-collision-");
        var diagnostics = SpellFormulaFiles.exportPack(root, catalog);
        assertEquals(2, diagnostics.size());
        assertFalse(java.nio.file.Files.exists(root.resolve("data/fixture/spells/fire/a/b.json")));
        var parsed = SpellFormulaFiles.parseResources(Map.of("fixture:spells/fire/a/b", changed()), catalog);
        assertTrue(parsed.byClass().isEmpty());
        assertEquals(1, parsed.diagnostics().size());
        var snapshot = SpellFormulaFiles.snapshot(Map.of(left.className(), SpellFormulaFiles.parse(left, changed()),
                right.className(), SpellFormulaFiles.parse(right, changed())), List.of());
        assertThrows(IllegalArgumentException.class, () -> SpellFormulaFiles.resourcesForSnapshot(snapshot));
    }

    @Test
    void originalSourceIdentityCoversHelperAndConstructorChanges() {
        var left = new org.objectweb.asm.tree.ClassNode();
        left.version = org.objectweb.asm.Opcodes.V17;
        left.name = "fixture/Addon";
        left.superName = "java/lang/Object";
        var method = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "helper", "()I", null, null);
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_1));
        method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        left.methods.add(method);
        var first = new FormulaAddonDiscovery.Discovery(FormulaClassRepository.of(left), List.of(left),
                java.util.Set.of(left.name), Map.of(left.name, "fixture"));
        String identity = first.fingerprint();
        method.instructions.set(method.instructions.getFirst(), new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_2));
        assertNotEquals(identity, first.fingerprint());
        var definition = spell("fixture:bolt", "fixture.addon.Bolt");
        assertNotEquals(new SpellFormulaCatalog(List.of(definition), identity).fingerprint(),
                new SpellFormulaCatalog(List.of(definition), first.fingerprint()).fingerprint());
    }

    private static SpellFormulaCatalog.Spell spell(String id, String name) {
        var parameters = new JsonObject();
        parameters.addProperty("cooldown_in_seconds", 10.0);
        return new SpellFormulaCatalog.Spell(id, name, "irons_spellbooks:fire", new JsonObject(), parameters, List.of());
    }

    private static JsonObject changed() {
        var result = new JsonObject();
        var parameters = new JsonObject();
        parameters.addProperty("cooldown_in_seconds", 12.0);
        result.add("parameters", parameters);
        return result;
    }
}
