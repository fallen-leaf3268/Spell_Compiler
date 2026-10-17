package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.events.SpellDamageEvent;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.SummonManager;
import io.redspace.ironsspellbooks.capabilities.magic.SummonedEntitiesCastData;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import io.redspace.ironsspellbooks.effect.ThunderstormEffect;
import io.redspace.ironsspellbooks.entity.mobs.SummonedPolarBear;
import io.redspace.ironsspellbooks.entity.spells.LightningStrike;
import io.redspace.ironsspellbooks.entity.spells.target_area.TargetedAreaEntity;
import io.redspace.ironsspellbooks.registries.MobEffectRegistry;
import io.redspace.ironsspellbooks.spells.TargetedTargetAreaCastData;
import io.redspace.ironsspellbooks.spells.blood.SacrificeSpell;
import io.redspace.ironsspellbooks.spells.holy.HasteSpell;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder("spell_compiler_formula")
@PrefixGameTestTemplate(false)
public final class SpellCompilerSitesGameTests {
    private SpellCompilerSitesGameTests() {}

    @GameTest(batch = "formula_sites_fire", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void fireboltDamageSourceUsesIntegerDurationAndRecoversAfterInvalidValue(GameTestHelper helper) {
        try (var fixture = new SpellCompilerCastingGameTests.Fixture(helper, "firebolt")) {
            helper.assertTrue(fixture.spell.getDamageSource(fixture.player, fixture.player).getFireTime() == 60,
                    "Native firebolt duration_ticks differs before editing");
            fixture.configure(Map.of("fire_duration_ticks", "19.9"), Map.of());
            helper.assertTrue(fixture.spell.getDamageSource(fixture.player, fixture.player).getFireTime() == 19,
                    "Firebolt damage source ignored the integer site duration_ticks");
            fixture.configure(Map.of("fire_duration_ticks", "-1"), Map.of());
            for (int attempt = 0; attempt < 3; attempt++)
                helper.assertTrue(fixture.spell.getDamageSource(fixture.player, fixture.player).getFireTime() == 60,
                        "Invalid fire duration_ticks did not retain the native source");
            fixture.configure(Map.of("fire_duration_ticks", "31"), Map.of());
            helper.assertTrue(fixture.spell.getDamageSource(fixture.player, fixture.player).getFireTime() == 31,
                    "Reload did not restore the disabled fire duration_ticks site");
            pass("native_firebolt_source_integer_duration_invalid_fallback_reload");
        }
        helper.succeed();
    }

    @GameTest(batch = "formula_sites_storm_lifecycle", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void thunderstormActualCastStoresAmplifierWhileLaterStrikesReadCurrentAttributes(GameTestHelper helper) {
        try (var fixture = new SpellCompilerCastingGameTests.Fixture(helper, "thunderstorm")) {
            String damageFormula = '(' + fixture.original("effect_damage") + ") + 1";
            fixture.configure(Map.of("effect_level", '(' + fixture.original("effect_level") + ") + 1",
                    "effect_damage", damageFormula), Map.of("power_multiplier", 2));
            fixture.player.getAttribute(AttributeRegistry.SPELL_POWER.get()).setBaseValue(1.25);
            var school = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.parse("irons_spellbooks:lightning_spell_power"));
            helper.assertTrue(school != null && fixture.player.getAttribute(school) != null,
                    "Missing thunderstorm lifecycle school attribute");
            fixture.player.getAttribute(school).setBaseValue(1.4);
            fixture.spell.onCast(helper.getLevel(), 2, fixture.player, CastSource.COMMAND, fixture.magic);
            var effectType = (ThunderstormEffect) MobEffectRegistry.THUNDERSTORM.get();
            var instance = fixture.player.getEffect(effectType);
            helper.assertTrue(instance != null && instance.getAmplifier() == 12 && instance.getDuration() == 540,
                    "Native thunderstorm cast did not store the integer generator output and tick duration_ticks");
            String initialPreview = Component.translatable("ui.irons_spellbooks.damage", Utils.stringTruncation(25.5, 2)).getString();
            helper.assertTrue(fixture.spell.getUniqueInfo(2, fixture.player).stream().map(Component::getString)
                            .anyMatch(initialPreview::equals),
                    "Thunderstorm preview did not compose both formula stages before attributes changed");
            fixture.player.getAttribute(AttributeRegistry.SPELL_POWER.get()).setBaseValue(0.5);
            fixture.player.getAttribute(school).setBaseValue(1.2);
            float currentPower = (float) ((double) (2f * 0.5f) * 1.2d);
            float expected = (12 - 7) * currentPower + 7 + 1;
            close(helper, ThunderstormEffect.getDamageFromAmplifier(instance.getAmplifier(), fixture.player), expected,
                    "Thunderstorm damage reused cast-time attributes instead of the current attributes");
            fixture.configure(Map.of("effect_level", '(' + fixture.original("effect_level") + ") + 50",
                    "effect_damage", damageFormula),
                    Map.of("power_multiplier", 2));
            helper.assertTrue(fixture.player.getEffect(effectType) == instance && instance.getAmplifier() == 12,
                    "Reloading the generator changed the amplifier already stored in a real effect");
            close(helper, ThunderstormEffect.getDamageFromAmplifier(instance.getAmplifier(), fixture.player), expected,
                    "The renamed amplifier input reran its generator instead of reading the stored integer");
            var target = mob(helper, EntityType.ZOMBIE, fixture.player.position().add(0, 0, 4));
            effectType.applyEffectTick(fixture.player, instance.getAmplifier());
            var strikes = fixture.spawned.stream().filter(LightningStrike.class::isInstance)
                    .map(LightningStrike.class::cast).toList();
            helper.assertTrue(strikes.size() == 1 && strikes.get(0).position().equals(target.position()),
                    "Stored thunderstorm effect did not create its real scheduled strike");
            close(helper, strikes.get(0).getDamage(), expected,
                    "Real thunderstorm strike did not retain the saved amplifier and current attributes");
            var amounts = new ArrayList<Float>();
            Consumer<LivingHurtEvent> listener = event -> {
                if (event.getEntity() == target && event.getSource() instanceof SpellDamageSource source
                        && source.spell() == fixture.spell) amounts.add(event.getAmount());
            };
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingHurtEvent.class, listener);
            try {
                float before = target.getHealth();
                strikes.get(0).tickCount = 20;
                strikes.get(0).tick();
                helper.assertTrue(amounts.size() == 1 && target.getHealth() < before,
                        "Stored thunderstorm lifecycle did not reach a real target damage event");
                close(helper, amounts.get(0), expected, "Actual thunderstorm lifecycle damage differs from the formula");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(listener);
            }
            pass("native_storm_actual_cast_saved_amplifier_current_attributes_generator_reload_preview_and_hit");
        }
        helper.succeed();
    }

    @GameTest(batch = "formula_sites_storm_constants", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void thunderstormConstantFormulasReachTheNativeEffectPreviewAndStrikeDamageEvent(GameTestHelper helper) throws java.io.IOException {
        try (var fixture = new SpellCompilerCastingGameTests.Fixture(helper, "thunderstorm")) {
            var json = SpellFormulaFiles.parseJson("""
                    {
                      "formulas": {
                        "effect_level": "4",
                        "effect_damage": "100",
                        "strike_interval_ticks": "10"
                      }
                    }
                    """).getAsJsonObject();
            var parsed = SpellFormulaFiles.parseResources(Map.of(fixture.spell.getSpellId(), json), SpellFormulaState.catalog());
            helper.assertTrue(parsed.diagnostics().isEmpty() && parsed.byClass().size() == 1,
                    "Constant thunderstorm fixture did not parse as one complete spell: " + parsed.diagnostics());
            var applied = SpellFormulaRuntime.applyServer(parsed);
            MinecraftForge.EVENT_BUS.post(new OnDatapackSyncEvent(helper.getLevel().getServer().getPlayerList(), null));
            var accepted = applied.byClass().get(fixture.spell.getClass().getName());
            helper.assertTrue(applied.diagnostics().isEmpty() && accepted != null && accepted.formulas().size() == 3
                            && SpellFormulaState.serverSnapshot().fingerprint().equals(applied.fingerprint()),
                    "Constant thunderstorm formulas were rejected or partially published: " + applied.diagnostics());
            var exported = SpellFormulaFiles.preview(accepted.spell());
            helper.assertTrue(!exported.getAsJsonObject("formulas").has("spell_power")
                            && !exported.getAsJsonObject("parameters").has("baseSpellPower")
                            && !exported.getAsJsonObject("parameters").has("spellPowerPerLevel"),
                    "Unused thunderstorm power controls leaked into the public export");
            fixture.spell.onCast(helper.getLevel(), 1, fixture.player, CastSource.COMMAND, fixture.magic);
            var effectType = (ThunderstormEffect) MobEffectRegistry.THUNDERSTORM.get();
            var instance = fixture.player.getEffect(effectType);
            helper.assertTrue(instance != null && instance.getAmplifier() == 4,
                    "The actual thunderstorm cast did not save the constant effect level");
            helper.assertTrue(effectType.isDurationEffectTick(10, instance.getAmplifier())
                            && !effectType.isDurationEffectTick(11, instance.getAmplifier()),
                    "The actual thunderstorm effect did not use its constant ten-tick strike interval");
            String preview = Component.translatable("ui.irons_spellbooks.damage", Utils.stringTruncation(100, 2)).getString();
            helper.assertTrue(fixture.spell.getUniqueInfo(1, fixture.player).stream().map(Component::getString)
                            .anyMatch(preview::equals),
                    "The thunderstorm preview did not display the constant effect damage");
            fixture.player.getAttribute(AttributeRegistry.SPELL_POWER.get()).setBaseValue(1.2);
            for (int attempt = 0; attempt < 3; attempt++) {
                close(helper, ThunderstormEffect.getDamageFromAmplifier(instance.getAmplifier(), fixture.player), 100,
                        "Current attributes changed or disabled the constant effect damage");
                helper.assertTrue(fixture.spell.getUniqueInfo(1, fixture.player).stream().map(Component::getString)
                                .anyMatch(preview::equals),
                        "Current attributes changed the constant thunderstorm damage preview");
            }
            var target = mob(helper, EntityType.ZOMBIE, fixture.player.position().add(0, 0, 4));
            effectType.applyEffectTick(fixture.player, instance.getAmplifier());
            var strikes = fixture.spawned.stream().filter(LightningStrike.class::isInstance)
                    .map(LightningStrike.class::cast).toList();
            helper.assertTrue(strikes.size() == 1 && strikes.get(0).position().equals(target.position()),
                    "The constant thunderstorm effect did not create its real target strike");
            close(helper, strikes.get(0).getDamage(), 100,
                    "The real thunderstorm strike did not retain the constant input damage");
            var amounts = new ArrayList<Float>();
            Consumer<SpellDamageEvent> listener = event -> {
                if (event.getEntity() == target && event.getSpellDamageSource().spell() == fixture.spell)
                    amounts.add(event.getAmount());
            };
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, SpellDamageEvent.class, listener);
            try {
                strikes.get(0).tickCount = 20;
                strikes.get(0).tick();
                helper.assertTrue(amounts.size() == 1,
                        "The real constant thunderstorm strike did not reach one native spell damage event");
                close(helper, amounts.get(0), 100,
                        "The native spell damage event did not receive the constant thunderstorm input");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(listener);
            }
            pass("native_storm_constant_json_saved_level_preview_attribute_independence_interval_and_input_hit");
        }
        helper.succeed();
    }

    @GameTest(batch = "formula_sites_haste", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void hastePrecastAreaAndCompletedCastUseOneChangedRadius(GameTestHelper helper) {
        try (var fixture = new SpellCompilerCastingGameTests.Fixture(helper, "haste")) {
            fixture.configure(Map.of("radius", "5"), Map.of());
            var inside = mob(helper, EntityType.WOLF, fixture.player.position().add(0, 0, 4));
            var outside = mob(helper, EntityType.WOLF, fixture.player.position().add(0, 0, 6));
            inside.tame(fixture.player);
            outside.tame(fixture.player);
            helper.assertTrue(Utils.shouldHealEntity(fixture.player, inside)
                            && Utils.shouldHealEntity(fixture.player, outside),
                    "Native haste targets are not eligible allied recipients");
            fixture.player.setXRot(-90);
            fixture.magic.setAdditionalCastData(new TargetEntityCastData(fixture.player));
            helper.assertTrue(fixture.spell.checkPreCastConditions(helper.getLevel(), 1, fixture.player, fixture.magic),
                    "Native haste preconditions rejected the radius fixture");
            helper.assertTrue(fixture.magic.getAdditionalCastData() instanceof TargetedTargetAreaCastData,
                    "Native haste preconditions did not preserve targeted area cast data");
            var targeted = (TargetedTargetAreaCastData) fixture.magic.getAdditionalCastData();
            helper.assertTrue(targeted.getTarget(helper.getLevel()) == fixture.player,
                    "Native haste target selection moved the radius fixture center");
            var areas = fixture.spawned.stream().filter(TargetedAreaEntity.class::isInstance)
                    .map(TargetedAreaEntity.class::cast).toList();
            helper.assertTrue(areas.size() == 1, "Native haste did not create one targeting area");
            close(helper, areas.get(0).getRadius(), 5, "Haste precast targeting area ignored the changed radius");
            fixture.spell.onCast(helper.getLevel(), 1, fixture.player, CastSource.COMMAND, fixture.magic);
            helper.assertTrue(inside.hasEffect(MobEffectRegistry.HASTENED.get())
                            && !outside.hasEffect(MobEffectRegistry.HASTENED.get()),
                    "Completed native haste did not use the same five-block radius");
            pass("completed_native_haste_precast_area_and_actual_recipients");
        }
        helper.succeed();
    }

    @GameTest(batch = "formula_sites_haste_duration", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void negativeHasteDurationFallsBackBeforeCreatingTheNativeEffect(GameTestHelper helper) {
        try (var fixture = new SpellCompilerCastingGameTests.Fixture(helper, "haste")) {
            var spell = (HasteSpell) fixture.spell;
            int nativeDuration = spell.getDuration(1, fixture.player);
            fixture.configure(Map.of("duration_ticks", "clamp((" + fixture.original("duration_ticks") + "), -1, -1)"), Map.of());
            fixture.player.setXRot(-90);
            fixture.magic.setAdditionalCastData(new TargetEntityCastData(fixture.player));
            helper.assertTrue(spell.checkPreCastConditions(helper.getLevel(), 1, fixture.player, fixture.magic),
                    "Native haste rejected the duration_ticks regression target");
            spell.onCast(helper.getLevel(), 1, fixture.player, CastSource.COMMAND, fixture.magic);
            var effect = fixture.player.getEffect(MobEffectRegistry.HASTENED.get());
            helper.assertTrue(effect != null && !effect.isInfiniteDuration() && effect.getDuration() == nativeDuration,
                    "Invalid negative haste duration_ticks escaped into a permanent native effect");
            helper.assertTrue(spell.getDuration(1, fixture.player) == nativeDuration,
                    "Disabled invalid duration_ticks did not continue to use the native formula");
            fixture.configure(Map.of("duration_ticks", "clamp((" + fixture.original("duration_ticks") + "), 80, 80)"), Map.of());
            helper.assertTrue(spell.getDuration(1, fixture.player) == 80,
                    "A valid reload did not restore native duration_ticks customization");
            pass("native_haste_negative_duration_fallback_and_valid_reload");
        }
        helper.succeed();
    }

    @GameTest(batch = "formula_sites_summon_overflow", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void summonDurationOverflowFallsBackBeforeNativeExpirationAndRecast(GameTestHelper helper) throws Exception {
        try (var fixture = new SpellCompilerCastingGameTests.Fixture(helper, "summon_polar_bear")) {
            helper.assertTrue(helper.getLevel().getServer().getTickCount() > 0, "Overflow fixture requires a running native server clock");
            fixture.configure(Map.of("summon_duration_ticks", "2147483647"), Map.of());
            int nativeTick = helper.getLevel().getServer().getTickCount();
            fixture.spell.onCast(helper.getLevel(), 1, fixture.player, CastSource.COMMAND, fixture.magic);
            var summons = fixture.spawned.stream().filter(SummonedPolarBear.class::isInstance)
                    .map(SummonedPolarBear.class::cast).toList();
            helper.assertTrue(summons.size() == 1, "Overflow fallback did not retain the native summon construction");
            var summon = summons.get(0);
            var recast = fixture.magic.getPlayerRecasts().getRecastInstance(fixture.spell.getSpellId());
            helper.assertTrue(recast.getTicksToLive() == 12000 && recast.getTicksRemaining() == 12000,
                    "Overflowing duration_ticks escaped into the native recast lifetime");
            var expiration = SummonManager.class.getDeclaredMethod("getExpirationTick", UUID.class);
            expiration.setAccessible(true);
            helper.assertTrue((Integer) expiration.invoke(SummonManager.INSTANCE, summon.getUUID()) == nativeTick + 12000,
                    "Overflowing duration_ticks escaped into the native summon expiration clock");
            pass("native_summon_duration_overflow_falls_back_in_expiration_and_recast");
        }
        helper.succeed();
    }

    private static float radiusPreview(SacrificeSpell spell, net.minecraft.world.entity.LivingEntity caster) {
        return spell.getUniqueInfo(1, caster).stream().map(Component::getContents)
                .filter(contents -> contents instanceof TranslatableContents translation
                        && translation.getKey().equals("ui.irons_spellbooks.radius"))
                .map(contents -> (TranslatableContents) contents)
                .map(translation -> ((Number) translation.getArgs()[0]).floatValue())
                .findFirst().orElseThrow();
    }

    private static <T extends Mob> T mob(GameTestHelper helper, EntityType<T> type, Vec3 position) {
        T target = type.create(helper.getLevel());
        helper.assertTrue(target != null, "Could not create native site target");
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setOnGround(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        if (target.getAttribute(Attributes.ARMOR) != null) target.getAttribute(Attributes.ARMOR).setBaseValue(0);
        target.setHealth(100);
        target.setPos(position);
        helper.assertTrue(helper.getLevel().addFreshEntity(target), "Could not spawn native site target");
        return target;
    }

    private static void close(GameTestHelper helper, float actual, float expected, String message) {
        helper.assertTrue(Float.isFinite(actual) && Math.abs(actual - expected) < 0.002f,
                message + ": actual=" + actual + " expected=" + expected);
    }

    private static void pass(String name) {
        SpellCompiler.LOGGER.info("FORMULA_SITE_PROBE_CASE_PASS case={}", name);
    }
}
