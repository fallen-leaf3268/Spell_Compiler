package com.example.spell_compiler.formula;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FormulaCatalogGenerator {
    private static final String SPELLS = "io/redspace/ironsspellbooks/spells/";
    private static final String ABSTRACT = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String SPELL_REGISTRY = "io/redspace/ironsspellbooks/api/registry/SpellRegistry";
    private record InheritedMethod(String alias, String name, String descriptor) {}
    private static final List<InheritedMethod> INHERITED_METHODS = List.of(
            new InheritedMethod("spell_power", "getSpellPower", "(ILnet/minecraft/world/entity/Entity;)F"),
            new InheritedMethod("mana_cost", "getManaCost", "(I)I"),
            new InheritedMethod("cooldown_ticks", "getSpellCooldown", "()I"),
            new InheritedMethod("base_cast_time", "getCastTime", "(I)I"),
            new InheritedMethod("cast_time", "getEffectiveCastTime", "(ILnet/minecraft/world/entity/LivingEntity;)I"));
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
        com.example.spell_compiler.formula.rules.FormulaNativeRules.validateBaseline(repository);
        return generate(repository, repository.sourceClasses(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(source))));
    }

    static JsonObject generate(FormulaClassRepository repository, List<ClassNode> nativeSources, String sourceFingerprint) {
        var metadata = new FormulaSpellMetadata(repository);
        Set<String> registeredClasses = registeredSpellClasses(repository);
        FormulaEffectAttributeControls.beginCatalog(registeredClasses);
        FormulaSourceControls.beginCatalog(registeredClasses);
        JsonObject catalog = new JsonObject();
        catalog.addProperty("schemaVersion", 5);
        catalog.addProperty("sourceSha256", sourceFingerprint);
        JsonArray spells = new JsonArray();
        JsonArray rejected = new JsonArray();
        JsonArray excluded = new JsonArray();
        JsonArray classErrors = new JsonArray();
        int scannedClasses = 0;
        int supportedMethods = 0;
        {
            FormulaUsage usage = new FormulaUsage(repository, repository.sourceClasses());
            for (ClassNode owner : nativeSources.stream().filter(value -> value.name.startsWith(SPELLS)
                    && value.name.substring(SPELLS.length()).contains("/") && !value.name.contains("$"))
                    .sorted(Comparator.comparing(value -> value.name)).toList()) {
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
                    JsonObject spell = metadata.extract(owner);
                    extractSpell(repository, owner, spell, castType, usage, !repository.baselineCoverage(), rejected);
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
        var ruleResults = new JsonArray();
        for (var decision : com.example.spell_compiler.formula.rules.FormulaNativeRules.prepare(repository).decisions()) {
            var result = new JsonObject();
            result.addProperty("rule", decision.rule());
            result.addProperty("available", decision.available());
            if (!decision.reason().isEmpty()) result.addProperty("reason", decision.reason());
            ruleResults.add(result);
        }
        catalog.add("bindingRules", ruleResults);
        return catalog;
    }

    static void extractSpell(FormulaClassRepository repository, ClassNode owner, JsonObject spell,
                             String castType, FormulaUsage usage, boolean inheritedGetters, JsonArray rejected) {
        JsonArray formulas = discoverFormulas(repository, owner, castType, inheritedGetters, rejected);
        Set<String> reserved = new LinkedHashSet<>();
        formulas.forEach(value -> reserved.add(value.getAsJsonObject().get("alias").getAsString()));
        Set<FormulaDataFlow.MethodId> exposedMethods = new LinkedHashSet<>();
        formulas.forEach(value -> {
            var formula = value.getAsJsonObject();
            exposedMethods.add(new FormulaDataFlow.MethodId(formula.get("declaringClass").getAsString(),
                    formula.get("name").getAsString(), formula.get("descriptor").getAsString()));
        });
        var automatic = FormulaAutomaticSites.scan(repository, owner.name, reserved, exposedMethods);
        FormulaAutomaticSites.register(automatic);
        var graph = FormulaExecutionGraph.index(repository).analyze(owner.name);
        JsonObject execution = FormulaCatalogEntries.execution(repository, owner.name, graph, automatic, exposedMethods);
        spell.add("execution", execution);
        automatic.sites().forEach(site -> formulas.add(FormulaCatalogEntries.automatic(site)));
        Set<String> publicAliases = new LinkedHashSet<>();
        formulas.forEach(value -> publicAliases.add(value.getAsJsonObject().get("alias").getAsString()));
        for (var control : FormulaSourceControls.register(repository, owner.name, automatic.sites())) {
            var site = control.numeric();
            if (!publicAliases.add(site.alias()))
                throw new IllegalArgumentException("来源消费公式别名冲突：" + site.alias());
            var formula = FormulaCatalogEntries.numeric(site);
            formula.addProperty("strictExpression", "native_value");
            formula.addProperty("readableExpression", "native_value");
            formula.addProperty("consumptionFingerprint", control.fingerprint());
            formulas.add(formula);
        }
        publishFormulas(repository, owner, spell, formulas, graph, automatic, usage, inheritedGetters,
                execution.getAsJsonArray("diagnostics"));
    }

    private static JsonArray discoverFormulas(FormulaClassRepository repository, ClassNode owner,
                                             String castType, boolean inheritedGetters, JsonArray rejected) {
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
                if (repository.baselineCoverage() && FormulaConsumerSites.excludedGetter(declaring.name, key)) {
                    reject(rejected, owner, method, "Native numeric getter has no native effect consumer");
                    continue;
                }
                var analysis = AutomaticFormulaExtractor.analyze(declaring, method, repository, owner.name);
                if (!analysis.supported()) {
                    reject(rejected, owner, method, analysis.rejection());
                    continue;
                }
                String baseAlias = alias(method.name, !inheritedGetters || owner.name.startsWith(SPELLS));
                int count = aliases.merge(baseAlias, 1, Integer::sum);
                formulas.add(FormulaCatalogEntries.method(count == 1 ? baseAlias : baseAlias + '_' + count,
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
                    formulas.add(FormulaCatalogEntries.method(selected.alias(), inherited.owner(), inherited.method(), analysis.formula(), castType));
                } else reject(rejected, owner, inherited.method(), analysis.rejection());
            }
        }
        for (var site : repository.baselineCoverage() ? FormulaSites.definitions(owner.name.replace('/', '.'))
                : List.<FormulaSites.Definition>of()) {
            ClassNode valuesOwner = repository.classNode(site.declaringClass().replace('.', '/'));
            MethodNode valuesMethod = valuesOwner.methods.stream()
                    .filter(method -> method.name.equals(site.helperMethod()) && method.desc.equals(site.descriptor()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("找不到内联数值公式：" + site.alias()));
            var analysis = AutomaticFormulaExtractor.analyze(valuesOwner, valuesMethod, repository);
            if (!analysis.supported())
                throw new IllegalArgumentException("内联数值公式无法提取：" + site.alias() + ": " + analysis.rejection());
            if (aliases.putIfAbsent(site.alias(), 1) != null)
                throw new IllegalArgumentException("内联数值公式别名冲突：" + site.alias());
            formulas.add(FormulaCatalogEntries.method(site.alias(), valuesOwner, valuesMethod, analysis.formula(), castType));
        }
        var effectAttributes = FormulaEffectAttributeControls.register(repository, owner.name);
        Set<String> attributeKeys = effectAttributes.stream().map(attribute -> attribute.numeric().key())
                .collect(java.util.stream.Collectors.toSet());
        var numeric = new ArrayList<FormulaNumericSites.Definition>();
        if (repository.baselineCoverage()) {
            numeric.addAll(FormulaNumericSites.definitions(owner.name.replace('/', '.')));
            numeric.addAll(FormulaEntitySites.definitions(owner.name.replace('/', '.')));
        }
        FormulaAddonNumericSites.definitions(owner.name.replace('/', '.')).stream()
                .filter(site -> !attributeKeys.contains(site.key())).forEach(numeric::add);
        for (var site : numeric) {
            if (aliases.putIfAbsent(site.alias(), 1) != null)
                throw new IllegalArgumentException("消费数值公式别名冲突：" + site.alias());
            formulas.add(FormulaCatalogEntries.numeric(site));
        }
        for (var attribute : effectAttributes) {
            var site = attribute.numeric();
            if (aliases.putIfAbsent(site.alias(), 1) != null)
                throw new IllegalArgumentException("效果属性公式别名冲突：" + site.alias());
            JsonObject formula = FormulaCatalogEntries.numeric(site);
            formula.addProperty("strictExpression", "native_value");
            formula.addProperty("readableExpression", "native_value");
            formula.addProperty("unit", attribute.operation().equals("ADD_VALUE") ? "attribute_value" : "percent");
            formula.addProperty("consumptionFingerprint", "EFFECT_ATTRIBUTE:" + attribute.fingerprint());
            formulas.add(formula);
        }
        return formulas;
    }

    private static void publishFormulas(FormulaClassRepository repository, ClassNode owner, JsonObject spell,
                                        JsonArray formulas, FormulaExecutionGraph.Analysis graph,
                                        FormulaAutomaticSites.Scan automatic, FormulaUsage usage,
                                        boolean inheritedGetters, JsonArray diagnostics) {
        var consumers = usage.forSpell(owner.name);
        for (var value : formulas) {
            JsonObject formula = value.getAsJsonObject();
            String key = formula.get("name").getAsString() + formula.get("descriptor").getAsString();
            if (key.equals("getSpellPower(ILnet/minecraft/world/entity/Entity;)F") && !consumers.usesMethod(key))
                formula.addProperty("editable", false);
            if (owner.name.equals("io/redspace/ironsspellbooks/spells/evocation/ArrowVolleySpell")
                    && key.equals("getCount(ILnet/minecraft/world/entity/LivingEntity;)I"))
                formula.addProperty("editable", false);
            if (owner.name.equals("org/xszb/interlace_spellweaves/spell/weaponSkill/GreatSwordStrike")
                    && key.equals("getDamage(ILnet/minecraft/world/entity/LivingEntity;)I"))
                formula.addProperty("editable", false);
        }
        JsonArray editableParameters = new JsonArray();
        for (String parameter : spell.getAsJsonObject("parameters").keySet()) {
            if (inheritedGetters && FormulaSpellMetadata.BASE_PARAMETERS.contains(parameter) && !consumers.usesField(parameter)) continue;
            if ((parameter.equals("baseSpellPower") || parameter.equals("spellPowerPerLevel"))
                    && !consumers.usesField(parameter)) continue;
            editableParameters.add(parameter);
        }
        spell.add("editableParameters", editableParameters);
        FormulaManaRatioBridge.registerGetters(repository, owner.name, formulas);
        JsonArray damageComponents = new JsonArray();
        var components = FormulaDamageComponents.analyze(repository, graph, automatic, formulas);
        components.stream().flatMap(component -> component.evidence().stream())
                .filter(evidence -> evidence.startsWith("UNRESOLVED_CONTROL_USE:")).distinct().sorted().forEach(diagnostics::add);
        components.forEach(component -> damageComponents.add(component.toJson()));
        FormulaDisplayControls.apply(repository, owner.name, formulas, components, diagnostics, formula -> {
            String alias = formula.get("alias").getAsString();
            String key = formula.get("name").getAsString() + formula.get("descriptor").getAsString();
            return repository.baselineCoverage() && FormulaConsumerSites.definitions(owner.name.replace('/', '.')).stream()
                    .anyMatch(definition -> definition.key().equals(key))
                    || FormulaAttributeBridgeProof.verified(repository, owner.name, alias)
                    || FormulaManaRatioBridge.hasVerifiedControl(repository, owner.name, alias);
        });
        spell.add("damageComponents", damageComponents);
        JsonArray attributeComponents = new JsonArray();
        var publishedKeys = new java.util.HashSet<String>();
        for (var raw : formulas) {
            var binding = raw.getAsJsonObject();
            if (!binding.has("editable") || binding.get("editable").getAsBoolean())
                publishedKeys.add(binding.get("name").getAsString() + binding.get("descriptor").getAsString());
        }
        FormulaEffectAttributeControls.components(repository, owner.name).stream().filter(component -> publishedKeys.contains(component.binding()))
                .forEach(component -> attributeComponents.add(component.toJson()));
        spell.add("attributeComponents", attributeComponents);
        spell.add("formulas", formulas);
    }

    public static Set<String> registeredSpellClasses(FormulaClassRepository repository) {
        ClassNode registry = repository.classNode(SPELL_REGISTRY);
        MethodNode initializer = registry.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElseThrow();
        List<AbstractInsnNode> instructions = FormulaAutomaticInjector.executable(initializer);
        Set<String> classes = new LinkedHashSet<>();
        for (int index = 0; index < instructions.size(); index++) {
            if (!(instructions.get(index) instanceof MethodInsnNode call) || !call.owner.equals(SPELL_REGISTRY)
                    || !call.name.equals("registerSpell")) continue;
            if (index < 3 || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.desc.equals("(L" + ABSTRACT + ";)Ljava/util/function/Supplier;")
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

    private static String alias(String method, boolean nativeAliases) {
        if (method.equals("getCastTime")) return "base_cast_time";
        if (method.equals("getEffectiveCastTime")) return "cast_time";
        String name = method.startsWith("get") && method.length() > 3 ? method.substring(3) : method;
        name = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        if (nativeAliases || name.equals("amplifier") || name.equals("amplifier_for_level") || name.endsWith("_amplifier"))
            return FormulaNames.alias(name);
        return name;
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
        Files.writeString(target, SpellFormulaFiles.canonical(value), java.nio.charset.StandardCharsets.UTF_8);
    }

}
