package com.example.spell_compiler.audit;

import com.example.spell_compiler.SpellCompiler;
import com.example.spell_compiler.formula.FormulaConsumerCarrier;
import com.example.spell_compiler.formula.FormulaConsumerHooks;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.registries.MobEffectRegistry;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = SpellCompiler.MODID)
public final class FormulaConsumerRemoteServerCases {
    private FormulaConsumerRemoteServerCases() {}

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!Boolean.getBoolean("spell_compiler.formulaRemoteProbe")) return;
        var command = Commands.literal("formula_consumer_probe").requires(source -> source.hasPermission(4));
        for (String action : new String[]{"apply", "refresh", "clear"})
            command.then(Commands.literal(action).executes(context -> execute(context.getSource().getPlayerOrException(), action)));
        event.getDispatcher().register(command);
    }

    private static int execute(ServerPlayer player, String action) {
        if (action.equals("clear")) {
            for (var effect : List.of(MobEffectRegistry.FROSTBITTEN_STRIKES.get(), MobEffectRegistry.CHARGED.get(),
                    MobEffectRegistry.GLUTTONY.get(), MobEffectRegistry.OAKSKIN.get(), MobEffectRegistry.SPIDER_ASPECT.get()))
                player.removeEffect(effect);
        } else {
            int level = action.equals("apply") ? 2 : 3;
            var spells = action.equals("apply") ? List.of("frostbite", "charge", "gluttony", "oakskin", "spider_aspect")
                    : List.of("charge", "gluttony", "oakskin", "spider_aspect");
            var magic = MagicData.getPlayerMagicData(player);
            for (var id : spells) {
                var spell = SpellRegistry.getSpell("irons_spellbooks:" + id);
                spell.onCast(player.serverLevel(), level, player, CastSource.COMMAND, magic);
            }
            if (action.equals("apply")) {
                persisted(player, MobEffectRegistry.FROSTBITTEN_STRIKES.get(), Map.of("damage", 9.5f));
                persisted(player, MobEffectRegistry.CHARGED.get(), Map.of("percent_attack_damage", 40f,
                        "percent_speed", 50f, "percent_spell_power", 60f));
                persisted(player, MobEffectRegistry.GLUTTONY.get(), Map.of("mana_ratio", 9f));
                persisted(player, MobEffectRegistry.OAKSKIN.get(), Map.of("percent_damage", 40f));
                persisted(player, MobEffectRegistry.SPIDER_ASPECT.get(), Map.of("percent_damage", 35f));
            } else {
                persisted(player, MobEffectRegistry.CHARGED.get(), Map.of("percent_attack_damage", 20f,
                        "percent_speed", 30f, "percent_spell_power", 40f));
                persisted(player, MobEffectRegistry.GLUTTONY.get(), Map.of("mana_ratio", 6f));
                persisted(player, MobEffectRegistry.OAKSKIN.get(), Map.of("percent_damage", 25f));
                persisted(player, MobEffectRegistry.SPIDER_ASPECT.get(), Map.of("percent_damage", 15f));
            }
            amount(player, Attributes.ATTACK_DAMAGE, action.equals("apply") ? 0.4 : 0.2);
            amount(player, Attributes.MOVEMENT_SPEED, action.equals("apply") ? 0.5 : 0.3);
            amount(player, AttributeRegistry.SPELL_POWER.get(), action.equals("apply") ? 0.6 : 0.4);
            for (var effect : player.getActiveEffects()) {
                var values = ((FormulaConsumerCarrier) effect).apoth$getFormulaValues();
                if (!values.isEmpty()) SpellCompiler.LOGGER.info("FORMULA_CONSUMER_REMOTE_SERVER_VALUES stage={} effect={} values={}",
                        action, effect.getEffect().getDescriptionId(), values);
            }
        }
        SpellCompiler.LOGGER.info("FORMULA_CONSUMER_REMOTE_SERVER_ACTION {}", action);
        return 1;
    }

    private static void persisted(ServerPlayer player, MobEffect effect, Map<String, Float> values) {
        var instance = player.getEffect(effect);
        if (instance == null) throw new AssertionError("Missing native effect before persisted network fixture");
        var tag = instance.save(new CompoundTag());
        tag.put(FormulaConsumerHooks.DATA_KEY, FormulaConsumerHooks.writeValues(values));
        var loaded = MobEffectInstance.load(tag);
        if (loaded == null || !((FormulaConsumerCarrier) loaded).apoth$getFormulaValues().equals(values))
            throw new AssertionError("Native effect loading lost prepared persisted numeric values");
        player.forceAddEffect(loaded, null);
    }

    private static void amount(ServerPlayer player, Attribute attribute, double expected) {
        var id = MobEffectRegistry.CHARGED.get().getAttributeModifiers().get(attribute).getId();
        var modifier = player.getAttribute(attribute).getModifier(id);
        if (modifier == null || Math.abs(modifier.getAmount() - expected) > 0.00001)
            throw new AssertionError("The server Charge modifier differs from the persisted fixture: " + attribute);
    }
}
