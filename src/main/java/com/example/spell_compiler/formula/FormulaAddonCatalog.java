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
        if (discovery == null) return new Result(builtin, List.of());
        List<SpellFormulaFiles.Diagnostic> diagnostics = new ArrayList<>();
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
                .filter(spell -> !ambiguous.contains(spell.className())).toList());
        Set<String> existing = new HashSet<>();
        spells.forEach(spell -> existing.add(spell.id()));
        FormulaUsage usage = new FormulaUsage(discovery.repository(), discovery.sources());
        for (var entry : new java.util.TreeMap<>(registered).entrySet()) {
            ResourceLocation id = entry.getKey();
            AbstractSpell actual = entry.getValue();
            String className = actual.getClass().getName();
            String internalName = className.replace('.', '/');
            if (existing.contains(id.toString()) || ambiguous.contains(className)
                    || !discovery.spellClasses().contains(internalName)) continue;
            try {
                if (!id.equals(actual.getSpellResource()))
                    throw new IllegalArgumentException("注册 ID 与法术资源 ID 不一致：" + actual.getSpellResource());
                var owner = discovery.repository().classNode(internalName);
                if (owner == null) throw new IllegalArgumentException("找不到附属法术原始字节码：" + className);
                validateBaseFields(discovery.repository(), owner);
                JsonObject metadata = metadata(id, actual, builtin);
                JsonArray rejected = new JsonArray();
                FormulaCatalogGenerator.extractSpell(discovery.repository(), owner, metadata, actual.getCastType().name(),
                        usage, true, rejected);
                JsonArray formulas = new JsonArray();
                for (var raw : metadata.getAsJsonArray("formulas")) {
                    JsonObject method = raw.getAsJsonObject();
                    String declaring = method.get("declaringClass").getAsString();
                    String key = method.get("name").getAsString() + method.get("descriptor").getAsString();
                    if (declaring.equals(ABSTRACT) || hooked.getOrDefault(declaring, Set.of()).contains(key)) formulas.add(method);
                    else diagnostics.add(diagnostic(id, "附属公式没有早期挂钩，保留原方法：" + declaring + '#' + key, null));
                }
                metadata.add("formulas", formulas);
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
                spells.addAll(SpellFormulaCatalog.parse(root).spells());
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
        spells.sort(java.util.Comparator.comparing(SpellFormulaCatalog.Spell::id));
        return new Result(new SpellFormulaCatalog(spells, discovery.fingerprint()), diagnostics);
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
            parameters.add(name, builtin.spells().get(0).parameters().get(name).deepCopy());
        result.add("parameters", parameters);
        return result;
    }

    private static SpellFormulaFiles.Diagnostic diagnostic(ResourceLocation id, String message, Throwable cause) {
        return new SpellFormulaFiles.Diagnostic(Path.of(id.getNamespace(), id.getPath()), message, cause);
    }
}
