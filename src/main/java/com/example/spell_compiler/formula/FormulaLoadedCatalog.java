package com.example.spell_compiler.formula;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;

final class FormulaLoadedCatalog {
    private static final Logger LOGGER = LogManager.getLogger(FormulaLoadedCatalog.class);
    private static SpellFormulaCatalog current;
    private static FormulaAddonDiscovery.Discovery preparedDiscovery;
    private static boolean disabled;

    private FormulaLoadedCatalog() {}

    static synchronized SpellFormulaCatalog prepare(ClassLoader loader, FormulaAddonDiscovery.Discovery discovery)
            throws IOException {
        if (disabled) return current;
        if (current == null || preparedDiscovery == null && discovery != null) {
            current = select(SpellFormulaCatalog.load(loader), discovery);
            preparedDiscovery = discovery;
        }
        return current;
    }

    static SpellFormulaCatalog select(SpellFormulaCatalog builtin, FormulaAddonDiscovery.Discovery discovery) {
        if (discovery == null || discovery.nativeSources().isEmpty()) return builtin;
        var repository = discovery.repository();
        repository.sealSourcePrefix("io/redspace/ironsspellbooks/");
        boolean compatible = !discovery.nativeFingerprint().isEmpty()
                && discovery.nativeFingerprint().equalsIgnoreCase(builtin.sourceFingerprint());
        repository.baselineCoverage(compatible);
        if (compatible) return builtin;
        var generated = FormulaCatalogGenerator.generate(repository, discovery.nativeSources(), discovery.nativeFingerprint());
        for (var failure : generated.getAsJsonArray("classErrors"))
            LOGGER.warn("[SpellFormula] 实际铁魔法来源的法术目录无法完整提取，保留对应原生行为：{}", failure);
        var result = SpellFormulaCatalog.parse(generated);
        LOGGER.info("[SpellFormula] 已从实际铁魔法原始来源重新提取 {} 支法术，停用不匹配的内置消费锚点", result.spells().size());
        return result;
    }

    static synchronized SpellFormulaCatalog unavailable() {
        if (!disabled || current == null) current = new SpellFormulaCatalog(List.of());
        disabled = true;
        preparedDiscovery = null;
        return current;
    }

    static synchronized boolean disabled() {
        return disabled;
    }
}
