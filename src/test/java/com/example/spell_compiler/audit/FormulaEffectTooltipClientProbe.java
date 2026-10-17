package com.example.spell_compiler.audit;

import com.example.spell_compiler.SpellCompiler;
import com.example.spell_compiler.formula.FormulaConsumerCarrier;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.registries.MobEffectRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = SpellCompiler.MODID, value = Dist.CLIENT)
public final class FormulaEffectTooltipClientProbe {
    private static final JsonArray CASES = new JsonArray();
    private static final Map<String, Float> SCREENSHOT = Map.of("percent_attack_damage", 1500f,
            "percent_speed", 5f, "percent_spell_power", 150f);
    private static long started;
    private static boolean stopped;
    private static Method tooltipMethod;
    private static Method formatterMethod;
    private static TooltipFlag tooltipFlag;

    private FormulaEffectTooltipClientProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("spell_compiler.effectTooltipClientProbe") || stopped
                || event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (started == 0) started = System.nanoTime();
        try {
            check(System.nanoTime() - started < 180_000_000_000L, "效果提示客户端启动超时");
            if (minecraft.getOverlay() != null || !(minecraft.screen instanceof TitleScreen)) return;
            check(FMLEnvironment.dist == Dist.CLIENT, "探针未运行在真实客户端");
            check(minecraft.player == null && minecraft.level == null && minecraft.getSingleplayerServer() == null,
                    "主菜单探针意外创建世界或玩家");
            check(!ModList.get().isLoaded("apotheosis") && !ModList.get().isLoaded("apotheosis_spells"),
                    "独立提示探针意外依赖旧桥接或Apotheosis");
            if (Boolean.getBoolean("spell_compiler.effectTooltipWithoutAttributesLib")) {
                check(!ModList.get().isLoaded("attributeslib"), "缺少可选模组模式仍加载AttributesLib");
                JsonObject entry = new JsonObject();
                entry.addProperty("name", "optional_client_starts_without_attributeslib");
                entry.addProperty("passed", true);
                CASES.add(entry);
            } else {
                runCases();
            }
            stopped = true;
            writeReport(true, null);
            SpellCompiler.LOGGER.info("FORMULA_EFFECT_TOOLTIP_CLIENT_PASS cases={} withoutAttributesLib={}",
                    CASES.size(), Boolean.getBoolean("spell_compiler.effectTooltipWithoutAttributesLib"));
            minecraft.stop();
        } catch (Throwable failure) {
            stopped = true;
            SpellCompiler.LOGGER.error("FORMULA_EFFECT_TOOLTIP_CLIENT_FAIL", failure);
            try {
                writeReport(false, failure);
            } catch (Exception reportFailure) {
                SpellCompiler.LOGGER.error("FORMULA_EFFECT_TOOLTIP_CLIENT_REPORT_FAIL", reportFailure);
            }
            minecraft.stop();
        }
    }

    private static void runCases() throws Exception {
        check(ModList.get().isLoaded("attributeslib") && ModList.get().isLoaded("placebo"), "提示依赖未加载");
        check(ModList.get().getModContainerById("attributeslib").orElseThrow().getModInfo().getVersion().toString()
                .equals("1.3.7"), "AttributesLib版本不匹配");
        tooltipMethod = Class.forName("dev.shadowsoffire.attributeslib.asm.ALHooks").getMethod("getEffectTooltip",
                EffectRenderingInventoryScreen.class, MobEffectInstance.class, List.class);
        formatterMethod = Class.forName("dev.shadowsoffire.attributeslib.api.IFormattableAttribute")
                .getMethod("toComponent", Attribute.class, AttributeModifier.class, TooltipFlag.class);
        tooltipFlag = (TooltipFlag) Class.forName("dev.shadowsoffire.attributeslib.AttributesLib")
                .getMethod("getTooltipFlag").invoke(null);
        MobEffect charged = MobEffectRegistry.CHARGED.get();
        check(charged.getAttributeModifiers().size() == 3, "超负荷原生属性模板数不同");
        Map<String, Double> templates = templateAmounts(charged);
        MobEffectInstance nativeEffect = instance(charged, 2, Map.of());
        assertTooltip("charged_native_III", nativeEffect, Map.of());
        MobEffectInstance saved = instance(charged, 2, SCREENSHOT);
        assertTooltip("charged_saved_1500_5_150", saved, SCREENSHOT);
        for (int amplifier : new int[]{0, 7})
            assertTooltip("charged_saved_not_rescaled_amplifier_" + amplifier,
                    instance(charged, amplifier, SCREENSHOT), SCREENSHOT);
        Map<String, Float> partial = Map.of("percent_attack_damage", 37f);
        assertTooltip("charged_partial_snapshot_keeps_other_native_values", instance(charged, 2, partial), partial);
        Map<String, Float> zeros = Map.of("percent_attack_damage", 0f, "percent_speed", 0f, "percent_spell_power", 0f);
        assertTooltip("charged_zero_values_keep_native_formatter", instance(charged, 2, zeros), zeros);
        Map<String, Float> negative = Map.of("percent_attack_damage", -37f, "percent_speed", -5f, "percent_spell_power", -150f);
        assertTooltip("charged_negative_values_keep_native_formatter_and_color", instance(charged, 2, negative), negative);
        Map<String, Float> otherValues = Map.of("percent_attack_damage", 23f, "percent_speed", 7f, "percent_spell_power", 11f);
        assertTooltip("charged_independent_instance", instance(charged, 2, otherValues), otherValues);
        assertTooltip("charged_original_instance_remains_independent", saved, SCREENSHOT);
        MobEffectInstance copied = new MobEffectInstance(saved);
        check(((FormulaConsumerCarrier) copied).apoth$getFormulaValues().equals(SCREENSHOT), "复制效果丢失快照");
        assertTooltip("charged_copy_snapshot", copied, SCREENSHOT);
        MobEffectInstance loaded = MobEffectInstance.load(saved.save(new CompoundTag()));
        check(loaded != null && ((FormulaConsumerCarrier) loaded).apoth$getFormulaValues().equals(SCREENSHOT), "NBT读取丢失快照");
        assertTooltip("charged_nbt_snapshot", loaded, SCREENSHOT);
        ((FormulaConsumerCarrier) saved).apoth$setFormulaValues(Map.of());
        assertTooltip("charged_clear_restores_native_values", saved, Map.of());
        MobEffectInstance speed = instance(MobEffects.MOVEMENT_SPEED, 2, SCREENSHOT);
        check(((FormulaConsumerCarrier) speed).apoth$getFormulaValues().isEmpty(), "非超负荷效果接受了超负荷快照");
        assertTooltip("native_speed_remains_unchanged", speed, Map.of());
        check(templateAmounts(charged).equals(templates), "提示生成修改了共享属性模板");
    }

    private static MobEffectInstance instance(MobEffect effect, int amplifier, Map<String, Float> values) {
        MobEffectInstance result = new MobEffectInstance(effect, 1200, amplifier);
        ((FormulaConsumerCarrier) result).apoth$setFormulaValues(values);
        return result;
    }

    private static void assertTooltip(String name, MobEffectInstance effect, Map<String, Float> expectedValues) throws Exception {
        CompoundTag before = effect.save(new CompoundTag());
        List<Component> initial = new ArrayList<>();
        initial.add(effect.getEffect().getDisplayName().copy());
        initial.add(Component.literal("1:00"));
        Object returned = tooltipMethod.invoke(null, new Object[]{null, effect, initial});
        check(returned instanceof List<?>, "ALHooks未返回提示列表");
        List<Component> actual = new ArrayList<>();
        for (Object value : (List<?>) returned) {
            check(value instanceof Component, "真实提示包含非Component值");
            actual.add((Component) value);
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("amplifier", effect.getAmplifier());
        entry.add("snapshot", new com.google.gson.Gson().toJsonTree(expectedValues));
        entry.add("actual", components(actual));
        JsonArray expectedComponents = new JsonArray();
        List<Component> expected = new ArrayList<>();
        for (var template : effect.getEffect().getAttributeModifiers().entrySet()) {
            String alias = alias(template.getKey());
            Float stored = expectedValues.get(alias);
            double amount = stored == null ? effect.getEffect().getAttributeModifierValue(effect.getAmplifier(), template.getValue())
                    : stored.doubleValue() / 100;
            AttributeModifier modifier = new AttributeModifier(template.getValue().getName(), amount,
                    template.getValue().getOperation());
            Component component = (Component) formatterMethod.invoke(null, template.getKey(), modifier, tooltipFlag);
            expected.add(component);
            expectedComponents.add(Component.Serializer.toJson(component));
        }
        entry.add("expectedAttributeComponents", expectedComponents);
        entry.addProperty("passed", false);
        CASES.add(entry);
        for (Component component : expected)
            check(actual.stream().anyMatch(component::equals), name + " 属性提示未匹配：expected="
                    + Component.Serializer.toJson(component) + " actual=" + components(actual));
        long attributeLines = actual.stream().filter(component -> component.getContents()
                instanceof net.minecraft.network.chat.contents.TranslatableContents content
                && (content.getKey().equals("attributeslib.modifier.plus") || content.getKey().equals("attributeslib.modifier.take"))).count();
        check(attributeLines == expected.size(), name + " 属性提示数量发生变化：" + attributeLines);
        check(before.equals(effect.save(new CompoundTag())), name + " 提示生成改变了效果NBT或快照");
        entry.addProperty("passed", true);
        SpellCompiler.LOGGER.info("FORMULA_EFFECT_TOOLTIP_CLIENT_CASE {}", name);
    }

    private static String alias(Attribute attribute) {
        if (attribute == Attributes.ATTACK_DAMAGE) return "percent_attack_damage";
        if (attribute == Attributes.MOVEMENT_SPEED) return "percent_speed";
        if (attribute == AttributeRegistry.SPELL_POWER.get()) return "percent_spell_power";
        return "";
    }

    private static Map<String, Double> templateAmounts(MobEffect effect) {
        Map<String, Double> result = new LinkedHashMap<>();
        effect.getAttributeModifiers().forEach((attribute, modifier) -> result.put(
                ForgeRegistries.ATTRIBUTES.getKey(attribute).toString(), modifier.getAmount()));
        return result;
    }

    private static JsonArray components(List<Component> values) {
        JsonArray result = new JsonArray();
        for (Component value : values) {
            JsonObject component = new JsonObject();
            component.addProperty("text", value.getString());
            component.addProperty("json", Component.Serializer.toJson(value));
            result.add(component);
        }
        return result;
    }

    private static void writeReport(boolean passed, Throwable failure) throws Exception {
        JsonObject report = new JsonObject();
        report.addProperty("passed", passed);
        report.addProperty("utc", Instant.now().toString());
        report.addProperty("pid", ProcessHandle.current().pid());
        report.addProperty("dist", FMLEnvironment.dist.name());
        report.addProperty("worldCreated", Minecraft.getInstance().level != null);
        report.addProperty("withoutAttributesLib", Boolean.getBoolean("spell_compiler.effectTooltipWithoutAttributesLib"));
        report.addProperty("attributesLibLoaded", ModList.get().isLoaded("attributeslib"));
        report.add("cases", CASES);
        if (failure != null) report.addProperty("failure", failure.toString());
        Path target = Path.of(System.getProperty("spell_compiler.effectTooltipClientReport"));
        Files.createDirectories(target.getParent());
        Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
