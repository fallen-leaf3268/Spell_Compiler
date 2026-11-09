package com.example.spell_compiler.formula;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class SpellFormulaFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> RARITIES = Set.of("common", "uncommon", "rare", "epic", "legendary");
    private static final Set<String> INTEGER_PARAMETERS = Set.of("baseManaCost", "manaCostPerLevel", "castTime");
    private static final Set<String> LONG_PARAMETERS = Set.of("baseSpellPower", "spellPowerPerLevel");
    private static final Set<String> NONNEGATIVE_PARAMETERS = Set.of("baseManaCost", "manaCostPerLevel", "castTime", "cooldown_in_seconds", "power_multiplier", "mana_cost_multiplier");

    public record Diagnostic(Path path, String message, Throwable cause) {
        public Diagnostic(Path path, String message) {
            this(path, message, null);
        }
    }

    public record SpellOverride(SpellFormulaCatalog.Spell spell, Map<String, JsonElement> advanced,
                                Map<String, JsonElement> parameters, Map<String, String> formulas,
                                Map<String, FormulaAttributes.Config> attributes) {
        public SpellOverride {
            advanced = Map.copyOf(advanced);
            parameters = Map.copyOf(parameters);
            formulas = Map.copyOf(formulas);
            attributes = Map.copyOf(attributes);
        }

        public SpellOverride(SpellFormulaCatalog.Spell spell, Map<String, JsonElement> advanced,
                             Map<String, JsonElement> parameters, Map<String, String> formulas) {
            this(spell, advanced, parameters, formulas, Map.of());
        }

        public boolean isEmpty() {
            return advanced.isEmpty() && parameters.isEmpty() && formulas.isEmpty() && attributes.isEmpty();
        }
    }

    public record Snapshot(Map<String, SpellOverride> byClass, List<Diagnostic> diagnostics, String fingerprint) {
        public Snapshot {
            byClass = Map.copyOf(byClass);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private SpellFormulaFiles() {}

    public static List<Diagnostic> export(Path packDirectory, SpellFormulaCatalog catalog) throws IOException {
        return exportPack(packDirectory, catalog);
    }

    public static List<Diagnostic> exportPack(Path packDirectory, SpellFormulaCatalog catalog) {
        return exportPack(packDirectory, catalog, true, null);
    }

    public static List<Diagnostic> exportPack(Path packDirectory, SpellFormulaCatalog catalog, Path diagnosticDirectory) {
        return exportPack(packDirectory, catalog, true, diagnosticDirectory);
    }

    private static List<Diagnostic> exportPack(Path packDirectory, SpellFormulaCatalog catalog, boolean overwrite, Path diagnosticDirectory) {
        Path root = packDirectory.toAbsolutePath().normalize();
        boolean existing = Files.exists(root.resolve("data"));
        List<Diagnostic> diagnostics = new ArrayList<>();
        JsonObject metadata = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 15);
        JsonObject description = new JsonObject();
        description.addProperty("translate", "pack.spell_compiler.spells");
        pack.add("description", description);
        metadata.add("pack", pack);
        exportFile(root, root.resolve("pack.mcmeta"), metadata, diagnostics, overwrite);
        Map<String, List<SpellFormulaCatalog.Spell>> paths = resourcePaths(catalog);
        Set<String> ambiguous = ambiguousSpells(paths);
        for (var spell : catalog.spells()) {
            if (ambiguous.contains(spell.id()))
                diagnostics.add(new Diagnostic(file(root, spell), "多个法术使用相同数据路径，未导出：" + resourceKey(spell)));
            else exportFile(root, file(root, spell), preview(spell), diagnostics, overwrite);
        }
        if (diagnostics.isEmpty()) try {
            if (overwrite) exportFile(root, root.resolve("spell_compiler-source.json"), FormulaReports.manifest(catalog, false), diagnostics, true);
            if (diagnosticDirectory != null)
                FormulaReports.write(diagnosticDirectory, "migration", FormulaReports.migration(root, catalog));
            if (!overwrite) exportFile(root, root.resolve("spell_compiler-source.json"), FormulaReports.manifest(catalog, existing), diagnostics, false);
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            diagnostics.add(new Diagnostic(root, "无法生成数据包迁移报告：" + failure.getMessage(), failure));
        }
        return List.copyOf(diagnostics);
    }

    private static void exportFile(Path root, Path file, JsonElement json, List<Diagnostic> diagnostics, boolean overwrite) {
        try { writeExportFile(root, file, json, overwrite); }
        catch (IOException failure) { diagnostics.add(new Diagnostic(file, "无法导出法术数据包：" + failure.getMessage(), failure)); }
    }

    public static Snapshot exportAndLoad(Path packDirectory, SpellFormulaCatalog catalog) throws IOException {
        List<Diagnostic> diagnostics = new ArrayList<>(exportPack(packDirectory, catalog, false, null));
        Path root = packDirectory.toAbsolutePath().normalize();
        Map<String, JsonElement> resources = new LinkedHashMap<>();
        Set<String> unreadableResources = new HashSet<>();
        Path data = root.resolve("data");
        if (Files.isDirectory(data)) {
            try (var files = Files.walk(data)) {
                for (Path path : files.filter(value -> value.toString().endsWith(".json")).sorted().toList()) {
                    Path relative = data.relativize(path);
                    if (relative.getNameCount() < 3 || !relative.getName(1).toString().equals("spells")) continue;
                    String key = relative.getName(0) + ":" + relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
                    key = key.substring(0, key.length() - 5);
                    resources.put(key, JsonNull.INSTANCE);
                    try {
                        rejectSymbolicPath(root, path);
                        if (Files.size(path) > 262_144) throw new IllegalArgumentException("文件超过 256 KiB");
                        resources.put(key, parseJson(Files.readString(path, StandardCharsets.UTF_8)));
                    } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                        unreadableResources.add(key);
                        diagnostics.add(new Diagnostic(path, "数据包法术文件未应用：" + failure.getMessage(), failure));
                    }
                }
            }
        }
        Snapshot parsed = parseResources(resources, catalog, unreadableResources);
        diagnostics.addAll(parsed.diagnostics());
        return snapshot(parsed.byClass(), diagnostics);
    }

    public static Snapshot parseResources(Map<String, JsonElement> resources, SpellFormulaCatalog catalog) {
        return parseResources(resources, catalog, Set.of());
    }

    static Snapshot parseResources(Map<String, JsonElement> resources, SpellFormulaCatalog catalog,
                                   Set<String> unreadableResources) {
        Map<String, SpellFormulaCatalog.Spell> spells = new LinkedHashMap<>();
        catalog.spells().forEach(spell -> spells.put(spell.id(), spell));
        Map<String, List<SpellFormulaCatalog.Spell>> paths = resourcePaths(catalog);
        Set<String> ambiguous = ambiguousSpells(paths);
        Map<String, String> seen = new LinkedHashMap<>();
        Map<String, SpellOverride> overrides = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (var entry : new TreeMap<>(resources).entrySet()) {
            String resource = entry.getKey();
            Path path = Path.of(resource.replace(':', '/'));
            String id = null;
            try {
                SpellFormulaCatalog.requireId(resource);
                int namespaceEnd = resource.indexOf(':');
                SpellFormulaCatalog.Spell spell = null;
                if (resource.substring(namespaceEnd + 1).startsWith("spells/")) {
                    List<SpellFormulaCatalog.Spell> candidates = paths.getOrDefault(resource, List.of());
                    if (candidates.size() > 1) throw new IllegalArgumentException("多个法术使用相同数据路径：" + resource);
                    if (!candidates.isEmpty()) spell = candidates.get(0);
                } else spell = spells.get(resource);
                if (spell == null) {
                    String name = resource.substring(Math.max(namespaceEnd, resource.lastIndexOf('/')) + 1);
                    var legacy = spells.get(resource.substring(0, namespaceEnd + 1) + name);
                    if (legacy != null && legacy.id().startsWith("irons_spellbooks:")) spell = legacy;
                }
                id = spell == null ? resource : spell.id();
                if (spell == null) {
                    if (unreadableResources.contains(resource)) continue;
                    throw new IllegalArgumentException("未知或尚不支持的法术：" + id);
                }
                if (ambiguous.contains(id)) throw new IllegalArgumentException("法术资源路径存在歧义：" + id);
                if (seen.putIfAbsent(id, resource) != null) {
                    overrides.remove(spell.className());
                    throw new IllegalArgumentException("同一法术存在多个数据路径：" + seen.get(id) + " 和 " + resource);
                }
                if (unreadableResources.contains(resource)) continue;
                SpellOverride override = parse(spell, entry.getValue());
                if (!override.isEmpty()) overrides.put(spell.className(), override);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                if (id != null && spells.containsKey(id)) overrides.remove(spells.get(id).className());
                diagnostics.add(new Diagnostic(path, "数据包法术配置未应用：" + failure.getMessage(), failure));
            }
        }
        return snapshot(overrides, diagnostics);
    }

    public static Snapshot snapshot(Map<String, SpellOverride> overrides, List<Diagnostic> diagnostics) {
        return new Snapshot(overrides, diagnostics, fingerprint(overrides));
    }

    public static Map<String, JsonElement> resourcesForSnapshot(Snapshot snapshot) {
        Map<String, JsonElement> resources = new LinkedHashMap<>();
        Set<String> ambiguous = ambiguousSpells(resourcePaths(new SpellFormulaCatalog(snapshot.byClass().values().stream()
                .map(SpellOverride::spell).toList())));
        for (var override : snapshot.byClass().values()) {
            if (ambiguous.contains(override.spell().id()))
                throw new IllegalArgumentException("法术资源路径存在歧义：" + override.spell().id());
            JsonObject json = new JsonObject();
            JsonObject advanced = new JsonObject();
            override.advanced().forEach((key, value) -> advanced.add(key, value.deepCopy()));
            json.add("advanced", advanced);
            JsonObject parameters = new JsonObject();
            override.parameters().forEach((key, value) -> parameters.add(key, value.deepCopy()));
            json.add("parameters", parameters);
            JsonObject formulas = new JsonObject();
            for (var method : override.spell().editableFormulas()) {
                String expression = override.formulas().get(method.key());
                if (expression != null && override.spell().attributeComponents().stream().noneMatch(component ->
                        component.binding().equals(method.key()) && override.attributes().containsKey(component.key())))
                    formulas.addProperty(method.alias(), expression);
            }
            json.add("formulas", formulas);
            JsonObject attributes = new JsonObject();
            override.attributes().forEach((name, config) -> attributes.add(name, attributeJson(config)));
            if (attributes.size() != 0) json.add("attributes", attributes);
            String key = resourceKey(override.spell());
            if (resources.putIfAbsent(key, json) != null)
                throw new IllegalArgumentException("多个法术使用相同数据路径：" + key);
        }
        return Map.copyOf(resources);
    }

    private static void writeExportFile(Path root, Path file, JsonElement json, boolean overwrite) throws IOException {
        rejectSymbolicPath(root, file);
        Files.createDirectories(file.getParent());
        try {
            Files.writeString(file, GSON.toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8,
                    overwrite ? StandardOpenOption.CREATE : StandardOpenOption.CREATE_NEW,
                    overwrite ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.WRITE,
                    StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            if (overwrite) throw ignored;
        }
    }

    public static SpellOverride parse(SpellFormulaCatalog.Spell spell, String text) throws IOException {
        return parse(spell, parseJson(text));
    }

    public static JsonElement parseJson(String text) throws IOException {
        JsonElement parsed;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            parsed = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("JSON 结尾含有多余内容");
        }
        return parsed;
    }

    public static SpellOverride parse(SpellFormulaCatalog.Spell spell, JsonElement parsed) {
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("配置根节点必须是对象");
        JsonObject root = parsed.getAsJsonObject();
        checkKeys(root, Set.of("advanced", "parameters", "formulas", "attributes"), "根节点");
        Map<String, JsonElement> advanced = values(group(root, "advanced"), spell.advanced(), true);
        Map<String, JsonElement> parameters = values(group(root, "parameters"), spell.editableParameters(), false);
        FormulaParameterLimits.validate(spell, advanced, parameters);
        JsonObject formulas = group(root, "formulas");
        Map<String, SpellFormulaCatalog.Method> bindings = new LinkedHashMap<>();
        spell.editableFormulas().forEach(method -> bindings.put(method.alias(), method));
        spell.formulas().stream().filter(method -> !method.editable() && !method.readOnlyReason().isEmpty())
                .forEach(method -> bindings.put(method.alias(), method));
        Map<String, List<String>> replacements = new LinkedHashMap<>();
        for (var component : spell.damageComponents()) for (String upstream : component.upstreamAliases()) {
            replacements.computeIfAbsent(upstream, ignored -> new ArrayList<>()).add(component.alias());
            spell.formulas().stream().filter(method -> method.alias().equals(upstream) && !method.editable())
                    .findFirst().ifPresent(method -> bindings.put(upstream, method));
        }
        checkKeys(formulas, bindings.keySet(), "formulas");
        Map<String, String> changedFormulas = new LinkedHashMap<>();
        for (var entry : formulas.entrySet()) {
            String expression = requireString(entry.getValue(), "formulas." + entry.getKey());
            if (expression.length() > 8192) throw new IllegalArgumentException("公式过长：" + entry.getKey());
            SpellFormulaCatalog.Method binding = bindings.get(entry.getKey());
            Map<String, Type> types = new LinkedHashMap<>();
            binding.variables().forEach((name, type) -> types.put(name, Type.getType(type)));
            Type result = Type.getReturnType(binding.descriptor());
            FormulaCompiler.validateExpression(expression, types, result.equals(Type.FLOAT_TYPE) || result.equals(Type.DOUBLE_TYPE));
            String resolved = ReadableFormula.resolve(binding.strictExpression(), expression, types).expression();
            if (!compact(resolved).equals(compact(binding.strictExpression()))) {
                if (!binding.editable())
                    throw new IllegalArgumentException(!binding.readOnlyReason().isEmpty()
                            ? "公式入口 formulas." + entry.getKey() + " 已设为只读：" + binding.readOnlyReason()
                                    + "；旧入口仅接受原始默认公式，请迁移自定义修改"
                            : "伤害公式入口 formulas." + entry.getKey()
                            + " 已合并至最终伤害组件 " + String.join(", ", replacements.get(entry.getKey()))
                            + "；旧入口仅接受原始默认公式，请将修改迁移到最终组件，避免同一路伤害重复计算");
                changedFormulas.put(binding.key(), expression);
            }
        }
        Map<String, FormulaAttributes.Config> changedAttributes = new LinkedHashMap<>();
        JsonObject attributes = group(root, "attributes");
        Map<String, FormulaAttributes.Component> components = new LinkedHashMap<>();
        spell.attributeComponents().forEach(component -> components.put(component.key(), component));
        checkKeys(attributes, components.keySet(), "attributes");
        for (var entry : attributes.entrySet()) {
            var component = components.get(entry.getKey());
            if (formulas.has(component.alias())) throw new IllegalArgumentException("属性组件同时使用新旧入口：" + entry.getKey());
            if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("属性组件必须是对象：" + entry.getKey());
            var provided = entry.getValue().getAsJsonObject();
            checkKeys(provided, Set.of("target_attribute", "operation", "formula"), "attributes." + entry.getKey());
            var binding = spell.formulas().stream().filter(method -> method.key().equals(component.binding())).findFirst().orElseThrow();
            String target = provided.has("target_attribute") ? requireString(provided.get("target_attribute"), "target_attribute") : component.sourceAttribute();
            SpellFormulaCatalog.requireId(target);
            var operation = provided.has("operation") ? FormulaAttributes.Operation.parse(requireString(provided.get("operation"), "operation")) : component.operation();
            String expression = provided.has("formula") ? requireString(provided.get("formula"), "formula") : binding.readableExpression();
            if (expression.length() > 8192) throw new IllegalArgumentException("属性公式过长：" + entry.getKey());
            Map<String, Type> types = new LinkedHashMap<>();
            binding.variables().forEach((name, type) -> types.put(name, Type.getType(type)));
            Type result = Type.getReturnType(binding.descriptor());
            FormulaCompiler.validateExpression(expression, types, result.equals(Type.FLOAT_TYPE) || result.equals(Type.DOUBLE_TYPE));
            String resolved = ReadableFormula.resolve(binding.strictExpression(), expression, types).expression();
            if (!target.equals(component.sourceAttribute()) || operation != component.operation()
                    || !compact(resolved).equals(compact(binding.strictExpression()))) {
                changedAttributes.put(component.key(), new FormulaAttributes.Config(target, operation, expression));
                changedFormulas.put(binding.key(), expression);
            }
        }
        return new SpellOverride(spell, advanced, parameters, changedFormulas, changedAttributes);
    }

    public static JsonObject preview(SpellFormulaCatalog.Spell spell) {
        JsonObject root = new JsonObject();
        root.add("advanced", spell.advanced().deepCopy());
        root.add("parameters", spell.editableParameters());
        JsonObject formulas = new JsonObject();
        for (var method : spell.editableFormulas())
            if (spell.attributeComponents().stream().noneMatch(component -> component.binding().equals(method.key())))
                formulas.addProperty(method.alias(), method.readableExpression());
        root.add("formulas", formulas);
        JsonObject attributes = new JsonObject();
        for (var component : spell.attributeComponents()) {
            var binding = spell.formulas().stream().filter(method -> method.key().equals(component.binding())).findFirst().orElseThrow();
            attributes.add(component.key(), attributeJson(new FormulaAttributes.Config(component.sourceAttribute(), component.operation(), binding.readableExpression())));
        }
        if (attributes.size() != 0) root.add("attributes", attributes);
        return root;
    }

    private static JsonObject attributeJson(FormulaAttributes.Config config) {
        JsonObject result = new JsonObject();
        result.addProperty("target_attribute", config.targetAttribute());
        result.addProperty("operation", config.operation().id());
        result.addProperty("formula", config.formula());
        return result;
    }

    private static Map<String, JsonElement> values(JsonObject provided, JsonObject defaults, boolean advanced) {
        checkKeys(provided, defaults.keySet(), advanced ? "advanced" : "parameters");
        Map<String, JsonElement> changed = new LinkedHashMap<>();
        for (var entry : provided.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            JsonElement baseline = defaults.get(key);
            validate(key, value, baseline, advanced);
            boolean equal = LONG_PARAMETERS.contains(key) ? value.getAsLong() == baseline.getAsLong()
                    : INTEGER_PARAMETERS.contains(key) || key.equals("max_level")
                    ? value.getAsInt() == baseline.getAsInt() : same(value, baseline);
            if (!equal) changed.put(key, value.deepCopy());
        }
        return changed;
    }

    private static void validate(String key, JsonElement value, JsonElement baseline, boolean advanced) {
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException(key + " 必须是基本类型");
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        JsonPrimitive expected = baseline.getAsJsonPrimitive();
        if (expected.isBoolean()) {
            if (!primitive.isBoolean()) throw new IllegalArgumentException(key + " 必须是 true 或 false");
        } else if (expected.isString()) {
            String text = requireString(value, key);
            if (key.equals("school")) SpellFormulaCatalog.requireId(text);
            if (key.equals("min_rarity") && !RARITIES.contains(text)) throw new IllegalArgumentException("未知最低稀有度：" + text);
        } else {
            if (!primitive.isNumber()) throw new IllegalArgumentException(key + " 必须是数值");
            BigDecimal number = primitive.getAsBigDecimal();
            if (LONG_PARAMETERS.contains(key)) {
                try { number.longValueExact(); }
                catch (ArithmeticException failure) { throw new IllegalArgumentException(key + " 必须是 64 位整数", failure); }
            } else if (INTEGER_PARAMETERS.contains(key) || key.equals("max_level")) {
                int integer;
                try { integer = number.intValueExact(); }
                catch (ArithmeticException failure) { throw new IllegalArgumentException(key + " 必须是 32 位整数", failure); }
                if (key.equals("max_level") && integer < 1) throw new IllegalArgumentException("max_level 必须大于 0");
            }
            double decimal = primitive.getAsDouble();
            if (!Double.isFinite(decimal)) throw new IllegalArgumentException(key + " 必须是有限数值");
            if (NONNEGATIVE_PARAMETERS.contains(key) && number.signum() < 0)
                throw new IllegalArgumentException(key + " 不允许负数");
            if (key.equals("power_multiplier") && !Float.isFinite((float) decimal))
                throw new IllegalArgumentException("power_multiplier 超出原生浮点范围");
        }
    }

    private static boolean same(JsonElement first, JsonElement second) {
        if (first.isJsonPrimitive() && first.getAsJsonPrimitive().isNumber()
                && second.isJsonPrimitive() && second.getAsJsonPrimitive().isNumber()) {
            if (first.getAsDouble() == 0 && second.getAsDouble() == 0)
                return Double.doubleToRawLongBits(first.getAsDouble()) == Double.doubleToRawLongBits(second.getAsDouble());
            return first.getAsBigDecimal().compareTo(second.getAsBigDecimal()) == 0;
        }
        return first.equals(second);
    }

    private static JsonObject group(JsonObject root, String key) {
        if (!root.has(key)) return new JsonObject();
        if (!root.get(key).isJsonObject()) throw new IllegalArgumentException(key + " 必须是对象");
        return root.getAsJsonObject(key);
    }

    private static void checkKeys(JsonObject object, Set<String> allowed, String group) {
        for (var entry : object.entrySet()) {
            if (!allowed.contains(entry.getKey())) throw new IllegalArgumentException("未知字段：" + group + '.' + entry.getKey());
        }
    }

    private static String requireString(JsonElement value, String key) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key + " 必须是字符串");
        return value.getAsString();
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new IllegalArgumentException("JSON 嵌套过深");
        return switch (reader.peek()) {
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw new IllegalArgumentException("JSON 重复字段：" + name);
                    object.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new LazilyParsedNumber(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("不支持的 JSON 类型：" + reader.peek());
        };
    }

    private static Path file(Path root, SpellFormulaCatalog.Spell spell) {
        String namespace = spell.id().substring(0, spell.id().indexOf(':'));
        String id = spell.id().substring(spell.id().indexOf(':') + 1);
        String school = spell.school().substring(spell.school().indexOf(':') + 1);
        Path result = root.resolve("data").resolve(namespace).resolve("spells").resolve(school).resolve(id + ".json").normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("配置路径越界：" + spell.id());
        return result;
    }

    static String resourceKey(SpellFormulaCatalog.Spell spell) {
        int separator = spell.id().indexOf(':');
        return spell.id().substring(0, separator + 1) + "spells/"
                + spell.school().substring(spell.school().indexOf(':') + 1) + '/'
                + spell.id().substring(separator + 1);
    }

    private static Map<String, List<SpellFormulaCatalog.Spell>> resourcePaths(SpellFormulaCatalog catalog) {
        Map<String, List<SpellFormulaCatalog.Spell>> paths = new LinkedHashMap<>();
        for (var spell : catalog.spells()) {
            paths.computeIfAbsent(resourceKey(spell), ignored -> new ArrayList<>()).add(spell);
            paths.computeIfAbsent(spell.id(), ignored -> new ArrayList<>()).add(spell);
        }
        return paths;
    }

    private static Set<String> ambiguousSpells(Map<String, List<SpellFormulaCatalog.Spell>> paths) {
        Set<String> ambiguous = new HashSet<>();
        paths.values().stream().filter(entries -> entries.size() > 1)
                .forEach(entries -> entries.forEach(spell -> ambiguous.add(spell.id())));
        return ambiguous;
    }

    static void rejectSymbolicPath(Path root, Path file) throws IOException {
        for (Path current = file; current != null && current.startsWith(root); current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("配置目录中不允许符号链接：" + current);
    }

    static String fingerprint(Map<String, SpellOverride> overrides) {
        TreeMap<String, String> entries = new TreeMap<>();
        overrides.forEach((owner, override) -> {
            override.advanced().forEach((name, value) -> entries.put(owner + "/advanced/" + name, canonical(value)));
            override.parameters().forEach((name, value) -> entries.put(owner + "/parameters/" + name, canonical(value)));
            override.formulas().forEach((name, value) -> entries.put(owner + "/formulas/" + name, compact(value)));
            override.attributes().forEach((name, value) -> entries.put(owner + "/attributes/" + name, canonical(attributeJson(value))));
        });
        try {
            byte[] bytes = GSON.toJson(entries).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static String canonical(JsonElement value) {
        if (value.isJsonObject()) {
            Map<String, JsonElement> sorted = new TreeMap<>();
            value.getAsJsonObject().entrySet().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            return sorted.entrySet().stream().map(entry -> GSON.toJson(entry.getKey()) + ':' + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value.isJsonArray()) {
            List<String> elements = new ArrayList<>();
            value.getAsJsonArray().forEach(element -> elements.add(canonical(element)));
            return '[' + String.join(",", elements) + ']';
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return value.toString();
        if (value.getAsDouble() == 0 && Double.doubleToRawLongBits(value.getAsDouble()) < 0) return "-0.0";
        return value.getAsBigDecimal().stripTrailingZeros().toString();
    }

    private static String compact(String expression) {
        return expression.replaceAll("\\s+", "");
    }
}
