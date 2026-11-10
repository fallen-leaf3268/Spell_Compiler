package com.example.spell_compiler.formula;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class FormulaSourceControls {
    public enum DamageInput { NONE, RAW, NATIVE_OR_UNKNOWN }
    public record Control(FormulaNumericSites.Definition numeric, String fingerprint) {}
    @FunctionalInterface
    private interface Discovery {
        List<Control> apply(FormulaClassRepository repository, String spell, List<FormulaAutomaticSites.Site> sites);
    }
    private record Provider(String id, DamageInput damageInput, List<String> runtimeTargets, Discovery discover,
                            Predicate<String> available) {}
    private record Entry(Control control, Provider provider) {}
    private record Index(Map<String, List<Entry>> bySpell, Map<String, Entry> byBinding) {}

    private static final List<Provider> PROVIDERS = List.of(new Provider("effect_publication", DamageInput.RAW, List.of("net.minecraft.world.effect.MobEffect"),
            (repository, spell, sites) -> FormulaEffectPublicationControls.register(repository, spell).stream()
                    .map(control -> new Control(control.numeric(), control.fingerprint())).toList(),
            FormulaEffectPublicationControls::availableBinding),
            new Provider("explosion", DamageInput.NATIVE_OR_UNKNOWN, List.of("net.minecraft.world.level.Explosion"), FormulaSourceControls::explosions, key -> {
                var definition = FormulaExplosionBindings.find(key);
                return definition != null && FormulaExplosionBindings.available(key)
                        && FormulaEntityFlow.hasVerifiedSource(definition.spellClassName());
            }),
            new Provider("lifesteal", DamageInput.NONE, List.of("io.redspace.ironsspellbooks.damage.SpellDamageSource",
                    "io.redspace.ironsspellbooks.damage.DamageSources"),
                    (repository, spell, sites) -> FormulaLifestealControls.register(repository, spell).stream()
                            .map(control -> new Control(control.numeric(), control.fingerprint())).toList(),
                    FormulaLifestealControls::availableBinding));
    private static volatile Index registered = new Index(Map.of(), Map.of());
    private static java.util.Set<String> nativeCatalogScope = java.util.Set.of();
    private static final java.util.Set<String> CHECKED_TARGETS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private FormulaSourceControls() {}

    public static synchronized void beginCatalog(java.util.Set<String> registeredClasses) {
        var nextScope = registeredClasses.stream().map(name -> name.replace('/', '.'))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var removed = new java.util.HashSet<>(nativeCatalogScope);
        removed.addAll(nextScope);
        Map<String, List<Entry>> bySpell = new LinkedHashMap<>(registered.bySpell());
        bySpell.keySet().removeAll(removed);
        Map<String, Entry> byBinding = new LinkedHashMap<>();
        bySpell.values().forEach(entries -> entries.forEach(entry -> byBinding.put(entry.control().numeric().dispatchKey(), entry)));
        registered = new Index(Map.copyOf(bySpell), Map.copyOf(byBinding));
        nativeCatalogScope = nextScope;
    }

    public static void initializeRuntime() {
        for (Provider provider : PROVIDERS) {
            if (registered.byBinding().values().stream().noneMatch(entry -> entry.provider() == provider)) continue;
            for (String target : provider.runtimeTargets()) {
                if (!CHECKED_TARGETS.add(target)) continue;
                try { Class.forName(target, false, FormulaSourceControls.class.getClassLoader()); }
                catch (ClassNotFoundException | LinkageError failure) {
                    org.apache.logging.log4j.LogManager.getLogger(FormulaSourceControls.class).warn(
                            "[SpellFormula] 无法验证来源消费目标 {}，保留该分支原生行为", target, failure);
                }
            }
        }
    }

    private static List<Control> explosions(FormulaClassRepository repository, String spell, List<FormulaAutomaticSites.Site> sites) {
        var discovery = FormulaExplosionBindings.register(repository, spell, sites);
        String proof = discovery.bindings().stream().map(binding -> binding.physicalKey() + ':' + binding.sourceEntityClass())
                .sorted().collect(java.util.stream.Collectors.joining("|"));
        String fingerprint;
        try {
            fingerprint = "EXPLOSION_DAMAGE:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(proof.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        return discovery.definitions().stream().map(definition -> new Control(definition, fingerprint)).toList();
    }

    public static List<Control> register(FormulaClassRepository repository, String spellClass) {
        return register(repository, spellClass, List.of());
    }

    public static synchronized List<Control> register(FormulaClassRepository repository, String spellClass,
                                                     List<FormulaAutomaticSites.Site> sites) {
        String spell = spellClass.replace('/', '.');
        var controls = List.copyOf(sites);
        Map<String, Entry> selected = new LinkedHashMap<>();
        for (Provider provider : PROVIDERS) for (Control control : provider.discover().apply(repository, spell, controls)) {
            if (!control.numeric().spellClassName().equals(spell))
                throw new IllegalArgumentException("来源消费归属不符：" + provider.id());
            if (selected.putIfAbsent(control.numeric().dispatchKey(), new Entry(control, provider)) != null)
                throw new IllegalArgumentException("来源消费入口重复：" + control.numeric().dispatchKey());
        }
        Map<String, List<Entry>> bySpell = new LinkedHashMap<>(registered.bySpell());
        bySpell.put(spell, List.copyOf(selected.values()));
        Map<String, Entry> byBinding = new LinkedHashMap<>();
        bySpell.values().forEach(entries -> entries.forEach(entry -> byBinding.put(entry.control().numeric().dispatchKey(), entry)));
        registered = new Index(Map.copyOf(bySpell), Map.copyOf(byBinding));
        return selected.values().stream().map(Entry::control).toList();
    }

    public static FormulaNumericSites.Definition find(String key) {
        Entry entry = registered.byBinding().get(key);
        return entry == null ? null : entry.control().numeric();
    }

    public static boolean isDamage(String key) {
        return damageInput(key) != DamageInput.NONE;
    }

    public static DamageInput damageInput(String key) {
        Entry entry = registered.byBinding().get(key);
        return entry == null ? DamageInput.NONE : entry.provider().damageInput();
    }

    public static boolean availableBinding(String spell, String method, String fingerprint) {
        String key = FormulaRuntimeDispatcher.key(spell, method);
        Entry entry = registered.byBinding().get(key);
        return entry != null && entry.control().fingerprint().equals(fingerprint) && entry.provider().available().test(key);
    }
}
