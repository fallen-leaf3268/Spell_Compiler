package com.example.spell_compiler.formula;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class SpellFormulaState {
    private static SpellFormulaCatalog catalog;
    private static volatile SpellFormulaFiles.Snapshot serverSnapshot = SpellFormulaFiles.snapshot(Map.of(), List.of());
    private static volatile SpellFormulaFiles.Snapshot clientSnapshot = SpellFormulaFiles.snapshot(Map.of(), List.of());

    private SpellFormulaState() {}

    public static synchronized SpellFormulaCatalog initialize(ClassLoader loader) throws IOException {
        if (catalog == null) catalog = SpellFormulaCatalog.load(loader);
        return catalog;
    }

    public static SpellFormulaFiles.Snapshot initialize(Path ignored, ClassLoader loader) throws IOException {
        initialize(loader);
        return serverSnapshot;
    }

    public static SpellFormulaFiles.Snapshot snapshot() {
        return serverSnapshot;
    }

    public static SpellFormulaFiles.Snapshot serverSnapshot() {
        return serverSnapshot;
    }

    public static SpellFormulaFiles.Snapshot clientSnapshot() {
        return clientSnapshot;
    }

    public static void publishServer(SpellFormulaFiles.Snapshot value) {
        serverSnapshot = value;
    }

    public static void publishClient(SpellFormulaFiles.Snapshot value) {
        clientSnapshot = value;
    }

    public static synchronized SpellFormulaCatalog catalog() {
        if (catalog == null) throw new IllegalStateException("法术公式目录尚未初始化");
        return catalog;
    }

    static synchronized void replaceCatalog(SpellFormulaCatalog value) {
        if (!serverSnapshot.byClass().isEmpty() || !clientSnapshot.byClass().isEmpty())
            throw new IllegalStateException("不能在公式已应用后替换法术目录");
        catalog = value;
    }

    public static synchronized String catalogFingerprint() {
        if (catalog == null) {
            try {
                initialize(SpellFormulaState.class.getClassLoader());
            } catch (IOException failure) {
                throw new IllegalStateException("无法读取法术目录指纹", failure);
            }
        }
        return catalog.fingerprint();
    }
}
