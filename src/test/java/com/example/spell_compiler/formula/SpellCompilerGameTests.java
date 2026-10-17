package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.spells.fire.FireballSpell;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@GameTestHolder("spell_compiler_formula")
@PrefixGameTestTemplate(false)
public final class SpellCompilerGameTests {
    private SpellCompilerGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void independentCatalogAndExportPreserveAllNativeDefaults(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            var catalog = SpellFormulaState.catalog();
            var registered = SpellRegistry.REGISTRY.get().getValues().stream()
                    .map(spell -> spell.getClass().getName())
                    .filter(name -> name.startsWith("io.redspace.ironsspellbooks.spells."))
                    .collect(Collectors.toSet());
            helper.assertTrue(registered.size() == 109 && catalog.spells().stream().map(SpellFormulaCatalog.Spell::className)
                    .collect(Collectors.toSet()).equals(registered), "Independent catalog differs from the native registry");
            int publicFormulas = 0;
            int castFormulas = 0;
            for (var entry : catalog.spells()) {
                var spell = SpellRegistry.getSpell(entry.id());
                var exported = SpellFormulaFiles.preview(entry);
                helper.assertTrue(SpellFormulaFiles.parse(entry, exported.toString()).isEmpty(),
                        "Default export installed an override: " + entry.id());
                var formulas = exported.getAsJsonObject("formulas");
                publicFormulas += formulas.size();
                boolean editableCast = spell.getCastType() != CastType.INSTANT;
                helper.assertTrue(formulas.has("cast_time") == editableCast
                                && exported.getAsJsonObject("parameters").has("castTime") == editableCast,
                        "Cast controls differ from the native cast type: " + entry.id());
                if (editableCast) castFormulas++;
                helper.assertTrue(!formulas.has("cooldown_ticks") && !formulas.has("base_cast_time")
                                && !formulas.has("effective_cast_time"), "Internal timing control leaked into export: " + entry.id());
                for (String fieldName : List.of("baseManaCost", "manaCostPerLevel", "baseSpellPower", "spellPowerPerLevel", "castTime")) {
                    if (!entry.parameters().has(fieldName)) continue;
                    var field = AbstractSpell.class.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    helper.assertTrue(field.getInt(spell) == entry.parameters().get(fieldName).getAsInt(),
                            "Independent startup changed a native field: " + entry.id() + '/' + fieldName);
                }
            }
            helper.assertTrue(publicFormulas == 437 && castFormulas == 62, "Public formula/type coverage differs");
            Path directory = FMLPaths.GAMEDIR.get().resolve("spell_compiler");
            var server = helper.getLevel().getServer();
            var source = server.createCommandSourceStack().withPermission(2).withSuppressedOutput();
            var command = server.getCommands().getDispatcher().getRoot().getChild("spell_compiler");
            helper.assertTrue(command != null && !command.canUse(source.withPermission(1)), "Export permission differs");
            helper.assertTrue(server.getCommands().performPrefixedCommand(source, "spell_compiler export_spells") == 109,
                    "Independent export command did not export every native spell");
            helper.assertTrue(Files.isRegularFile(directory.resolve("pack.mcmeta")), "Export metadata is missing");
            try (var files = Files.walk(directory.resolve("data"))) {
                helper.assertTrue(files.filter(path -> path.toString().endsWith(".json")).count() == 109,
                        "Export file count differs from the native registry");
            }
            Path fireball = directory.resolve("data/irons_spellbooks/spells/fire/fireball.json");
            String changed = "{\"parameters\":{\"baseManaCost\":99}}";
            Files.writeString(fireball, changed);
            helper.assertTrue(server.getCommands().performPrefixedCommand(source, "spell_compiler export_spells") == 109
                            && Files.readString(fireball).equals(changed), "Repeated export replaced a player's edit");
            helper.assertTrue(SpellFormulaState.serverSnapshot().byClass().isEmpty(), "Export activated its files");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void everyNativeAndSiteFormulaCompilesInsideForgeModules(GameTestHelper helper) {
        try (var saved = new SavedState(helper)) {
            int compiledMethods = 0;
            for (var entry : SpellFormulaState.catalog().spells()) {
                var spell = SpellRegistry.getSpell(entry.id());
                Map<String, String> expressions = new LinkedHashMap<>();
                for (var method : entry.formulas())
                    expressions.put(method.key(), '(' + method.readableExpression() + ") + 0");
                var compiled = FormulaDelegateFactory.compileSpell(spell.getClass(), entry, expressions);
                helper.assertTrue(compiled.keySet().equals(expressions.keySet()), "Module compilation coverage differs: " + entry.id());
                compiledMethods += compiled.size();
            }
            int expected = SpellFormulaState.catalog().spells().stream().mapToInt(value -> value.formulas().size()).sum();
            helper.assertTrue(compiledMethods == expected && expected == 716,
                    "Not every native and site formula compiled: " + compiledMethods + '/' + expected);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void datapackPriorityReloadAndRemovalRestoreFieldsAndFormulas(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            var fireball = (FireballSpell) SpellRegistry.getSpell("irons_spellbooks:fireball");
            var definition = definition("fireball");
            String original = formula(definition, "damage");
            Path root = helper.getLevel().getServer().getWorldPath(LevelResource.DATAPACK_DIR).resolve("compiler-reload-probe");
            Path lower = root.resolve("lower");
            Path upper = root.resolve("upper");
            var lowerJson = configuration(Map.of("damage", '(' + original + ") + 7"), Map.of("baseManaCost", 63));
            var upperJson = configuration(Map.of("damage", '(' + original + ") + 9"), Map.of("baseManaCost", 71));
            write(lower, lowerJson);
            write(upper, upperJson);
            reload(helper, List.of(new PathPackResources("compiler_lower", lower, false)));
            helper.assertTrue(fireball.getManaCost(1) == 63, "Lower pack parameter did not apply");
            close(helper, fireball.getDamage(1, null), 17, "Lower pack formula did not apply");
            reload(helper, List.of(new PathPackResources("compiler_lower", lower, false),
                    new PathPackResources("compiler_upper", upper, false)));
            helper.assertTrue(fireball.getManaCost(1) == 71, "Higher priority parameter did not replace the lower resource");
            close(helper, fireball.getDamage(1, null), 19, "Higher priority formula did not replace the lower resource");
            reload(helper, List.of(new PathPackResources("compiler_lower", lower, false)));
            helper.assertTrue(fireball.getManaCost(1) == 63, "Removing the upper pack did not restore the lower parameter");
            close(helper, fireball.getDamage(1, null), 17, "Removing the upper pack did not restore the lower formula");
            reload(helper, List.of());
            helper.assertTrue(SpellFormulaState.serverSnapshot().byClass().isEmpty() && fireball.getManaCost(1) == 60,
                    "Removing all packs retained an override");
            close(helper, fireball.getDamage(1, null), 10, "Removing all packs did not restore the native formula");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void invalidMathFallsBackAndCooldownUsesOnlyItsNativeParameter(GameTestHelper helper) {
        try (var saved = new SavedState(helper)) {
            var definition = definition("fireball");
            var spell = (FireballSpell) SpellRegistry.getSpell(definition.id());
            int originalRadius = spell.getRadius(1, null);
            float nativeLevelFiveDamage = spell.getDamage(5, null);
            float nativeLevelFivePower = spell.getSpellPower(5, null);
            var invalid = configuration(Map.of("damage", '(' + formula(definition, "damage") + ") + 1 / (level - level)",
                    "radius", "int(sqrt(-1)) + 99 + (" + formula(definition, "radius") + ')'), Map.of());
            apply(helper, definition, invalid);
            for (int attempt = 0; attempt < 3; attempt++) {
                close(helper, spell.getDamage(1, null), 10, "Non-finite damage did not fall back to its native method");
                helper.assertTrue(spell.getRadius(1, null) == originalRadius, "Invalid integer conversion escaped fallback");
            }
            helper.assertTrue(nativeLevelFivePower > 1 && !Float.isFinite((float) (nativeLevelFivePower * 1e38d)),
                    "Level-five fixture does not overflow the hidden native float boundary");
            String overflowingPower = "(" + formula(definition, "damage").replaceAll("\\bspell_power\\b", "1e38")
                    + ") / 1e38";
            apply(helper, definition, configuration(Map.of("damage", overflowingPower), Map.of()));
            var damageMethod = definition.formulas().stream().filter(value -> value.alias().equals("damage")).findFirst().orElseThrow();
            String damageKey = FormulaRuntimeDispatcher.key(definition.className(), damageMethod.key());
            var delegate = FormulaRuntimeDispatcher.resolve(damageKey, null);
            helper.assertTrue(delegate != null, "Level-five overflow fixture did not install its delegate");
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            FormulaRuntimeDispatcher.replaceServer(Map.of(damageKey, (receiver, arguments) -> {
                calls.incrementAndGet();
                return delegate.invoke(receiver, arguments);
            }));
            float expectedLevelFive = (float) (nativeLevelFiveDamage / 1e38d);
            for (int attempt = 0; attempt < 3; attempt++) {
                float actual = spell.getDamage(5, null);
                helper.assertTrue(Float.isFinite(actual) && actual > 0 && Math.abs(actual / expectedLevelFive - 1) < 0.00001,
                        "Hidden power overflow used a different level or the whole-method fallback: actual=" + actual
                                + " expected=" + expectedLevelFive);
            }
            helper.assertTrue(calls.get() == 3, "Finite level-five recovery disabled or bypassed its compiled delegate");
            var valid = configuration(Map.of("damage", '(' + formula(definition, "damage") + ") + 7",
                    "mana_cost", "0.5 * (baseManaCost + manaCostPerLevel * (level - 1))"), Map.of());
            valid.getAsJsonObject("parameters").addProperty("cooldown_in_seconds", 25.6);
            apply(helper, definition, valid);
            close(helper, spell.getDamage(1, null), 17, "Reload did not recover the disabled damage formula");
            helper.assertTrue(spell.getManaCost(1) == 30 && spell.getManaCost(2) == 37,
                    "Mana final conversion lost native truncation");
            helper.assertTrue(SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.COOLDOWN_IN_SECONDS) == 25.6
                            && spell.getSpellCooldown() == 512, "Native cooldown parameter did not reach its original getter");
            var hidden = configuration(Map.of("cooldown_ticks", "1"), Map.of("baseManaCost", 777));
            var rejected = SpellFormulaFiles.parseResources(Map.of(definition.id(), hidden), SpellFormulaState.catalog());
            helper.assertTrue(rejected.byClass().isEmpty() && !rejected.diagnostics().isEmpty()
                            && spell.getManaCost(1) == 30 && spell.getSpellCooldown() == 512,
                    "An inaccessible cooldown formula partially changed the live spell");
            SpellFormulaRuntime.applyServer(empty());
            synchronize(helper);
            helper.assertTrue(spell.getManaCost(1) == 60 && spell.getSpellCooldown() == 500,
                    "Removing formulas and cooldown parameters did not restore native values");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void configuredLevelContextRetainsThePlayersEmbeddedPowerInputEdit(GameTestHelper helper) {
        try (var saved = new SavedState(helper)) {
            var definition = definition("fireball");
            var spell = (FireballSpell) SpellRegistry.getSpell(definition.id());
            String original = formula(definition, "damage");
            helper.assertTrue(original.startsWith("5 + 5 * ") && original.contains("spell_power"),
                    "Fireball native damage no longer follows the audited 5 + 5 * spell power basis");
            float originalPower = spell.getSpellPower(5, null);
            float originalDamage = spell.getDamage(5, null);
            var parameters = Map.of("baseSpellPower", 17, "spellPowerPerLevel", 2);
            apply(helper, definition, configuration(Map.of(), parameters));
            float configuredPower = spell.getSpellPower(5, null);
            float configuredDamage = spell.getDamage(5, null);
            float lowerPower = spell.getSpellPower(1, null);
            float lowerDamage = spell.getDamage(1, null);
            close(helper, configuredPower, 25, "Configured level-five native spell power differs");
            close(helper, configuredDamage, 5 + 5 * configuredPower, "Configured native damage basis differs");
            float slope = (configuredDamage - lowerDamage) / (configuredPower - lowerPower);
            float expected = configuredDamage + configuredPower * slope;
            String edited = original.replaceAll("\\bspell_power\\b", "2");
            helper.assertTrue(!edited.equals(original), "Embedded null-caster power input was not doubled from one to two");
            apply(helper, definition, configuration(Map.of("damage", edited), parameters));
            for (int attempt = 0; attempt < 3; attempt++) {
                close(helper, spell.getSpellPower(5, null), configuredPower,
                        "Editing damage changed the original spell power getter");
                close(helper, spell.getDamage(5, null), expected,
                        "Configured evaluation recomputed native power and erased the player's embedded input edit");
            }
            SpellFormulaRuntime.applyServer(empty());
            synchronize(helper);
            close(helper, spell.getSpellPower(5, null), originalPower, "Removing the combined override did not restore native power");
            close(helper, spell.getDamage(5, null), originalDamage, "Removing the combined override did not restore native damage");
        }
        helper.succeed();
    }

    private static SpellFormulaCatalog.Spell definition(String name) {
        return SpellFormulaState.catalog().spells().stream().filter(value -> value.id().equals("irons_spellbooks:" + name))
                .findFirst().orElseThrow();
    }

    private static String formula(SpellFormulaCatalog.Spell definition, String alias) {
        return definition.formulas().stream().filter(value -> value.alias().equals(alias)).findFirst().orElseThrow().readableExpression();
    }

    private static JsonObject configuration(Map<String, String> formulas, Map<String, Integer> parameters) {
        var json = new JsonObject();
        var formulaJson = new JsonObject();
        formulas.forEach(formulaJson::addProperty);
        json.add("formulas", formulaJson);
        var parameterJson = new JsonObject();
        parameters.forEach(parameterJson::addProperty);
        json.add("parameters", parameterJson);
        return json;
    }

    private static void apply(GameTestHelper helper, SpellFormulaCatalog.Spell definition, JsonObject json) {
        var parsed = SpellFormulaFiles.parseResources(Map.of(definition.id(), json), SpellFormulaState.catalog());
        helper.assertTrue(parsed.diagnostics().isEmpty(), "Independent configuration failed to parse: " + parsed.diagnostics());
        var applied = SpellFormulaRuntime.applyServer(parsed);
        synchronize(helper);
        helper.assertTrue(applied.diagnostics().isEmpty(), "Independent configuration failed to compile: " + applied.diagnostics());
    }

    private static void write(Path root, JsonObject json) throws Exception {
        Path file = root.resolve("data/irons_spellbooks/spells/fire/fireball.json");
        Files.createDirectories(file.getParent());
        Files.writeString(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"Spell compiler reload probe\"}}");
        Files.writeString(file, json.toString());
    }

    private static void reload(GameTestHelper helper, List<PackResources> packs) {
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs)) {
            var listener = new SpellFormulaReloadListener();
            listener.apply(listener.prepare(resources, InactiveProfiler.INSTANCE), resources, InactiveProfiler.INSTANCE);
        }
        synchronize(helper);
    }

    private static SpellFormulaFiles.Snapshot empty() {
        return SpellFormulaFiles.snapshot(Map.of(), List.of());
    }

    private static void synchronize(GameTestHelper helper) {
        MinecraftForge.EVENT_BUS.post(new OnDatapackSyncEvent(helper.getLevel().getServer().getPlayerList(), null));
    }

    private static void close(GameTestHelper helper, float actual, float expected, String message) {
        helper.assertTrue(Float.isFinite(actual) && Math.abs(actual - expected) < 0.0001,
                message + ": actual=" + actual + " expected=" + expected);
    }

    private static final class SavedState implements AutoCloseable {
        private final GameTestHelper helper;
        private final SpellFormulaFiles.Snapshot server;

        private SavedState(GameTestHelper helper) {
            this.helper = helper;
            server = SpellFormulaState.serverSnapshot();
            SpellFormulaRuntime.applyServer(empty());
            synchronize(helper);
        }

        @Override
        public void close() {
            SpellFormulaRuntime.applyServer(server);
            synchronize(helper);
        }
    }
}
