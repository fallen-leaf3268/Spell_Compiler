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
    public static final String RESOURCE = "formula/ironsspells-3.16.1-catalog.json";

    public record Method(String alias, String name, String descriptor, String strictExpression,
                         String readableExpression, Map<String, String> variables, String declaringClass,
                         boolean editable) {
        public Method {
            variables = Map.copyOf(variables);
            if (!declaringClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))
                throw new IllegalArgumentException("无效公式声明类名：" + declaringClass);
        }

        public Method(String alias, String name, String descriptor, String strictExpression,
                      String readableExpression, Map<String, String> variables, String declaringClass) {
            this(alias, name, descriptor, strictExpression, readableExpression, variables, declaringClass, true);
        }

        public String key() {
            return name + descriptor;
        }
    }

    public record Spell(String id, String className, String school, JsonObject advanced,
                        JsonObject parameters, List<Method> formulas, Set<String> editableParameterNames) {
        public Spell {
            advanced = advanced.deepCopy();
            parameters = parameters.deepCopy();
            formulas = List.copyOf(formulas);
            editableParameterNames = Set.copyOf(editableParameterNames);
            if (!parameters.keySet().containsAll(editableParameterNames))
                throw new IllegalArgumentException("未知公开参数：" + id);
            requireId(id);
            requireId(school);
            if (!className.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))
                throw new IllegalArgumentException("无效法术类名：" + className);
            if (formulas.stream().map(Method::alias).distinct().count() != formulas.size()
                    || formulas.stream().map(Method::key).distinct().count() != formulas.size())
                throw new IllegalArgumentException("重复公式绑定：" + id);
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

    public SpellFormulaCatalog(List<Spell> spells, String sourceFingerprint) {
        this.spells = List.copyOf(spells);
        this.sourceFingerprint = java.util.Objects.requireNonNull(sourceFingerprint);
        if (spells.stream().map(Spell::id).distinct().count() != spells.size()
                || spells.stream().map(Spell::className).distinct().count() != spells.size())
            throw new IllegalArgumentException("法术目录包含重复 ID 或类名");
    }

    public List<Spell> spells() {
        return spells;
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
                JsonObject variables = new JsonObject();
                method.variables().forEach(variables::addProperty);
                binding.add("variables", variables);
                methods.add(binding);
            }
            entry.add("methods", methods);
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
        for (JsonElement value : root.getAsJsonArray("spells")) {
            JsonObject entry = value.getAsJsonObject();
            List<Method> methods = new ArrayList<>();
            for (JsonElement rawMethod : entry.getAsJsonArray("formulas")) {
                JsonObject method = rawMethod.getAsJsonObject();
                Map<String, String> variables = new LinkedHashMap<>();
                method.getAsJsonObject("variables").entrySet()
                        .forEach(variable -> variables.put(variable.getKey(), variable.getValue().getAsString()));
                methods.add(new Method(string(method, "alias"), string(method, "name"),
                        string(method, "descriptor"), string(method, "strictExpression"),
                        string(method, "readableExpression"), variables, string(method, "declaringClass"),
                        !method.has("editable") || method.get("editable").getAsBoolean()));
            }
            Set<String> editableParameters = new LinkedHashSet<>();
            if (entry.has("editableParameters"))
                entry.getAsJsonArray("editableParameters").forEach(name -> editableParameters.add(name.getAsString()));
            else editableParameters.addAll(entry.getAsJsonObject("parameters").keySet());
            entries.add(new Spell(string(entry, "id"), string(entry, "className"), string(entry, "school"),
                    entry.getAsJsonObject("advanced"), entry.getAsJsonObject("parameters"), methods, editableParameters));
        }
        return new SpellFormulaCatalog(entries);
    }

    private static String string(JsonObject object, String key) {
        return object.get(key).getAsString();
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
