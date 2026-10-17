package com.example.spell_compiler.audit;

import com.example.spell_compiler.SpellCompiler;
import com.example.spell_compiler.formula.FormulaMixinPlugin;
import com.example.spell_compiler.formula.SpellFormulaCatalog;
import com.example.spell_compiler.formula.SpellFormulaFiles;
import com.example.spell_compiler.formula.SpellFormulaRuntime;
import com.example.spell_compiler.formula.SpellFormulaState;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.network.SyncJsonConfigPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.util.thread.EffectiveSide;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Mod.EventBusSubscriber(modid = SpellCompiler.MODID, value = Dist.CLIENT)
public final class FormulaAddonClientProbe {
    private static final String PINNING_ID = "iss_csw:pinning_stake";
    private static final String FROST_ID = "iss_csw:charge_ray_of_frost";
    private static final String PINNING = "org.xszb.interlace_spellweaves.spell.nature.PinningStake";
    private static final String FROST = "org.xszb.interlace_spellweaves.spell.ice.ChargeRayOfFrostSpell";
    private static final String DAMAGE = "getDamage(ILnet/minecraft/world/entity/LivingEntity;)F";
    private static final String RANGE = "getRange(ILnet/minecraft/world/entity/LivingEntity;)F";
    private static final JsonArray CASES = new JsonArray();
    private static final JsonArray PREVIEWS = new JsonArray();
    private static final JsonObject VALUES = new JsonObject();
    private static long started;
    private static boolean stopped;
    private static int registeredCount;
    private static int catalogCount;
    private static boolean nativeClientConfigInitialized;
    private static String fingerprint;
    private static String savedSnapshot;
    private static String finalSnapshot;

    private FormulaAddonClientProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("spell_compiler.addonClientProbe") || stopped
                || event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (started == 0) started = System.nanoTime();
        try {
            check(System.nanoTime() - started < 180_000_000_000L, "附属法术客户端启动超时");
            if (minecraft.getOverlay() != null || !(minecraft.screen instanceof TitleScreen)) return;
            runCases(minecraft);
            stopped = true;
            writeReport(true, null);
            SpellCompiler.LOGGER.info("FORMULA_ADDON_CLIENT_PASS cases={} catalog={} fingerprint={}",
                    CASES.size(), catalogCount, fingerprint);
            minecraft.stop();
        } catch (Throwable failure) {
            stopped = true;
            SpellCompiler.LOGGER.error("FORMULA_ADDON_CLIENT_FAIL", failure);
            try {
                writeReport(false, failure);
            } catch (Exception reportFailure) {
                SpellCompiler.LOGGER.error("FORMULA_ADDON_CLIENT_REPORT_FAIL", reportFailure);
            }
            minecraft.stop();
        }
    }

    private static void runCases(Minecraft minecraft) throws Throwable {
        JsonObject lifecycle = begin("actual_client_catalog_early_hook_and_restored_loader");
        check(FMLEnvironment.dist == Dist.CLIENT && EffectiveSide.get().isClient(), "探针不在真实CLIENT线程");
        check(minecraft.player == null && minecraft.level == null && minecraft.getSingleplayerServer() == null,
                "主菜单探针意外创建世界或玩家");
        check(ModList.get().isLoaded("iss_csw"), "真实DEV附属夹具未加载");
        check(!ModList.get().isLoaded("goety") && !ModList.get().isLoaded("cataclysm")
                && !ModList.get().isLoaded("apotheosis_spells"), "独立附属客户端意外加载条件兼容或旧桥接");
        Map<String, String> registered = SpellRegistry.REGISTRY.get().getValues().stream()
                .filter(spell -> spell.getSpellResource().getNamespace().equals("iss_csw"))
                .collect(Collectors.toMap(spell -> spell.getSpellResource().toString(), spell -> spell.getClass().getName()));
        List<SpellFormulaCatalog.Spell> addons = SpellFormulaState.catalog().spells().stream()
                .filter(spell -> spell.id().startsWith("iss_csw:")).toList();
        Map<String, String> catalog = addons.stream().collect(Collectors.toMap(
                SpellFormulaCatalog.Spell::id, SpellFormulaCatalog.Spell::className));
        registeredCount = registered.size();
        catalogCount = catalog.size();
        fingerprint = SpellFormulaState.catalogFingerprint();
        lifecycle.addProperty("registered", registeredCount);
        lifecycle.addProperty("catalog", catalogCount);
        lifecycle.add("identities", new com.google.gson.Gson().toJsonTree(new TreeMap<>(registered)));
        check(registeredCount == 27 && catalog.equals(registered), "真实附属CLIENT目录与注册不一致：" + catalog);
        check(PINNING.equals(registered.get(PINNING_ID)) && FROST.equals(registered.get(FROST_ID)), "附属类身份不同");
        check(FormulaMixinPlugin.hookedMethods().getOrDefault(PINNING, java.util.Set.of()).contains(DAMAGE),
                "私有伤害入口没有早期挂钩");
        check(FormulaMixinPlugin.hookedMethods().getOrDefault(FROST, java.util.Set.of()).contains(RANGE),
                "自有静态范围入口没有早期挂钩");
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        check(loader == null || !loader.getClass().getName().contains("FormulaDynamicMixin$ResourceClassLoader"),
                "动态Mixin资源代理未恢复");
        lifecycle.addProperty("contextLoader", loader == null ? "null" : loader.getClass().getName());
        pass(lifecycle);

        JsonObject defaults = begin("all_27_default_previews_install_no_override");
        for (var definition : addons) {
            JsonObject preview = SpellFormulaFiles.preview(definition);
            boolean unchanged = SpellFormulaFiles.parse(definition, preview.toString()).isEmpty();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", definition.id());
            entry.addProperty("formulas", preview.getAsJsonObject("formulas").size());
            entry.addProperty("unchanged", unchanged);
            PREVIEWS.add(entry);
            check(unchanged, "默认附属导出意外安装override：" + definition.id());
        }
        defaults.addProperty("checked", PREVIEWS.size());
        pass(defaults);

        SpellFormulaFiles.Snapshot saved = SpellFormulaState.clientSnapshot();
        savedSnapshot = saved.fingerprint();
        try {
            SpellConfigManager.getInstance().handleClientSync(new SyncJsonConfigPacket(Map.of()));
            nativeClientConfigInitialized = true;
            SpellFormulaRuntime.applyClient(empty());
            AbstractSpell pinning = registeredSpell(PINNING_ID);
            AbstractSpell frost = registeredSpell(FROST_ID);
            Method damage = pinning.getClass().getDeclaredMethod("getDamage", int.class, LivingEntity.class);
            Method range = frost.getClass().getDeclaredMethod("getRange", int.class, LivingEntity.class);
            damage.setAccessible(true);
            range.setAccessible(true);
            check(Modifier.isPrivate(damage.getModifiers()) && Modifier.isStatic(range.getModifiers()),
                    "真实附属方法的private/static契约不同");
            float nativeDamage = value(damage, pinning);
            float nativeRange = value(range, null);
            VALUES.addProperty("nativeDamage", nativeDamage);
            VALUES.addProperty("nativeRange", nativeRange);
            check(Float.isFinite(nativeDamage) && nativeDamage != 100, "原生伤害基线不适合验证100覆盖");
            check(nativeRange == 30F, "原生静态范围不是30：" + nativeRange);

            JsonObject changedDamage = begin("client_private_damage_constant_100_with_null_actor");
            SpellFormulaFiles.Snapshot damageSnapshot = changed(definition(addons, PINNING_ID), DAMAGE, "100");
            var damageEffective = SpellFormulaRuntime.applyClient(damageSnapshot);
            check(damageEffective.fingerprint().equals(damageSnapshot.fingerprint()), "CLIENT伤害公式未完整接受");
            float actualDamage = value(damage, pinning);
            changedDamage.addProperty("actual", actualDamage);
            check(actualDamage == 100F, "CLIENT私有伤害公式未返回100：" + actualDamage);
            pass(changedDamage);

            JsonObject damageRemoved = begin("removing_client_damage_restores_native_value");
            SpellFormulaRuntime.applyClient(empty());
            float restoredDamage = value(damage, pinning);
            damageRemoved.addProperty("actual", restoredDamage);
            check(Float.floatToIntBits(restoredDamage) == Float.floatToIntBits(nativeDamage), "移除CLIENT伤害未恢复原值");
            pass(damageRemoved);

            JsonObject changedRange = begin("client_own_static_range_constant_47_with_null_self");
            SpellFormulaFiles.Snapshot rangeSnapshot = changed(definition(addons, FROST_ID), RANGE, "47");
            var rangeEffective = SpellFormulaRuntime.applyClient(rangeSnapshot);
            check(rangeEffective.fingerprint().equals(rangeSnapshot.fingerprint()), "CLIENT静态范围公式未完整接受");
            float actualRange = value(range, null);
            changedRange.addProperty("actual", actualRange);
            check(actualRange == 47F, "CLIENT静态范围未返回47：" + actualRange);
            pass(changedRange);

            JsonObject rangeRemoved = begin("removing_client_static_range_restores_30");
            SpellFormulaRuntime.applyClient(empty());
            float restoredRange = value(range, null);
            rangeRemoved.addProperty("actual", restoredRange);
            check(restoredRange == 30F, "移除CLIENT静态范围未恢复30：" + restoredRange);
            pass(rangeRemoved);
        } finally {
            SpellFormulaRuntime.applyClient(saved);
            finalSnapshot = SpellFormulaState.clientSnapshot().fingerprint();
        }
        JsonObject restored = begin("original_client_snapshot_restored");
        check(savedSnapshot.equals(finalSnapshot), "探针未恢复原CLIENT快照");
        restored.addProperty("fingerprint", finalSnapshot);
        pass(restored);
    }

    private static SpellFormulaCatalog.Spell definition(List<SpellFormulaCatalog.Spell> addons, String id) {
        return addons.stream().filter(spell -> spell.id().equals(id)).findFirst().orElseThrow();
    }

    private static AbstractSpell registeredSpell(String id) {
        AbstractSpell spell = SpellRegistry.REGISTRY.get().getValue(ResourceLocation.parse(id));
        check(spell != null, "真实附属法术未注册：" + id);
        return spell;
    }

    private static SpellFormulaFiles.Snapshot changed(SpellFormulaCatalog.Spell definition, String key, String expression) {
        var method = definition.editableFormulas().stream().filter(value -> value.key().equals(key)).findFirst().orElseThrow();
        JsonObject formulas = new JsonObject();
        formulas.addProperty(method.alias(), expression);
        JsonObject json = new JsonObject();
        json.add("formulas", formulas);
        return SpellFormulaFiles.snapshot(Map.of(definition.className(), SpellFormulaFiles.parse(definition, json)), List.of());
    }

    private static SpellFormulaFiles.Snapshot empty() {
        return SpellFormulaFiles.snapshot(Map.of(), List.of());
    }

    private static float value(Method method, Object receiver) throws Throwable {
        try {
            return ((Number) method.invoke(receiver, new Object[]{1, null})).floatValue();
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static JsonObject begin(String name) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("passed", false);
        CASES.add(entry);
        return entry;
    }

    private static void pass(JsonObject entry) {
        entry.addProperty("passed", true);
        SpellCompiler.LOGGER.info("FORMULA_ADDON_CLIENT_CASE {}", entry.get("name").getAsString());
    }

    private static void writeReport(boolean passed, Throwable failure) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject report = new JsonObject();
        report.addProperty("passed", passed);
        report.addProperty("utc", Instant.now().toString());
        report.addProperty("pid", ProcessHandle.current().pid());
        report.addProperty("dist", FMLEnvironment.dist.name());
        report.addProperty("effectiveSide", EffectiveSide.get().name());
        report.addProperty("worldCreated", minecraft.level != null);
        report.addProperty("playerPresent", minecraft.player != null);
        report.addProperty("serverPresent", minecraft.getSingleplayerServer() != null);
        report.addProperty("addonLoaded", ModList.get().isLoaded("iss_csw"));
        report.addProperty("registered", registeredCount);
        report.addProperty("catalog", catalogCount);
        report.addProperty("nativeClientConfigInitialized", nativeClientConfigInitialized);
        report.addProperty("catalogFingerprint", fingerprint);
        report.addProperty("savedSnapshotFingerprint", savedSnapshot);
        report.addProperty("finalSnapshotFingerprint", finalSnapshot);
        report.add("previews", PREVIEWS);
        report.add("values", VALUES);
        report.add("cases", CASES);
        if (failure != null) report.addProperty("failure", failure.toString());
        Path target = Path.of(System.getProperty("spell_compiler.addonClientReport"));
        Files.createDirectories(target.getParent());
        Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
