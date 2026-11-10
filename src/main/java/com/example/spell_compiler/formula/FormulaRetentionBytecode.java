package com.example.spell_compiler.formula;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

final class FormulaRetentionBytecode {
    private FormulaRetentionBytecode() {}

    static ClassNode analysisCopy(ClassNode target) {
        if (target == null) return null;
        try {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            target.accept(writer);
            ClassNode copy = new ClassNode();
            new ClassReader(writer.toByteArray()).accept(copy, ClassReader.SKIP_FRAMES);
            return copy;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return null;
        }
    }
}
