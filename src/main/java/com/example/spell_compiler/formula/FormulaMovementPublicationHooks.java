package com.example.spell_compiler.formula;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaMovementPublicationHooks {
    private static final Logger LOGGER = LogManager.getLogger(FormulaMovementPublicationHooks.class);
    private static final Map<String, List<String>> GROUPS = new ConcurrentHashMap<>();
    private static final Set<String> FAILURES = ConcurrentHashMap.newKeySet();

    private FormulaMovementPublicationHooks() {}

    static void register(String group, List<String> keys) { GROUPS.put(group, List.copyOf(keys)); }

    public static boolean active(Object spell, Object caster, String group) {
        var keys = GROUPS.get(group);
        if (keys == null || spell == null || caster == null) return false;
        for (String key : keys) {
            var definition = FormulaNumericHooks.runtimeDefinition(key, spell);
            if (definition != null && FormulaRuntimeDispatcher.isActive(definition.dispatchKey(), caster)) return true;
        }
        return false;
    }

    public static boolean representable(double x, double y, double z) {
        return Float.isFinite((float) x) && Float.isFinite((float) y) && Float.isFinite((float) z);
    }

    public static void invalid(String group) {
        if (FAILURES.add(group)) LOGGER.warn("[SpellFormula] {} 运动变化超出原生浮点冲量数据范围，保留原生数据包；本次服务端运动没有被重新计算", group);
    }
}
