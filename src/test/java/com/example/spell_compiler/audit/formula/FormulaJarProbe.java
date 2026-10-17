package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaClassRepository;
import com.example.spell_compiler.formula.FormulaCompiler;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

public final class FormulaJarProbe {
    private static final String SPELLS = "io/redspace/ironsspellbooks/spells/";
    private static final Map<String, List<String>> EXECUTION_METHODS = Map.of(
            "fire/FireballSpell", List.of("getDamage", "getRadius"),
            "fire/MagmaBombSpell", List.of("getDamage", "getAoeDamage", "getRadius"),
            "holy/HealingCircleSpell", List.of("getHealing", "getRadius", "getDuration"),
            "ice/SummonPolarBearSpell", List.of("getBearHealth", "getBearDamage"),
            "lightning/ChainLightningSpell", List.of("getDamage", "getMaxConnections", "getRange"),
            "nature/EarthquakeSpell", List.of("getDamage", "getRadius", "getSlownessAmplifier"));

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected source JAR and output JSON path");
        Path source = Path.of(args[0]).toAbsolutePath();
        FormulaClassRepository repository = FormulaClassRepository.fromJar(source);
        JsonObject report = new JsonObject();
        report.addProperty("scope", "offline numeric bytecode prototype; recursively expanded spell-class methods; native AbstractSpell power helpers and SchoolType.getPowerFor execution baseline; external attribute/config reads isolated; no Forge bootstrap or entity-stage coverage");
        report.addProperty("source_jar", source.toString());
        report.addProperty("source_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        JsonArray supported = new JsonArray();
        JsonArray rejected = new JsonArray();
        List<ClassNode> classes = new ArrayList<>();
        int candidates = 0;
        int untouchedClasses = 0;
        int readableUntouchedClasses = 0;
        try (JarFile jar = new JarFile(source.toFile())) {
            var entries = jar.stream().filter(entry -> entry.getName().startsWith(SPELLS)
                    && entry.getName().substring(SPELLS.length()).contains("/")
                    && entry.getName().endsWith(".class") && !entry.getName().contains("$"))
                    .sorted(java.util.Comparator.comparing(java.util.jar.JarEntry::getName)).toList();
            for (var entry : entries) {
                byte[] bytes;
                try (var stream = jar.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                ClassNode owner = new ClassNode();
                new ClassReader(bytes).accept(owner, 0);
                classes.add(owner);
                Map<String, String> defaults = new LinkedHashMap<>();
                Map<String, String> readableDefaults = new LinkedHashMap<>();
                for (var method : owner.methods) {
                    int sort = Type.getReturnType(method.desc).getSort();
                    if (sort != Type.INT && sort != Type.FLOAT && sort != Type.DOUBLE && sort != Type.LONG) continue;
                    candidates++;
                    JsonObject item = new JsonObject();
                    item.addProperty("class", owner.name.replace('/', '.'));
                    item.addProperty("method", method.name);
                    item.addProperty("descriptor", method.desc);
                    var analysis = AutomaticFormulaExtractor.analyze(owner, method, repository);
                    if (analysis.supported()) {
                        var formula = analysis.formula();
                        item.addProperty("expression", formula.expression());
                        item.addProperty("readable_expression", formula.readableExpression());
                        JsonObject variables = new JsonObject();
                        formula.variables().forEach((name, variable) -> variables.addProperty(name, variable.type().getClassName()));
                        item.add("variables", variables);
                        item.addProperty("return_type", formula.returnType().getClassName());
                        defaults.put(method.name + method.desc, formula.expression());
                        readableDefaults.put(method.name + method.desc, formula.readableExpression());
                        supported.add(item);
                    } else {
                        item.addProperty("reason", analysis.rejection());
                        rejected.add(item);
                    }
                }
                if (!Arrays.equals(bytes, FormulaCompiler.transform(bytes, Map.of(), repository))
                        || !Arrays.equals(bytes, FormulaCompiler.transform(bytes, defaults, repository))) {
                    throw new AssertionError("Untouched formulas rewrote original class: " + owner.name);
                }
                untouchedClasses++;
                if (!Arrays.equals(bytes, FormulaCompiler.transform(bytes, readableDefaults, repository))) {
                    throw new AssertionError("Untouched readable formulas rewrote original class: " + owner.name);
                }
                readableUntouchedClasses++;
            }
        }
        JsonArray execution = new JsonArray();
        RealSpellFormulaHarness harness = new RealSpellFormulaHarness(source);
        int comparisons = 0;
        int readableComparisons = 0;
        int executionMethods = 0;
        for (String name : EXECUTION_METHODS.keySet().stream().sorted().toList()) {
            ClassNode owner = classes.stream().filter(node -> node.name.equals(SPELLS + name)).findFirst().orElseThrow();
            var verified = verifyExecution(owner, EXECUTION_METHODS.get(name), repository, harness, execution);
            comparisons += verified.strict();
            readableComparisons += verified.readable();
            executionMethods += EXECUTION_METHODS.get(name).size();
        }
        ClassNode fireball = classes.stream().filter(node -> node.name.equals(SPELLS + "fire/FireballSpell")).findFirst().orElseThrow();
        int factorComparisons = verifyIndependentInputs(fireball, repository, harness);
        int readableEditComparisons = verifyReadableEdits(fireball, repository, harness, report);
        report.addProperty("spell_class_files", classes.size());
        report.addProperty("numeric_methods_examined", candidates);
        report.addProperty("automatically_supported_methods", supported.size());
        report.addProperty("rejected_methods", rejected.size());
        report.addProperty("unchanged_class_bytes_verified", untouchedClasses);
        report.addProperty("readable_unchanged_class_bytes_verified", readableUntouchedClasses);
        report.addProperty("readable_formulas_exported", supported.size());
        report.addProperty("execution_methods", executionMethods);
        report.addProperty("execution_comparisons", comparisons);
        report.addProperty("readable_execution_comparisons", readableComparisons);
        report.addProperty("readable_edit_comparisons", readableEditComparisons);
        report.addProperty("independent_factor_comparisons", factorComparisons);
        report.addProperty("default_comparison", "raw IEEE-754 bits for float/double; exact integer values");
        report.addProperty("baseline", "native JAR method bytecode, including getSpellPower/getEntityPowerMultiplier and SchoolType missing-attribute fallback; native Minecraft integer clamp bytecode");
        report.addProperty("readable_strategy", "unchanged readable defaults restore original strict expressions; edited expressions reuse matched original subtrees and preserve their native casts/order; newly written operations use the typed formula compiler, without promising arbitrary algebraic reassociation is bit-equivalent");
        report.add("execution_examples", execution);
        report.add("supported", supported);
        report.add("rejected", rejected);
        Path output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report));
        System.out.println("Classes=" + classes.size() + ", numeric methods=" + candidates + ", supported=" + supported.size()
                + ", rejected=" + rejected.size() + ", execution comparisons=" + comparisons + ", readable comparisons=" + readableComparisons
                + ", readable edits=" + readableEditComparisons + ", independent factors=" + factorComparisons);
        System.out.println("Report=" + output);
    }

    private record ExecutionCounts(int strict, int readable) {}

    private static ExecutionCounts verifyExecution(ClassNode original, List<String> names, FormulaClassRepository repository,
                                       RealSpellFormulaHarness harness, JsonArray execution) throws Exception {
        List<MethodNode> methods = original.methods.stream().filter(method -> names.contains(method.name)).toList();
        if (methods.size() != names.size()) throw new AssertionError("Missing expected methods in " + original.name);
        Map<String, String> defaults = new LinkedHashMap<>();
        Map<String, String> changed = new LinkedHashMap<>();
        Map<String, String> readableDefaults = new LinkedHashMap<>();
        Map<String, String> readableChanged = new LinkedHashMap<>();
        for (var method : methods) {
            var analysis = AutomaticFormulaExtractor.analyze(original, method, repository);
            if (!analysis.supported()) throw new AssertionError(original.name + "#" + method.name + ": " + analysis.rejection());
            String expression = analysis.formula().expression();
            defaults.put(method.name + method.desc, expression);
            changed.put(method.name + method.desc, "(" + expression + ") + 7");
            String readable = analysis.formula().readableExpression();
            readableDefaults.put(method.name + method.desc, readable);
            readableChanged.put(method.name + method.desc, "(" + readable + ") + 7");
        }
        byte[] bytes = RealSpellFormulaHarness.bytes(original);
        Class<?> baseline = harness.load(original, bytes, methods);
        Class<?> roundTrip = harness.load(original, FormulaCompiler.compileForVerification(bytes, defaults, repository), methods);
        Class<?> modified = harness.load(original, FormulaCompiler.transform(bytes, changed, repository), methods);
        Class<?> readableRoundTrip = harness.load(original, FormulaCompiler.compileForVerification(bytes, readableDefaults, repository), methods);
        Class<?> readableModified = harness.load(original, FormulaCompiler.transform(bytes, readableChanged, repository), methods);
        Map<String, Integer> nativeDefaults = RealSpellFormulaHarness.defaults(original);
        int nativeBase = nativeDefaults.get("baseSpellPower");
        int nativeGrowth = nativeDefaults.get("spellPowerPerLevel");
        int[][] bases = {{nativeBase, nativeGrowth}, {nativeBase, 4}, {13, nativeGrowth}, {13, 4}, {0, 0}};
        int comparisons = 0;
        for (int level : new int[]{1, 5, 15}) {
            for (int[] base : bases) {
                for (double global : new double[]{0, 1, 1.2345678901, 2.5}) {
                    for (double school : new double[]{0.7, 1, 1.9999999993}) {
                        for (double config : new double[]{0.3, 1, 1.2345678912}) {
                            for (var caster : RealSpellFormulaHarness.Caster.values()) {
                                var input = new RealSpellFormulaHarness.Inputs(level, base[0], base[1], global, school, config, caster);
                                Object first = harness.create(baseline, input);
                                Object second = harness.create(roundTrip, input);
                                Object third = harness.create(modified, input);
                                Object fourth = harness.create(readableRoundTrip, input);
                                Object fifth = harness.create(readableModified, input);
                                for (var method : methods) {
                                    Number before = harness.invoke(baseline, first, method, input);
                                    Number rebuilt = harness.invoke(roundTrip, second, method, input);
                                    Number after = harness.invoke(modified, third, method, input);
                                    Number readableRebuilt = harness.invoke(readableRoundTrip, fourth, method, input);
                                    Number readableAfter = harness.invoke(readableModified, fifth, method, input);
                                    if (!sameBits(before, rebuilt)) {
                                        throw new AssertionError("Default formula changed result: " + original.name + "#" + method.name
                                                + " " + input + ": " + before + " -> " + rebuilt);
                                    }
                                    Number expected = addSeven(before);
                                    if (!sameBits(expected, after)) {
                                        throw new AssertionError("Override not applied: " + original.name + "#" + method.name
                                                + " " + input + ": expected " + expected + " but got " + after);
                                    }
                                    if (!sameBits(before, readableRebuilt) || !sameBits(expected, readableAfter)) {
                                        throw new AssertionError("Readable formula changed native precision: " + original.name + "#" + method.name
                                                + " " + input + ": before=" + before + ", rebuilt=" + readableRebuilt + ", expected override=" + expected
                                                + ", modified=" + readableAfter);
                                    }
                                    comparisons += 2;
                                    if (level == 5 && base[0] == 13 && base[1] == 4 && global == 1.2345678901
                                            && school == 1.9999999993 && config == 0.3) {
                                        JsonObject item = new JsonObject();
                                        item.addProperty("class", original.name.replace('/', '.'));
                                        item.addProperty("method", method.name);
                                        item.addProperty("formula", defaults.get(method.name + method.desc));
                                        item.addProperty("override", changed.get(method.name + method.desc));
                                        item.addProperty("readable_formula", readableDefaults.get(method.name + method.desc));
                                        item.addProperty("readable_override", readableChanged.get(method.name + method.desc));
                                        item.addProperty("level", level);
                                        item.addProperty("baseSpellPower", base[0]);
                                        item.addProperty("spellPowerPerLevel", base[1]);
                                        item.addProperty("spellpower_attribute", global);
                                        item.addProperty("school_spellpower_attribute", school);
                                        item.addProperty("power_multiplier_config", config);
                                        item.addProperty("caster", caster.name());
                                        item.addProperty("original", before);
                                        item.addProperty("recompiled_default", rebuilt);
                                        item.addProperty("modified", after);
                                        item.addProperty("readable_recompiled_default", readableRebuilt);
                                        item.addProperty("readable_modified", readableAfter);
                                        execution.add(item);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return new ExecutionCounts(comparisons, comparisons);
    }

    private static int verifyIndependentInputs(ClassNode original, FormulaClassRepository repository,
                                               RealSpellFormulaHarness harness) throws Exception {
        MethodNode method = original.methods.stream().filter(target -> target.name.equals("getDamage")).findFirst().orElseThrow();
        var formula = AutomaticFormulaExtractor.analyze(original, method, repository).formula();
        for (String variable : List.of("baseSpellPower", "spellPowerPerLevel", "spell_power", "school_spell_power", "power_multiplier")) {
            if (!formula.variables().containsKey(variable)) throw new AssertionError("Missing independent input: " + variable);
        }
        String key = method.name + method.desc;
        byte[] bytes = RealSpellFormulaHarness.bytes(original);
        Class<?> globalOnly = harness.load(original, FormulaCompiler.transform(bytes, Map.of(key, "float(spell_power)"), repository), List.of(method));
        Class<?> schoolOnly = harness.load(original, FormulaCompiler.transform(bytes, Map.of(key, "float(school_spell_power)"), repository), List.of(method));
        Class<?> configOnly = harness.load(original, FormulaCompiler.transform(bytes, Map.of(key, "float(power_multiplier)"), repository), List.of(method));
        String withoutSchool = formula.expression().replaceAll("\\bschool_spell_power\\b", "double(1)");
        if (withoutSchool.equals(formula.expression())) throw new AssertionError("School removal did not change the extracted formula");
        Class<?> schoolRemoved = harness.load(original, FormulaCompiler.transform(bytes, Map.of(key, withoutSchool), repository), List.of(method));
        int comparisons = 0;
        for (double global : new double[]{0.3, 1, 1.2345678901}) {
            for (double school : new double[]{0.7, 1.9999999993}) {
                for (double config : new double[]{0.3, 1.2345678912}) {
                    for (var caster : RealSpellFormulaHarness.Caster.values()) {
                        var input = new RealSpellFormulaHarness.Inputs(7, 13, 4, global, school, config, caster);
                        Number value = harness.invoke(globalOnly, harness.create(globalOnly, input), method, input);
                        float expected = caster == RealSpellFormulaHarness.Caster.NULL ? 1.0f : (float) global;
                        if (!sameBits(Float.valueOf(expected), value)) throw new AssertionError("spell_power must be only the general attribute: " + input + " -> " + value);
                        Number schoolValue = harness.invoke(schoolOnly, harness.create(schoolOnly, input), method, input);
                        float expectedSchool = caster == RealSpellFormulaHarness.Caster.PRESENT ? (float) school : 1.0f;
                        if (!sameBits(Float.valueOf(expectedSchool), schoolValue)) throw new AssertionError("school_spell_power must preserve native fallback: " + input + " -> " + schoolValue);
                        Number configValue = harness.invoke(configOnly, harness.create(configOnly, input), method, input);
                        if (!sameBits(Float.valueOf((float) config), configValue)) throw new AssertionError("power_multiplier must be only the config multiplier: " + input + " -> " + configValue);
                        var alternateSchool = new RealSpellFormulaHarness.Inputs(7, 13, 4, global, 4.123456789, config, caster);
                        Number first = harness.invoke(schoolRemoved, harness.create(schoolRemoved, input), method, input);
                        Number second = harness.invoke(schoolRemoved, harness.create(schoolRemoved, alternateSchool), method, alternateSchool);
                        if (!sameBits(first, second)) throw new AssertionError("Removed school factor still changed output: " + input);
                        comparisons += 4;
                    }
                }
            }
        }
        return comparisons;
    }

    private static int verifyReadableEdits(ClassNode original, FormulaClassRepository repository,
                                           RealSpellFormulaHarness harness, JsonObject report) throws Exception {
        MethodNode damage = original.methods.stream().filter(method -> method.name.equals("getDamage")).findFirst().orElseThrow();
        MethodNode radius = original.methods.stream().filter(method -> method.name.equals("getRadius")).findFirst().orElseThrow();
        var damageFormula = AutomaticFormulaExtractor.analyze(original, damage, repository).formula();
        var radiusFormula = AutomaticFormulaExtractor.analyze(original, radius, repository).formula();
        String readableDamage = damageFormula.readableExpression();
        var coefficients = java.util.regex.Pattern.compile("(?<![\\w.])5(?![\\w.])").matcher(readableDamage);
        int coefficientCount = 0;
        int start = -1;
        int end = -1;
        while (coefficients.find()) {
            if (++coefficientCount == 2) {
                start = coefficients.start();
                end = coefficients.end();
            }
        }
        if (coefficientCount != 2) throw new AssertionError("Expected the two native Fireball coefficients: " + readableDamage);
        String editedDamage = readableDamage.substring(0, start) + "7" + readableDamage.substring(end);
        String limitedRadius = "min((" + radiusFormula.readableExpression() + "), 16)";
        byte[] bytes = RealSpellFormulaHarness.bytes(original);
        ClassNode nativeCoefficient = new ClassNode();
        new ClassReader(bytes).accept(nativeCoefficient, 0);
        MethodNode nativeDamage = nativeCoefficient.methods.stream().filter(method -> method.name.equals("getDamage")).findFirst().orElseThrow();
        int constants = 0;
        for (var instruction : nativeDamage.instructions) {
            if (instruction instanceof LdcInsnNode value && value.cst instanceof Float number && number.equals(5.0f)) {
                if (++constants == 2) value.cst = 7.0f;
            }
        }
        if (constants != 2) throw new AssertionError("Expected the two native Fireball float constants");
        List<MethodNode> methods = List.of(damage, radius);
        Class<?> baseline = harness.load(original, bytes, methods);
        Class<?> nativeChanged = harness.load(original, RealSpellFormulaHarness.bytes(nativeCoefficient), methods);
        Class<?> readableChanged = harness.load(original,
                FormulaCompiler.transform(bytes, Map.of(damage.name + damage.desc, editedDamage), repository), methods);
        Class<?> readableLimited = harness.load(original,
                FormulaCompiler.transform(bytes, Map.of(radius.name + radius.desc, limitedRadius), repository), methods);
        int comparisons = 0;
        int limited = 0;
        int unchangedRadius = 0;
        for (int level : new int[]{1, 5, 15}) {
            for (double global : new double[]{0.3, 1.2345678901, 40.25}) {
                for (double school : new double[]{0.7, 1.9999999993}) {
                    for (double config : new double[]{0.3, 1.2345678912}) {
                        for (var caster : RealSpellFormulaHarness.Caster.values()) {
                            var input = new RealSpellFormulaHarness.Inputs(level, 13, 4, global, school, config, caster);
                            Number expected = harness.invoke(nativeChanged, harness.create(nativeChanged, input), damage, input);
                            Number edited = harness.invoke(readableChanged, harness.create(readableChanged, input), damage, input);
                            if (!sameBits(expected, edited)) {
                                throw new AssertionError("Readable coefficient edit differs from original-bytecode coefficient edit: "
                                        + input + ": " + expected + " -> " + edited);
                            }
                            int nativeRadius = harness.invoke(baseline, harness.create(baseline, input), radius, input).intValue();
                            Number clipped = harness.invoke(readableLimited, harness.create(readableLimited, input), radius, input);
                            if (!sameBits(Integer.valueOf(Math.min(nativeRadius, 16)), clipped)) {
                                throw new AssertionError("Readable min wrapper failed to preserve native radius subexpression: "
                                        + input + ": " + nativeRadius + " -> " + clipped);
                            }
                            if (nativeRadius > 16) limited++;
                            else unchangedRadius++;
                            comparisons += 2;
                        }
                    }
                }
            }
        }
        if (limited == 0 || unchangedRadius == 0) throw new AssertionError("Readable radius limit did not cover both sides of the bound");
        report.addProperty("readable_coefficient_edit", editedDamage);
        report.addProperty("readable_coefficient_baseline", "second Float(5.0) LDC changed to Float(7.0) in original Fireball.getDamage bytecode");
        report.addProperty("readable_radius_limit", limitedRadius);
        report.addProperty("readable_radius_limited_cases", limited);
        report.addProperty("readable_radius_unchanged_cases", unchangedRadius);
        return comparisons;
    }

    private static boolean sameBits(Number first, Number second) {
        if (first instanceof Float && second instanceof Float)
            return Float.floatToRawIntBits(first.floatValue()) == Float.floatToRawIntBits(second.floatValue());
        if (first instanceof Double && second instanceof Double)
            return Double.doubleToRawLongBits(first.doubleValue()) == Double.doubleToRawLongBits(second.doubleValue());
        return first.equals(second);
    }

    private static Number addSeven(Number before) {
        if (before instanceof Float) return before.floatValue() + 7.0f;
        if (before instanceof Double) return before.doubleValue() + 7.0;
        if (before instanceof Long) return before.longValue() + 7L;
        return before.intValue() + 7;
    }
}
