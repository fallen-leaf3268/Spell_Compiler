package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import io.redspace.ironsspellbooks.spells.fire.FireballSpell;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@GameTestHolder("spell_compiler_formula")
@PrefixGameTestTemplate(false)
public final class SpellCompilerWideGameTests {
    private static final String SITE_VALUES = "com.example.spell_compiler.formula.FormulaSiteValues";

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void everyCatalogFormulaRunsThroughItsRegisteredMethod(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            var player = player(helper, "Catalog");
            int nativeMethods = 0;
            int nativeComparisons = 0;
            int siteMethods = 0;
            int siteComparisons = 0;
            int excludedNullComparisons = 0;
            List<String> unsupported = new ArrayList<>();
            for (var definition : SpellFormulaState.catalog().spells()) {
                AbstractSpell spell = spell(definition);
                for (var formula : definition.formulas()) {
                    BoundMethod method = method(spell, formula);
                    if (!supportedArguments(method)) {
                        unsupported.add(definition.id() + '#' + formula.key());
                        continue;
                    }
                    String expression = '(' + formula.readableExpression() + ") + 7";
                    var compiled = FormulaDelegateFactory.compileSpell(spell.getClass(), definition, Map.of(formula.key(), expression));
                    helper.assertTrue(compiled.keySet().equals(Set.of(formula.key())), "Missing catalog delegate: " + formula.key());
                    AtomicInteger calls = new AtomicInteger();
                    var delegate = compiled.get(formula.key());
                    FormulaRuntimeDispatcher.Invoker counted = (receiver, arguments) -> {
                        calls.incrementAndGet();
                        return delegate.invoke(receiver, arguments);
                    };
                    String key = FormulaRuntimeDispatcher.key(definition.className(), formula.key());
                    for (int level : new int[]{1, Math.max(1, definition.advanced().get("max_level").getAsInt())}) {
                        for (LivingEntity caster : new LivingEntity[]{null, player}) {
                            if (caster == null && (definition.id().equals("irons_spellbooks:sacrifice")
                                    && formula.name().equals("getRadius") || definition.id().equals("irons_spellbooks:flaming_strike")
                                    && formula.name().equals("getDamage"))) {
                                FormulaRuntimeDispatcher.clearServer();
                                expectNullActorFailure(helper, method, level);
                                FormulaRuntimeDispatcher.replaceServer(Map.of(key, counted));
                                int before = calls.get();
                                expectNullActorFailure(helper, method, level);
                                helper.assertTrue(calls.get() == before + 1, "Null actor rejection did not exercise the delegate: " + key);
                                excludedNullComparisons++;
                                continue;
                            }
                            if (caster != null) caster.setHealth(caster.getMaxHealth());
                            FormulaRuntimeDispatcher.clearServer();
                            Number baseline = invoke(method, level, caster);
                            if (!method.site()) FormulaRuntimeDispatcher.replaceServer(Map.of(key, counted));
                            int before = calls.get();
                            Number actual = method.site()
                                    ? invokeDelegate(counted, method, level, caster) : invoke(method, level, caster);
                            Number expectedResult = spell.getCastType() == io.redspace.ironsspellbooks.api.spells.CastType.INSTANT
                                    && formula.declaringClass().equals("io.redspace.ironsspellbooks.api.spells.AbstractSpell")
                                    && Set.of("getCastTime", "getEffectiveCastTime").contains(formula.name())
                                    ? Integer.valueOf(0) : plusSeven(baseline);
                            same(helper, expectedResult, actual, definition.id() + '#' + formula.alias()
                                    + " level=" + level + " caster=" + (caster != null));
                            helper.assertTrue(calls.get() == before + 1, "Formula comparison did not invoke its delegate exactly once: " + key);
                            if (method.site()) siteComparisons++;
                            else nativeComparisons++;
                        }
                    }
                    FormulaRuntimeDispatcher.clearServer();
                    if (method.site()) siteMethods++;
                    else nativeMethods++;
                }
            }
            helper.assertTrue(unsupported.isEmpty(), "Catalog methods require unsupported test arguments: " + unsupported);
            int expectedNative = (int) SpellFormulaState.catalog().spells().stream().flatMap(value -> value.formulas().stream())
                    .filter(value -> !value.declaringClass().equals(SITE_VALUES)).count();
            int expectedSites = (int) SpellFormulaState.catalog().spells().stream().flatMap(value -> value.formulas().stream())
                    .filter(value -> value.declaringClass().equals(SITE_VALUES)).count();
            helper.assertTrue(excludedNullComparisons == 4, "Unexpected native null-caster rejection count");
            helper.assertTrue(nativeMethods == expectedNative && nativeComparisons == expectedNative * 4 - excludedNullComparisons,
                    "Native method execution coverage differs: " + nativeMethods + '/' + nativeComparisons);
            helper.assertTrue(siteMethods == expectedSites && siteComparisons == expectedSites * 4,
                    "Pure site-helper comparison coverage differs: " + siteMethods + '/' + siteComparisons);
            System.out.println("SPELL_COMPILER_WIDE_CATALOG native_methods=" + nativeMethods + " native_comparisons="
                    + nativeComparisons + " site_helpers=" + siteMethods + " site_comparisons=" + siteComparisons
                    + " excluded_null_comparisons=" + excludedNullComparisons);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void eachExpandedPowerFactorCanBeEditedAcrossSchools(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            ServerPlayer player = player(helper, "Factors");
            AttributeInstance global = player.getAttribute(AttributeRegistry.SPELL_POWER.get());
            double previousGlobal = global.getBaseValue();
            int comparisons = 0;
            try {
                global.setBaseValue(1);
                for (String school : List.of("fire", "ice", "lightning", "holy")) {
                    var definition = SpellFormulaState.catalog().spells().stream()
                            .filter(value -> value.school().equals("irons_spellbooks:" + school))
                            .filter(value -> value.formulas().stream().anyMatch(SpellCompilerWideGameTests::hasAllPowerInputs))
                            .findFirst().orElseThrow();
                    AbstractSpell spell = spell(definition);
                    var formula = definition.formulas().stream().filter(SpellCompilerWideGameTests::hasAllPowerInputs)
                            .findFirst().orElseThrow();
                    BoundMethod method = method(spell, formula);
                    var schoolAttribute = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.parse("irons_spellbooks:" + school + "_spell_power"));
                    AttributeInstance schoolPower = player.getAttribute(schoolAttribute);
                    helper.assertTrue(schoolPower != null, "Player has no school attribute: " + school);
                    double previousSchool = schoolPower.getBaseValue();
                    Map<String, Integer> previousFields = baseValues(spell);
                    try {
                        schoolPower.setBaseValue(1);
                        for (String factor : List.of("level", "baseSpellPower", "spellPowerPerLevel", "spell_power", "school_spell_power", "power_multiplier")) {
                            String value = switch (factor) {
                                case "level" -> "int(3)";
                                case "baseSpellPower" -> "int(13)";
                                case "spellPowerPerLevel" -> "int(4)";
                                case "spell_power" -> "double(1.75)";
                                case "school_spell_power" -> "double(1.3)";
                                case "power_multiplier" -> "float(1.25)";
                                default -> throw new IllegalStateException(factor);
                            };
                            FormulaRuntimeDispatcher.clearServer();
                            int referenceLevel = factor.equals("level") ? 3 : 2;
                            if (factor.equals("baseSpellPower")) setBase(spell, factor, 13);
                            if (factor.equals("spellPowerPerLevel")) setBase(spell, factor, 4);
                            if (factor.equals("spell_power")) global.setBaseValue(1.75);
                            if (factor.equals("school_spell_power")) schoolPower.setBaseValue(1.3);
                            if (factor.equals("power_multiplier")) {
                                var json = new JsonObject();
                                var parameters = new JsonObject();
                                parameters.addProperty("power_multiplier", 1.25);
                                json.add("parameters", parameters);
                                apply(helper, definition, json);
                            }
                            Number expected = invoke(method, referenceLevel, player);
                            SpellFormulaRuntime.applyServer(empty());
                            synchronize(helper);
                            restoreBase(spell, previousFields);
                            global.setBaseValue(1);
                            schoolPower.setBaseValue(1);
                            String expression = formula.readableExpression().replaceAll("\\b" + factor + "\\b", value);
                            install(spell, formula, expression);
                            same(helper, expected, invoke(method, 2, player), school + '/' + factor);
                            comparisons++;
                        }
                    } finally {
                        FormulaRuntimeDispatcher.clearServer();
                        schoolPower.setBaseValue(previousSchool);
                        restoreBase(spell, previousFields);
                    }
                }
            } finally {
                global.setBaseValue(previousGlobal);
            }
            helper.assertTrue(comparisons == 24, "Independent school/input coverage differs: " + comparisons);
            System.out.println("SPELL_COMPILER_WIDE_FACTORS comparisons=" + comparisons);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void typedMathFunctionsReachTheRealHealingCircle(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            var definition = definition("healing_circle");
            AbstractSpell spell = spell(definition);
            var formula = formula(definition, "radius");
            BoundMethod method = method(spell, formula);
            Map<String, Float> examples = new LinkedHashMap<>();
            examples.put("5 / 2", 2.5f);
            examples.put("int(5) / 2", 2f);
            examples.put("long(5) / 2", 2f);
            examples.put("float(5) / 2", 2.5f);
            examples.put("double(5) / 2", 2.5f);
            examples.put("ceil(4 / 3)", 1f);
            examples.put("ceil(4.0 / 3)", 2f);
            examples.put("floor(5.9)", 5f);
            examples.put("sqrt(81)", 9f);
            examples.put("abs(-7)", 7f);
            examples.put("round(2.5)", 3f);
            examples.put("round(-2.5f)", -2f);
            examples.put("min(3, 7)", 3f);
            examples.put("max(3, 7)", 7f);
            examples.put("clamp(level, 1, 4)", 4f);
            examples.put("mth_floor(-2.5)", -3f);
            examples.put("mth_ceil(-2.5)", -2f);
            examples.put("int(long(5)) / 2", 2f);
            for (var example : examples.entrySet()) {
                install(spell, formula, example.getKey());
                same(helper, example.getValue(), invoke(method, 5, null), example.getKey());
            }
            System.out.println("SPELL_COMPILER_WIDE_MATH cases=" + examples.size());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void chargeGroupingChangesTheRealFloatingPointResult(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            var definition = definition("charge");
            AbstractSpell spell = spell(definition);
            var formula = formula(definition, "percent_attack_damage");
            BoundMethod method = method(spell, formula);
            float original = invoke(method, 3, null).floatValue();
            same(helper, (3f * 0.1f) * 100f, original, "Native Charge grouping");
            helper.assertTrue(Float.floatToRawIntBits(original) != Float.floatToRawIntBits(30f), "Native Charge no longer exercises rounding distinction");
            for (var example : Map.of("level * (0.1 * 100)", 30f,
                    "level * (0.1 * 200)", 60f,
                    "ceil(level * (0.1 * 100))", 30f).entrySet()) {
                install(spell, formula, example.getKey());
                same(helper, example.getValue(), invoke(method, 3, null), example.getKey());
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void failedDelegateIsDisabledAndReloadingTheCachedDelegateRecovers(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            var definition = definition("healing_circle");
            AbstractSpell spell = spell(definition);
            var formula = formula(definition, "radius");
            BoundMethod method = method(spell, formula);
            float nativeRadius = invoke(method, 1, null).floatValue();
            for (String tail : List.of("1 / (level - 1)", "1.0 / (level - 1)")) {
                String expression = '(' + formula.readableExpression() + ") + " + tail;
                Map<String, String> changes = Map.of(formula.key(), expression);
                var compiled = FormulaDelegateFactory.compileSpell(spell.getClass(), definition, changes);
                var underlying = FormulaDelegateFactory.compile(spell.getClass(), changes);
                var cached = FormulaDelegateFactory.compile(spell.getClass(), changes);
                helper.assertTrue(underlying.get(formula.key()) == cached.get(formula.key()), "Delegate cache did not reuse unchanged compiled formulas");
                AtomicInteger calls = new AtomicInteger();
                FormulaRuntimeDispatcher.Invoker delegate = (receiver, arguments) -> {
                    calls.incrementAndGet();
                    return compiled.get(formula.key()).invoke(receiver, arguments);
                };
                String key = FormulaRuntimeDispatcher.key(definition.className(), formula.key());
                FormulaRuntimeDispatcher.replaceServer(Map.of(key, delegate));
                same(helper, nativeRadius, invoke(method, 1, null), "Initial failed formula fallback");
                same(helper, nativeRadius, invoke(method, 2, null), "Disabled formula fallback");
                helper.assertTrue(calls.get() == 1, "Disabled formula executed again after its first failure");
                FormulaRuntimeDispatcher.replaceServer(Map.of(key, delegate));
                same(helper, nativeRadius + 1f, invoke(method, 2, null), "Reloaded cached formula recovery");
                helper.assertTrue(calls.get() == 2, "Reinstalled cached formula retained its disabled state");
                FormulaRuntimeDispatcher.clearServer();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void allNativeParametersAndAdvancedSettingsReachTheRegistryAndReset(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            var definition = definition("fireball");
            AbstractSpell spell = spell(definition);
            Map<String, Integer> originalFields = baseValues(spell);
            var fire = SchoolRegistry.getSchool(ResourceLocation.parse("irons_spellbooks:fire"));
            var ice = SchoolRegistry.getSchool(ResourceLocation.parse("irons_spellbooks:ice"));
            helper.assertTrue(SpellRegistry.getSpellsForSchool(fire).contains(spell)
                    && !SpellRegistry.getSpellsForSchool(ice).contains(spell), "Native school cache differs");
            JsonObject json = new JsonObject();
            JsonObject advanced = new JsonObject();
            advanced.addProperty("enabled", false);
            advanced.addProperty("max_level", 7);
            advanced.addProperty("min_rarity", "epic");
            advanced.addProperty("allow_crafting", false);
            advanced.addProperty("school", "irons_spellbooks:ice");
            json.add("advanced", advanced);
            JsonObject parameters = new JsonObject();
            parameters.addProperty("baseManaCost", 73);
            parameters.addProperty("manaCostPerLevel", 9);
            parameters.addProperty("baseSpellPower", 17);
            parameters.addProperty("spellPowerPerLevel", 6);
            parameters.addProperty("castTime", 22);
            parameters.addProperty("cooldown_in_seconds", 12.5);
            parameters.addProperty("power_multiplier", 1.4);
            parameters.addProperty("mana_cost_multiplier", 0.75);
            json.add("parameters", parameters);
            apply(helper, definition, json);
            helper.assertTrue(baseValues(spell).equals(Map.of("baseManaCost", 73, "manaCostPerLevel", 9,
                    "baseSpellPower", originalFields.get("baseSpellPower"), "spellPowerPerLevel", originalFields.get("spellPowerPerLevel"),
                    "castTime", 22)), "Native integer settings or preserved power fields differ");
            helper.assertTrue(!spell.isEnabled() && spell.getMaxLevel() == 7 && spell.getSchoolType() == ice,
                    "Native enabled/max level/school getters differ");
            helper.assertTrue(SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.MIN_RARITY) == SpellRarity.EPIC
                            && !SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.ALLOW_CRAFTING),
                    "Minimum rarity or crafting setting did not apply");
            helper.assertTrue(SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.COOLDOWN_IN_SECONDS) == 12.5
                            && SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.POWER_MULTIPLIER) == 1.4
                            && SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.MANA_MULTIPLIER) == 0.75,
                    "Native cooldown or multiplier settings did not apply");
            helper.assertTrue(spell.getManaCost(2) == 61 && spell.getCastTime(2) == 22 && spell.getSpellCooldown() == 250,
                    "Native mana/cast/cooldown calculations ignored parameters");
            same(helper, (float) ((17 + 6 * (2 - 1)) * (double) (float) 1.4), spell.getSpellPower(2, null),
                    "Native power multiplier result");
            advanced.addProperty("enabled", true);
            apply(helper, definition, json);
            helper.assertTrue(spell.isEnabled(), "Enabling the configured spell did not apply");
            helper.assertTrue(!SpellRegistry.getSpellsForSchool(fire).contains(spell)
                    && SpellRegistry.getSpellsForSchool(ice).contains(spell), "School cache did not change");
            SpellFormulaRuntime.applyServer(empty());
            synchronize(helper);
            helper.assertTrue(baseValues(spell).equals(originalFields) && spell.getSchoolType() == fire
                            && spell.isEnabled() && spell.getMaxLevel() == definition.advanced().get("max_level").getAsInt(),
                    "Removed native settings did not reset");
            helper.assertTrue(SpellRegistry.getSpellsForSchool(fire).contains(spell)
                    && !SpellRegistry.getSpellsForSchool(ice).contains(spell), "Removed school override left stale cache");
            helper.assertTrue(SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.MIN_RARITY) == SpellRarity.RARE
                            && SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.ALLOW_CRAFTING)
                            && SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.POWER_MULTIPLIER) == 1.0
                            && SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.MANA_MULTIPLIER) == 1.0
                            && SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.COOLDOWN_IN_SECONDS) == 25.0,
                    "Removed config values did not reset to native values");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void unregisteredSchoolRejectsEverySettingOfThatSpell(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            var definition = definition("fireball");
            var fireball = (FireballSpell) spell(definition);
            Map<String, Integer> original = baseValues(fireball);
            float damage = fireball.getDamage(1, null);
            JsonObject json = new JsonObject();
            JsonObject advanced = new JsonObject();
            advanced.addProperty("school", "spell_compiler:missing_formula_school");
            json.add("advanced", advanced);
            JsonObject parameters = new JsonObject();
            parameters.addProperty("baseManaCost", 777);
            json.add("parameters", parameters);
            JsonObject formulas = new JsonObject();
            formulas.addProperty("damage", '(' + formula(definition, "damage").readableExpression() + ") + 7");
            json.add("formulas", formulas);
            var candidate = SpellFormulaFiles.parseResources(Map.of(definition.id(), json), SpellFormulaState.catalog());
            helper.assertTrue(candidate.diagnostics().isEmpty(), "Missing-school fixture failed before registry validation");
            var result = SpellFormulaRuntime.applyServer(candidate);
            synchronize(helper);
            helper.assertTrue(result.byClass().isEmpty() && result.diagnostics().size() == 1
                            && result.diagnostics().get(0).cause() instanceof IllegalArgumentException,
                    "Unregistered school was not rejected with its original cause");
            helper.assertTrue(baseValues(fireball).equals(original)
                            && fireball.getSchoolType().getId().toString().equals("irons_spellbooks:fire"),
                    "Rejected school partially changed fields or native school");
            same(helper, damage, fireball.getDamage(1, null), "Rejected school partially installed formula");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void arrowVolleyNativeParentAndOverriddenParentUseTheirRespectiveFormulas(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            var definition = definition("arrow_volley");
            AbstractSpell spell = spell(definition);
            var count = formula(definition, "count");
            var rows = formula(definition, "rows");
            var arrows = formula(definition, "arrows_per_row");
            BoundMethod countMethod = method(spell, count);
            BoundMethod rowsMethod = method(spell, rows);
            BoundMethod arrowsMethod = method(spell, arrows);
            var player = player(helper, "DepsArrow");
            int originalRows = invoke(rowsMethod, 3, player).intValue();
            int originalArrows = invoke(arrowsMethod, 3, player).intValue();
            int originalCount = invoke(countMethod, 3, player).intValue();
            helper.assertTrue(originalCount == originalRows * originalArrows, "Native ArrowVolley count no longer calls its row helpers");
            JsonObject json = new JsonObject();
            JsonObject formulas = new JsonObject();
            formulas.addProperty(rows.alias(), '(' + rows.readableExpression() + ") + 2");
            formulas.addProperty(arrows.alias(), '(' + arrows.readableExpression() + ") + 3");
            json.add("formulas", formulas);
            apply(helper, definition, json);
            helper.assertTrue(invoke(rowsMethod, 3, player).intValue() == originalRows + 2
                            && invoke(arrowsMethod, 3, player).intValue() == originalArrows + 3,
                    "ArrowVolley child overrides did not reach their registered methods");
            int inheritedCount = (originalRows + 2) * (originalArrows + 3);
            helper.assertTrue(invoke(countMethod, 3, player).intValue() == inheritedCount,
                    "Native ArrowVolley parent did not call the modified child methods");
            formulas.addProperty(count.alias(), '(' + count.readableExpression() + ") + 7");
            apply(helper, definition, json);
            helper.assertTrue(invoke(rowsMethod, 3, player).intValue() == originalRows + 2
                            && invoke(arrowsMethod, 3, player).intValue() == originalArrows + 3,
                    "Installing the parent formula removed ArrowVolley child overrides");
            helper.assertTrue(invoke(countMethod, 3, player).intValue() == originalCount + 7,
                    "Overridden ArrowVolley parent did not use its own complete expanded formula");
            helper.assertTrue(originalCount + 7 != inheritedCount + 7,
                    "ArrowVolley fixture does not distinguish complete-parent replacement from child recomposition");
            System.out.println("SPELL_COMPILER_WIDE_ARROW_DEPENDENCIES native=" + originalCount
                    + " child_only=" + inheritedCount + " parent_and_children=" + (originalCount + 7));
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void earthquakeNativeParentAndOverriddenParentUseTheirRespectiveFormulas(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            var definition = definition("earthquake");
            AbstractSpell spell = spell(definition);
            var damage = formula(definition, "damage");
            var slowness = formula(definition, "effect_level");
            BoundMethod damageMethod = method(spell, damage);
            BoundMethod slownessMethod = method(spell, slowness);
            var player = player(helper, "DepsQuake");
            AttributeInstance global = player.getAttribute(AttributeRegistry.SPELL_POWER.get());
            var natureAttribute = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.parse("irons_spellbooks:nature_spell_power"));
            AttributeInstance nature = player.getAttribute(natureAttribute);
            double previousGlobal = global.getBaseValue();
            double previousNature = nature.getBaseValue();
            try {
                global.setBaseValue(1);
                nature.setBaseValue(1);
                float originalDamage = invoke(damageMethod, 1, player).floatValue();
                int originalSlowness = invoke(slownessMethod, 1, player).intValue();
                int nativeExpected = Math.min(Math.max((int) originalDamage - 2, 0), 2);
                helper.assertTrue(originalSlowness == nativeExpected, "Native Earthquake slowness no longer calls its damage helper");
                JsonObject json = new JsonObject();
                JsonObject formulas = new JsonObject();
                formulas.addProperty(damage.alias(), '(' + damage.readableExpression() + ") + 4");
                json.add("formulas", formulas);
                apply(helper, definition, json);
                same(helper, originalDamage + 4f, invoke(damageMethod, 1, player), "Earthquake modified child damage");
                int inheritedSlowness = Math.min(Math.max((int) (originalDamage + 4f) - 2, 0), 2);
                helper.assertTrue(inheritedSlowness != originalSlowness, "Earthquake fixture does not distinguish child propagation");
                helper.assertTrue(invoke(slownessMethod, 1, player).intValue() == inheritedSlowness,
                        "Native Earthquake parent did not call its modified damage child");
                formulas.addProperty(slowness.alias(), '(' + slowness.readableExpression() + ") + 1");
                apply(helper, definition, json);
                same(helper, originalDamage + 4f, invoke(damageMethod, 1, player), "Earthquake child damage after parent installation");
                helper.assertTrue(invoke(slownessMethod, 1, player).intValue() == originalSlowness + 1,
                        "Overridden Earthquake parent did not use its own complete expanded formula");
                helper.assertTrue(originalSlowness + 1 != inheritedSlowness + 1,
                        "Earthquake fixture does not distinguish complete-parent replacement from child recomposition");
                System.out.println("SPELL_COMPILER_WIDE_EARTHQUAKE_DEPENDENCIES native=" + originalSlowness
                        + " child_only=" + inheritedSlowness + " parent_and_child=" + (originalSlowness + 1));
            } finally {
                global.setBaseValue(previousGlobal);
                nature.setBaseValue(previousNature);
            }
        }
        helper.succeed();
    }

    private static boolean hasAllPowerInputs(SpellFormulaCatalog.Method method) {
        return method.variables().keySet().containsAll(Set.of("level", "baseSpellPower", "spellPowerPerLevel",
                "spell_power", "school_spell_power", "power_multiplier"));
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(
                UUID.nameUUIDFromBytes(("formula-wide-" + name).getBytes(StandardCharsets.UTF_8)), "[Wide" + name + ']'));
    }

    private static SpellFormulaCatalog.Spell definition(String path) {
        return SpellFormulaState.catalog().spells().stream().filter(value -> value.id().equals("irons_spellbooks:" + path))
                .findFirst().orElseThrow();
    }

    private static SpellFormulaCatalog.Method formula(SpellFormulaCatalog.Spell definition, String alias) {
        return definition.formulas().stream().filter(value -> value.alias().equals(alias)).findFirst().orElseThrow();
    }

    private static AbstractSpell spell(SpellFormulaCatalog.Spell definition) {
        var spell = SpellRegistry.REGISTRY.get().getValue(ResourceLocation.parse(definition.id()));
        if (spell == null || !spell.getClass().getName().equals(definition.className()))
            throw new IllegalStateException("Catalog registration differs: " + definition.id());
        return spell;
    }

    private record BoundMethod(String key, MethodType signature, MethodHandle handle, boolean site, AbstractSpell spell) {}

    private static BoundMethod method(AbstractSpell spell, SpellFormulaCatalog.Method formula) throws Exception {
        Class<?> owner = spell.getClass();
        Class<?> declaring = Class.forName(formula.declaringClass(), false, owner.getClassLoader());
        boolean site = formula.declaringClass().equals(SITE_VALUES);
        MethodType signature = MethodType.fromMethodDescriptorString(formula.descriptor(), owner.getClassLoader());
        ClassNode source = new ClassNode();
        String resource = '/' + declaring.getName().replace('.', '/') + ".class";
        try (var stream = declaring.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Catalog class resource not found: " + resource);
            new ClassReader(stream).accept(source, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        var declaration = source.methods.stream()
                .filter(value -> value.name.equals(formula.name()) && value.desc.equals(formula.descriptor()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Catalog method not found: " + formula.key()));
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(declaring, MethodHandles.lookup());
        MethodHandle handle = (declaration.access & Opcodes.ACC_STATIC) != 0
                ? lookup.findStatic(declaring, formula.name(), signature)
                : lookup.findVirtual(declaring, formula.name(), signature).bindTo(spell);
        return new BoundMethod(owner.getName() + '#' + formula.key(), signature, handle, site, spell);
    }

    private static boolean supportedArguments(BoundMethod method) {
        for (Class<?> type : method.signature().parameterArray()) {
            if (type == int.class || type == LivingEntity.class || type == Entity.class) continue;
            if (method.site() && (type == float.class || type == double.class || type == long.class || type == Object.class)) continue;
            return false;
        }
        return true;
    }

    private static Number invoke(BoundMethod method, int level, LivingEntity caster) throws Exception {
        return invokeHandle(method, arguments(method, level, caster));
    }

    private static void expectNullActorFailure(GameTestHelper helper, BoundMethod method, int level) throws Exception {
        try {
            invoke(method, level, null);
        } catch (NullPointerException expected) {
            return;
        }
        helper.assertTrue(false, "Native method accepted an unsupported null actor: " + method.key());
    }

    private static Object[] arguments(BoundMethod method, int level, LivingEntity caster) {
        Object[] arguments = new Object[method.signature().parameterCount()];
        Class<?>[] types = method.signature().parameterArray();
        for (int index = 0; index < arguments.length; index++) {
            Class<?> type = types[index];
            if (type == int.class) arguments[index] = method.site() ? level + 8 : level;
            else if (type == float.class) arguments[index] = 1.125f + index * 0.125f;
            else if (type == double.class) arguments[index] = 1.375 + index * 0.125;
            else if (type == long.class) arguments[index] = (long) level;
            else if (type == Object.class) arguments[index] = index == arguments.length - 1 ? caster : method.spell();
            else arguments[index] = caster;
        }
        return arguments;
    }

    private static Number invokeHandle(BoundMethod method, Object[] arguments) throws Exception {
        try {
            return (Number) method.handle().invokeWithArguments(arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            if (failure instanceof Exception exception) throw exception;
            throw new IllegalStateException("Catalog method invocation failed: " + method.key(), failure);
        }
    }

    private static Number invokeDelegate(FormulaRuntimeDispatcher.Invoker delegate, BoundMethod method,
                                         int level, LivingEntity caster) throws Exception {
        try {
            return delegate.invoke(null, arguments(method, level, caster));
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            if (failure instanceof Exception exception) throw exception;
            throw new IllegalStateException("Site helper delegate invocation failed: " + method.key(), failure);
        }
    }

    private static Number plusSeven(Number baseline) {
        if (baseline instanceof Float) return baseline.floatValue() + 7f;
        if (baseline instanceof Double) return baseline.doubleValue() + 7d;
        if (baseline instanceof Long) return baseline.longValue() + 7L;
        return baseline.intValue() + 7;
    }

    private static void same(GameTestHelper helper, Number expected, Number actual, String context) {
        boolean equal;
        if (expected instanceof Float) equal = Float.floatToRawIntBits(expected.floatValue()) == Float.floatToRawIntBits(actual.floatValue());
        else if (expected instanceof Double) equal = Double.doubleToRawLongBits(expected.doubleValue()) == Double.doubleToRawLongBits(actual.doubleValue());
        else equal = expected.longValue() == actual.longValue();
        helper.assertTrue(equal, context + ": expected=" + expected + " actual=" + actual);
    }

    private static void install(AbstractSpell spell, SpellFormulaCatalog.Method formula, String expression) {
        var definition = SpellFormulaState.catalog().spells().stream()
                .filter(value -> value.className().equals(spell.getClass().getName())).findFirst().orElseThrow();
        var compiled = FormulaDelegateFactory.compileSpell(spell.getClass(), definition, Map.of(formula.key(), expression));
        var invoker = compiled.get(formula.key());
        if (invoker == null) throw new IllegalStateException("Formula edit did not create a delegate: " + expression);
        FormulaRuntimeDispatcher.replaceServer(Map.of(FormulaRuntimeDispatcher.key(spell.getClass().getName(), formula.key()), invoker));
    }

    private static SpellFormulaFiles.Snapshot apply(GameTestHelper helper, SpellFormulaCatalog.Spell definition, JsonObject json) {
        var candidate = SpellFormulaFiles.parseResources(Map.of(definition.id(), json), SpellFormulaState.catalog());
        helper.assertTrue(candidate.diagnostics().isEmpty(), "Fixture parse failed: " + candidate.diagnostics());
        var effective = SpellFormulaRuntime.applyServer(candidate);
        synchronize(helper);
        helper.assertTrue(effective.diagnostics().isEmpty(), "Fixture compile failed: " + effective.diagnostics());
        return effective;
    }

    private static SpellFormulaFiles.Snapshot empty() {
        return SpellFormulaFiles.snapshot(Map.of(), List.of());
    }

    private static void synchronize(GameTestHelper helper) {
        MinecraftForge.EVENT_BUS.post(new OnDatapackSyncEvent(helper.getLevel().getServer().getPlayerList(), null));
    }

    private static Map<String, Integer> baseValues(AbstractSpell spell) throws Exception {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String name : List.of("baseManaCost", "manaCostPerLevel", "baseSpellPower", "spellPowerPerLevel", "castTime")) {
            Field field = AbstractSpell.class.getDeclaredField(name);
            field.setAccessible(true);
            values.put(name, field.getInt(spell));
        }
        return Map.copyOf(values);
    }

    private static void setBase(AbstractSpell spell, String name, int value) throws Exception {
        Field field = AbstractSpell.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(spell, value);
    }

    private static void restoreBase(AbstractSpell spell, Map<String, Integer> values) throws Exception {
        for (var entry : values.entrySet()) setBase(spell, entry.getKey(), entry.getValue());
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
