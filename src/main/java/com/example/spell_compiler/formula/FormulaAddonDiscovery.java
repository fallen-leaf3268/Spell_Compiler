package com.example.spell_compiler.formula;

import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.forgespi.language.ModFileScanData;
import net.minecraftforge.forgespi.locating.IModFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FormulaAddonDiscovery {
    public record Discovery(FormulaClassRepository repository, List<ClassNode> sources,
                            Set<String> spellClasses, Map<String, String> owners) {
        public Discovery {
            sources = List.copyOf(sources);
            spellClasses = Set.copyOf(spellClasses);
            owners = Map.copyOf(owners);
        }

        public String fingerprint() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                MessageDigest classDigest = MessageDigest.getInstance("SHA-256");
                for (ClassNode source : sources.stream().sorted(Comparator.comparing(value -> value.name)).toList()) {
                    digest.update(source.name.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(owners.getOrDefault(source.name, "").getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    ClassWriter writer = new ClassWriter(0);
                    source.accept(writer);
                    digest.update(classDigest.digest(writer.toByteArray()));
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("无法计算附属法术原始字节码指纹", failure);
            }
        }
    }

    private record Source(IModFile file, String modId, String parent) {}

    private static final String ABSTRACT = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static volatile Discovery current;

    private FormulaAddonDiscovery() {}

    public static Discovery current() {
        return current;
    }

    public static synchronized Discovery discover() throws IOException {
        if (current != null) return current;
        LoadingModList loading = LoadingModList.get();
        if (loading == null) throw new IllegalStateException("附属法术扫描发生在Forge模组列表就绪之前");
        Map<String, Source> classes = new LinkedHashMap<>();
        Map<IModFile, List<String>> files = new LinkedHashMap<>();
        Set<String> mixins = new HashSet<>();
        Set<IModFile> selectedFiles = new LinkedHashSet<>();
        Set<IModFile> nativeFiles = new HashSet<>();
        for (var info : loading.getModFiles()) {
            IModFile file = info.getFile();
            String modId = String.join(",", file.getModInfos().stream().map(value -> value.getModId()).sorted().toList());
            ModFileScanData scan = file.getScanResult();
            if (scan == null) continue;
            for (var annotation : scan.getAnnotations())
                if (annotation.annotationType().getDescriptor().equals(MIXIN))
                    mixins.add(annotation.clazz().getInternalName());
            List<String> names = new ArrayList<>();
            for (var value : scan.getClasses()) {
                String name = value.clazz().getInternalName();
                names.add(name);
                classes.putIfAbsent(name, new Source(file, modId,
                        value.parent() == null ? null : value.parent().getInternalName()));
            }
            files.put(file, List.copyOf(names));
            if (file.getModInfos().stream().anyMatch(value -> value.getModId().equals("irons_spellbooks"))) {
                selectedFiles.add(file);
                nativeFiles.add(file);
            }
        }
        Map<String, Boolean> subtypes = new HashMap<>();
        Map<String, ClassNode> candidates = new LinkedHashMap<>();
        for (var entry : classes.entrySet()) {
            String name = entry.getKey();
            if (nativeFiles.contains(entry.getValue().file()) || mixins.contains(name)
                    || !isSpell(name, classes, subtypes, new HashSet<>())) continue;
            ClassNode source = read(entry.getValue().file(), name);
            if (isMixin(source)) continue;
            candidates.put(name, source);
            selectedFiles.add(entry.getValue().file());
        }
        List<ClassNode> sources = new ArrayList<>();
        Map<String, String> owners = new LinkedHashMap<>();
        Set<String> added = new HashSet<>();
        for (IModFile file : selectedFiles) {
            for (String name : files.getOrDefault(file, List.of()).stream().sorted().toList()) {
                if (!added.add(name)) continue;
                ClassNode source = candidates.get(name);
                if (source == null) source = read(file, name);
                sources.add(source);
                owners.put(name, classes.get(name).modId());
            }
        }
        if (sources.isEmpty()) throw new IOException("附属法术扫描没有找到铁魔法的原始类资源");
        sources.sort(Comparator.comparing(value -> value.name));
        FormulaClassRepository repository = FormulaClassRepository.of(sources.get(0));
        for (int index = 1; index < sources.size(); index++) repository.add(sources.get(index));
        Set<String> names = new LinkedHashSet<>();
        candidates.keySet().stream().sorted().forEach(names::add);
        current = new Discovery(repository, sources, names, owners);
        return current;
    }

    private static boolean isSpell(String name, Map<String, Source> classes, Map<String, Boolean> results,
                                   Set<String> visiting) {
        if (name.equals(ABSTRACT)) return true;
        Boolean known = results.get(name);
        if (known != null) return known;
        Source source = classes.get(name);
        if (source == null || source.parent() == null || !visiting.add(name)) return false;
        boolean result = isSpell(source.parent(), classes, results, visiting);
        visiting.remove(name);
        results.put(name, result);
        return result;
    }

    private static ClassNode read(IModFile file, String name) throws IOException {
        ClassNode result = new ClassNode();
        try (var stream = Files.newInputStream(file.findResource(name + ".class"))) {
            new ClassReader(stream).accept(result, ClassReader.SKIP_DEBUG);
        }
        if (!result.name.equals(name)) throw new IOException("附属法术类资源名称不一致：" + name);
        return result;
    }

    private static boolean isMixin(ClassNode source) {
        return hasMixin(source.visibleAnnotations) || hasMixin(source.invisibleAnnotations);
    }

    private static boolean hasMixin(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(value -> value.desc.equals(MIXIN));
    }
}
