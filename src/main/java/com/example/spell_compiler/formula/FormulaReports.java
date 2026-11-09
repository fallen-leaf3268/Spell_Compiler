package com.example.spell_compiler.formula;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class FormulaReports {
    private FormulaReports() {}

    public static void reloaded(net.neoforged.neoforge.event.OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return;
        var snapshot = SpellFormulaState.serverSnapshot();
        if (snapshot.diagnostics().isEmpty()) return;
        var message = net.minecraft.network.chat.Component.translatable("commands.spell_compiler.status.summary",
                SpellFormulaState.catalog().spells().size(), snapshot.byClass().size(), snapshot.diagnostics().size(),
                FormulaRuntimeDispatcher.disabledServerKeys().size());
        event.getPlayerList().getPlayers().stream().filter(player -> player.hasPermissions(2))
                .forEach(player -> player.sendSystemMessage(message));
    }

    public static JsonObject report(SpellFormulaCatalog catalog, SpellFormulaFiles.Snapshot snapshot,
                                    java.util.Set<String> disabled) {
        JsonObject root = new JsonObject();
        root.addProperty("reportVersion", 1);
        root.addProperty("sourceFingerprint", catalog.sourceFingerprint());
        root.addProperty("catalogFingerprint", catalog.fingerprint());
        root.addProperty("snapshotFingerprint", snapshot.fingerprint());
        root.addProperty("countingUnit", "recognized_reachable_consumption_instruction");
        root.addProperty("unknownPathsExcludedFromDenominator", true);
        root.add("scanDiagnostics", catalog.analysis("__catalog__"));
        JsonObject compatibility = new JsonObject();
        FormulaFixedMixinPlugin.diagnostics().forEach(compatibility::addProperty);
        FormulaManaRatioBridge.diagnostics().forEach((name, reason) -> compatibility.addProperty("mana_ratio:" + name, reason));
        FormulaEffectAttributeControls.diagnostics().forEach((name, reason) -> compatibility.addProperty("effect_attribute:" + name, reason));
        root.add("compatibilityDiagnostics", compatibility);
        JsonArray spells = new JsonArray();
        for (var spell : catalog.spells()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", spell.id());
            entry.add("execution", catalog.analysis(spell.id()));
            JsonArray damageComponents = new JsonArray();
            spell.damageComponents().forEach(component -> damageComponents.add(component.toJson()));
            entry.add("damageComponents", damageComponents);
            JsonArray readOnlyBindings = new JsonArray();
            for (var method : spell.formulas()) if (!method.readOnlyReason().isEmpty()) {
                JsonObject binding = new JsonObject();
                binding.addProperty("alias", method.alias());
                binding.addProperty("method", method.key());
                binding.addProperty("reason", method.readOnlyReason());
                readOnlyBindings.add(binding);
            }
            entry.add("readOnlyBindings", readOnlyBindings);
            var override = snapshot.byClass().get(spell.className());
            entry.addProperty("configured", override != null);
            JsonArray bindings = new JsonArray();
            for (var method : spell.editableFormulas()) {
                JsonObject binding = new JsonObject();
                binding.addProperty("alias", method.alias());
                binding.addProperty("method", method.key());
                binding.addProperty("identity", method.consumptionFingerprint());
                var component = spell.attributeComponents().stream()
                        .filter(value -> value.binding().equals(method.key())).findFirst();
                if (component.isPresent() || method.consumptionFingerprint().startsWith("EFFECT_ATTRIBUTE:")) {
                    var operation = component.map(value -> override != null && override.attributes().containsKey(value.key())
                            ? override.attributes().get(value.key()).operation() : value.operation())
                            .orElse(FormulaAttributes.Operation.MULTIPLY_TOTAL);
                    binding.addProperty("unit", operation == FormulaAttributes.Operation.ADDITION ? "attribute_value" : "percent");
                }
                boolean configured = override != null && override.formulas().containsKey(method.key());
                binding.addProperty("state", !configured ? "not_configured"
                        : disabled.contains(FormulaRuntimeDispatcher.key(spell.className(), method.key()))
                        ? "server_disabled" : "configured");
                bindings.add(binding);
            }
            entry.add("bindings", bindings);
            spells.add(entry);
        }
        root.add("spells", spells);
        JsonArray diagnostics = new JsonArray();
        for (var diagnostic : snapshot.diagnostics()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", diagnostic.path().toString());
            entry.addProperty("message", diagnostic.message());
            diagnostics.add(entry);
        }
        root.add("loadDiagnostics", diagnostics);
        return root;
    }

    static JsonObject manifest(SpellFormulaCatalog catalog, boolean existingSourceUnknown) {
        JsonObject root = new JsonObject();
        root.addProperty("manifestVersion", 1);
        root.addProperty("sourceFingerprint", catalog.sourceFingerprint());
        root.addProperty("catalogFingerprint", catalog.fingerprint());
        root.addProperty("existingSourceUnknown", existingSourceUnknown);
        JsonObject definitions = new JsonObject();
        for (var spell : catalog.spells()) {
            JsonObject entry = SpellFormulaFiles.preview(spell);
            JsonObject identities = new JsonObject();
            spell.editableFormulas().forEach(method -> identities.addProperty(method.alias(),
                    method.key() + ':' + method.consumptionFingerprint()));
            entry.add("bindingIdentities", identities);
            JsonArray damageComponents = new JsonArray();
            spell.damageComponents().forEach(component -> damageComponents.add(component.toJson()));
            entry.add("damageComponents", damageComponents);
            definitions.add(SpellFormulaFiles.resourceKey(spell), entry);
        }
        root.add("definitions", definitions);
        return root;
    }

    static JsonObject migration(Path root, SpellFormulaCatalog catalog) throws IOException {
        Path manifest = root.resolve("spell_compiler-source.json");
        SpellFormulaFiles.rejectSymbolicPath(root, manifest);
        JsonObject previous = null;
        if (Files.isRegularFile(manifest)) {
            if (Files.size(manifest) > 4 * 1024 * 1024) throw new IOException("Source manifest exceeds 4 MiB");
            JsonElement parsed = SpellFormulaFiles.parseJson(Files.readString(manifest));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Source manifest must be an object");
            previous = parsed.getAsJsonObject();
            if (!previous.has("definitions") || !previous.get("definitions").isJsonObject()
                    || !previous.has("existingSourceUnknown") || !previous.get("existingSourceUnknown").isJsonPrimitive()
                    || !previous.getAsJsonPrimitive("existingSourceUnknown").isBoolean())
                throw new IllegalArgumentException("Invalid source manifest definitions or source status");
        }
        JsonObject result = new JsonObject();
        result.addProperty("sourceKnown", previous != null && previous.has("existingSourceUnknown")
                && !previous.get("existingSourceUnknown").getAsBoolean()
                && previous.has("sourceFingerprint") && previous.get("sourceFingerprint").isJsonPrimitive()
                && previous.getAsJsonPrimitive("sourceFingerprint").isString()
                && previous.get("sourceFingerprint").getAsString().matches("[0-9a-fA-F]{64}"));
        result.addProperty("currentSource", catalog.sourceFingerprint());
        JsonObject oldDefinitions = previous != null && previous.has("definitions")
                ? previous.getAsJsonObject("definitions") : new JsonObject();
        JsonObject current = manifest(catalog, false).getAsJsonObject("definitions");
        JsonArray changes = new JsonArray();
        for (var entry : oldDefinitions.entrySet()) {
            if (!current.has(entry.getKey())) changes.add("removed_spell:" + entry.getKey());
            else if (!SpellFormulaFiles.canonical(entry.getValue()).equals(
                    SpellFormulaFiles.canonical(current.get(entry.getKey()))))
                changes.add("definition_or_binding_changed:" + entry.getKey());
        }
        for (var key : current.keySet()) if (!oldDefinitions.has(key)) changes.add("new_spell:" + key);
        Path data = root.resolve("data");
        SpellFormulaFiles.rejectSymbolicPath(root, data);
        Map<String, JsonElement> resources = new java.util.LinkedHashMap<>();
        java.util.Set<String> unreadable = new java.util.HashSet<>();
        if (Files.isDirectory(data)) try (var paths = Files.walk(data)) {
            for (var file : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                var relative = data.relativize(file);
                if (relative.getNameCount() < 3 || !relative.getName(1).toString().equals("spells")) continue;
                SpellFormulaFiles.rejectSymbolicPath(root, file);
                if (Files.size(file) > 262144) { changes.add("file_too_large:" + relative); continue; }
                String key = relative.getName(0) + ":" + relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
                key = key.substring(0, key.length() - 5);
                resources.put(key, com.google.gson.JsonNull.INSTANCE);
                try {
                    resources.put(key, SpellFormulaFiles.parseJson(Files.readString(file, StandardCharsets.UTF_8)));
                } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                    unreadable.add(key);
                    changes.add("unreadable_configuration:" + relative + ':' + failure.getMessage());
                }
            }
        }
        var parsed = SpellFormulaFiles.parseResources(resources, catalog, unreadable);
        parsed.diagnostics().forEach(d -> changes.add("configuration_rejected:" + d.path() + ':' + d.message()));
        result.add("changes", changes);
        return result;
    }

    static Path write(Path root, String prefix, JsonElement report) throws IOException {
        root = root.toAbsolutePath().normalize();
        Path reports = root;
        SpellFormulaFiles.rejectSymbolicPath(root, reports);
        Files.createDirectories(reports);
        Path file = Files.createTempFile(reports, prefix + '-', ".json");
        Files.writeString(file, SpellFormulaFiles.canonical(report), StandardCharsets.UTF_8, StandardOpenOption.WRITE);
        return file;
    }
}
