package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.registries.MobEffectRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@GameTestHolder("spell_compiler_formula")
@PrefixGameTestTemplate(false)
public final class SpellCompilerConsumerGameTests {
    private SpellCompilerConsumerGameTests() {}

    @GameTest(batch = "compiler_consumer_dimension", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 200)
    public static void dimensionChangesResendSavedEffectValuesAfterNativeEffects(GameTestHelper helper) {
        try (var fixture = new SpellCompilerCastingGameTests.Fixture(helper, "charge")) {
            var sent = capturePackets(fixture.player);
            fixture.configure(Map.of("percent_attack_damage", "1500", "percent_speed", "5", "percent_spell_power", "150"), Map.of());
            cast(helper, fixture, 2);
            var saved = Map.copyOf(values(charged(helper, fixture)));
            helper.assertTrue(sent.stream().anyMatch(FormulaConsumerNetwork.EffectPacket.class::isInstance),
                    "The connection did not capture the initial formula effect packet");
            fixture.configure(Map.of("percent_attack_damage", "1", "percent_speed", "2", "percent_spell_power", "3"), Map.of());
            fixture.player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0));
            var destination = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(destination != null, "The dimension test requires the Nether");
            sent.clear();
            fixture.player.changeDimension(destination, new ITeleporter() {});
            helper.assertTrue(fixture.player.serverLevel() == destination, "The native dimension change did not complete");
            assertDimensionPacket(helper, fixture.player, sent, saved);
            helper.assertTrue(values(charged(helper, fixture)).equals(saved), "Changing dimensions recalculated the saved effect values");
            fixture.player.forceAddEffect(new MobEffectInstance(MobEffectRegistry.CHARGED.get(), 200, 0), null);
            sent.clear();
            fixture.player.changeDimension(helper.getLevel(), new ITeleporter() {});
            helper.assertTrue(fixture.player.serverLevel() == helper.getLevel(), "The native return dimension change did not complete");
            assertDimensionPacket(helper, fixture.player, sent, Map.of());
            pass("dimension_change_resends_saved_percentages_and_empty_native_snapshot_after_native_effects");
        }
        helper.succeed();
    }

    private static List<Object> capturePackets(ServerPlayer player) {
        List<Object> sent = new ArrayList<>();
        player.connection = new ServerGamePacketListenerImpl(player.server, new Connection(PacketFlow.SERVERBOUND) {
            @Override
            public void send(Packet<?> packet) {
                if (packet instanceof ClientboundCustomPayloadPacket payload
                        && payload.getIdentifier().equals(ResourceLocation.parse("spell_compiler:spell_formula_effect"))) {
                    var buffer = payload.getData();
                    try {
                        if (buffer.readUnsignedByte() != 0) throw new AssertionError("Unexpected formula effect packet discriminator");
                        sent.add(FormulaConsumerNetwork.EffectPacket.decode(buffer));
                    } finally {
                        buffer.release();
                    }
                } else {
                    sent.add(packet);
                }
            }

            @Override
            public void send(Packet<?> packet, PacketSendListener listener) {
                send(packet);
            }
        }, player);
        return sent;
    }

    private static void assertDimensionPacket(GameTestHelper helper, ServerPlayer player, List<Object> sent,
                                              Map<String, Float> expected) {
        var snapshots = sent.stream().filter(FormulaConsumerNetwork.EffectPacket.class::isInstance)
                .map(FormulaConsumerNetwork.EffectPacket.class::cast).toList();
        helper.assertTrue(snapshots.size() == 1, "Changing dimensions must immediately send one formula effect snapshot to the player; got " + snapshots.size());
        var snapshot = snapshots.get(0);
        helper.assertTrue(snapshot.entityId() == player.getId() && snapshot.entityUuid().equals(player.getUUID())
                && snapshot.dimension().equals(player.level().dimension().location())
                && snapshot.effect().equals(ResourceLocation.parse("irons_spellbooks:charged")),
                "The dimension snapshot targets the wrong player, dimension, or effect");
        helper.assertTrue(snapshot.values().equals(expected), "The dimension snapshot did not preserve the saved percentages: " + snapshot.values());
        int respawn = -1;
        int nativeEffect = -1;
        for (int index = 0; index < sent.size(); index++) {
            if (sent.get(index) instanceof ClientboundRespawnPacket) respawn = index;
            if (sent.get(index) instanceof ClientboundUpdateMobEffectPacket effect && effect.getEffect() == MobEffectRegistry.CHARGED.get())
                nativeEffect = index;
        }
        helper.assertTrue(respawn >= 0 && nativeEffect > respawn && sent.indexOf(snapshot) > nativeEffect,
                "The formula snapshot must follow the native respawn and effect packets");
    }

    @GameTest(batch = "compiler_consumer_charge_snapshot", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void chargedSnapshotMatchesAllThreeAppliedModifiersAndSurvivesSaveLoad(GameTestHelper helper) {
        try (var fixture = new SpellCompilerCastingGameTests.Fixture(helper, "charge")) {
            fixture.configure(Map.of("percent_attack_damage", "1500", "percent_speed", "5", "percent_spell_power", "150"), Map.of());
            cast(helper, fixture, 2);
            var instance = charged(helper, fixture);
            helper.assertTrue(values(instance).equals(Map.of("percent_attack_damage", 1500f,
                    "percent_speed", 5f, "percent_spell_power", 150f)), "The native Charge cast did not capture all three final percentages");
            assertAppliedAmounts(helper, fixture, instance);
            var saved = instance.save(new CompoundTag());
            fixture.configure(Map.of("percent_attack_damage", "1", "percent_speed", "2", "percent_spell_power", "3"), Map.of());
            assertAppliedAmounts(helper, fixture, charged(helper, fixture));
            var loaded = MobEffectInstance.load(saved);
            helper.assertTrue(loaded != null && values(loaded).equals(values(instance)), "Saving and loading Charge changed its captured percentages");
            fixture.player.removeEffect(MobEffectRegistry.CHARGED.get());
            fixture.player.addEffect(loaded);
            assertAppliedAmounts(helper, fixture, charged(helper, fixture));
            pass("charged_three_actual_modifiers_and_instance_amounts_keep_the_saved_snapshot_after_reload");
        }
        helper.succeed();
    }

    @GameTest(batch = "compiler_consumer_effect_lifecycle", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void copiedUpdatedAndPromotedEffectsExposeOnlyTheirOwnSavedPercentages(GameTestHelper helper) {
        try (var fixture = new SpellCompilerCastingGameTests.Fixture(helper, "charge")) {
            fixture.configure(Map.of("percent_speed", "50"), Map.of());
            cast(helper, fixture, 2);
            var original = charged(helper, fixture);
            var copy = new MobEffectInstance(original);
            helper.assertTrue(values(copy).equals(Map.of("percent_speed", 50f)), "Copying an effect lost its saved percentage");
            fixture.configure(Map.of("percent_speed", "99"), Map.of());
            near(helper, amount(original, Attributes.MOVEMENT_SPEED), 0.5, "The active effect was recalculated from the current formula");
            near(helper, amount(copy, Attributes.MOVEMENT_SPEED), 0.5, "The copied effect was recalculated from the current formula");
            var stronger = new MobEffectInstance(MobEffectRegistry.CHARGED.get(), 2, 2);
            ((FormulaConsumerCarrier) stronger).apoth$setFormulaValues(Map.of("percent_speed", 30f));
            var weaker = new MobEffectInstance(MobEffectRegistry.CHARGED.get(), 100, 1);
            ((FormulaConsumerCarrier) weaker).apoth$setFormulaValues(Map.of("percent_speed", 70f));
            stronger.update(weaker);
            near(helper, amount(stronger, Attributes.MOVEMENT_SPEED), 0.3, "A hidden weaker effect replaced the active percentage");
            var loaded = MobEffectInstance.load(stronger.save(new CompoundTag()));
            helper.assertTrue(loaded != null, "The hidden effect chain did not load");
            near(helper, amount(loaded, Attributes.MOVEMENT_SPEED), 0.3, "Loading the hidden effect chain changed its active snapshot");
            loaded.tick(fixture.player, () -> {});
            loaded.tick(fixture.player, () -> {});
            near(helper, amount(loaded, Attributes.MOVEMENT_SPEED), 0.7, "Promoting a hidden effect did not expose its own saved percentage");
            var replacement = new MobEffectInstance(MobEffectRegistry.CHARGED.get(), 200, 1);
            ((FormulaConsumerCarrier) replacement).apoth$setFormulaValues(Map.of("percent_speed", 90f));
            loaded.update(replacement);
            near(helper, amount(loaded, Attributes.MOVEMENT_SPEED), 0.9, "An accepted duration extension kept stale effect percentages");
            loaded.update(new MobEffectInstance(MobEffectRegistry.CHARGED.get(), 300, 1));
            helper.assertTrue(values(loaded).isEmpty(), "A native replacement retained the previous formula snapshot");
            var modifier = loaded.getEffect().getAttributeModifiers().get(Attributes.MOVEMENT_SPEED);
            near(helper, amount(loaded, Attributes.MOVEMENT_SPEED),
                    loaded.getEffect().getAttributeModifierValue(loaded.getAmplifier(), modifier),
                    "The native replacement did not recover its original percentage");
            pass("charged_copy_nbt_update_hidden_promotion_and_native_replacement_read_instance_snapshots");
        }
        helper.succeed();
    }

    @GameTest(batch = "compiler_consumer_partial_fallback", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void partialZeroAndNegativePercentagesKeepNativeFallbacksAndModifierTemplates(GameTestHelper helper) {
        try (var fixture = new SpellCompilerCastingGameTests.Fixture(helper, "charge")) {
            var effect = MobEffectRegistry.CHARGED.get();
            var templates = Map.copyOf(effect.getAttributeModifiers());
            var amounts = templates.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey, entry -> entry.getValue().getAmount()));
            fixture.configure(Map.of("percent_speed", "0"), Map.of());
            cast(helper, fixture, 2);
            var zero = charged(helper, fixture);
            helper.assertTrue(values(zero).equals(Map.of("percent_speed", 0f)), "A partial zero override was not captured as an explicit value");
            assertAppliedAmount(helper, fixture, zero, Attributes.MOVEMENT_SPEED, 0);
            for (var attribute : java.util.List.of(Attributes.ATTACK_DAMAGE, AttributeRegistry.SPELL_POWER.get())) {
                var template = templates.get(attribute);
                assertAppliedAmount(helper, fixture, zero, attribute,
                        effect.getAttributeModifierValue(zero.getAmplifier(), template));
            }
            fixture.configure(Map.of("percent_speed", "-25"), Map.of());
            cast(helper, fixture, 3);
            var negative = charged(helper, fixture);
            helper.assertTrue(values(negative).equals(Map.of("percent_speed", -25f)), "A negative percentage was clamped or replaced");
            assertAppliedAmount(helper, fixture, negative, Attributes.MOVEMENT_SPEED, -0.25);
            var template = templates.get(Attributes.MOVEMENT_SPEED);
            var sameId = new AttributeModifier(template.getId(), "different modifier object", template.getAmount(), template.getOperation());
            near(helper, FormulaConsumerHooks.attributeAmount(negative, sameId, 0.73125), 0.73125,
                    "A different modifier object acquired the saved percentage by UUID alone");
            var vanilla = new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 100, 4);
            ((FormulaConsumerCarrier) vanilla).apoth$setFormulaValues(Map.of("percent_speed", 700f));
            near(helper, FormulaConsumerHooks.attributeAmount(vanilla,
                    vanilla.getEffect().getAttributeModifiers().get(Attributes.MOVEMENT_SPEED), 0.625), 0.625,
                    "A vanilla effect acquired a Charge formula percentage");
            for (var entry : templates.entrySet()) {
                helper.assertTrue(effect.getAttributeModifiers().get(entry.getKey()) == entry.getValue(),
                        "Reading saved percentages replaced a shared native modifier template");
                near(helper, entry.getValue().getAmount(), amounts.get(entry.getKey()), "Reading saved percentages mutated a native modifier template");
            }
            pass("charged_partial_zero_negative_missing_alias_and_unknown_modifier_fallbacks_preserve_templates");
        }
        helper.succeed();
    }

    private static void assertAppliedAmounts(GameTestHelper helper, SpellCompilerCastingGameTests.Fixture fixture,
                                             MobEffectInstance instance) {
        assertAppliedAmount(helper, fixture, instance, Attributes.ATTACK_DAMAGE, 15);
        assertAppliedAmount(helper, fixture, instance, Attributes.MOVEMENT_SPEED, 0.05);
        assertAppliedAmount(helper, fixture, instance, AttributeRegistry.SPELL_POWER.get(), 1.5);
    }

    private static void assertAppliedAmount(GameTestHelper helper, SpellCompilerCastingGameTests.Fixture fixture,
                                           MobEffectInstance instance, Attribute attribute, double expected) {
        near(helper, amount(instance, attribute), expected, "The instance amount differs from its saved final percentage");
        var template = instance.getEffect().getAttributeModifiers().get(attribute);
        var applied = fixture.player.getAttribute(attribute).getModifier(template.getId());
        helper.assertTrue(applied != null, "The native effect did not apply its attribute modifier");
        near(helper, applied.getAmount(), expected, "The actual applied modifier differs from the instance amount");
    }

    private static double amount(MobEffectInstance instance, Attribute attribute) {
        var effect = instance.getEffect();
        var modifier = effect.getAttributeModifiers().get(attribute);
        return FormulaConsumerHooks.attributeAmount(instance, modifier,
                effect.getAttributeModifierValue(instance.getAmplifier(), modifier));
    }

    private static MobEffectInstance charged(GameTestHelper helper, SpellCompilerCastingGameTests.Fixture fixture) {
        var instance = fixture.player.getEffect(MobEffectRegistry.CHARGED.get());
        helper.assertTrue(instance != null, "The native Charge cast did not apply its effect");
        return instance;
    }

    private static Map<String, Float> values(MobEffectInstance instance) {
        return ((FormulaConsumerCarrier) instance).apoth$getFormulaValues();
    }

    private static void cast(GameTestHelper helper, SpellCompilerCastingGameTests.Fixture fixture, int level) {
        fixture.spell.onCast(helper.getLevel(), level, fixture.player, CastSource.COMMAND, fixture.magic);
    }

    private static void near(GameTestHelper helper, double actual, double expected, String message) {
        helper.assertTrue(Double.isFinite(actual) && Math.abs(actual - expected) < 0.000001,
                message + ": actual=" + actual + " expected=" + expected);
    }

    private static void pass(String name) {
        SpellCompiler.LOGGER.info("FORMULA_CONSUMER_CASE_PASS case={}", name);
    }
}
