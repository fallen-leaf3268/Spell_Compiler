package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.entity.spells.AbstractMagicProjectile;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@GameTestHolder("spell_compiler_addons")
@PrefixGameTestTemplate(false)
public final class SpellCompilerAddonGameTests {
    private static final String PINNING = "org.xszb.interlace_spellweaves.spell.nature.PinningStake";
    private static final String DAMAGE = "getDamage(ILnet/minecraft/world/entity/LivingEntity;)F";

    private SpellCompilerAddonGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void actualAddonRegistersItsSpellsSchoolAndEarlyFormulaHooks(GameTestHelper helper) throws Exception {
        helper.assertTrue(ModList.get().isLoaded("iss_csw"), "真实附属夹具未加载");
        helper.assertTrue(!ModList.get().isLoaded("cataclysm") && !ModList.get().isLoaded("goety"),
                "核心附属夹具意外加载了条件兼容模组");
        var registered = SpellRegistry.REGISTRY.get().getValues().stream()
                .filter(spell -> spell.getSpellResource().getNamespace().equals("iss_csw"))
                .collect(Collectors.toMap(spell -> spell.getSpellResource().toString(), spell -> spell.getClass().getName()));
        helper.assertTrue(registered.size() == 27, "真实附属注册法术数量不同：" + registered.size() + '/' + registered.keySet());
        helper.assertTrue(PINNING.equals(registered.get("iss_csw:pinning_stake")), "真实附属法术身份不符");
        ResourceLocation fusion = (ResourceLocation) Class.forName("org.xszb.interlace_spellweaves.api.registry.RegistrySchool")
                .getField("FUSION_RESOURCE").get(null);
        helper.assertTrue(fusion.equals(ResourceLocation.parse("iss_csw:fusion")) && SchoolRegistry.getSchool(fusion) != null,
                "真实附属自定义学派未注册");
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        helper.assertTrue(contextLoader == null || !contextLoader.getClass().getName()
                        .equals("com.example.spell_compiler.formula.FormulaDynamicMixin$ResourceClassLoader"),
                "动态Mixin资源代理未恢复线程类加载器");
        SpellCompiler.LOGGER.info("SPELL_COMPILER_ADDON_FIXTURE registered={} fusion={} contextLoader={} catalogFingerprint={}",
                registered.size(), fusion, contextLoader == null ? "null" : contextLoader.getClass().getName(),
                SpellFormulaState.catalogFingerprint());
        var addonCatalog = SpellFormulaState.catalog().spells().stream()
                .filter(spell -> spell.id().startsWith("iss_csw:"))
                .collect(Collectors.toMap(SpellFormulaCatalog.Spell::id, SpellFormulaCatalog.Spell::className));
        helper.assertTrue(addonCatalog.equals(registered), "已注册真实附属未完整进入公式目录：registered="
                + registered.size() + " catalog=" + addonCatalog.size() + " entries=" + addonCatalog.keySet());
        Object recorded = FormulaMixinPlugin.class.getMethod("hookedMethods").invoke(null);
        helper.assertTrue(recorded instanceof Map<?, ?>, "附属注入记录不是映射");
        Object methods = ((Map<?, ?>) recorded).get(PINNING);
        helper.assertTrue(methods instanceof Set<?> && ((Set<?>) methods).contains(DAMAGE),
                "真实附属在注册前加载的伤害入口未挂钩：" + methods);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void actualAddonExecutesExportsParametersPrivateStaticAndNativeFallback(GameTestHelper helper) throws Exception {
        try (var saved = new SavedState(helper)) {
            verifyDefaults(helper);
            var pinning = SpellRegistry.getSpell("iss_csw:pinning_stake");
            var definition = definition(pinning.getSpellId());
            Method damage = pinning.getClass().getDeclaredMethod("getDamage", int.class, LivingEntity.class);
            damage.setAccessible(true);
            helper.assertTrue(Modifier.isPrivate(damage.getModifiers()), "附属伤害夹具不再是私有入口");
            ServerPlayer player = player(helper);
            var spawned = new ArrayList<AbstractMagicProjectile>();
            Consumer<EntityJoinLevelEvent> joined = event -> {
                if (event.getLevel() == helper.getLevel()
                        && event.getEntity().getClass().getName()
                        .equals("org.xszb.interlace_spellweaves.entity.spells.stake.StakeEntity")
                        && event.getEntity() instanceof AbstractMagicProjectile projectile && projectile.getOwner() == player)
                    spawned.add(projectile);
            };
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, joined);
            try {
                float nativeOne = damage(damage, pinning, 1, null);
                float nativeFive = damage(damage, pinning, 5, null);
                float nativePlayerFive = damage(damage, pinning, 5, player);
                var defaults = definition.parameters();
                long base = defaults.get("baseSpellPower").getAsLong();
                long growth = defaults.get("spellPowerPerLevel").getAsLong();
                helper.assertTrue(base + 4 * growth > 0 && nativeFive > nativeOne,
                        "附属参数夹具没有可区分的等级强度");
                close(helper, nativeFive, pinning.getSpellPower(5, null), "私有伤害原式不是本法术强度");
                float factor = nativeFive / (base + 4 * growth);
                var parameterOnly = configuration(Map.of(), Map.of("baseSpellPower", base + 11, "spellPowerPerLevel", growth + 3));
                var parsed = apply(helper, Map.of(definition.id(), parameterOnly));
                helper.assertTrue(parsed.byClass().size() == 1
                                && parsed.byClass().get(definition.className()).formulas().isEmpty(),
                        "附属仅参数修改意外依赖了玩家公式覆盖");
                close(helper, pinning.getSpellPower(5, null), (base + 11 + 4 * (growth + 3)) * factor,
                        "附属仅基础强度参数修改未进入继承入口");
                close(helper, damage(damage, pinning, 5, null), (base + 11 + 4 * (growth + 3)) * factor,
                        "附属仅参数修改未进入真实私有消费者");
                clear(helper);
                close(helper, damage(damage, pinning, 5, null), nativeFive, "移除附属参数未恢复原生值");

                apply(helper, Map.of(definition.id(), configuration(Map.of("damage", "100"), Map.of())));
                for (int level : List.of(1, 5))
                    close(helper, damage(damage, pinning, level, null), 100, "附属私有伤害常数未生效");
                close(helper, damage(damage, pinning, 5, player), 100, "真实施法者的附属私有伤害未生效");
                AbstractMagicProjectile configured = cast(helper, pinning, player, spawned);
                close(helper, configured.getDamage(), 100, "实际 onCast 生成的 StakeEntity 未消费私有伤害覆盖");
                clear(helper);
                close(helper, damage(damage, pinning, 5, player), nativePlayerFive, "移除附属私有伤害未恢复");
                close(helper, configured.getDamage(), 100, "移除覆盖改写了已生成实体保存的伤害");
                close(helper, cast(helper, pinning, player, spawned).getDamage(), nativePlayerFive,
                        "移除覆盖后新 StakeEntity 未恢复原生伤害");

                var binding = definition.formulas().stream().filter(method -> method.alias().equals("damage"))
                        .findFirst().orElseThrow();
                String key = FormulaRuntimeDispatcher.key(definition.className(), binding.key());
                apply(helper, Map.of(definition.id(), configuration(Map.of("damage", "1.0 / (level - level)"), Map.of())));
                helper.assertTrue(FormulaRuntimeDispatcher.resolve(key, player) != null, "非法运行结果夹具没有实际编译委托");
                for (int attempt = 0; attempt < 3; attempt++) {
                    close(helper, damage(damage, pinning, 5, player), nativePlayerFive,
                            "附属非有限运行结果未回退到当前等级原生伤害");
                    close(helper, damage(damage, pinning, 1, null), nativeOne,
                            "附属回退委托未恢复其他调用等级的原生伤害");
                }
                var failed = FormulaRuntimeDispatcher.resolve(key, player);
                helper.assertTrue(failed != null && FormulaRuntimeDispatcher.invoke(failed, pinning, new Object[]{5, player}) == null,
                        "附属失败委托没有在本次加载期间停用");
                apply(helper, Map.of(definition.id(), configuration(Map.of("damage", "101"), Map.of())));
                close(helper, damage(damage, pinning, 5, player), 101, "合法重载未恢复附属失败入口");
                clear(helper);
                close(helper, damage(damage, pinning, 5, player), nativePlayerFive, "移除合法重载未恢复附属原式");

                verifyStaticRange(helper);
                verifyCastTypes(helper, player);
                SpellCompiler.LOGGER.info("SPELL_COMPILER_ADDON_EXECUTION defaults=27 parameterOnly=true privateDamage=100"
                        + " projectileSnapshot=true removed=true runtimeFallback=true staticRange=47 castTypes=3");
            } finally {
                MinecraftForge.EVENT_BUS.unregister(joined);
                spawned.forEach(Entity::discard);
            }
        }
        helper.succeed();
    }

    private static void verifyDefaults(GameTestHelper helper) throws Exception {
        var addons = SpellFormulaState.catalog().spells().stream().filter(spell -> spell.id().startsWith("iss_csw:")).toList();
        helper.assertTrue(addons.size() == 27, "默认导出前真实附属目录不完整");
        int instant = 0;
        int longCast = 0;
        int continuous = 0;
        for (var definition : addons) {
            var spell = SpellRegistry.getSpell(definition.id());
            var preview = SpellFormulaFiles.preview(definition);
            helper.assertTrue(SpellFormulaFiles.parse(definition, preview.toString()).isEmpty(),
                    "附属默认公式回读产生了覆盖：" + definition.id());
            boolean editable = spell.getCastType() == CastType.LONG || spell.getCastType() == CastType.CONTINUOUS;
            helper.assertTrue(preview.getAsJsonObject("formulas").has("cast_time") == editable
                            && preview.getAsJsonObject("parameters").has("castTime") == editable,
                    "附属吟唱参数与实际类型不一致：" + definition.id());
            helper.assertTrue(!preview.getAsJsonObject("formulas").has("cooldown_ticks")
                            && !preview.getAsJsonObject("formulas").has("base_cast_time")
                            && !preview.getAsJsonObject("formulas").has("effective_cast_time"),
                    "附属导出暴露了内部时间入口：" + definition.id());
            if (spell.getCastType() == CastType.INSTANT) instant++;
            if (spell.getCastType() == CastType.LONG) longCast++;
            if (spell.getCastType() == CastType.CONTINUOUS) continuous++;
        }
        helper.assertTrue(instant == 14 && longCast == 8 && continuous == 5,
                "附属三种吟唱类型数量发生变化：" + instant + '/' + longCast + '/' + continuous);
        String runProperty = System.getProperty("spell_compiler.addonProbeRoot");
        helper.assertTrue(runProperty != null, "缺少附属私有验证目录属性");
        Path run = Path.of(runProperty).toAbsolutePath().normalize();
        helper.assertTrue(run.equals(FMLPaths.GAMEDIR.get().toAbsolutePath().normalize()), "附属导出目录不是本次私有游戏目录");
        Path export = run.resolve("addon-default-export");
        var loaded = SpellFormulaFiles.exportAndLoad(export, new SpellFormulaCatalog(addons));
        helper.assertTrue(loaded.diagnostics().isEmpty() && loaded.byClass().isEmpty(),
                "附属默认数据包导出回读不等价：" + loaded.diagnostics());
        try (var files = Files.walk(export.resolve("data"))) {
            helper.assertTrue(files.filter(path -> path.toString().endsWith(".json")).count() == 27,
                    "附属默认数据包文件数量不是 27");
        }
        for (var definition : addons) {
            Path file = export.resolve("data").resolve(SpellFormulaFiles.resourceKey(definition).replace(':', '/') + ".json");
            helper.assertTrue(Files.isRegularFile(file), "附属默认导出未使用自身命名空间和学派：" + file);
        }
        helper.assertTrue(addons.stream().anyMatch(spell -> spell.school().equals("iss_csw:fusion")),
                "附属自定义融合学派未进入默认导出");
    }

    private static void verifyStaticRange(GameTestHelper helper) throws Exception {
        var spell = SpellRegistry.getSpell("iss_csw:charge_ray_of_frost");
        Method range = spell.getClass().getDeclaredMethod("getRange", int.class, LivingEntity.class);
        helper.assertTrue(Modifier.isStatic(range.getModifiers()), "附属范围夹具不再是自有静态方法");
        close(helper, ((Number) range.invoke(null, 1, null)).floatValue(), 30, "附属静态范围原生基准改变");
        apply(helper, Map.of(spell.getSpellId(), configuration(Map.of("range", "47"), Map.of())));
        close(helper, ((Number) range.invoke(null, 1, null)).floatValue(), 47, "附属自有静态范围未执行覆盖");
        clear(helper);
        close(helper, ((Number) range.invoke(null, 1, null)).floatValue(), 30, "移除附属静态范围覆盖未恢复原生值");
    }

    private static void verifyCastTypes(GameTestHelper helper, ServerPlayer player) {
        var instant = SpellRegistry.getSpell("iss_csw:pinning_stake");
        var longCast = SpellRegistry.getSpell("iss_csw:ice_plate");
        var continuous = SpellRegistry.getSpell("iss_csw:barrage_volley");
        helper.assertTrue(instant.getCastType() == CastType.INSTANT && longCast.getCastType() == CastType.LONG
                        && continuous.getCastType() == CastType.CONTINUOUS, "代表性附属吟唱类型不符");
        int nativeLong = longCast.getEffectiveCastTime(5, player);
        int nativeContinuous = continuous.getEffectiveCastTime(5, player);
        var instantJson = SpellFormulaFiles.preview(definition(instant.getSpellId()));
        helper.assertTrue(!instantJson.getAsJsonObject("formulas").has("cast_time")
                        && !instantJson.getAsJsonObject("parameters").has("castTime"), "瞬发附属暴露无效吟唱控制");
        apply(helper, Map.of(longCast.getSpellId(), configuration(Map.of(), Map.of("castTime", 12)),
                continuous.getSpellId(), configuration(Map.of(), Map.of("castTime", 12))));
        int parameterLong = longCast.getEffectiveCastTime(5, player);
        int parameterContinuous = continuous.getEffectiveCastTime(5, player);
        helper.assertTrue(parameterLong != nativeLong && parameterContinuous != nativeContinuous,
                "附属吟唱基础参数未改变实际时长：LONG=" + parameterLong + '/' + nativeLong
                        + " CONTINUOUS=" + parameterContinuous + '/' + nativeContinuous);
        apply(helper, Map.of(longCast.getSpellId(), configuration(Map.of("cast_time", "37"), Map.of("castTime", 12)),
                continuous.getSpellId(), configuration(Map.of("cast_time", "61"), Map.of("castTime", 12))));
        helper.assertTrue(longCast.getEffectiveCastTime(5, player) == 37 && continuous.getEffectiveCastTime(5, player) == 61,
                "附属单一吟唱公式未进入 LONG/CONTINUOUS 实际时长");
        apply(helper, Map.of(longCast.getSpellId(), configuration(Map.of("cast_time", "1.0 / (level - level)"), Map.of("castTime", 12)),
                continuous.getSpellId(), configuration(Map.of("cast_time", "1.0 / (level - level)"), Map.of("castTime", 12))));
        helper.assertTrue(longCast.getEffectiveCastTime(5, player) == parameterLong
                        && continuous.getEffectiveCastTime(5, player) == parameterContinuous,
                "附属非法吟唱结果未保留配置参数并恢复原生类型时长");
        clear(helper);
        helper.assertTrue(longCast.getEffectiveCastTime(5, player) == nativeLong
                        && continuous.getEffectiveCastTime(5, player) == nativeContinuous,
                "移除附属吟唱覆盖未恢复原生时长");
    }

    private static AbstractMagicProjectile cast(GameTestHelper helper, AbstractSpell spell, ServerPlayer player,
                                                  List<AbstractMagicProjectile> spawned) {
        int previous = spawned.size();
        spell.onCast(helper.getLevel(), 5, player, CastSource.COMMAND, MagicData.getPlayerMagicData(player));
        helper.assertTrue(spawned.size() == previous + 1, "真实附属 onCast 未生成唯一 StakeEntity：" + spawned.size());
        return spawned.get(previous);
    }

    private static float damage(Method method, AbstractSpell spell, int level, LivingEntity actor) throws Exception {
        return ((Number) method.invoke(spell, level, actor)).floatValue();
    }

    private static SpellFormulaCatalog.Spell definition(String id) {
        return SpellFormulaState.catalog().spells().stream().filter(spell -> spell.id().equals(id)).findFirst().orElseThrow();
    }

    private static JsonObject configuration(Map<String, String> formulas, Map<String, ? extends Number> parameters) {
        JsonObject root = new JsonObject();
        JsonObject formulaJson = new JsonObject();
        formulas.forEach(formulaJson::addProperty);
        root.add("formulas", formulaJson);
        JsonObject parameterJson = new JsonObject();
        parameters.forEach(parameterJson::addProperty);
        root.add("parameters", parameterJson);
        return root;
    }

    private static SpellFormulaFiles.Snapshot apply(GameTestHelper helper, Map<String, JsonObject> resources) {
        Map<String, JsonElement> input = new LinkedHashMap<>(resources);
        var parsed = SpellFormulaFiles.parseResources(input, SpellFormulaState.catalog());
        helper.assertTrue(parsed.diagnostics().isEmpty(), "附属配置解析失败：" + parsed.diagnostics());
        var applied = SpellFormulaRuntime.applyServer(parsed);
        synchronize(helper);
        helper.assertTrue(applied.diagnostics().isEmpty(), "附属配置编译失败：" + applied.diagnostics());
        return parsed;
    }

    private static ServerPlayer player(GameTestHelper helper) {
        var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "[AddonFormula]"));
        player.connection = new ServerGamePacketListenerImpl(player.server, new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) {}
        }, player);
        player.setPos(helper.absoluteVec(new Vec3(1, 4, 1)));
        player.setNoGravity(true);
        player.getAttribute(AttributeRegistry.SPELL_POWER.get()).setBaseValue(1);
        return player;
    }

    private static void clear(GameTestHelper helper) {
        SpellFormulaRuntime.applyServer(SpellFormulaFiles.snapshot(Map.of(), List.of()));
        synchronize(helper);
    }

    private static void synchronize(GameTestHelper helper) {
        MinecraftForge.EVENT_BUS.post(new OnDatapackSyncEvent(helper.getLevel().getServer().getPlayerList(), null));
    }

    private static void close(GameTestHelper helper, float actual, float expected, String message) {
        helper.assertTrue(Float.isFinite(actual) && Math.abs(actual - expected) < 0.0001,
                message + "：actual=" + actual + " expected=" + expected);
    }

    private static final class SavedState implements AutoCloseable {
        private final GameTestHelper helper;
        private final SpellFormulaFiles.Snapshot server;

        private SavedState(GameTestHelper helper) {
            this.helper = helper;
            server = SpellFormulaState.serverSnapshot();
            clear(helper);
        }

        @Override
        public void close() {
            SpellFormulaRuntime.applyServer(server);
            synchronize(helper);
        }
    }
}
