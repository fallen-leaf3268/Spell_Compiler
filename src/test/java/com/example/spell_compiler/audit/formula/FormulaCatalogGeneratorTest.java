package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaCatalogGenerator;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class FormulaCatalogGeneratorTest {
    @Test
    void extractsTheNativeCatalogWithoutInventingUnresolvedDefaults() throws Exception {
        Path jar = Path.of("libs", "ironsspells-3.16.1.jar");
        JsonObject catalog = FormulaCatalogGenerator.generate(jar);
        assertEquals(4, catalog.get("schemaVersion").getAsInt());
        assertEquals("b847b5a0d0b8d81ebf9f21f29fee9ad180a235b8bf983efa902eb67fc1485862",
                catalog.get("sourceSha256").getAsString());
        assertTrue(catalog.getAsJsonArray("classErrors").isEmpty());
        assertTrue(catalog.getAsJsonArray("rejected").asList().stream()
                .allMatch(value -> value.getAsJsonObject().has("name")));
        var repository = FormulaClassRepository.fromJar(jar);
        var registeredClasses = new HashSet<String>();
        var registry = repository.classNode("io/redspace/ironsspellbooks/api/registry/SpellRegistry");
        var initializer = registry.methods.stream().filter(method -> method.name.equals("<clinit>")).findFirst().orElseThrow();
        int registrations = 0;
        for (var instruction : initializer.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("registerSpell")) continue;
            registrations++;
            var previous = call.getPrevious();
            while (previous.getOpcode() < 0) previous = previous.getPrevious();
            var constructor = assertInstanceOf(MethodInsnNode.class, previous);
            assertEquals("<init>", constructor.name);
            assertEquals("()V", constructor.desc);
            assertTrue(repository.isSubtype(constructor.owner, "io/redspace/ironsspellbooks/api/spells/AbstractSpell"));
            registeredClasses.add(constructor.owner.replace('/', '.'));
        }
        assertEquals(109, registrations);
        assertEquals(registrations, registeredClasses.size());
        var catalogClasses = new HashSet<String>();
        catalog.getAsJsonArray("spells").forEach(value -> catalogClasses.add(value.getAsJsonObject().get("className").getAsString()));
        assertEquals(registeredClasses, catalogClasses);
        assertEquals(registeredClasses.size(), catalog.get("registeredSpells").getAsInt());
        var exclusions = new java.util.HashMap<String, String>();
        catalog.getAsJsonArray("excluded").forEach(value -> exclusions.put(value.getAsJsonObject().get("className").getAsString(),
                value.getAsJsonObject().get("kind").getAsString()));
        assertEquals(java.util.Map.of(
                "io.redspace.ironsspellbooks.spells.eldritch.AbstractEldritchSpell", "abstract_class",
                "io.redspace.ironsspellbooks.spells.ender.GravityFissureSpell", "not_registered",
                "io.redspace.ironsspellbooks.spells.fire.SoulfireRaySpell", "not_registered",
                "io.redspace.ironsspellbooks.spells.lightning.ThunderStepSpell", "not_registered"), exclusions);
        assertFalse(catalogClasses.contains("io.redspace.ironsspellbooks.spells.NoneSpell"));
        var abstractClasses = new HashSet<String>();
        int scannedClasses = 0;
        try (var source = new JarFile(jar.toFile())) {
            for (var entry : source.stream().filter(value -> value.getName().startsWith("io/redspace/ironsspellbooks/spells/")
                    && value.getName().substring("io/redspace/ironsspellbooks/spells/".length()).contains("/")
                    && value.getName().endsWith(".class") && !value.getName().contains("$")).toList()) {
                String name = entry.getName().substring(0, entry.getName().length() - 6);
                scannedClasses++;
                if ((repository.classNode(name).access & Opcodes.ACC_ABSTRACT) != 0) abstractClasses.add(name);
            }
        }
        assertEquals(113, scannedClasses);
        assertEquals(java.util.Set.of("io/redspace/ironsspellbooks/spells/eldritch/AbstractEldritchSpell"), abstractClasses);
        var ids = new HashSet<String>();
        int formulaCount = 0;
        int declaredFormulaCount = 0;
        int inheritedFormulaCount = 0;
        int siteFormulaCount = 0;
        int expectedDeclaredFormulaCount = 0;
        int expectedInheritedFormulaCount = 0;
        int expectedRejectedMethods = 0;
        Map<String, String> inheritedBindings = Map.of(
                "getSpellPower(ILnet/minecraft/world/entity/Entity;)F", "spell_power",
                "getManaCost(I)I", "mana_cost",
                "getSpellCooldown()I", "cooldown_ticks",
                "getCastTime(I)I", "base_cast_time",
                "getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I", "cast_time");
        for (var value : catalog.getAsJsonArray("spells")) {
            JsonObject spell = value.getAsJsonObject();
            assertTrue(ids.add(spell.get("id").getAsString()));
            JsonObject advanced = spell.getAsJsonObject("advanced");
            JsonObject parameters = spell.getAsJsonObject("parameters");
            assertEquals(spell.get("school"), advanced.get("school"));
            assertTrue(advanced.get("max_level").getAsInt() > 0);
            assertTrue(parameters.get("cooldown_in_seconds").getAsDouble() >= 0);
            assertEquals(1.0, parameters.get("power_multiplier").getAsDouble());
            assertEquals(1.0, parameters.get("mana_cost_multiplier").getAsDouble());
            var aliases = new HashSet<String>();
            var owner = repository.classNode(spell.get("className").getAsString().replace('.', '/'));
            Set<String> expectedDeclared = new HashSet<>();
            for (var method : owner.methods) {
                int sort = Type.getReturnType(method.desc).getSort();
                if (!Set.of(Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE).contains(sort)) continue;
                if (!com.example.spell_compiler.formula.FormulaConsumerSites.excludedGetter(owner.name, method.name + method.desc)
                        && AutomaticFormulaExtractor.analyze(owner, method, repository).supported())
                    expectedDeclared.add(method.name + method.desc);
                else expectedRejectedMethods++;
            }
            Set<String> expectedInherited = new HashSet<>();
            for (var binding : inheritedBindings.entrySet()) {
                int start = binding.getKey().indexOf('(');
                var resolved = repository.resolveMethod(owner.name, binding.getKey().substring(0, start),
                        binding.getKey().substring(start));
                if (resolved != null && resolved.owner().name.equals("io/redspace/ironsspellbooks/api/spells/AbstractSpell")
                        && AutomaticFormulaExtractor.analyze(resolved.owner(), resolved.method(), repository).supported())
                    expectedInherited.add(binding.getKey());
            }
            Set<String> declaredForSpell = new HashSet<>();
            Set<String> inheritedForSpell = new HashSet<>();
            for (var entry : spell.getAsJsonArray("formulas")) {
                JsonObject formula = entry.getAsJsonObject();
                assertTrue(aliases.add(formula.get("alias").getAsString()));
                String declaring = formula.get("declaringClass").getAsString();
                boolean site = declaring.equals("com.example.spell_compiler.formula.FormulaSiteValues");
                var resolved = repository.resolveMethod(site ? declaring.replace('.', '/') : owner.name,
                        formula.get("name").getAsString(),
                        formula.get("descriptor").getAsString());
                assertNotNull(resolved);
                assertEquals(resolved.owner().name.replace('/', '.'), formula.get("declaringClass").getAsString());
                var analyzed = AutomaticFormulaExtractor.analyze(resolved.owner(), resolved.method(), repository,
                        site ? null : owner.name);
                assertTrue(analyzed.supported());
                assertEquals(analyzed.formula().expression(), formula.get("strictExpression").getAsString());
                assertEquals(analyzed.formula().readableExpression(), formula.get("readableExpression").getAsString());
                formula.getAsJsonObject("variables").entrySet().forEach(variable -> {
                    Type type = Type.getType(variable.getValue().getAsString());
                    assertTrue(java.util.Set.of(Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE).contains(type.getSort()));
                });
                formulaCount++;
                if (site) {
                    assertTrue((resolved.method().access & Opcodes.ACC_STATIC) != 0);
                    assertTrue((resolved.method().access & Opcodes.ACC_PUBLIC) != 0);
                    siteFormulaCount++;
                } else if (resolved.owner().name.equals(owner.name)) {
                    assertTrue(declaredForSpell.add(resolved.method().name + resolved.method().desc));
                    declaredFormulaCount++;
                } else {
                    assertEquals("io/redspace/ironsspellbooks/api/spells/AbstractSpell", resolved.owner().name);
                    String key = resolved.method().name + resolved.method().desc;
                    assertEquals(inheritedBindings.get(key), formula.get("alias").getAsString());
                    assertTrue(inheritedForSpell.add(key));
                    inheritedFormulaCount++;
                }
            }
            assertEquals(expectedDeclared, declaredForSpell, spell.get("id").getAsString());
            assertEquals(expectedInherited, inheritedForSpell, spell.get("id").getAsString());
            expectedDeclaredFormulaCount += expectedDeclared.size();
            expectedInheritedFormulaCount += expectedInherited.size();
        }
        assertEquals(expectedDeclaredFormulaCount, declaredFormulaCount);
        assertEquals(expectedInheritedFormulaCount, inheritedFormulaCount);
        assertEquals(declaredFormulaCount + inheritedFormulaCount + siteFormulaCount, formulaCount);
        assertEquals(formulaCount, catalog.get("supportedMethods").getAsInt());
        assertEquals(expectedRejectedMethods, catalog.getAsJsonArray("rejected").size());
        assertEquals(expectedRejectedMethods, catalog.get("unsupportedMethods").getAsInt());
        JsonObject fireball = catalog.getAsJsonArray("spells").asList().stream().map(value -> value.getAsJsonObject())
                .filter(value -> value.get("id").getAsString().equals("irons_spellbooks:fireball")).findFirst().orElseThrow();
        assertEquals("irons_spellbooks:fire", fireball.get("school").getAsString());
        assertEquals("rare", fireball.getAsJsonObject("advanced").get("min_rarity").getAsString());
        assertEquals(5, fireball.getAsJsonObject("advanced").get("max_level").getAsInt());
        assertEquals(60, fireball.getAsJsonObject("parameters").get("baseManaCost").getAsInt());
        assertEquals(15, fireball.getAsJsonObject("parameters").get("manaCostPerLevel").getAsInt());
        assertEquals(40, fireball.getAsJsonObject("parameters").get("castTime").getAsInt());
        assertEquals(25.0, fireball.getAsJsonObject("parameters").get("cooldown_in_seconds").getAsDouble());
    }
}
