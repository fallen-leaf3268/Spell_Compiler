package com.example.spell_compiler.formula;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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
                                Map<String, JsonElement> parameters, Map<String, String> formulas) {
        public SpellOverride {
            advanced = Map.copyOf(advanced);
            parameters = Map.copyOf(parameters);
            formulas = Map.copyOf(formulas);
        }

        public boolean isEmpty() {
            return advanced.isEmpty() && parameters.isEmpty() && formulas.isEmpty();
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
        Path root = packDirectory.toAbsolutePath().normalize();
        List<Diagnostic> diagnostics = new ArrayList<>();
        JsonObject metadata = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 15);
        JsonObject description = new JsonObject();
        description.addProperty("translate", "pack.spell_compiler.spells");
        pack.add("description", description);
        metadata.add("pack", pack);
        exportFile(root, root.resolve("pack.mcmeta"), metadata, diagnostics);
        Map<String, List<SpellFormulaCatalog.Spell>> paths = resourcePaths(catalog);
        Set<String> ambiguous = ambiguousSpells(paths);
        for (var spell : catalog.spells()) {
            if (ambiguous.contains(spell.id()))
                diagnostics.add(new Diagnostic(file(root, spell), "多个法术使用相同数据路径，未导出：" + resourceKey(spell)));
            else exportFile(root, file(root, spell), preview(spell), diagnostics);
        }
        return List.copyOf(diagnostics);
    }

    private static void exportFile(Path root, Path file, JsonElement json, List<Diagnostic> diagnostics) {
        try { writeMissing(root, file, json); }
        catch (IOException failure) { diagnostics.add(new Diagnostic(file, "无法导出法术数据包：" + failure.getMessage(), failure)); }
    }

    public static Snapshot exportAndLoad(Path packDirectory, SpellFormulaCatalog catalog) throws IOException {
        List<Diagnostic> diagnostics = new ArrayList<>(exportPack(packDirectory, catalog));
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
                if (expression != null) formulas.addProperty(method.alias(), expression);
            }
            json.add("formulas", formulas);
            String key = resourceKey(override.spell());
            if (resources.putIfAbsent(key, json) != null)
                throw new IllegalArgumentException("多个法术使用相同数据路径：" + key);
        }
        return Map.copyOf(resources);
    }

    private static void writeMissing(Path root, Path file, JsonElement json) throws IOException {
        rejectSymbolicPath(root, file);
        Files.createDirectories(file.getParent());
        try {
            Files.writeString(file, GSON.toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
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
        checkKeys(root, Set.of("advanced", "parameters", "formulas"), "根节点");
        Map<String, JsonElement> advanced = values(group(root, "advanced"), spell.advanced(), true);
        Map<String, JsonElement> parameters = values(group(root, "parameters"), spell.editableParameters(), false);
        FormulaParameterLimits.validate(spell, advanced, parameters);
        JsonObject formulas = group(root, "formulas");
        Map<String, SpellFormulaCatalog.Method> bindings = new LinkedHashMap<>();
        spell.editableFormulas().forEach(method -> bindings.put(method.alias(), method));
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
            if (!compact(resolved).equals(compact(binding.strictExpression())))
                changedFormulas.put(binding.key(), expression);
        }
        return new SpellOverride(spell, advanced, parameters, changedFormulas);
    }

    public static JsonObject preview(SpellFormulaCatalog.Spell spell) {
        JsonObject root = new JsonObject();
        root.add("advanced", spell.advanced().deepCopy());
        root.add("parameters", spell.editableParameters());
        JsonObject formulas = new JsonObject();
        for (var method : spell.editableFormulas()) formulas.addProperty(method.alias(), method.readableExpression());
        root.add("formulas", formulas);
        return root;
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

    private static void rejectSymbolicPath(Path root, Path file) throws IOException {
        for (Path current = file; current != null && current.startsWith(root); current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("配置目录中不允许符号链接：" + current);
    }

    static String fingerprint(Map<String, SpellOverride> overrides) {
        TreeMap<String, String> entries = new TreeMap<>();
        overrides.forEach((owner, override) -> {
            override.advanced().forEach((name, value) -> entries.put(owner + "/advanced/" + name, canonical(value)));
            override.parameters().forEach((name, value) -> entries.put(owner + "/parameters/" + name, canonical(value)));
            override.formulas().forEach((name, value) -> entries.put(owner + "/formulas/" + name, compact(value)));
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
