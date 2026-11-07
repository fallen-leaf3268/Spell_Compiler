package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FormulaInputCapture {
    private record Capture(MethodNode method, AbstractInsnNode producer, Type type, int slot, boolean original) {}
    private record Loaded(Type type, int slot) {}

    static final class Plan {
        private final List<Capture> captures;
        private final Map<FormulaAutomaticSites.Site, List<Loaded>> loads;

        private Plan(List<Capture> captures, Map<FormulaAutomaticSites.Site, List<Loaded>> loads) {
            this.captures = List.copyOf(captures);
            this.loads = Map.copyOf(loads);
        }

        boolean empty() { return loads.isEmpty(); }

        void emit(InsnList instructions, FormulaAutomaticSites.Site site) {
            List<Loaded> inputs = loads.get(site);
            if (inputs == null) throw new IllegalArgumentException("未准备公式输入：" + site.definition().dispatchKey());
            instructions.add(new LdcInsnNode(inputs.size()));
            instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            for (int index = 0; index < inputs.size(); index++) {
                Loaded input = inputs.get(index);
                instructions.add(new InsnNode(Opcodes.DUP));
                instructions.add(new LdcInsnNode(index));
                instructions.add(new VarInsnNode(input.type().getOpcode(Opcodes.ILOAD), input.slot()));
                String box = switch (input.type().getSort()) {
                    case Type.INT -> "java/lang/Integer";
                    case Type.LONG -> "java/lang/Long";
                    case Type.FLOAT -> "java/lang/Float";
                    case Type.DOUBLE -> "java/lang/Double";
                    default -> throw new IllegalArgumentException("公式输入不是数值");
                };
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, box, "valueOf",
                        '(' + input.type().getDescriptor() + ")L" + box + ';', false));
                instructions.add(new InsnNode(Opcodes.AASTORE));
            }
        }

        void apply(Map<AbstractInsnNode, AbstractInsnNode> modifiedResults) {
            Map<MethodNode, InsnList> initializers = new IdentityHashMap<>();
            for (Capture capture : captures) {
                var initializer = initializers.computeIfAbsent(capture.method(), ignored -> new InsnList());
                initializer.add(new InsnNode(switch (capture.type().getSort()) {
                    case Type.INT -> Opcodes.ICONST_0;
                    case Type.LONG -> Opcodes.LCONST_0;
                    case Type.FLOAT -> Opcodes.FCONST_0;
                    case Type.DOUBLE -> Opcodes.DCONST_0;
                    default -> throw new IllegalArgumentException("公式输入不是数值");
                }));
                initializer.add(new VarInsnNode(capture.type().getOpcode(Opcodes.ISTORE), capture.slot()));
                InsnList store = new InsnList();
                store.add(new InsnNode(capture.type().getSize() == 2 ? Opcodes.DUP2 : Opcodes.DUP));
                store.add(new VarInsnNode(capture.type().getOpcode(Opcodes.ISTORE), capture.slot()));
                var anchor = capture.original() ? capture.producer()
                        : modifiedResults.getOrDefault(capture.producer(), capture.producer());
                capture.method().instructions.insert(anchor, store);
            }
            initializers.forEach((method, instructions) -> method.instructions.insert(instructions));
        }
    }

    private FormulaInputCapture() {}

    static Plan prepare(FormulaClassRepository repository, String owner, List<MethodNode> methods,
                        List<FormulaAutomaticSites.Site> sites) {
        List<Capture> captures = new ArrayList<>();
        Map<FormulaAutomaticSites.Site, List<Loaded>> loads = new LinkedHashMap<>();
        Map<MethodNode, Map<String, Loaded>> reused = new IdentityHashMap<>();
        Map<MethodNode, List<AbstractInsnNode>> instructions = new IdentityHashMap<>();
        for (var site : sites) {
            if (site.inputs().isEmpty()) continue;
            MethodNode method = methods.stream().filter(value -> value.name.equals(site.methodName())
                    && value.desc.equals(site.methodDescriptor())).findFirst().orElseThrow();
            var executable = instructions.computeIfAbsent(method, FormulaAutomaticInjector::executable);
            if (site.producerIndex() < 0 || site.producerIndex() >= executable.size())
                throw new IllegalArgumentException("输入捕获的消费位置不存在");
            AbstractInsnNode sink = executable.get(site.producerIndex());
            var graph = FormulaDataFlow.analyze(repository, owner, method);
            if (!graph.successful()) throw new IllegalArgumentException("输入捕获无法验证原数据流：" + graph.diagnostics());
            List<Loaded> values = new ArrayList<>();
            for (var input : site.inputs()) {
                Type type = input.type();
                if (input.localSlot() >= 0) {
                    var frame = graph.frameBefore(sink);
                    if (frame == null || input.localSlot() >= frame.locals().size()
                            || !type.equals(frame.locals().get(input.localSlot()).type()))
                        throw new IllegalArgumentException("捕获局部变量类型不符：" + input.name());
                    values.add(new Loaded(type, input.localSlot()));
                    continue;
                }
                if (input.producerIndex() >= executable.size()) throw new IllegalArgumentException("捕获源位置不存在：" + input.name());
                AbstractInsnNode producer = executable.get(input.producerIndex());
                boolean original = producer == sink && site.argumentIndex() < 0;
                var operation = graph.instruction(producer);
                int opcode = producer.getOpcode();
                if (operation == null || operation.results().size() != 1 || !type.equals(operation.results().getFirst().type())
                        || opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE || opcode >= Opcodes.DUP && opcode <= Opcodes.SWAP
                        || opcode == Opcodes.IINC || !original && !graph.resultDominates(producer, sink))
                    throw new IllegalArgumentException("捕获源未在所有消费路径先产生唯一数值：" + input.name());
                String key = input.producerIndex() + ":" + input.descriptor() + ':' + original;
                var known = reused.computeIfAbsent(method, ignored -> new LinkedHashMap<>());
                Loaded loaded = known.get(key);
                if (loaded == null) {
                    loaded = new Loaded(type, method.maxLocals);
                    method.maxLocals += type.getSize();
                    known.put(key, loaded);
                    captures.add(new Capture(method, producer, type, loaded.slot(), original));
                }
                values.add(loaded);
            }
            loads.put(site, List.copyOf(values));
        }
        return new Plan(captures, loads);
    }
}
