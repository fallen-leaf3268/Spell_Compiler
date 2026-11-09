package com.example.spell_compiler.formula;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;

public final class SpellFormulaCatalog {
    public static final String RESOURCE = "formula/ironsspells-catalog.json";

    public record Method(String alias, String name, String descriptor, String strictExpression,
                         String readableExpression, Map<String, String> variables, String declaringClass,
                         boolean editable, String consumptionFingerprint, String readOnlyReason) {
        public Method {
            variables = Map.copyOf(variables);
            consumptionFingerprint = java.util.Objects.requireNonNull(consumptionFingerprint);
            readOnlyReason = java.util.Objects.requireNonNull(readOnlyReason);
            if (editable && !readOnlyReason.isEmpty()) throw new IllegalArgumentException("公开公式不能标为只读：" + alias);
            if (!declaringClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))
                throw new IllegalArgumentException("无效公式声明类名：" + declaringClass);
        }

        public Method(String alias, String name, String descriptor, String strictExpression,
                      String readableExpression, Map<String, String> variables, String declaringClass,
                      boolean editable, String consumptionFingerprint) {
            this(alias, name, descriptor, strictExpression, readableExpression, variables, declaringClass,
                    editable, consumptionFingerprint, "");
        }

        public Method(String alias, String name, String descriptor, String strictExpression,
                      String readableExpression, Map<String, String> variables, String declaringClass,
                      boolean editable) {
            this(alias, name, descriptor, strictExpression, readableExpression, variables, declaringClass, editable, "");
        }

        public Method(String alias, String name, String descriptor, String strictExpression,
                      String readableExpression, Map<String, String> variables, String declaringClass) {
            this(alias, name, descriptor, strictExpression, readableExpression, variables, declaringClass, true, "");
        }

        public String key() {
            return name + descriptor;
        }
    }

    public record DamageComponent(String id, String alias, List<String> terminalBindings,
                                  List<String> upstreamAliases, List<String> previewMethods, List<String> evidence) {
        public DamageComponent {
            java.util.Objects.requireNonNull(id);
            java.util.Objects.requireNonNull(alias);
            terminalBindings = List.copyOf(terminalBindings);
            upstreamAliases = List.copyOf(upstreamAliases);
            previewMethods = List.copyOf(previewMethods);
            evidence = List.copyOf(evidence);
            if (terminalBindings.isEmpty()) throw new IllegalArgumentException("伤害组件缺少最终消费绑定：" + alias);
        }

        public String unit() { return "raw_damage_before_defenses"; }

        public JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("id", id);
            result.addProperty("alias", alias);
            result.addProperty("unit", unit());
            result.add("terminalBindings", strings(terminalBindings));
            result.add("upstreamAliases", strings(upstreamAliases));
            result.add("previewMethods", strings(previewMethods));
            result.add("evidence", strings(evidence));
            return result;
        }
    }

    public record Spell(String id, String className, String school, JsonObject advanced,
                        JsonObject parameters, List<Method> formulas, Set<String> editableParameterNames,
                        List<DamageComponent> damageComponents, List<FormulaAttributes.Component> attributeComponents) {
        public Spell {
            advanced = advanced.deepCopy();
            parameters = parameters.deepCopy();
            formulas = List.copyOf(formulas);
            editableParameterNames = Set.copyOf(editableParameterNames);
            damageComponents = List.copyOf(damageComponents);
            attributeComponents = List.copyOf(attributeComponents);
            if (!parameters.keySet().containsAll(editableParameterNames))
                throw new IllegalArgumentException("未知公开参数：" + id);
            requireId(id);
            requireId(school);
            if (!className.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))
                throw new IllegalArgumentException("无效法术类名：" + className);
            if (formulas.stream().map(Method::alias).distinct().count() != formulas.size()
                    || formulas.stream().map(Method::key).distinct().count() != formulas.size())
                throw new IllegalArgumentException("重复公式绑定：" + id);
            Set<String> keys = formulas.stream().map(Method::key).collect(java.util.stream.Collectors.toSet());
            Set<String> aliases = formulas.stream().map(Method::alias).collect(java.util.stream.Collectors.toSet());
            if (damageComponents.stream().map(DamageComponent::id).distinct().count() != damageComponents.size()
                    || damageComponents.stream().map(DamageComponent::alias).distinct().count() != damageComponents.size())
                throw new IllegalArgumentException("重复伤害组件：" + id);
            Set<String> claimed = new LinkedHashSet<>();
            for (DamageComponent component : damageComponents) {
                if (!aliases.contains(component.alias()) || !keys.containsAll(component.terminalBindings())
                        || !aliases.containsAll(component.upstreamAliases())
                        || component.terminalBindings().stream().anyMatch(key -> !claimed.add(key)))
                    throw new IllegalArgumentException("无效伤害组件绑定：" + id + '/' + component.alias());
                Method canonical = formulas.stream().filter(method -> method.alias().equals(component.alias())).findFirst().orElseThrow();
                if (!canonical.editable() || !component.terminalBindings().contains(canonical.key()))
                    throw new IllegalArgumentException("伤害组件缺少公开入口：" + id + '/' + component.alias());
                if (formulas.stream().anyMatch(method -> component.upstreamAliases().contains(method.alias()) && method.editable()))
                    throw new IllegalArgumentException("伤害组件上游入口仍然公开：" + id + '/' + component.alias());
            }
            if (attributeComponents.size() > 32
                    || attributeComponents.stream().map(FormulaAttributes.Component::key).distinct().count() != attributeComponents.size()
                    || attributeComponents.stream().map(FormulaAttributes.Component::alias).distinct().count() != attributeComponents.size()
                    || attributeComponents.stream().map(FormulaAttributes.Component::binding).distinct().count() != attributeComponents.size())
                throw new IllegalArgumentException("属性组件重复或超过数量限制：" + id);
            Set<String> attributeSources = new LinkedHashSet<>();
            for (var component : attributeComponents) {
                if (!component.key().matches("[a-z][a-z0-9_]{0,63}"))
                    throw new IllegalArgumentException("无效属性组件键：" + component.key());
                requireId(component.effectId());
                requireId(component.sourceAttribute());
                java.util.UUID.fromString(component.modifierId());
                if (!attributeSources.add(component.effectId() + '#' + component.sourceAttribute() + '#' + component.modifierId())
                        || formulas.stream().noneMatch(method -> method.editable() && method.key().equals(component.binding())
                        && method.alias().equals(component.alias())))
                    throw new IllegalArgumentException("无效属性组件绑定：" + id + '/' + component.key());
            }
        }

        public Spell(String id, String className, String school, JsonObject advanced,
                     JsonObject parameters, List<Method> formulas, Set<String> editableParameterNames,
                     List<DamageComponent> damageComponents) {
            this(id, className, school, advanced, parameters, formulas, editableParameterNames, damageComponents, List.of());
        }

        public Spell(String id, String className, String school, JsonObject advanced,
                     JsonObject parameters, List<Method> formulas, Set<String> editableParameterNames) {
            this(id, className, school, advanced, parameters, formulas, editableParameterNames, List.of());
        }

        public Spell(String id, String className, String school, JsonObject advanced,
                     JsonObject parameters, List<Method> formulas) {
            this(id, className, school, advanced, parameters, formulas, parameters.keySet());
        }

        public List<Method> editableFormulas() {
            return formulas.stream().filter(Method::editable).toList();
        }

        public JsonObject editableParameters() {
            JsonObject result = parameters.deepCopy();
            for (String name : parameters.keySet())
                if (!editableParameterNames.contains(name)) result.remove(name);
            if (formulas.stream().anyMatch(method -> method.key()
                    .equals("getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I") && !method.editable()))
                result.remove("castTime");
            return result;
        }
    }

    private final List<Spell> spells;

    public SpellFormulaCatalog(List<Spell> spells) {
        this(spells, "");
    }

    private final String sourceFingerprint;
    private final Map<String, JsonObject> analysisReports;

    public SpellFormulaCatalog(List<Spell> spells, String sourceFingerprint) {
        this(spells, sourceFingerprint, Map.of());
    }

    public SpellFormulaCatalog(List<Spell> spells, String sourceFingerprint, Map<String, JsonObject> reports) {
        this.spells = List.copyOf(spells);
        this.sourceFingerprint = java.util.Objects.requireNonNull(sourceFingerprint);
        Map<String, JsonObject> copied = new LinkedHashMap<>();
        spells.forEach(spell -> {
            if (reports.containsKey(spell.id())) copied.put(spell.id(), reports.get(spell.id()).deepCopy());
        });
        if (reports.containsKey("__catalog__")) copied.put("__catalog__", reports.get("__catalog__").deepCopy());
        this.analysisReports = Map.copyOf(copied);
        if (spells.stream().map(Spell::id).distinct().count() != spells.size()
                || spells.stream().map(Spell::className).distinct().count() != spells.size())
            throw new IllegalArgumentException("法术目录包含重复 ID 或类名");
    }

    public List<Spell> spells() {
        return spells;
    }

    public String sourceFingerprint() {
        return sourceFingerprint;
    }

    public Map<String, JsonObject> analysisReports() {
        Map<String, JsonObject> copied = new LinkedHashMap<>();
        analysisReports.forEach((id, report) -> copied.put(id, report.deepCopy()));
        return Map.copyOf(copied);
    }

    public JsonObject analysis(String spellId) {
        JsonObject report = analysisReports.get(spellId);
        if (report != null) return report.deepCopy();
        JsonObject missing = new JsonObject();
        missing.addProperty("analysis", "not_provided");
        return missing;
    }

    public String fingerprint() {
        JsonArray entries = new JsonArray();
        for (var spell : spells.stream().sorted(java.util.Comparator.comparing(Spell::id)).toList()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", spell.id());
            entry.addProperty("class", spell.className());
            entry.addProperty("school", spell.school());
            entry.add("advanced", spell.advanced());
            entry.add("parameters", spell.parameters());
            JsonArray editable = new JsonArray();
            spell.editableParameterNames().stream().sorted().forEach(editable::add);
            entry.add("editableParameters", editable);
            JsonArray methods = new JsonArray();
            for (var method : spell.formulas().stream().sorted(java.util.Comparator.comparing(Method::key)).toList()) {
                JsonObject binding = new JsonObject();
                binding.addProperty("key", method.key());
                binding.addProperty("alias", method.alias());
                binding.addProperty("declaringClass", method.declaringClass());
                binding.addProperty("strict", method.strictExpression());
                binding.addProperty("readable", method.readableExpression());
                binding.addProperty("editable", method.editable());
                binding.addProperty("consumptionFingerprint", method.consumptionFingerprint());
                binding.addProperty("readOnlyReason", method.readOnlyReason());
                JsonObject variables = new JsonObject();
                method.variables().forEach(variables::addProperty);
                binding.add("variables", variables);
                methods.add(binding);
            }
            entry.add("methods", methods);
            JsonArray components = new JsonArray();
            spell.damageComponents().stream().sorted(java.util.Comparator.comparing(DamageComponent::id))
                    .forEach(component -> components.add(component.toJson()));
            entry.add("damageComponents", components);
            JsonArray attributes = new JsonArray();
            spell.attributeComponents().stream().sorted(java.util.Comparator.comparing(FormulaAttributes.Component::key))
                    .forEach(component -> attributes.add(component.toJson()));
            entry.add("attributeComponents", attributes);
            entries.add(entry);
        }
        try {
            JsonObject identity = new JsonObject();
            identity.addProperty("source", sourceFingerprint);
            identity.add("spells", entries);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(SpellFormulaFiles.canonical(identity).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static SpellFormulaCatalog load(ClassLoader loader) throws IOException {
        var stream = loader.getResourceAsStream(RESOURCE);
        if (stream == null) throw new IOException("找不到法术公式目录资源：" + RESOURCE);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (RuntimeException failure) {
            throw new IOException("无法解析法术公式目录：" + failure.getMessage(), failure);
        }
    }

    public static SpellFormulaCatalog parse(JsonObject root) {
        List<Spell> entries = new ArrayList<>();
        Map<String, JsonObject> reports = new LinkedHashMap<>();
        JsonObject scan = new JsonObject();
        for (String key : List.of("rejected", "excluded", "classErrors"))
            if (root.has(key)) scan.add(key, root.get(key).deepCopy());
        if (scan.size() != 0) reports.put("__catalog__", scan);
        for (JsonElement value : root.getAsJsonArray("spells")) {
            JsonObject entry = value.getAsJsonObject();
            if (entry.has("execution")) reports.put(string(entry, "id"), entry.getAsJsonObject("execution"));
            List<Method> methods = new ArrayList<>();
            for (JsonElement rawMethod : entry.getAsJsonArray("formulas")) {
                JsonObject method = rawMethod.getAsJsonObject();
                Map<String, String> variables = new LinkedHashMap<>();
                method.getAsJsonObject("variables").entrySet()
                        .forEach(variable -> variables.put(variable.getKey(), variable.getValue().getAsString()));
                methods.add(new Method(string(method, "alias"), string(method, "name"),
                        string(method, "descriptor"), string(method, "strictExpression"),
                        string(method, "readableExpression"), variables, string(method, "declaringClass"),
                        !method.has("editable") || method.get("editable").getAsBoolean(),
                        method.has("consumptionFingerprint") ? string(method, "consumptionFingerprint") : "",
                        method.has("readOnlyReason") ? string(method, "readOnlyReason") : ""));
            }
            Set<String> editableParameters = new LinkedHashSet<>();
            if (entry.has("editableParameters"))
                entry.getAsJsonArray("editableParameters").forEach(name -> editableParameters.add(name.getAsString()));
            else editableParameters.addAll(entry.getAsJsonObject("parameters").keySet());
            List<DamageComponent> components = new ArrayList<>();
            if (entry.has("damageComponents")) for (JsonElement raw : entry.getAsJsonArray("damageComponents")) {
                JsonObject component = raw.getAsJsonObject();
                if (component.has("unit") && !string(component, "unit").equals("raw_damage_before_defenses"))
                    throw new IllegalArgumentException("未知伤害组件单位：" + string(component, "unit"));
                components.add(new DamageComponent(string(component, "id"), string(component, "alias"),
                        strings(component, "terminalBindings"), strings(component, "upstreamAliases"),
                        strings(component, "previewMethods"), strings(component, "evidence")));
            }
            List<FormulaAttributes.Component> attributes = new ArrayList<>();
            if (entry.has("attributeComponents")) for (JsonElement raw : entry.getAsJsonArray("attributeComponents")) {
                JsonObject component = raw.getAsJsonObject();
                attributes.add(new FormulaAttributes.Component(string(component, "key"), string(component, "alias"),
                        string(component, "binding"), string(component, "effectId"), string(component, "sourceAttribute"),
                        string(component, "modifierId"), FormulaAttributes.Operation.parse(string(component, "operation"))));
            }
            entries.add(new Spell(string(entry, "id"), string(entry, "className"), string(entry, "school"),
                    entry.getAsJsonObject("advanced"), entry.getAsJsonObject("parameters"), methods, editableParameters, components, attributes));
        }
        return new SpellFormulaCatalog(entries, root.has("sourceSha256") ? string(root, "sourceSha256") : "", reports);
    }

    private static String string(JsonObject object, String key) {
        return object.get(key).getAsString();
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static List<String> strings(JsonObject object, String key) {
        if (!object.has(key)) return List.of();
        return object.getAsJsonArray(key).asList().stream().map(JsonElement::getAsString).toList();
    }

    static void requireId(String id) {
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("无效注册 ID：" + id);
        String path = id.substring(id.indexOf(':') + 1);
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//"))
            throw new IllegalArgumentException("无效注册路径：" + id);
        for (String segment : path.split("/"))
            if (segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("无效注册路径：" + id);
    }
}
