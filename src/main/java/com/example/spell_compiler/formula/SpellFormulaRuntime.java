package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import com.google.gson.JsonElement;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.fml.util.thread.EffectiveSide;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final PendingServer PENDING_SERVER = new PendingServer();

    private record ChangedField(AbstractSpell spell, String field) {}

    private record Prepared(SpellFormulaFiles.Snapshot snapshot,
                            Map<String, FormulaRuntimeDispatcher.Invoker> invokers) {}

    private record SavedField(AbstractSpell spell, Field field, int value) {}

    private record CommitState(SpellFormulaFiles.Snapshot server, SpellFormulaFiles.Snapshot client,
                               boolean serverActive, boolean clientActive,
                               FormulaRuntimeDispatcher.Installed bindings, FormulaNativeValues.ConfigState nativeConfig,
                               List<SavedField> fields, Set<ChangedField> modified) {}

    static final class PendingServer {
        private record Candidate(WeakReference<Object> owner, SpellFormulaFiles.Snapshot snapshot) {}
        private final List<Candidate> candidates = new ArrayList<>();

        void stage(SpellFormulaFiles.Snapshot requested, Object resources) {
            if (resources == null) throw new IllegalArgumentException("Unloaded server configuration requires an actual reload resource owner");
            java.util.Objects.requireNonNull(requested);
            candidates.removeIf(candidate -> candidate.owner().get() == null || candidate.owner().get() == resources);
            candidates.add(new Candidate(new WeakReference<>(resources), requested));
        }

        SpellFormulaFiles.Snapshot take(Object resources) {
            var iterator = candidates.iterator();
            while (iterator.hasNext()) {
                var candidate = iterator.next();
                Object source = candidate.owner().get();
                if (source == null || source == resources) {
                    iterator.remove();
                    if (source != null) return candidate.snapshot();
                }
            }
            return null;
        }

        void clear() {
            candidates.clear();
        }
    }

    private SpellFormulaRuntime() {}

    public static synchronized void initialize() {
        if (registered) return;
        try {
            SpellFormulaState.initialize(SpellFormulaRuntime.class.getClassLoader());
            FormulaRuntimeHooks.initialize(SpellFormulaState.catalog());
        } catch (IOException failure) {
            throw new IllegalStateException("无法加载法术公式目录", failure);
        }
        FormulaSpellConfig.initialize();
        NeoForge.EVENT_BUS.addListener(SpellFormulaRuntime::addReloadListener);
        NeoForge.EVENT_BUS.addListener(SpellFormulaRuntime::serverAboutToStart);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, SpellFormulaRuntime::commitReload);
        NeoForge.EVENT_BUS.addListener(SpellFormulaRuntime::serverStopped);
        registered = true;
        SpellCompiler.LOGGER.info("[SpellFormula] 已注册 {} 个原生法术的数据包公式入口。", SpellFormulaState.catalog().spells().size());
    }

    private static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SpellFormulaReloadListener(event.getServerResources()));
    }

    private static synchronized void serverAboutToStart(ServerAboutToStartEvent event) {
        var requested = PENDING_SERVER.take(event.getServer().getServerResources().managers());
        if (requested == null) return;
        if (!FormulaSpellConfig.ready()) throw new IllegalStateException("Native spell configuration is not loaded before server startup");
        applyServer(requested);
    }

    private static synchronized void commitReload(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        var requested = PENDING_SERVER.take(event.getPlayerList().getServer().getServerResources().managers());
        if (requested != null) applyServer(requested);
    }

    public static synchronized void initializeAddons() {
        if (addonsInitialized) return;
        Map<ResourceLocation, AbstractSpell> registeredSpells = new LinkedHashMap<>();
        var registry = SpellRegistry.REGISTRY;
        for (var spell : registry) registeredSpells.put(registry.getKey(spell), spell);
        var current = SpellFormulaState.catalog();
        var discovered = FormulaAddonCatalog.discover(current, registeredSpells,
                FormulaAddonDiscovery.current(), FormulaMixinPlugin.hookedMethods(), FormulaMixinPlugin.automaticHooks());
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
        if (!FormulaSpellConfig.ready()) throw new IllegalStateException("Native spell configuration is not loaded");
        Prepared prepared = prepare(requested);
        validateSnapshot(prepared);
        commit(prepared, false, Set.of());
        log(prepared.snapshot());
        return prepared.snapshot();
    }

    static synchronized SpellFormulaFiles.Snapshot applyServer(SpellFormulaFiles.Snapshot requested, Object resourceOwner) {
        PENDING_SERVER.stage(requested, resourceOwner);
        return requested;
    }

    public static synchronized SpellFormulaFiles.Snapshot applyClient(SpellFormulaFiles.Snapshot requested) {
        return applyClient(requested, Set.of());
    }

    static synchronized SpellFormulaFiles.Snapshot applyClient(SpellFormulaFiles.Snapshot requested, Set<String> disabled) {
        Prepared prepared = prepare(requested);
        if (!prepared.snapshot().fingerprint().equals(requested.fingerprint())) {
            prepared.snapshot().diagnostics().forEach(SpellFormulaRuntime::logDiagnostic);
            Throwable cause = prepared.snapshot().diagnostics().stream().map(SpellFormulaFiles.Diagnostic::cause)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            throw new IllegalArgumentException("服务器法术配置未能完整编译，已保留上一次完整配置", cause);
        }
        validateSnapshot(prepared);
        if (!prepared.invokers().keySet().containsAll(disabled))
            throw new IllegalArgumentException("服务器停用列表包含未安装公式");
        commit(prepared, true, disabled);
        log(prepared.snapshot());
        return prepared.snapshot();
    }

    public static synchronized void resetServer() {
        PENDING_SERVER.clear();
        boolean wasActive = serverActive;
        FormulaNativeValues.resetConfig(false);
        FormulaRuntimeHooks.clearParameterFailures();
        FormulaRuntimeDispatcher.clearServer();
        SpellFormulaState.publishServer(SpellFormulaFiles.snapshot(Map.of(), List.of()));
        serverActive = false;
        FormulaSpellConfig.reset(false);
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
        FormulaSpellConfig.reset(true);
        if (!serverActive) applyActiveFields();
        if (wasActive && !serverActive) resetNativeConfig();
        SpellRegistry.onConfigReload();
    }

    private static void resetNativeConfig() {
        FormulaSpellConfig.resetNative();
    }

    private static void commit(Prepared prepared, boolean clientSide, Set<String> disabled) {
        CommitState previous = checkpoint(prepared, clientSide);
        try {
            if (clientSide) {
                FormulaRuntimeDispatcher.replaceClient(prepared.invokers(), disabled);
                SpellFormulaState.publishClient(prepared.snapshot());
                clientActive = true;
                if (!serverActive) applyActiveFields();
            } else {
                FormulaRuntimeDispatcher.replaceServer(prepared.invokers());
                SpellFormulaState.publishServer(prepared.snapshot());
                serverActive = true;
                applyActiveFields();
            }
            FormulaSpellConfig.apply(prepared.snapshot(), clientSide);
            SpellRegistry.onConfigReload();
        } catch (RuntimeException | LinkageError failure) {
            rollback(previous, clientSide, failure);
            throw failure;
        }
        FormulaRuntimeHooks.clearParameterFailures();
    }

    private static CommitState checkpoint(Prepared prepared, boolean clientSide) {
        List<SavedField> fields = new ArrayList<>();
        Set<ChangedField> affected = new LinkedHashSet<>();
        if (!clientSide || !serverActive) {
            affected.addAll(MODIFIED);
            for (var override : prepared.snapshot().byClass().values()) {
                AbstractSpell spell = registeredSpell(override.spell());
                for (String name : override.parameters().keySet())
                    if (BASE_FIELDS.contains(name) && !name.equals("baseSpellPower") && !name.equals("spellPowerPerLevel"))
                        affected.add(new ChangedField(spell, name));
            }
        }
        try {
            for (ChangedField changed : affected) {
                Field field = FIELDS.get(changed.field());
                fields.add(new SavedField(changed.spell(), field, field.getInt(changed.spell())));
            }
        } catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot checkpoint spell parameters", failure); }
        return new CommitState(SpellFormulaState.serverSnapshot(), SpellFormulaState.clientSnapshot(), serverActive, clientActive,
                FormulaRuntimeDispatcher.checkpoint(), FormulaNativeValues.configCheckpoint(), List.copyOf(fields),
                new LinkedHashSet<>(MODIFIED));
    }

    private static void rollback(CommitState previous, boolean clientSide, Throwable failure) {
        restoreOwned(previous, failure);
        try {
            boolean active = clientSide ? previous.clientActive() : previous.serverActive();
            var snapshot = clientSide ? previous.client() : previous.server();
            if (active) FormulaSpellConfig.apply(snapshot, clientSide);
            else {
                FormulaSpellConfig.reset(clientSide);
                if (!clientSide) {
                    FormulaSpellConfig.apply(snapshot, false);
                    FormulaSpellConfig.reset(false);
                }
            }
            SpellRegistry.onConfigReload();
        } catch (RuntimeException | LinkageError rollbackFailure) {
            if (rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
        } finally {
            restoreOwned(previous, failure);
        }
        SpellCompiler.LOGGER.error("[SpellFormula] 提交失败，已尝试恢复此前的本模组状态及原版后端", failure);
    }

    private static void restoreOwned(CommitState previous, Throwable failure) {
        SpellFormulaState.publishServer(previous.server());
        SpellFormulaState.publishClient(previous.client());
        serverActive = previous.serverActive();
        clientActive = previous.clientActive();
        FormulaRuntimeDispatcher.restore(previous.bindings());
        FormulaNativeValues.restoreConfig(previous.nativeConfig());
        for (SavedField saved : previous.fields()) {
            try { saved.field().setInt(saved.spell(), saved.value()); }
            catch (IllegalAccessException | RuntimeException restoreFailure) {
                if (restoreFailure != failure) failure.addSuppressed(restoreFailure);
            }
        }
        MODIFIED.clear();
        MODIFIED.addAll(previous.modified());
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
                if (school != null && SchoolRegistry.REGISTRY.get(ResourceLocation.parse(school.getAsString())) == null)
                    throw new IllegalArgumentException("配置学派尚未注册：" + school.getAsString());
                FormulaParameterLimits.validate(override.spell(), override.advanced(), override.parameters());
                for (var attribute : override.attributes().values())
                    if (!net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.containsKey(ResourceLocation.parse(attribute.targetAttribute())))
                        throw new IllegalArgumentException("目标属性尚未注册：" + attribute.targetAttribute());
                var forced = override.spell().attributeComponents().stream()
                        .filter(component -> override.attributes().containsKey(component.key()))
                        .map(FormulaAttributes.Component::binding).collect(java.util.stream.Collectors.toSet());
                var compiled = FormulaDelegateFactory.compileSpell(spell.getClass(), override.spell(), override.formulas(), true, forced);
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
        AbstractSpell spell = SpellRegistry.REGISTRY.get(ResourceLocation.parse(definition.id()));
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

    private static void validateSnapshot(Prepared prepared) {
        try {
            SpellFormulaNetwork.validateSnapshot(prepared.snapshot(), prepared.invokers().keySet());
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

}
