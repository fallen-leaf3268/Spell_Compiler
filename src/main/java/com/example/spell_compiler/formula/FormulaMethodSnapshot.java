package com.example.spell_compiler.formula;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class FormulaMethodSnapshot {
    private final MethodNode method;
    private final List<AbstractInsnNode> instructions;
    private final Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
    private String fingerprint;

    FormulaMethodSnapshot(MethodNode method) {
        this.method = method;
        instructions = List.copyOf(FormulaAutomaticInjector.executable(method));
        for (int index = 0; index < instructions.size(); index++) positions.put(instructions.get(index), index);
    }

    List<AbstractInsnNode> instructions() {
        return instructions;
    }

    int indexOf(AbstractInsnNode instruction) {
        return positions.getOrDefault(instruction, -1);
    }

    String fingerprint() {
        if (fingerprint == null) fingerprint = FormulaAutomaticInjector.fingerprint(method);
        return fingerprint;
    }
}
