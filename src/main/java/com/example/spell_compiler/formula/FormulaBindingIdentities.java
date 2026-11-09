package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

final class FormulaBindingIdentities {
    private static final JsonObject SOURCES = load();
    private static final Pattern INPUT = Pattern.compile("\\binput_[0-9]+\\b");
    private final JsonObject identities;
    private final Set<String> reserved = new HashSet<>();
    private final Set<String> assigned = new HashSet<>();

    FormulaBindingIdentities(FormulaClassRepository repository, String spell) {
        JsonObject selected = null;
        for (String fingerprint : repository.sourceFingerprints()) {
            var source = SOURCES.getAsJsonObject(fingerprint);
            if (source == null || !source.has(spell)) continue;
            var found = source.getAsJsonObject(spell);
            if (selected != null && !selected.equals(found)) throw new IllegalStateException("AMBIGUOUS_BINDING_IDENTITIES: " + spell);
            selected = found;
        }
        identities = selected;
        if (identities != null) identities.entrySet().forEach(entry -> reserved.add(entry.getValue().getAsJsonObject().get("alias").getAsString()));
    }

    String alias(FormulaAutomaticSites.Site site) {
        String identity = identity(site);
        var baseline = identities == null ? null : identities.getAsJsonObject(identity);
        String alias = baseline == null ? site.definition().alias() : baseline.get("alias").getAsString();
        if (baseline == null && identities != null) alias = alias.replaceFirst("_[0-9]+$", "") + "_p" + digest(identity);
        if (!assigned.add(alias) || baseline == null && identities != null && reserved.contains(alias))
            throw new IllegalStateException("BINDING_IDENTITY_COLLISION: " + identity);
        return alias;
    }

    FormulaBackwardSlice.Result inputs(FormulaAutomaticSites.Site site, FormulaBackwardSlice.Result result) {
        var baseline = identities == null ? null : identities.getAsJsonObject(identity(site));
        if (baseline == null || !result.supported()) return result;
        Map<String, JsonObject> previous = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (var element : baseline.getAsJsonArray("inputs")) {
            var value = element.getAsJsonObject();
            previous.put(capture(value.get("type").getAsString(), value.get("producer").getAsInt(), value.get("local").getAsInt()), value);
            used.add(value.get("name").getAsString());
        }
        Map<String, String> renamed = new HashMap<>();
        List<FormulaCapturedInput> inputs = new ArrayList<>();
        int next = 0;
        for (var input : result.inputs()) {
            var old = previous.remove(capture(input.descriptor(), input.producerIndex(), input.localSlot()));
            String name;
            if (old != null) name = old.get("name").getAsString();
            else {
                while (used.contains("input_" + next)) next++;
                name = "input_" + next++;
                used.add(name);
            }
            renamed.put(input.name(), name);
            inputs.add(new FormulaCapturedInput(name, input.descriptor(), input.producerIndex(), input.localSlot(), input.nativePower(), input.powerUnknown()));
        }
        for (var old : previous.values()) {
            if (!old.has("nativePower") || !old.has("powerStageUnknown"))
                return new FormulaBackwardSlice.Result("native_value", List.of(), FormulaBackwardSlice.Completeness.UNSUPPORTED,
                        List.of("INPUT_IDENTITY_UNPROVEN: " + old.get("name").getAsString()), false);
            inputs.add(new FormulaCapturedInput(old.get("name").getAsString(), old.get("type").getAsString(),
                    old.get("producer").getAsInt(), old.get("local").getAsInt(),
                    old.get("nativePower").getAsBoolean(), old.get("powerStageUnknown").getAsBoolean()));
        }
        String expression = INPUT.matcher(result.expression()).replaceAll(match -> renamed.getOrDefault(match.group(), match.group()));
        inputs.sort(Comparator.comparingInt(input -> Integer.parseInt(input.name().substring(6))));
        return new FormulaBackwardSlice.Result(expression, inputs, result.completeness(), result.diagnostics(), result.nativePower());
    }

    static String identity(FormulaAutomaticSites.Site site) {
        return site.kind() + ":" + site.owner() + '#' + site.methodName() + site.methodDescriptor() + ':'
                + site.producerIndex() + ':' + site.methodFingerprint() + ':' + site.argumentIndex() + ':'
                + site.contextMode() + ':' + site.contextSlot();
    }

    private static String capture(String descriptor, int producer, int local) {
        return descriptor + ':' + producer + ':' + local;
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 12); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static JsonObject load() {
        try (var stream = FormulaBindingIdentities.class.getResourceAsStream("/formula/binding-identities.json")) {
            if (stream == null) throw new IllegalStateException("BINDING_IDENTITIES_RESOURCE_MISSING");
            var document = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (document.get("schemaVersion").getAsInt() != 1) throw new IllegalStateException("BINDING_IDENTITIES_SCHEMA");
            return document.getAsJsonObject("sources");
        } catch (java.io.IOException failure) { throw new IllegalStateException("BINDING_IDENTITIES_UNREADABLE", failure); }
    }
}
