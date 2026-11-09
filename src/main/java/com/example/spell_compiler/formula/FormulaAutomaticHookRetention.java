package com.example.spell_compiler.formula;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;
import java.util.function.Consumer;

final class FormulaAutomaticHookRetention {
    private static final Set<String> HOOK_OWNERS = Set.of(
            "com/example/spell_compiler/formula/FormulaNumericHooks",
            "com/example/spell_compiler/formula/FormulaEntityContext",
            "com/example/spell_compiler/formula/FormulaCastContext");
    private static final Set<String> HOOK_METHODS = Set.of("overrideInt", "overrideFloat", "overrideDouble");

    private FormulaAutomaticHookRetention() {}

    static void pruneMissing(String owner, ClassNode target, Set<String> installed, Consumer<String> reportMissing) {
        Set<String> retained = new java.util.HashSet<>();
        for (MethodNode method : target.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !HOOK_OWNERS.contains(call.owner)
                        || !HOOK_METHODS.contains(call.name)) continue;
                int distance = 0;
                for (var previous = call.getPrevious(); previous != null && distance < 8; previous = previous.getPrevious()) {
                    if (previous.getOpcode() < 0) continue;
                    distance++;
                    if (previous instanceof MethodInsnNode) break;
                    if (previous instanceof LdcInsnNode constant && constant.cst instanceof String key) {
                        var site = FormulaAutomaticSites.find(key);
                        if (site != null && site.owner().equals(owner) && site.methodName().equals(method.name)
                                && site.methodDescriptor().equals(method.desc)) retained.add(physicalPoint(site));
                        break;
                    }
                }
            }
        }
        installed.removeIf(key -> {
            var site = FormulaAutomaticSites.find(key);
            boolean missing = site != null && site.owner().equals(owner) && !retained.contains(physicalPoint(site));
            if (missing) reportMissing.accept(key);
            return missing;
        });
    }

    private static String physicalPoint(FormulaAutomaticSites.Site site) {
        return site.owner() + '#' + site.methodName() + site.methodDescriptor() + ':' + site.producerIndex() + ':'
                + site.methodFingerprint() + ':' + site.kind() + ':' + site.definition().valueType()
                + ':' + site.argumentIndex() + ':' + site.contextMode() + ':' + site.contextSlot();
    }
}
