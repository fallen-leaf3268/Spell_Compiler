package com.example.spell_compiler.formula;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class FormulaAddonCatalog {
    record Result(SpellFormulaCatalog catalog, List<SpellFormulaFiles.Diagnostic> diagnostics) {
        Result { diagnostics = List.copyOf(diagnostics); }
    }

    private static final String ABSTRACT = "io.redspace.ironsspellbooks.api.spells.AbstractSpell";
    private static final List<String> BASE_FIELDS = List.of("baseManaCost", "manaCostPerLevel",
            "baseSpellPower", "spellPowerPerLevel", "castTime");

    private FormulaAddonCatalog() {}

    static Result discover(SpellFormulaCatalog builtin, Map<ResourceLocation, AbstractSpell> registered,
                           FormulaAddonDiscovery.Discovery discovery, Map<String, Set<String>> hooked) {
        Set<String> automatic = new HashSet<>();
        hooked.forEach((owner, methods) -> methods.forEach(key -> automatic.add(FormulaRuntimeDispatcher.key(owner, key))));
        return discover(builtin, registered, discovery, hooked, automatic);
    }

    static Result discover(SpellFormulaCatalog builtin, Map<ResourceLocation, AbstractSpell> registered,
                           FormulaAddonDiscovery.Discovery discovery, Map<String, Set<String>> hooked,
                           Set<String> automaticHooked) {
        if (FormulaLoadedCatalog.disabled()) return new Result(FormulaLoadedCatalog.unavailable(), List.of());
        if (discovery == null) {
            var retained = builtin.spells().stream().map(spell -> retain(spell,
                    method -> method.consumptionFingerprint().isEmpty())).toList();
            return new Result(new SpellFormulaCatalog(retained, builtin.sourceFingerprint(), builtin.analysisReports()), List.of());
        }
        List<SpellFormulaFiles.Diagnostic> diagnostics = new ArrayList<>();
        Map<String, JsonObject> reports = new HashMap<>(builtin.analysisReports());
        Map<String, List<ResourceLocation>> identities = new HashMap<>();
        registered.forEach((id, spell) -> identities.computeIfAbsent(spell.getClass().getName(), ignored -> new ArrayList<>()).add(id));
        Set<String> ambiguous = new HashSet<>();
        identities.forEach((name, ids) -> {
            if (ids.size() > 1) {
                ambiguous.add(name);
                diagnostics.add(diagnostic(ids.get(0), "同一法术类注册了多个 ID，保留原生行为：" + ids, null));
            }
        });
        List<SpellFormulaCatalog.Spell> spells = new ArrayList<>(builtin.spells().stream()
                .filter(spell -> !ambiguous.contains(spell.className()))
                .filter(spell -> discovery.nativeSources().isEmpty()
                        || registered.entrySet().stream().anyMatch(entry -> entry.getKey().toString().equals(spell.id())
                        && entry.getValue().getClass().getName().equals(spell.className())))
                .map(spell -> retain(spell, method -> method.consumptionFingerprint().startsWith("EFFECT_ATTRIBUTE:")
                                ? attributeBinding(spell.className(), method.key(), method.consumptionFingerprint())
                                : method.consumptionFingerprint().isEmpty()
                                ? discovery.repository().baselineCoverage() || hooked(spell.className(), method.declaringClass(), method.key(), hooked)
                                : automaticHooked.contains(FormulaRuntimeDispatcher.key(spell.className(), method.key())))).toList());
        Set<String> existing = new HashSet<>();
        spells.forEach(spell -> existing.add(spell.id()));
        FormulaUsage usage = new FormulaUsage(discovery.repository(), discovery.sources());
        for (var entry : new java.util.TreeMap<>(registered).entrySet()) {
            ResourceLocation id = entry.getKey();
            AbstractSpell actual = entry.getValue();
            String className = actual.getClass().getName();
            String internalName = className.replace('.', '/');
            if (existing.contains(id.toString()) || ambiguous.contains(className)
                    || !discovery.spellClasses().contains(internalName)
                    && !discovery.nativeSpellClasses().contains(internalName)) continue;
            try {
                if (!id.equals(actual.getSpellResource()))
                    throw new IllegalArgumentException("注册 ID 与法术资源 ID 不一致：" + actual.getSpellResource());
                var owner = discovery.repository().classNode(internalName);
                if (owner == null) throw new IllegalArgumentException("找不到附属法术原始字节码：" + className);
                validateBaseFields(discovery.repository(), owner);
                JsonObject metadata = metadata(id, actual, builtin);
                JsonArray rejected = new JsonArray();
                FormulaCatalogGenerator.extractSpell(discovery.repository(), owner, metadata, actual.getCastType().name(),
                        usage, !discovery.nativeSpellClasses().contains(internalName) || !discovery.repository().baselineCoverage(), rejected);
                Set<String> retainedBindings = new HashSet<>();
                for (var raw : metadata.getAsJsonArray("formulas")) {
                    JsonObject method = raw.getAsJsonObject();
                    String declaring = method.get("declaringClass").getAsString();
                    String key = method.get("name").getAsString() + method.get("descriptor").getAsString();
                    boolean automatic = method.has("consumptionFingerprint");
                    String fingerprint = automatic ? method.get("consumptionFingerprint").getAsString() : "";
                    boolean retained = fingerprint.startsWith("EFFECT_ATTRIBUTE:")
                            ? attributeBinding(className, key, fingerprint)
                            : automatic ? automaticHooked.contains(FormulaRuntimeDispatcher.key(className, key))
                            : hooked(className, declaring, key, hooked);
                    if (retained) retainedBindings.add(key);
                    else diagnostics.add(diagnostic(id, "附属公式没有早期挂钩，保留原方法：" + declaring + '#' + key, null));
                }
                metadata.getAsJsonObject("execution").add("rejectedMethods", rejected.deepCopy());
                Set<String> unsafeFields = usage.uncoveredFields(owner.name,
                        Set.of(ABSTRACT.replace('.', '/') + "#getSpellPower(ILnet/minecraft/world/entity/Entity;)F"));
                JsonArray editableParameters = new JsonArray();
                for (var value : metadata.getAsJsonArray("editableParameters")) {
                    String name = value.getAsString();
                    if ((name.equals("baseSpellPower") || name.equals("spellPowerPerLevel")) && unsafeFields.contains(name))
                        diagnostics.add(diagnostic(id, "基础强度存在未覆盖的字段读取，隐藏无效参数：" + name, null));
                    else editableParameters.add(value);
                }
                metadata.add("editableParameters", editableParameters);
                JsonObject root = new JsonObject();
                JsonArray entries = new JsonArray();
                entries.add(metadata);
                root.add("spells", entries);
                var extracted = SpellFormulaCatalog.parse(root);
                spells.addAll(extracted.spells().stream()
                        .map(spell -> retain(spell, method -> retainedBindings.contains(method.key()))).toList());
                reports.putAll(extracted.analysisReports());
                for (var raw : rejected) {
                    JsonObject method = raw.getAsJsonObject();
                    diagnostics.add(diagnostic(id, "附属公式暂不支持：" + method.get("name").getAsString()
                            + method.get("descriptor").getAsString() + ": " + method.get("reason").getAsString(), null));
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                diagnostics.add(diagnostic(id, "附属法术未接入，保留原生行为：" + failure.getMessage(), failure));
            }
        }
        Map<String, List<SpellFormulaCatalog.Spell>> paths = new HashMap<>();
        spells.forEach(spell -> {
            paths.computeIfAbsent(SpellFormulaFiles.resourceKey(spell), ignored -> new ArrayList<>()).add(spell);
            paths.computeIfAbsent(spell.id(), ignored -> new ArrayList<>()).add(spell);
        });
        Set<String> conflicting = new HashSet<>();
        paths.forEach((path, entries) -> {
            if (entries.size() < 2) return;
            entries.forEach(spell -> {
                conflicting.add(spell.id());
                diagnostics.add(diagnostic(ResourceLocation.parse(spell.id()), "法术数据路径发生冲突，保留原生行为：" + path, null));
            });
        });
        spells.removeIf(spell -> conflicting.contains(spell.id()));
        spells.replaceAll(spell -> retain(spell, method -> {
                    if (!method.consumptionFingerprint().isEmpty()) return true;
                    String reason = FormulaMethodCompatibility.unavailableReason(method.declaringClass(), method.key());
                    if (reason == null) return true;
                    diagnostics.add(diagnostic(ResourceLocation.parse(spell.id()), reason, null));
                    return false;
                }));
        Map<String, String> manaFailures = FormulaManaRatioBridge.diagnostics();
        spells.replaceAll(spell -> {
            String reason = manaFailures.get(spell.className());
            if (reason == null) return spell;
            var failedGetters = FormulaManaRatioBridge.definitions(spell.className()).stream()
                    .map(FormulaConsumerSites.Definition::key).collect(java.util.stream.Collectors.toSet());
            var formulas = spell.formulas().stream().map(method -> {
                if (!failedGetters.contains(method.key())) return method;
                String message = "回魔公式未接入实际效果消费，保留原生行为：" + reason;
                diagnostics.add(diagnostic(ResourceLocation.parse(spell.id()), message, null));
                return new SpellFormulaCatalog.Method(method.alias(), method.name(), method.descriptor(), method.strictExpression(),
                        method.readableExpression(), method.variables(), method.declaringClass(), false,
                        method.consumptionFingerprint(), message);
            }).toList();
            return new SpellFormulaCatalog.Spell(spell.id(), spell.className(), spell.school(), spell.advanced(), spell.parameters(),
                    formulas, spell.editableParameterNames(), spell.damageComponents(), spell.attributeComponents().stream()
                            .filter(component -> formulas.stream().anyMatch(method -> method.editable() && method.key().equals(component.binding()))).toList());
        });
        spells.sort(java.util.Comparator.comparing(SpellFormulaCatalog.Spell::id));
        JsonObject scanReport = reports.getOrDefault("__catalog__", new JsonObject());
        JsonArray discoveryDiagnostics = new JsonArray();
        diagnostics.forEach(diagnostic -> discoveryDiagnostics.add(diagnostic.path() + ":" + diagnostic.message()));
        scanReport.add("discoveryDiagnostics", discoveryDiagnostics);
        reports.put("__catalog__", scanReport);
        return new Result(new SpellFormulaCatalog(spells, discovery.fingerprint(), reports), diagnostics);
    }

    private static SpellFormulaCatalog.Spell retain(SpellFormulaCatalog.Spell spell,
                                                     java.util.function.Predicate<SpellFormulaCatalog.Method> predicate) {
        var formulas = spell.formulas().stream().filter(predicate).toList();
        var keys = formulas.stream().map(SpellFormulaCatalog.Method::key).collect(java.util.stream.Collectors.toSet());
        var blocked = spell.damageComponents().stream().filter(component -> !keys.containsAll(component.terminalBindings()))
                .flatMap(component -> component.terminalBindings().stream()).collect(java.util.stream.Collectors.toSet());
        formulas = formulas.stream().filter(method -> !blocked.contains(method.key())).toList();
        var aliases = formulas.stream().map(SpellFormulaCatalog.Method::alias).collect(java.util.stream.Collectors.toSet());
        var attributeKeys = formulas.stream().filter(SpellFormulaCatalog.Method::editable).map(SpellFormulaCatalog.Method::key)
                .collect(java.util.stream.Collectors.toSet());
        var components = spell.damageComponents().stream().filter(component -> keys.containsAll(component.terminalBindings())
                        && aliases.contains(component.alias()))
                .map(component -> new SpellFormulaCatalog.DamageComponent(component.id(), component.alias(), component.terminalBindings(),
                        component.upstreamAliases().stream().filter(aliases::contains).toList(), component.previewMethods(), component.evidence())).toList();
        return new SpellFormulaCatalog.Spell(spell.id(), spell.className(), spell.school(), spell.advanced(), spell.parameters(),
                formulas, spell.editableParameterNames(), components, spell.attributeComponents().stream()
                        .filter(component -> attributeKeys.contains(component.binding())).toList());
    }

    private static boolean attributeBinding(String spellClass, String method, String fingerprint) {
        String key = FormulaRuntimeDispatcher.key(spellClass, method);
        return FormulaEffectAttributeControls.availableBinding(key)
                && FormulaEffectAttributeControls.definitions(spellClass).stream().anyMatch(definition ->
                definition.numeric().key().equals(method) && fingerprint.equals("EFFECT_ATTRIBUTE:" + definition.fingerprint())
                        && FormulaEntityFlow.hasVerifiedEffectSource(spellClass, definition.registryOwner(), definition.registryField()));
    }

    private static void validateBaseFields(FormulaClassRepository repository, org.objectweb.asm.tree.ClassNode owner) {
        Set<String> visited = new HashSet<>();
        while (owner != null && !owner.name.equals(ABSTRACT.replace('.', '/')) && visited.add(owner.name)) {
            for (var field : owner.fields)
                if (BASE_FIELDS.contains(field.name))
                    throw new IllegalArgumentException("附属声明了同名基础字段，无法安全区分：" + owner.name + '.' + field.name);
            owner = owner.superName == null ? null : repository.classNode(owner.superName);
        }
    }

    private static boolean hooked(String spellClass, String declaring, String key, Map<String, Set<String>> methods) {
        return declaring.equals(ABSTRACT) || methods.getOrDefault(declaring, Set.of()).contains(key)
                || declaring.equals(FormulaNumericValues.class.getName())
                && methods.getOrDefault(spellClass, Set.of()).contains(key);
    }

    private static JsonObject metadata(ResourceLocation id, AbstractSpell spell, SpellFormulaCatalog builtin)
            throws ReflectiveOperationException {
        var defaults = spell.getDefaultConfig();
        if (defaults == null || defaults.schoolResource == null || defaults.minRarity == null || defaults.maxLevel < 1)
            throw new IllegalArgumentException("附属法术默认配置不完整");
        JsonObject result = new JsonObject();
        result.addProperty("id", id.toString());
        result.addProperty("className", spell.getClass().getName());
        result.addProperty("school", defaults.schoolResource.toString());
        JsonObject advanced = new JsonObject();
        advanced.addProperty("enabled", defaults.enabled);
        advanced.addProperty("max_level", defaults.maxLevel);
        advanced.addProperty("min_rarity", defaults.minRarity.name().toLowerCase(Locale.ROOT));
        advanced.addProperty("allow_crafting", defaults.allowCrafting);
        advanced.addProperty("school", defaults.schoolResource.toString());
        result.add("advanced", advanced);
        JsonObject parameters = new JsonObject();
        for (String name : BASE_FIELDS) {
            Field field = AbstractSpell.class.getDeclaredField(name);
            field.setAccessible(true);
            parameters.addProperty(name, field.getInt(spell));
        }
        parameters.addProperty("cooldown_in_seconds", defaults.cooldownInSeconds);
        for (String name : List.of("power_multiplier", "mana_cost_multiplier"))
            if (!builtin.spells().isEmpty() && builtin.spells().getFirst().parameters().has(name))
                parameters.add(name, builtin.spells().getFirst().parameters().get(name).deepCopy());
            else parameters.addProperty(name, 1d);
        result.add("parameters", parameters);
        return result;
    }

    private static SpellFormulaFiles.Diagnostic diagnostic(ResourceLocation id, String message, Throwable cause) {
        return new SpellFormulaFiles.Diagnostic(Path.of(id.getNamespace(), id.getPath()), message, cause);
    }
}
