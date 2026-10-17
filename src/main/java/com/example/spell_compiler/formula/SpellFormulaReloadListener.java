package com.example.spell_compiler.formula;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class SpellFormulaReloadListener extends SimpleJsonResourceReloadListener {
    private List<SpellFormulaFiles.Diagnostic> readingDiagnostics = List.of();
    private Set<ResourceLocation> unreadableResources = Set.of();

    public SpellFormulaReloadListener() {
        super(new Gson(), "spells");
    }

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> resources = new LinkedHashMap<>();
        List<SpellFormulaFiles.Diagnostic> diagnostics = new ArrayList<>();
        Set<ResourceLocation> unreadable = new HashSet<>();
        for (var entry : manager.listResources("spells", id -> id.getPath().endsWith(".json")).entrySet()) {
            ResourceLocation location = entry.getKey();
            ResourceLocation key = null;
            try {
                String path = location.getPath();
                key = ResourceLocation.fromNamespaceAndPath(location.getNamespace(),
                        path.substring(0, path.length() - ".json".length()));
                resources.put(key, JsonNull.INSTANCE);
                try (var stream = entry.getValue().open()) {
                    byte[] bytes = stream.readNBytes(262_145);
                    if (bytes.length > 262_144) throw new IllegalArgumentException("文件超过 256 KiB");
                    resources.put(key, SpellFormulaFiles.parseJson(new String(bytes, StandardCharsets.UTF_8)));
                }
            } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                if (key != null) unreadable.add(key);
                diagnostics.add(new SpellFormulaFiles.Diagnostic(Path.of(location.toString().replace(':', '/')),
                        "数据包 " + entry.getValue().sourcePackId() + " 的法术文件未应用：" + failure.getMessage(), failure));
            }
        }
        readingDiagnostics = List.copyOf(diagnostics);
        unreadableResources = Set.copyOf(unreadable);
        return resources;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        SpellFormulaRuntime.applyServer(snapshot(resources));
    }

    public static SpellFormulaFiles.Snapshot load(ResourceManager manager) {
        SpellFormulaReloadListener listener = new SpellFormulaReloadListener();
        return listener.snapshot(listener.prepare(manager, null));
    }

    private SpellFormulaFiles.Snapshot snapshot(Map<ResourceLocation, JsonElement> resources) {
        Map<String, JsonElement> json = new LinkedHashMap<>();
        resources.forEach((key, value) -> json.put(key.toString(), value));
        var parsed = SpellFormulaFiles.parseResources(json, SpellFormulaState.catalog(),
                unreadableResources.stream().map(ResourceLocation::toString).collect(Collectors.toSet()));
        List<SpellFormulaFiles.Diagnostic> diagnostics = new ArrayList<>(readingDiagnostics);
        diagnostics.addAll(parsed.diagnostics());
        return SpellFormulaFiles.snapshot(parsed.byClass(), diagnostics);
    }
}
