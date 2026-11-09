package com.example.spell_compiler.formula;

import org.objectweb.asm.Type;

import java.util.Objects;
import java.util.Set;

public record FormulaCapturedInput(String name, String descriptor, int producerIndex, int localSlot,
                                   boolean nativePower, boolean powerUnknown) {
    public FormulaCapturedInput {
        Objects.requireNonNull(name);
        Objects.requireNonNull(descriptor);
        if (!name.matches("[a-z][a-z0-9_]*") || !Set.of("I", "J", "F", "D").contains(descriptor)
                || (producerIndex >= 0) == (localSlot >= 0))
            throw new IllegalArgumentException("无效的公式输入捕获计划：" + name);
    }

    public FormulaCapturedInput(String name, String descriptor, int producerIndex, int localSlot) {
        this(name, descriptor, producerIndex, localSlot, false, false);
    }

    public FormulaCapturedInput(String name, String descriptor, int producerIndex, int localSlot, boolean nativePower) {
        this(name, descriptor, producerIndex, localSlot, nativePower, false);
    }

    public Type type() { return Type.getType(descriptor); }
}
