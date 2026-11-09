package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

final class FormulaTooltipFormatting {
    private static final String FORMAT = "com/example/spell_compiler/formula/FormulaNumberFormatting";
    private static final String UTILS = "io/redspace/ironsspellbooks/api/util/Utils";
    private static final String INFO = "(ILnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;";
    private static final Set<String> DISCRETE = Set.of("hits_dodged", "echoing_hits", "max_victims", "slowness_effect", "dimensions");

    private FormulaTooltipFormatting() {}

    static void apply(ClassNode target, FormulaClassRepository repository) {
        var root = target.methods.stream().filter(method -> method.name.equals("getUniqueInfo") && method.desc.equals(INFO)).findFirst().orElse(null);
        if (root == null) return;
        var copy = new ClassNode();
        target.accept(copy);
        var queue = new ArrayDeque<MethodNode>();
        queue.add(copy.methods.get(target.methods.indexOf(root)));
        Set<MethodNode> visited = new HashSet<>();
        boolean changed = false;
        while (!queue.isEmpty()) {
            var method = queue.removeFirst();
            if (!visited.add(method)) continue;
            var flow = FormulaDataFlow.analyze(repository, copy.name, method);
            if (!flow.successful()) continue;
            Set<FormulaDataFlow.Node> values = new HashSet<>();
            if (!method.name.equals("getUniqueInfo")) {
                for (var instruction : flow.instructions()) {
                    if (instruction.instruction().getOpcode() == Opcodes.ARETURN)
                        for (var value : instruction.operands()) values.addAll(value.sources());
                }
            } else {
                for (var instruction : flow.instructions()) {
                    if (!(instruction.instruction() instanceof MethodInsnNode call) || !call.owner.equals("net/minecraft/network/chat/Component")
                            || !(call.name.equals("translatable") || call.name.equals("m_237110_"))) continue;
                    var args = flow.arguments(call);
                    if (args.size() != 2) continue;
                    var label = flow.origin(args.get(0));
                    if (label == null || !(label.instruction() instanceof LdcInsnNode literal)
                            || !(literal.cst instanceof String key) || !continuous(key)) continue;
                    var array = flow.origin(args.get(1));
                    if (array == null) continue;
                    for (var write : flow.instructions())
                        if (write.instruction().getOpcode() == Opcodes.AASTORE && write.operands().size() == 3
                                && array.equals(flow.origin(write.operands().get(0))) && flow.dominates(write.instruction(), call))
                            values.addAll(write.operands().get(2).sources());
                }
            }
            var pending = new ArrayDeque<>(values);
            Set<FormulaDataFlow.Node> seen = new HashSet<>();
            while (!pending.isEmpty()) {
                var node = pending.removeFirst();
                if (!seen.add(node)) continue;
                var instruction = node.instruction();
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals(UTILS) && (call.name.equals("stringTruncation") && call.desc.equals("(DI)Ljava/lang/String;")
                            || call.name.equals("timeFromTicks") && call.desc.equals("(FI)Ljava/lang/String;"))) {
                        call.owner = FORMAT;
                        if (call.name.equals("stringTruncation")) call.name = "tooltip";
                        changed = true;
                        continue;
                    }
                    if ((call.owner.equals("java/lang/Float") && call.desc.equals("(F)Ljava/lang/Float;")
                            || call.owner.equals("java/lang/Double") && call.desc.equals("(D)Ljava/lang/Double;")) && call.name.equals("valueOf")) {
                        if (!values.contains(node)) continue;
                        call.owner = FORMAT;
                        call.name = "format";
                        call.desc = '(' + Type.getArgumentTypes(call.desc)[0].getDescriptor() + ")Ljava/lang/String;";
                        changed = true;
                        continue;
                    }
                    if (call.owner.equals("java/lang/Integer") && call.name.equals("valueOf") && call.desc.equals("(I)Ljava/lang/Integer;")) {
                        var args = flow.arguments(call);
                        var input = args.size() == 1 ? flow.origin(args.get(0)) : null;
                        if (input != null && input.instruction() != null && previous(call) == input.instruction()
                                && (input.instruction().getOpcode() == Opcodes.F2I || input.instruction().getOpcode() == Opcodes.D2I)) {
                            call.owner = FORMAT;
                            call.name = "format";
                            call.desc = input.instruction().getOpcode() == Opcodes.F2I ? "(F)Ljava/lang/String;" : "(D)Ljava/lang/String;";
                            method.instructions.remove(input.instruction());
                            changed = true;
                        }
                        continue;
                    }
                    if (call.owner.equals(copy.name) && Type.getReturnType(call.desc).equals(Type.getType(String.class))) {
                        var metadata = new JsonObject();
                        metadata.addProperty("declaringClass", copy.name);
                        metadata.addProperty("name", call.name);
                        metadata.addProperty("descriptor", call.desc);
                        if (FormulaDisplayControls.onlyDescription(repository, copy.name, metadata))
                            copy.methods.stream().filter(helper -> helper.name.equals(call.name) && helper.desc.equals(call.desc)).findFirst().ifPresent(queue::add);
                    }
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic
                        && dynamic.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")) {
                    var types = Type.getArgumentTypes(dynamic.desc);
                    if (java.util.Arrays.stream(types).anyMatch(type -> type.getSort() == Type.FLOAT || type.getSort() == Type.DOUBLE)) {
                        var replacement = new InsnList();
                        int[] slots = new int[types.length];
                        for (int i = types.length - 1; i >= 0; i--) {
                            slots[i] = method.maxLocals;
                            method.maxLocals += types[i].getSize();
                            replacement.add(new VarInsnNode(types[i].getOpcode(Opcodes.ISTORE), slots[i]));
                        }
                        for (int i = 0; i < types.length; i++) {
                            replacement.add(new VarInsnNode(types[i].getOpcode(Opcodes.ILOAD), slots[i]));
                            if (types[i].getSort() == Type.FLOAT || types[i].getSort() == Type.DOUBLE) {
                                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FORMAT, "format",
                                        '(' + types[i].getDescriptor() + ")Ljava/lang/String;", false));
                                types[i] = Type.getType(String.class);
                            }
                        }
                        method.instructions.insertBefore(dynamic, replacement);
                        dynamic.desc = Type.getMethodDescriptor(Type.getReturnType(dynamic.desc), types);
                        changed = true;
                    }
                }
                if (instruction != null && instruction.getOpcode() == Opcodes.ANEWARRAY)
                    for (var write : flow.instructions())
                        if (write.instruction().getOpcode() == Opcodes.AASTORE && write.operands().size() == 3
                                && node.equals(flow.origin(write.operands().get(0))))
                            pending.addAll(write.operands().get(2).sources());
                if (node.type() != null && node.type().getSort() >= Type.BOOLEAN && node.type().getSort() <= Type.DOUBLE) continue;
                for (var input : flow.dependencies(node)) pending.addAll(input.sources());
            }
        }
        if (!changed) return;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String first, String second) {
                return repository.commonSuperClass(first, second);
            }
        };
        copy.accept(writer);
        var framed = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(framed, ClassReader.EXPAND_FRAMES);
        target.methods = framed.methods;
    }

    private static boolean continuous(String key) {
        if (key.startsWith("attribute.modifier.")) return true;
        if (!key.startsWith("ui.irons_spellbooks.")) return false;
        String name = key.substring("ui.irons_spellbooks.".length());
        return !name.endsWith("_count") && !name.endsWith("_level") && !name.endsWith("_ticks") && !DISCRETE.contains(name);
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}
