package com.example.spell_compiler.audit;

import com.example.spell_compiler.SpellCompiler;
import com.example.spell_compiler.formula.FormulaConsumerCarrier;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.effect.OakskinEffect;
import io.redspace.ironsspellbooks.registries.MobEffectRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;
import java.util.Map;

public final class FormulaConsumerRemoteClientCases {
    private static int phase;
    private static int stableTicks;
    private static long started;

    private FormulaConsumerRemoteClientCases() {}

    public static boolean advance(Minecraft minecraft) {
        if (phase == 4) return true;
        if (started == 0) started = System.nanoTime();
        check(System.nanoTime() - started < 60_000_000_000L, "Effect value TCP synchronization timed out in phase " + phase);
        check(minecraft.getSingleplayerServer() == null && minecraft.getConnection() != null
                        && !minecraft.getConnection().getConnection().isMemoryConnection(),
                "Effect values were not checked on an independent TCP client");
        if (phase == 0) {
            command(minecraft, "apply");
            phase = 1;
            return false;
        }
        if (phase == 1 || phase == 2) {
            boolean first = phase == 1;
            int oakskinPercent = first ? 40 : 25;
            if (!matches(minecraft, MobEffectRegistry.FROSTBITTEN_STRIKES.get(), Map.of("damage", 9.5f))
                    || !matches(minecraft, MobEffectRegistry.CHARGED.get(), Map.of("percent_attack_damage", first ? 40f : 20f,
                    "percent_speed", first ? 50f : 30f, "percent_spell_power", first ? 60f : 40f))
                    || !matches(minecraft, MobEffectRegistry.GLUTTONY.get(), Map.of("mana_ratio", first ? 9f : 6f))
                    || !matches(minecraft, MobEffectRegistry.OAKSKIN.get(), Map.of("percent_damage", (float) oakskinPercent))
                    || !matches(minecraft, MobEffectRegistry.SPIDER_ASPECT.get(), Map.of("percent_damage", first ? 35f : 15f))
                    || !amount(minecraft, Attributes.ATTACK_DAMAGE, first ? 0.4 : 0.2)
                    || !amount(minecraft, Attributes.MOVEMENT_SPEED, first ? 0.5 : 0.3)
                    || !amount(minecraft, AttributeRegistry.SPELL_POWER.get(), first ? 0.6 : 0.4)) {
                stableTicks = 0;
                return false;
            }
            var oakskin = minecraft.player.getEffect(MobEffectRegistry.OAKSKIN.get());
            var description = ((OakskinEffect) MobEffectRegistry.OAKSKIN.get()).getDescriptionLine(oakskin);
            check(description.getString().equals(Component.translatable("tooltip.irons_spellbooks.oakskin_description",
                    oakskinPercent).getString()), "The client active Oakskin description ignored its synchronized formula value");
            if (++stableTicks < 6) return false;
            pass(phase == 1 ? "initial_native_effect_packets_sync_all_seven_values_and_active_description"
                    : "native_effect_refresh_keeps_custom_values_and_prior_frostbite_cast_snapshot");
            command(minecraft, phase == 1 ? "refresh" : "clear");
            phase++;
            stableTicks = 0;
            return false;
        }
        for (var effect : List.of(MobEffectRegistry.FROSTBITTEN_STRIKES.get(), MobEffectRegistry.CHARGED.get(),
                MobEffectRegistry.GLUTTONY.get(), MobEffectRegistry.OAKSKIN.get(), MobEffectRegistry.SPIDER_ASPECT.get()))
            if (minecraft.player.hasEffect(effect)) return false;
        pass("native_effect_removal_clears_synchronized_effect_instances");
        SpellCompiler.LOGGER.info("FORMULA_CONSUMER_REMOTE_CLIENT_PASS cases=3");
        phase = 4;
        return true;
    }

    private static boolean matches(Minecraft minecraft, MobEffect effect, Map<String, Float> expected) {
        var instance = minecraft.player.getEffect(effect);
        if (!(instance instanceof FormulaConsumerCarrier carrier)) return false;
        var actual = carrier.apoth$getFormulaValues();
        if (!actual.keySet().equals(expected.keySet())) return false;
        for (var entry : expected.entrySet())
            if (Math.abs(actual.get(entry.getKey()) - entry.getValue()) > 0.002f) return false;
        return true;
    }

    private static boolean amount(Minecraft minecraft, Attribute attribute, double expected) {
        var template = MobEffectRegistry.CHARGED.get().getAttributeModifiers().get(attribute);
        var instance = minecraft.player.getAttribute(attribute);
        var modifier = instance == null ? null : instance.getModifier(template.getId());
        return modifier != null && Math.abs(modifier.getAmount() - expected) < 0.00001;
    }

    private static void command(Minecraft minecraft, String action) {
        minecraft.getConnection().sendCommand("formula_consumer_probe " + action);
    }

    private static void pass(String name) {
        SpellCompiler.LOGGER.info("FORMULA_CONSUMER_REMOTE_CASE_PASS {}", name);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
