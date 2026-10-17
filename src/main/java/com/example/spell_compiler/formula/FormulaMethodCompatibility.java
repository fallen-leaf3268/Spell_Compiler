package com.example.spell_compiler.formula;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class FormulaMethodCompatibility {
    private record Call(int opcode, String owner, String name, String descriptor, boolean ownerInterface) {
        String signature() {
            return owner.replace('/', '.') + '#' + name + descriptor + " [opcode=" + opcode + ']';
        }
    }

    private static final Map<String, Map<Call, Integer>> ORIGINALS = new ConcurrentHashMap<>();
    private static final Map<String, String> CONFLICTS = new ConcurrentHashMap<>();
    private static final Map<String, ClassNode> TRANSFORMED = new ConcurrentHashMap<>();

    private FormulaMethodCompatibility() {}

    static void recordOriginal(String owner, MethodNode method) {
        String key = key(owner, method.name + method.desc);
        ORIGINALS.put(key, calls(method));
        CONFLICTS.remove(key);
        TRANSFORMED.remove(key);
    }

    static void observeTransformed(ClassNode owner, Set<String> methodKeys) {
        methodKeys.forEach(methodKey -> TRANSFORMED.put(key(owner.name, methodKey), owner));
    }

    static void inspect(String owner, String methodKey, MethodNode method) {
        String key = key(owner, methodKey);
        Map<Call, Integer> original = ORIGINALS.get(key);
        if (original == null) return;
        String missing;
        if (method == null) {
            missing = "原方法已被移除";
        } else {
            Map<Call, Integer> actual = calls(method);
            missing = original.entrySet().stream()
                    .filter(entry -> actual.getOrDefault(entry.getKey(), 0) < entry.getValue())
                    .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(Call::signature)))
                    .map(entry -> entry.getKey().signature() + "（原有 " + entry.getValue()
                            + " 次，现有 " + actual.getOrDefault(entry.getKey(), 0) + " 次）")
                    .collect(Collectors.joining("；"));
        }
        if (missing.isEmpty()) {
            CONFLICTS.remove(key);
        } else {
            CONFLICTS.put(key, "检测到数值调用点被替换或移除，公式覆盖可能绕过第三方修改；已拒绝该公式覆盖，保留原生方法："
                    + key + "；缺失调用：" + missing);
        }
    }

    public static void requireCompatible(String owner, String methodKey) {
        String key = key(owner, methodKey);
        ClassNode transformed = TRANSFORMED.get(key);
        if (transformed != null) {
            MethodNode method = transformed.methods.stream()
                    .filter(value -> (value.name + value.desc).equals(methodKey)).findFirst().orElse(null);
            inspect(owner, methodKey, method);
        }
        String conflict = CONFLICTS.get(key);
        if (conflict != null) throw new IllegalArgumentException(conflict);
    }

    private static Map<Call, Integer> calls(MethodNode method) {
        Map<Call, Integer> calls = new LinkedHashMap<>();
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            int sort = Type.getReturnType(call.desc).getSort();
            if (sort != Type.INT && sort != Type.LONG && sort != Type.FLOAT && sort != Type.DOUBLE) continue;
            var key = new Call(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
            calls.merge(key, 1, Integer::sum);
        }
        return Map.copyOf(calls);
    }

    private static String key(String owner, String methodKey) {
        return owner.replace('/', '.') + '#' + methodKey;
    }
}
