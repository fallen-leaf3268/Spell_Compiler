package com.example.spell_compiler.formula;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

public final class FormulaCatalogGenerator {
    private static final String SPELLS = "io/redspace/ironsspellbooks/spells/";
    private static final String ABSTRACT = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String SPELL_REGISTRY = "io/redspace/ironsspellbooks/api/registry/SpellRegistry";
    private static final String CONFIG = "io/redspace/ironsspellbooks/api/config/DefaultConfig";
    private static final String SCHOOL_REGISTRY = "io/redspace/ironsspellbooks/api/registry/SchoolRegistry";
    private static final String RARITY = "io/redspace/ironsspellbooks/api/spells/SpellRarity";
    private static final String RESOURCE = "net/minecraft/resources/ResourceLocation";
    private static final String MOD = "io/redspace/ironsspellbooks/IronsSpellbooks";
    private static final String CONFIG_PARAMETER = "io/redspace/ironsspellbooks/api/config/SpellConfigParameter";
    private record InheritedMethod(String alias, String name, String descriptor) {}
    private static final List<InheritedMethod> INHERITED_METHODS = List.of(
            new InheritedMethod("spell_power", "getSpellPower", "(ILnet/minecraft/world/entity/Entity;)F"),
            new InheritedMethod("mana_cost", "getManaCost", "(I)I"),
            new InheritedMethod("cooldown_ticks", "getSpellCooldown", "()I"),
            new InheritedMethod("base_cast_time", "getCastTime", "(I)I"),
            new InheritedMethod("cast_time", "getEffectiveCastTime", "(ILnet/minecraft/world/entity/LivingEntity;)I"));
    private static final Object UNKNOWN = new Object();
    private static final Object THIS = new Object();
    private static final List<String> PARAMETERS = List.of("baseManaCost", "manaCostPerLevel",
            "baseSpellPower", "spellPowerPerLevel", "castTime");

    private FormulaCatalogGenerator() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected source JAR, catalog JSON and targets JSON");
        JsonObject catalog = generate(Path.of(args[0]));
        JsonArray targets = new JsonArray();
        catalog.getAsJsonArray("spells").forEach(spell -> targets.add(spell.getAsJsonObject().get("className").getAsString()));
        write(Path.of(args[1]), catalog);
        write(Path.of(args[2]), targets);
        System.out.println("Formula catalog spells=" + targets.size() + ", excluded classes="
                + catalog.getAsJsonArray("excluded").size() + ", rejected methods=" + catalog.getAsJsonArray("rejected").size());
    }

    public static JsonObject generate(Path source) throws Exception {
        FormulaClassRepository repository = FormulaClassRepository.fromJar(source);
        FormulaSites.validate(repository);
        FormulaConsumerSites.validate(repository);
        String namespace = namespace(repository);
        Map<String, Object> schoolResources = resourceFields(repository.classNode(SCHOOL_REGISTRY), namespace);
        Map<String, Object> configDefaults = defaultConfig(repository.classNode(CONFIG));
        Map<String, Double> multipliers = multiplierDefaults(repository.classNode(CONFIG_PARAMETER));
        Set<String> registeredClasses = registeredSpellClasses(repository);
        JsonObject catalog = new JsonObject();
        catalog.addProperty("schemaVersion", 4);
        catalog.addProperty("sourceSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(source))));
        JsonArray spells = new JsonArray();
        JsonArray rejected = new JsonArray();
        JsonArray excluded = new JsonArray();
        JsonArray classErrors = new JsonArray();
        int scannedClasses = 0;
        int supportedMethods = 0;
        try (JarFile jar = new JarFile(source.toFile())) {
            List<ClassNode> sourceClasses = jar.stream().filter(entry -> entry.getName().endsWith(".class"))
                    .map(entry -> repository.classNode(entry.getName().substring(0, entry.getName().length() - 6)))
                    .toList();
            FormulaUsage usage = new FormulaUsage(repository, sourceClasses);
            for (var entry : jar.stream().filter(value -> value.getName().startsWith(SPELLS)
                    && value.getName().substring(SPELLS.length()).contains("/")
                    && value.getName().endsWith(".class") && !value.getName().contains("$"))
                    .sorted(Comparator.comparing(java.util.jar.JarEntry::getName)).toList()) {
                ClassNode owner = repository.classNode(entry.getName().substring(0, entry.getName().length() - 6));
                scannedClasses++;
                if ((owner.access & Opcodes.ACC_ABSTRACT) != 0) {
                    exclude(excluded, owner, "abstract_class", "Abstract spell class has no registered instance");
                    continue;
                }
                if (!repository.isSubtype(owner.name, ABSTRACT)) {
                    exclude(excluded, owner, "not_spell_subclass", "Class does not extend AbstractSpell");
                    continue;
                }
                if (!registeredClasses.contains(owner.name)) {
                    exclude(excluded, owner, "not_registered", "Native SpellRegistry does not register this concrete spell class");
                    continue;
                }
                try {
                    String castType = AutomaticFormulaExtractor.nativeCastType(repository, owner.name);
                    Map<String, Object> values = metadata(owner, schoolResources, configDefaults, namespace);
                    JsonObject spell = metadataJson(owner, values, multipliers);
                    extractSpell(repository, owner, spell, castType, usage, false, rejected);
                    supportedMethods += spell.getAsJsonArray("formulas").size();
                    spells.add(spell);
                } catch (IllegalArgumentException failure) {
                    reject(classErrors, owner, null, failure.getMessage());
                }
            }
        }
        catalog.add("spells", spells);
        catalog.add("rejected", rejected);
        catalog.add("excluded", excluded);
        catalog.add("classErrors", classErrors);
        catalog.addProperty("scannedSpellClasses", scannedClasses);
        catalog.addProperty("registeredSpells", registeredClasses.size());
        catalog.addProperty("supportedMethods", supportedMethods);
        catalog.addProperty("unsupportedMethods", rejected.size());
        return catalog;
    }

    static void extractSpell(FormulaClassRepository repository, ClassNode owner, JsonObject spell,
                             String castType, FormulaUsage usage, boolean inheritedGetters, JsonArray rejected) {
        JsonArray formulas = new JsonArray();
        Map<String, Integer> aliases = new HashMap<>();
        Set<String> selectedKeys = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        ClassNode declaring = owner;
        while (declaring != null && !declaring.name.equals(ABSTRACT) && visited.add(declaring.name)) {
            for (MethodNode method : declaring.methods) {
                int sort = Type.getReturnType(method.desc).getSort();
                if (sort != Type.INT && sort != Type.LONG && sort != Type.FLOAT && sort != Type.DOUBLE) continue;
                if (!declaring.name.equals(owner.name) && (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0)
                    continue;
                String key = method.name + method.desc;
                if (!selectedKeys.add(key)) continue;
                if (FormulaConsumerSites.excludedGetter(declaring.name, key)) {
                    reject(rejected, owner, method, "Native numeric getter has no native effect consumer");
                    continue;
                }
                var analysis = AutomaticFormulaExtractor.analyze(declaring, method, repository, owner.name);
                if (!analysis.supported()) {
                    reject(rejected, owner, method, analysis.rejection());
                    continue;
                }
                String baseAlias = alias(method.name, !inheritedGetters);
                int count = aliases.merge(baseAlias, 1, Integer::sum);
                formulas.add(formulaJson(count == 1 ? baseAlias : baseAlias + '_' + count,
                        declaring, method, analysis.formula(), castType));
            }
            if (!inheritedGetters) break;
            declaring = declaring.superName == null ? null : repository.classNode(declaring.superName);
        }
        for (var selected : INHERITED_METHODS) {
            var inherited = repository.resolveMethod(owner.name, selected.name(), selected.descriptor());
            if (inherited != null && inherited.owner().name.equals(ABSTRACT)
                    && !selectedKeys.contains(selected.name() + selected.descriptor())) {
                var analysis = AutomaticFormulaExtractor.analyze(inherited.owner(), inherited.method(), repository, owner.name);
                if (analysis.supported()) {
                    if (aliases.containsKey(selected.alias()))
                        throw new IllegalArgumentException("Inherited formula alias conflicts with a declared formula: " + selected.alias());
                    formulas.add(formulaJson(selected.alias(), inherited.owner(), inherited.method(), analysis.formula(), castType));
                } else reject(rejected, owner, inherited.method(), analysis.rejection());
            }
        }
        for (var site : FormulaSites.definitions(owner.name.replace('/', '.'))) {
            ClassNode valuesOwner = repository.classNode(site.declaringClass().replace('.', '/'));
            MethodNode valuesMethod = valuesOwner.methods.stream()
                    .filter(method -> method.name.equals(site.helperMethod()) && method.desc.equals(site.descriptor()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("找不到内联数值公式：" + site.alias()));
            var analysis = AutomaticFormulaExtractor.analyze(valuesOwner, valuesMethod, repository);
            if (!analysis.supported())
                throw new IllegalArgumentException("内联数值公式无法提取：" + site.alias() + ": " + analysis.rejection());
            if (aliases.putIfAbsent(site.alias(), 1) != null)
                throw new IllegalArgumentException("内联数值公式别名冲突：" + site.alias());
            formulas.add(formulaJson(site.alias(), valuesOwner, valuesMethod, analysis.formula(), castType));
        }
        var consumers = usage.forSpell(owner.name);
        for (var value : formulas) {
            JsonObject formula = value.getAsJsonObject();
            String key = formula.get("name").getAsString() + formula.get("descriptor").getAsString();
            if (key.equals("getSpellPower(ILnet/minecraft/world/entity/Entity;)F") && !consumers.usesMethod(key))
                formula.addProperty("editable", false);
        }
        JsonArray editableParameters = new JsonArray();
        for (String parameter : spell.getAsJsonObject("parameters").keySet()) {
            if (inheritedGetters && PARAMETERS.contains(parameter) && !consumers.usesField(parameter)) continue;
            if ((parameter.equals("baseSpellPower") || parameter.equals("spellPowerPerLevel"))
                    && !consumers.usesField(parameter)) continue;
            editableParameters.add(parameter);
        }
        spell.add("editableParameters", editableParameters);
        spell.add("formulas", formulas);
    }

    private static JsonObject formulaJson(String alias, ClassNode declaringOwner, MethodNode method,
                                          AutomaticFormulaExtractor.Formula extracted, String castType) {
        JsonObject formula = new JsonObject();
        formula.addProperty("alias", alias);
        formula.addProperty("name", method.name);
        formula.addProperty("descriptor", method.desc);
        String key = method.name + method.desc;
        boolean editable = !key.equals("getCastTime(I)I") && !key.equals("getSpellCooldown()I");
        if (key.equals("getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I"))
            editable = castType.equals("LONG") || castType.equals("CONTINUOUS");
        formula.addProperty("editable", editable);
        formula.addProperty("strictExpression", extracted.expression());
        formula.addProperty("readableExpression", extracted.readableExpression());
        JsonObject variables = new JsonObject();
        extracted.variables().forEach((name, variable) -> variables.addProperty(name, variable.type().getDescriptor()));
        formula.add("variables", variables);
        formula.addProperty("declaringClass", declaringOwner.name.replace('/', '.'));
        return formula;
    }

    public static Set<String> registeredSpellClasses(FormulaClassRepository repository) {
        ClassNode registry = repository.classNode(SPELL_REGISTRY);
        MethodNode initializer = registry.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElseThrow();
        List<AbstractInsnNode> instructions = executable(initializer);
        Set<String> classes = new LinkedHashSet<>();
        for (int index = 0; index < instructions.size(); index++) {
            if (!(instructions.get(index) instanceof MethodInsnNode call) || !call.owner.equals(SPELL_REGISTRY)
                    || !call.name.equals("registerSpell")) continue;
            if (index < 3 || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.desc.equals("(L" + ABSTRACT + ";)Lnet/minecraftforge/registries/RegistryObject;")
                    || !(instructions.get(index - 1) instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL || !constructor.name.equals("<init>")
                    || !constructor.desc.equals("()V") || instructions.get(index - 2).getOpcode() != Opcodes.DUP
                    || !(instructions.get(index - 3) instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW || !allocation.desc.equals(constructor.owner)
                    || !repository.isSubtype(constructor.owner, ABSTRACT))
                throw new IllegalArgumentException("Unrecognized native spell registration at instruction " + index);
            if (constructor.owner.startsWith(SPELLS) && constructor.owner.substring(SPELLS.length()).contains("/"))
                classes.add(constructor.owner);
        }
        if (classes.isEmpty()) throw new IllegalArgumentException("No native registered spell classes found");
        return Set.copyOf(classes);
    }

    private static JsonObject metadataJson(ClassNode owner, Map<String, Object> values, Map<String, Double> multipliers) {
        String id = required(values, "spellId", String.class);
        ConfigValue config = required(values, "defaultConfig", ConfigValue.class);
        Map<String, Object> defaults = config.values;
        String school = required(defaults, "schoolResource", String.class);
        JsonObject spell = new JsonObject();
        spell.addProperty("id", id);
        spell.addProperty("className", owner.name.replace('/', '.'));
        spell.addProperty("school", school);
        JsonObject advanced = new JsonObject();
        advanced.addProperty("enabled", required(defaults, "enabled", Number.class).intValue() != 0);
        advanced.addProperty("max_level", required(defaults, "maxLevel", Number.class).intValue());
        advanced.addProperty("min_rarity", required(defaults, "minRarity", String.class).toLowerCase(Locale.ROOT));
        advanced.addProperty("allow_crafting", required(defaults, "allowCrafting", Number.class).intValue() != 0);
        advanced.addProperty("school", school);
        spell.add("advanced", advanced);
        JsonObject parameters = new JsonObject();
        for (String field : PARAMETERS) parameters.addProperty(field, required(values, field, Number.class).intValue());
        parameters.addProperty("cooldown_in_seconds", required(defaults, "cooldownInSeconds", Number.class).doubleValue());
        parameters.addProperty("power_multiplier", required(multipliers, "POWER_MULTIPLIER", Double.class));
        parameters.addProperty("mana_cost_multiplier", required(multipliers, "MANA_MULTIPLIER", Double.class));
        spell.add("parameters", parameters);
        return spell;
    }

    private static Map<String, Object> metadata(ClassNode owner, Map<String, Object> schools,
                                                Map<String, Object> configDefaults, String namespace) {
        MethodNode constructor = owner.methods.stream().filter(method -> method.name.equals("<init>")
                && method.desc.equals("()V")).findFirst().orElseThrow(() -> new IllegalArgumentException("No no-argument constructor"));
        ArrayDeque<Object> stack = new ArrayDeque<>();
        Map<Integer, Object> locals = new HashMap<>();
        locals.put(0, THIS);
        Map<String, Object> fields = new LinkedHashMap<>();
        for (AbstractInsnNode instruction : constructor.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            Object constant = constant(instruction);
            if (constant != UNKNOWN) {
                stack.push(constant);
                continue;
            }
            switch (opcode) {
                case Opcodes.NOP, Opcodes.RETURN -> {}
                case Opcodes.ACONST_NULL -> stack.push(UNKNOWN);
                case Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.LLOAD ->
                        stack.push(locals.getOrDefault(((VarInsnNode) instruction).var, UNKNOWN));
                case Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.LSTORE ->
                        locals.put(((VarInsnNode) instruction).var, stack.pop());
                case Opcodes.NEW -> stack.push(((TypeInsnNode) instruction).desc.equals(CONFIG)
                        ? new ConfigValue(new LinkedHashMap<>(configDefaults)) : UNKNOWN);
                case Opcodes.DUP -> stack.push(stack.peek());
                case Opcodes.POP -> stack.pop();
                case Opcodes.GETSTATIC -> {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    Object value = field.owner.equals(SCHOOL_REGISTRY) ? schools.getOrDefault(field.name, UNKNOWN)
                            : field.owner.equals(RARITY) ? field.name : UNKNOWN;
                    stack.push(value);
                }
                case Opcodes.PUTFIELD -> {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    Object value = stack.pop();
                    Object receiver = stack.pop();
                    if (receiver == THIS) fields.put(field.name, value);
                }
                case Opcodes.GETFIELD -> {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    stack.push(stack.pop() == THIS ? fields.getOrDefault(field.name, UNKNOWN) : UNKNOWN);
                }
                case Opcodes.CHECKCAST -> {}
                case Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> {
                    MethodInsnNode method = (MethodInsnNode) instruction;
                    Object[] args = new Object[Type.getArgumentTypes(method.desc).length];
                    for (int index = args.length - 1; index >= 0; index--) args[index] = stack.pop();
                    Object receiver = opcode == Opcodes.INVOKESTATIC ? UNKNOWN : stack.pop();
                    Object result = invocation(method, receiver, args, namespace);
                    if (Type.getReturnType(method.desc).getSort() != Type.VOID) stack.push(result);
                }
                default -> throw new IllegalArgumentException("Unsupported constructor opcode " + opcode);
            }
        }
        return fields;
    }

    private static Object invocation(MethodInsnNode method, Object receiver, Object[] args, String namespace) {
        if (method.owner.equals(RESOURCE) && method.name.equals("fromNamespaceAndPath")
                && args.length == 2 && args[0] instanceof String first && args[1] instanceof String second)
            return first + ':' + second;
        if (method.owner.equals(MOD) && method.name.equals("id") && args.length == 1 && args[0] instanceof String path)
            return namespace + ':' + path;
        if (method.owner.equals(CONFIG) && receiver instanceof ConfigValue config) {
            String field = switch (method.name) {
                case "setMinRarity" -> "minRarity";
                case "setSchoolResource" -> "schoolResource";
                case "setMaxLevel" -> "maxLevel";
                case "setCooldownSeconds" -> "cooldownInSeconds";
                case "setAllowCrafting" -> "allowCrafting";
                case "setDeprecated" -> "enabled";
                default -> null;
            };
            if (field != null && args.length == 1) {
                Object value = args[0];
                if (method.name.equals("setDeprecated"))
                    value = value instanceof Number number ? (number.intValue() == 0 ? 1 : 0) : UNKNOWN;
                config.values.put(field, value);
                return config;
            }
            if (method.name.equals("build") && args.length == 0) return config;
            if (method.name.equals("<init>") && args.length == 0) return UNKNOWN;
            throw new IllegalArgumentException("Unsupported DefaultConfig invocation " + method.name + method.desc);
        }
        return UNKNOWN;
    }

    private static String namespace(FormulaClassRepository repository) {
        ClassNode mod = repository.classNode(MOD);
        MethodNode helper = mod.methods.stream().filter(method -> method.name.equals("id")
                && method.desc.equals("(Ljava/lang/String;)L" + RESOURCE + ';')).findFirst().orElseThrow();
        List<AbstractInsnNode> instructions = executable(helper);
        if (instructions.size() != 4 || !(instructions.get(0) instanceof LdcInsnNode literal)
                || !(literal.cst instanceof String result) || !(instructions.get(1) instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD || load.var != 0
                || !(instructions.get(2) instanceof MethodInsnNode call) || !call.owner.equals(RESOURCE)
                || !call.name.equals("fromNamespaceAndPath") || instructions.get(3).getOpcode() != Opcodes.ARETURN)
            throw new IllegalArgumentException("Unrecognized native namespace helper");
        return result;
    }

    private static Map<String, Object> resourceFields(ClassNode owner, String namespace) {
        Map<String, Object> result = new LinkedHashMap<>();
        MethodNode initializer = owner.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElseThrow();
        List<AbstractInsnNode> instructions = executable(initializer);
        for (int index = 2; index < instructions.size(); index++) {
            if (!(instructions.get(index) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !field.desc.equals('L' + RESOURCE + ';')) continue;
            if (!(instructions.get(index - 1) instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(MOD) && call.name.equals("id") && constant(instructions.get(index - 2)) instanceof String path)
                result.put(field.name, namespace + ':' + path);
            else if (index >= 3 && call.owner.equals(RESOURCE) && call.name.equals("fromNamespaceAndPath")
                    && constant(instructions.get(index - 3)) instanceof String first
                    && constant(instructions.get(index - 2)) instanceof String second)
                result.put(field.name, first + ':' + second);
        }
        return result;
    }

    private static Map<String, Object> defaultConfig(ClassNode owner) {
        MethodNode constructor = owner.methods.stream().filter(method -> method.name.equals("<init>")
                && method.desc.equals("()V")).findFirst().orElseThrow();
        List<AbstractInsnNode> instructions = executable(constructor);
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 2; index < instructions.size(); index++) {
            if (instructions.get(index) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(CONFIG) && instructions.get(index - 2) instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ALOAD && load.var == 0) {
                Object value = constant(instructions.get(index - 1));
                if (value != UNKNOWN) values.put(field.name, value);
            }
        }
        return values;
    }

    private static Map<String, Double> multiplierDefaults(ClassNode owner) {
        MethodNode initializer = owner.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElseThrow();
        List<AbstractInsnNode> instructions = executable(initializer);
        Map<String, Double> values = new LinkedHashMap<>();
        for (int index = 3; index < instructions.size(); index++) {
            if (!(instructions.get(index) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !Set.of("POWER_MULTIPLIER", "MANA_MULTIPLIER").contains(field.name)) continue;
            if (instructions.get(index - 1) instanceof MethodInsnNode constructor && constructor.owner.equals(CONFIG_PARAMETER)
                    && constructor.name.equals("<init>") && constructor.desc.endsWith("Ljava/lang/Object;)V")
                    && instructions.get(index - 2) instanceof MethodInsnNode boxing && boxing.owner.equals("java/lang/Double")
                    && boxing.name.equals("valueOf") && constant(instructions.get(index - 3)) instanceof Double value)
                values.put(field.name, value);
        }
        return values;
    }

    private static Object constant(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (opcode >= Opcodes.LCONST_0 && opcode <= Opcodes.LCONST_1) return (long) (opcode - Opcodes.LCONST_0);
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return (float) (opcode - Opcodes.FCONST_0);
        if (opcode >= Opcodes.DCONST_0 && opcode <= Opcodes.DCONST_1) return (double) (opcode - Opcodes.DCONST_0);
        if (instruction instanceof LdcInsnNode literal && (literal.cst instanceof String || literal.cst instanceof Number))
            return literal.cst;
        if (instruction instanceof IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return push.operand;
        return UNKNOWN;
    }

    private static List<AbstractInsnNode> executable(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    private static String alias(String method, boolean nativeAliases) {
        if (method.equals("getCastTime")) return "base_cast_time";
        if (method.equals("getEffectiveCastTime")) return "cast_time";
        String name = method.startsWith("get") && method.length() > 3 ? method.substring(3) : method;
        name = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        if (nativeAliases || name.equals("amplifier") || name.equals("amplifier_for_level") || name.endsWith("_amplifier"))
            return FormulaNames.alias(name);
        return name;
    }

    private static <T> T required(Map<String, ?> source, String name, Class<T> type) {
        Object value = source.get(name);
        if (!type.isInstance(value)) throw new IllegalArgumentException("Unresolved native default " + name);
        return type.cast(value);
    }

    private static void reject(JsonArray rejected, ClassNode owner, MethodNode method, String reason) {
        JsonObject entry = new JsonObject();
        entry.addProperty("className", owner.name.replace('/', '.'));
        if (method != null) {
            entry.addProperty("name", method.name);
            entry.addProperty("descriptor", method.desc);
        }
        entry.addProperty("reason", reason);
        rejected.add(entry);
    }

    private static void exclude(JsonArray excluded, ClassNode owner, String kind, String reason) {
        JsonObject entry = new JsonObject();
        entry.addProperty("className", owner.name.replace('/', '.'));
        entry.addProperty("kind", kind);
        entry.addProperty("reason", reason);
        excluded.add(entry);
    }

    private static void write(Path path, com.google.gson.JsonElement value) throws IOException {
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Files.writeString(target, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value));
    }

    private record ConfigValue(Map<String, Object> values) {}
}
