package com.example.spell_compiler.formula;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class FormulaAutomaticSites {
    public enum Kind { DURATION, SUMMON_DURATION, RECAST_WINDOW, RADIUS, DAMAGE, HEALING, EVENT_DAMAGE, EVENT_HEALING, HIT_INTERVAL, DAMAGE_RATIO,
        EFFECT_LEVEL, ACTIVATION_DELAY, MOVEMENT_SCALE, MOVEMENT_X_SCALE, MOVEMENT_Y_SCALE, MOVEMENT_Z_SCALE,
        MOVEMENT_X_OFFSET, MOVEMENT_Y_OFFSET, MOVEMENT_Z_OFFSET, MOVEMENT_X, MOVEMENT_Y, MOVEMENT_Z,
        QUERY_RADIUS, QUERY_X_RANGE, QUERY_Y_RANGE, QUERY_Z_RANGE,
        MOVEMENT_KNOCKBACK_STRENGTH, MOVEMENT_KNOCKBACK_X_DIRECTION, MOVEMENT_KNOCKBACK_Z_DIRECTION }

    public enum ContextMode { DIRECT, ENTITY, SCOPED }

    public record Site(FormulaNumericSites.Definition definition, String owner, String methodName,
                       String methodDescriptor, int producerIndex, String methodFingerprint,
                       int casterSlot, int levelSlot, int spellSlot, Kind kind, String defaultExpression,
                       int argumentIndex, ContextMode contextMode, int contextSlot, List<FormulaCapturedInput> inputs,
                       String nativeExpression) {
        public Site {
            Objects.requireNonNull(definition);
            owner = Objects.requireNonNull(owner).replace('/', '.');
            Objects.requireNonNull(methodName);
            Objects.requireNonNull(methodDescriptor);
            Objects.requireNonNull(methodFingerprint);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(defaultExpression);
            Objects.requireNonNull(contextMode);
            Objects.requireNonNull(nativeExpression);
            inputs = List.copyOf(inputs);
            if (inputs.stream().map(FormulaCapturedInput::name).distinct().count() != inputs.size())
                throw new IllegalArgumentException("自动消费包含重复输入名");
            if (argumentIndex < -1 || contextSlot < -1 || contextMode == ContextMode.ENTITY && contextSlot < 0)
                throw new IllegalArgumentException("无效的自动消费上下文或参数位置");
        }

        public Site(FormulaNumericSites.Definition definition, String owner, String methodName,
                    String methodDescriptor, int producerIndex, String methodFingerprint,
                    int casterSlot, int levelSlot, int spellSlot, Kind kind, String defaultExpression,
                    int argumentIndex, ContextMode contextMode, int contextSlot, List<FormulaCapturedInput> inputs) {
            this(definition, owner, methodName, methodDescriptor, producerIndex, methodFingerprint, casterSlot,
                    levelSlot, spellSlot, kind, defaultExpression, argumentIndex, contextMode, contextSlot, inputs,
                    "double(" + defaultExpression + ')');
        }

        public Site(FormulaNumericSites.Definition definition, String owner, String methodName,
                    String methodDescriptor, int producerIndex, String methodFingerprint,
                    int casterSlot, int levelSlot, int spellSlot, Kind kind, String defaultExpression,
                    int argumentIndex, ContextMode contextMode, int contextSlot) {
            this(definition, owner, methodName, methodDescriptor, producerIndex, methodFingerprint, casterSlot,
                    levelSlot, spellSlot, kind, defaultExpression, argumentIndex, contextMode, contextSlot, List.of());
        }

        public Site(FormulaNumericSites.Definition definition, String owner, String methodName,
                    String methodDescriptor, int producerIndex, String methodFingerprint,
                    int casterSlot, int levelSlot, int spellSlot, Kind kind, String defaultExpression) {
            this(definition, owner, methodName, methodDescriptor, producerIndex, methodFingerprint,
                    casterSlot, levelSlot, spellSlot, kind, defaultExpression, -1, ContextMode.DIRECT, -1);
        }

        public Site(FormulaNumericSites.Definition definition, String owner, String methodName,
                    String methodDescriptor, int producerIndex, String methodFingerprint,
                    int casterSlot, int levelSlot, int spellSlot, Kind kind, String defaultExpression, int argumentIndex) {
            this(definition, owner, methodName, methodDescriptor, producerIndex, methodFingerprint,
                    casterSlot, levelSlot, spellSlot, kind, defaultExpression, argumentIndex, ContextMode.DIRECT, -1);
        }
    }

    public record Scan(String spellClassName, List<Site> sites, List<String> diagnostics,
                       List<FormulaEntityFlow.Capture> captures, List<FormulaCastContext.Root> scopes,
                       List<FormulaUnifiedAnalysis.Restoration> restorations) {
        public Scan {
            spellClassName = Objects.requireNonNull(spellClassName).replace('/', '.');
            sites = List.copyOf(sites);
            diagnostics = List.copyOf(diagnostics);
            captures = List.copyOf(captures);
            scopes = List.copyOf(scopes);
            restorations = List.copyOf(restorations);
        }

        public Scan(String spellClassName, List<Site> sites, List<String> diagnostics,
                    List<FormulaEntityFlow.Capture> captures, List<FormulaCastContext.Root> scopes) {
            this(spellClassName, sites, diagnostics, captures, scopes, List.of());
        }

        public Scan(String spellClassName, List<Site> sites, List<String> diagnostics) {
            this(spellClassName, sites, diagnostics, List.of(), List.of());
        }

        public Scan(String spellClassName, List<Site> sites, List<String> diagnostics, List<FormulaEntityFlow.Capture> captures) {
            this(spellClassName, sites, diagnostics, captures, List.of());
        }

        public List<FormulaNumericSites.Definition> definitions() {
            return sites.stream().map(Site::definition).distinct().toList();
        }
    }

    private static final Map<String, Scan> SCANS = new ConcurrentHashMap<>();
    private record Lookup(Map<String, Site> byKey, Map<String, Site> byPoint,
                          Map<Site, String> physicalKeys, Map<String, Map<String, Site>> bySpell,
                          Map<String, List<Site>> byOwner, Map<String, List<FormulaNumericSites.Definition>> definitions) {}
    private static volatile Lookup INDEX = new Lookup(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    private FormulaAutomaticSites() {}

    public static Scan scan(FormulaClassRepository repository, String spellClassName, Set<String> reservedAliases) {
        return scan(repository, spellClassName, reservedAliases, Set.of());
    }

    public static Scan scan(FormulaClassRepository repository, String spellClassName, Set<String> reservedAliases,
                            Set<FormulaDataFlow.MethodId> exposedMethods) {
        Objects.requireNonNull(repository);
        Objects.requireNonNull(reservedAliases);
        Objects.requireNonNull(exposedMethods);
        Scan direct = FormulaAutomaticFlow.scan(repository, spellClassName, reservedAliases);
        var reserved = new java.util.HashSet<>(reservedAliases);
        direct.definitions().forEach(definition -> reserved.add(definition.alias()));
        Scan effects = FormulaEffectGraphSites.scan(repository, spellClassName, reserved);
        effects.definitions().forEach(definition -> reserved.add(definition.alias()));
        var sites = new java.util.ArrayList<>(direct.sites());
        sites.addAll(effects.sites());
        var entities = FormulaEntityFlow.scan(repository, spellClassName, reserved, sites);
        sites.addAll(entities.sites());
        var diagnostics = new java.util.ArrayList<>(direct.diagnostics());
        diagnostics.addAll(effects.diagnostics());
        diagnostics.addAll(entities.diagnostics());
        var scopes = new java.util.ArrayList<>(direct.scopes());
        scopes.addAll(FormulaEntityFlow.effectScopes(repository, spellClassName, entities.captures()));
        return FormulaUnifiedAnalysis.finish(repository, FormulaUnifiedAnalysis.effectEvents(repository,
                new Scan(spellClassName.replace('/', '.'), sites, diagnostics, entities.captures(), scopes.stream().distinct().toList()), reservedAliases),
                exposedMethods);
    }

    public static synchronized void register(Scan scan) {
        Map<String, Site> next = new HashMap<>(INDEX.byKey());
        Map<String, Site> points = new HashMap<>(INDEX.byPoint());
        Scan previous = SCANS.put(scan.spellClassName(), scan);
        if (previous != null) for (Site site : previous.sites()) {
            next.remove(site.definition().dispatchKey());
            points.remove(pointKey(previous.spellClassName(), site));
        }
        for (Site site : scan.sites()) {
            next.put(site.definition().dispatchKey(), site);
            points.put(pointKey(scan.spellClassName(), site), site);
        }
        Map<Site, String> physicalKeys = new HashMap<>();
        Map<String, Map<String, Site>> bySpell = new HashMap<>();
        Map<String, List<Site>> byOwner = new HashMap<>();
        Map<String, List<FormulaNumericSites.Definition>> definitions = new HashMap<>();
        for (Scan registered : SCANS.values()) {
            definitions.put(registered.spellClassName(), registered.definitions());
            Map<String, Site> entries = new HashMap<>();
            for (Site site : registered.sites()) {
                String physical = pointKey("", site);
                physicalKeys.put(site, physical);
                entries.put(physical, site);
                byOwner.computeIfAbsent(site.owner(), ignored -> new java.util.ArrayList<>()).add(site);
            }
            bySpell.put(registered.spellClassName(), Map.copyOf(entries));
        }
        byOwner.replaceAll((owner, sites) -> List.copyOf(sites));
        INDEX = new Lookup(Map.copyOf(next), Map.copyOf(points), Map.copyOf(physicalKeys), Map.copyOf(bySpell), Map.copyOf(byOwner), Map.copyOf(definitions));
        FormulaEntityFlow.register(scan.spellClassName(), scan.captures());
        FormulaCastContext.register(scan.spellClassName(), scan.scopes());
    }

    public static List<FormulaNumericSites.Definition> definitions(String spellClassName) {
        return INDEX.definitions().getOrDefault(spellClassName.replace('/', '.'), List.of());
    }

    public static Site find(String dispatchKey) {
        return INDEX.byKey().get(dispatchKey);
    }

    public static Site findForSpell(String spellClassName, Site anchor) {
        Lookup index = INDEX;
        String physical = index.physicalKeys().get(anchor);
        Map<String, Site> entries = index.bySpell().get(spellClassName.replace('/', '.'));
        return physical == null || entries == null ? null : entries.get(physical);
    }

    private static String pointKey(String spellClassName, Site site) {
        return spellClassName.replace('/', '.') + ':' + site.owner() + '#' + site.methodName()
                + site.methodDescriptor() + ':' + site.producerIndex() + ':' + site.argumentIndex()
                + ':' + site.contextMode() + ':' + site.contextSlot() + ':' + site.casterSlot()
                + ':' + site.levelSlot() + ':' + site.spellSlot() + ':' + site.methodFingerprint()
                + ':' + site.kind() + ':' + site.definition().valueType() + ':' + captureLayout(site);
    }

    static String captureLayout(Site site) {
        return site.inputs().stream().map(input -> input.descriptor() + ':' + input.producerIndex() + ':' + input.localSlot())
                .collect(Collectors.joining(","));
    }

    public static Set<String> targetClasses() {
        var targets = new java.util.HashSet<>(INDEX.byOwner().keySet());
        targets.addAll(FormulaEntityFlow.captureTargets());
        targets.addAll(FormulaCastContext.targetClasses());
        return Set.copyOf(targets);
    }

    public static Set<String> apply(String owner, ClassNode target) {
        return FormulaAutomaticInjector.apply(owner, target, INDEX.byOwner().getOrDefault(owner.replace('/', '.'), List.of()));
    }

    static String fingerprint(MethodNode method) {
        return FormulaAutomaticInjector.fingerprint(method);
    }
}
