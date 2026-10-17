package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import com.example.spell_compiler.mixin.ModifySpellConfigAccessor;
import com.google.gson.JsonElement;
import io.redspace.ironsspellbooks.api.config.ModifyDefaultConfigValuesEvent;
import io.redspace.ironsspellbooks.api.config.SpellConfigHolder;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import io.redspace.ironsspellbooks.network.SyncJsonConfigPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.util.thread.EffectiveSide;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SpellFormulaRuntime {
    private static final Set<String> BASE_FIELDS = Set.of("baseManaCost", "manaCostPerLevel", "baseSpellPower", "spellPowerPerLevel", "castTime");
    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();
    private static final Map<AbstractSpell, Map<String, Integer>> ORIGINALS = new IdentityHashMap<>();
    private static final Set<ChangedField> MODIFIED = new LinkedHashSet<>();
    private static boolean registered;
    private static boolean serverActive;
    private static boolean clientActive;
    private static boolean addonsInitialized;

    private record ChangedField(AbstractSpell spell, String field) {}

    private record Prepared(SpellFormulaFiles.Snapshot snapshot,
                            Map<String, FormulaRuntimeDispatcher.Invoker> invokers) {}

    private SpellFormulaRuntime() {}

    public static synchronized void initialize() {
        if (registered) return;
        try {
            SpellFormulaState.initialize(SpellFormulaRuntime.class.getClassLoader());
            FormulaRuntimeHooks.initialize(SpellFormulaState.catalog());
        } catch (IOException failure) {
            throw new IllegalStateException("无法加载法术公式目录", failure);
        }
        MinecraftForge.EVENT_BUS.addListener(SpellFormulaRuntime::modifyConfig);
        MinecraftForge.EVENT_BUS.addListener(SpellFormulaRuntime::addReloadListener);
        MinecraftForge.EVENT_BUS.addListener(SpellFormulaRuntime::serverStopped);
        registered = true;
        SpellCompiler.LOGGER.info("[SpellFormula] 已注册 {} 个原生法术的数据包公式入口。", SpellFormulaState.catalog().spells().size());
    }

    private static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SpellFormulaReloadListener());
    }

    public static synchronized void initializeAddons() {
        if (addonsInitialized) return;
        Map<ResourceLocation, AbstractSpell> registeredSpells = new LinkedHashMap<>();
        var registry = SpellRegistry.REGISTRY.get();
        for (var spell : registry.getValues()) registeredSpells.put(registry.getKey(spell), spell);
        var current = SpellFormulaState.catalog();
        var discovered = FormulaAddonCatalog.discover(current, registeredSpells,
                FormulaAddonDiscovery.current(), FormulaMixinPlugin.hookedMethods());
        SpellFormulaState.replaceCatalog(discovered.catalog());
        FormulaRuntimeHooks.initialize(discovered.catalog());
        addonsInitialized = true;
        discovered.diagnostics().forEach(diagnostic -> {
            if (diagnostic.cause() == null)
                SpellCompiler.LOGGER.warn("[SpellFormula] {}: {}", diagnostic.path(), diagnostic.message());
            else SpellCompiler.LOGGER.warn("[SpellFormula] {}: {}", diagnostic.path(), diagnostic.message(), diagnostic.cause());
        });
        SpellCompiler.LOGGER.info("[SpellFormula] 注册完成，共 {} 支法术接入公式目录，原目录 {} 支",
                discovered.catalog().spells().size(), current.spells().size());
    }

    private static void serverStopped(ServerStoppedEvent event) {
        resetServer();
    }

    public static synchronized void applyBaseParameters() {
        captureOriginals();
        applyActiveFields();
    }

    public static synchronized SpellFormulaFiles.Snapshot applyServer(SpellFormulaFiles.Snapshot requested) {
        Prepared prepared = prepare(requested);
        validateSnapshot(prepared.snapshot());
        FormulaRuntimeHooks.clearParameterFailures();
        FormulaRuntimeDispatcher.replaceServer(prepared.invokers());
        SpellFormulaState.publishServer(prepared.snapshot());
        serverActive = true;
        applyActiveFields();
        SpellConfigManager.getInstance().handleServerConfigUpdate();
        SpellRegistry.onConfigReload();
        log(prepared.snapshot());
        return prepared.snapshot();
    }

    public static synchronized SpellFormulaFiles.Snapshot applyClient(SpellFormulaFiles.Snapshot requested) {
        Prepared prepared = prepare(requested);
        if (!prepared.snapshot().fingerprint().equals(requested.fingerprint())) {
            prepared.snapshot().diagnostics().forEach(SpellFormulaRuntime::logDiagnostic);
            Throwable cause = prepared.snapshot().diagnostics().stream().map(SpellFormulaFiles.Diagnostic::cause)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            throw new IllegalArgumentException("服务器法术配置未能完整编译，已保留上一次完整配置", cause);
        }
        validateSnapshot(prepared.snapshot());
        FormulaRuntimeHooks.clearParameterFailures();
        FormulaRuntimeDispatcher.replaceClient(prepared.invokers());
        SpellFormulaState.publishClient(prepared.snapshot());
        clientActive = true;
        if (!serverActive) applyActiveFields();
        SpellRegistry.onConfigReload();
        log(prepared.snapshot());
        return prepared.snapshot();
    }

    public static synchronized void resetServer() {
        boolean wasActive = serverActive;
        FormulaNativeValues.resetConfig(false);
        FormulaRuntimeHooks.clearParameterFailures();
        FormulaRuntimeDispatcher.clearServer();
        SpellFormulaState.publishServer(SpellFormulaFiles.snapshot(Map.of(), List.of()));
        serverActive = false;
        applyActiveFields();
        if (wasActive && !clientActive) resetNativeConfig();
        SpellRegistry.onConfigReload();
    }

    public static synchronized void resetClient() {
        boolean wasActive = clientActive;
        FormulaNativeValues.resetConfig(true);
        FormulaRuntimeHooks.clearParameterFailures();
        FormulaRuntimeDispatcher.clearClient();
        SpellFormulaState.publishClient(SpellFormulaFiles.snapshot(Map.of(), List.of()));
        clientActive = false;
        if (!serverActive) applyActiveFields();
        if (wasActive && !serverActive) resetNativeConfig();
        SpellRegistry.onConfigReload();
    }

    private static void resetNativeConfig() {
        SpellConfigManager.getInstance().handleClientSync(new SyncJsonConfigPacket(Map.of()));
    }

    private static Prepared prepare(SpellFormulaFiles.Snapshot requested) {
        captureOriginals();
        Map<String, SpellFormulaFiles.SpellOverride> accepted = new LinkedHashMap<>();
        Map<String, FormulaRuntimeDispatcher.Invoker> invokers = new LinkedHashMap<>();
        List<SpellFormulaFiles.Diagnostic> diagnostics = new ArrayList<>(requested.diagnostics());
        for (var entry : requested.byClass().entrySet()) {
            var override = entry.getValue();
            try {
                AbstractSpell spell = registeredSpell(override.spell());
                JsonElement school = override.advanced().get("school");
                if (school != null && SchoolRegistry.REGISTRY.get().getValue(ResourceLocation.parse(school.getAsString())) == null)
                    throw new IllegalArgumentException("配置学派尚未注册：" + school.getAsString());
                FormulaParameterLimits.validate(override.spell(), override.advanced(), override.parameters());
                var compiled = FormulaDelegateFactory.compileSpell(spell.getClass(), override.spell(), override.formulas(), true);
                compiled.forEach((method, invoker) -> invokers.put(FormulaRuntimeDispatcher.key(entry.getKey(), method), invoker));
                accepted.put(entry.getKey(), override);
            } catch (RuntimeException | LinkageError failure) {
                diagnostics.add(new SpellFormulaFiles.Diagnostic(Path.of(override.spell().id().replace(':', '/')),
                        "该法术的数据包配置未应用：" + failure.getMessage(), failure));
            }
        }
        return new Prepared(SpellFormulaFiles.snapshot(accepted, diagnostics), Map.copyOf(invokers));
    }

    private static AbstractSpell registeredSpell(SpellFormulaCatalog.Spell definition) {
        AbstractSpell spell = SpellRegistry.REGISTRY.get().getValue(ResourceLocation.parse(definition.id()));
        if (spell == null || !spell.getClass().getName().equals(definition.className()))
            throw new IllegalStateException("法术目录与实际注册法术不符：" + definition.id());
        return spell;
    }

    private static void captureOriginals() {
        if (!ORIGINALS.isEmpty()) return;
        try {
            for (String name : BASE_FIELDS) {
                Field field = AbstractSpell.class.getDeclaredField(name);
                field.setAccessible(true);
                FIELDS.put(name, field);
            }
            for (var definition : SpellFormulaState.catalog().spells()) {
                AbstractSpell spell = registeredSpell(definition);
                Map<String, Integer> values = new LinkedHashMap<>();
                for (var entry : FIELDS.entrySet()) values.put(entry.getKey(), entry.getValue().getInt(spell));
                ORIGINALS.put(spell, Map.copyOf(values));
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法读取原生法术基础参数", failure);
        }
    }

    static int originalParameter(AbstractSpell spell, String name, int fallback) {
        return ORIGINALS.getOrDefault(spell, Map.of()).getOrDefault(name, fallback);
    }

    private static void applyActiveFields() {
        try {
            for (var changed : MODIFIED)
                FIELDS.get(changed.field()).setInt(changed.spell(), ORIGINALS.get(changed.spell()).get(changed.field()));
            MODIFIED.clear();
            var snapshot = serverActive ? SpellFormulaState.serverSnapshot()
                    : clientActive ? SpellFormulaState.clientSnapshot() : SpellFormulaFiles.snapshot(Map.of(), List.of());
            for (var override : snapshot.byClass().values()) {
                AbstractSpell spell = registeredSpell(override.spell());
                for (var entry : override.parameters().entrySet()) {
                    if (!BASE_FIELDS.contains(entry.getKey())) continue;
                    if (entry.getKey().equals("baseSpellPower") || entry.getKey().equals("spellPowerPerLevel")) continue;
                    FIELDS.get(entry.getKey()).setInt(spell, entry.getValue().getAsInt());
                    MODIFIED.add(new ChangedField(spell, entry.getKey()));
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("无法应用数据包法术基础参数", failure);
        }
    }

    private static void validateSnapshot(SpellFormulaFiles.Snapshot snapshot) {
        try {
            SpellFormulaNetwork.validateSnapshot(snapshot);
        } catch (RuntimeException | LinkageError failure) {
            SpellCompiler.LOGGER.error("[SpellFormula] 新法术配置无法同步，已保留上一次完整配置", failure);
            throw failure;
        }
    }

    public static void logDiagnostic(SpellFormulaFiles.Diagnostic diagnostic) {
        if (diagnostic.cause() == null)
            SpellCompiler.LOGGER.error("[SpellFormula] {}: {}", diagnostic.path(), diagnostic.message());
        else
            SpellCompiler.LOGGER.error("[SpellFormula] {}: {}", diagnostic.path(), diagnostic.message(), diagnostic.cause());
    }

    private static void log(SpellFormulaFiles.Snapshot snapshot) {
        snapshot.diagnostics().forEach(SpellFormulaRuntime::logDiagnostic);
        SpellCompiler.LOGGER.info("[SpellFormula] 已应用 {} 个法术的数据包变更。", snapshot.byClass().size());
    }

    private static void modifyConfig(ModifyDefaultConfigValuesEvent event) {
        SpellConfigHolder holder = ((ModifySpellConfigAccessor) event).getSpellCompilerConfig();
        FormulaNativeValues.captureConfig(event.getSpell(), holder);
        if (!EffectiveSide.get().isServer()) return;
        var override = SpellFormulaState.serverSnapshot().byClass().get(event.getSpell().getClass().getName());
        if (override == null) return;
        override.advanced().forEach((name, value) -> set(holder, name, value));
        override.parameters().forEach((name, value) -> {
            if (!BASE_FIELDS.contains(name)) set(holder, name, value);
        });
        if (!override.advanced().isEmpty()) {
            event.getSpell().resetRarityWeights();
        }
    }

    private static void set(SpellConfigHolder holder, String name, JsonElement value) {
        switch (name) {
            case "enabled" -> holder.set(SpellConfigParameter.ENABLED, value.getAsBoolean());
            case "max_level" -> holder.set(SpellConfigParameter.MAX_LEVEL, value.getAsInt());
            case "min_rarity" -> holder.set(SpellConfigParameter.MIN_RARITY, SpellRarity.valueOf(value.getAsString().toUpperCase(Locale.ROOT)));
            case "allow_crafting" -> holder.set(SpellConfigParameter.ALLOW_CRAFTING, value.getAsBoolean());
            case "school" -> holder.set(SpellConfigParameter.SCHOOL, SchoolRegistry.REGISTRY.get().getValue(ResourceLocation.parse(value.getAsString())));
            case "cooldown_in_seconds" -> holder.set(SpellConfigParameter.COOLDOWN_IN_SECONDS, value.getAsDouble());
            case "power_multiplier" -> holder.set(SpellConfigParameter.POWER_MULTIPLIER, value.getAsDouble());
            case "mana_cost_multiplier" -> holder.set(SpellConfigParameter.MANA_MULTIPLIER, value.getAsDouble());
            default -> throw new IllegalArgumentException("不支持的原生法术配置：" + name);
        }
    }
}
