package com.example.spell_compiler.formula;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FormulaEffectCallbackSources {
    record Source(FormulaExecutionGraph.MethodId method, int hostSlot, String holderDescriptor) {}
    private record Work(FormulaExecutionGraph.MethodId method, int hostSlot) {}
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";

    private FormulaEffectCallbackSources() {}

    static List<Source> scan(FormulaClassRepository repository, FormulaExecutionGraph.Analysis graph,
                             FormulaExecutionGraph.EffectLink effect) {
        if (!effect.ownershipComplete() || !effect.spellClasses().equals(Set.of(graph.spellClass()))) return List.of();
        var registry = repository.classNode(effect.registryOwner());
        if (registry == null || (registry.access & Opcodes.ACC_PUBLIC) == 0) return List.of();
        var field = registry.fields.stream().filter(value -> value.name.equals(effect.registryField())
                && (value.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) == (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC))
                .findFirst().orElse(null);
        if (field == null || Type.getType(field.desc).getSort() != Type.OBJECT) return List.of();
        String effectClass = effect.effectClass().replace('.', '/');
        var incoming = references(repository, effectClass);
        var pending = new ArrayDeque<Work>();
        for (var edge : graph.edges()) {
            if (edge.kind() != FormulaExecutionGraph.EdgeKind.EFFECT_CALLBACK
                    || !effect.applications().contains(edge.from()) || !edge.to().owner().equals(effectClass)
                    || !FormulaMinecraftNames.method(edge.to().name()).equals("applyEffectTick")
                    || !edge.to().descriptor().equals("(L" + LIVING + ";I)V")) continue;
            pending.add(new Work(edge.to(), 1));
        }
        for (var event : effect.eventMethods()) {
            var resolved = repository.resolveMethod(event.owner(), event.name(), event.descriptor());
            if (resolved == null) continue;
            var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
            if (!flow.successful()) continue;
            for (var operation : flow.instructions()) {
                if (!(operation.instruction() instanceof MethodInsnNode read) || !flow.reachable(read)
                        || !FormulaMinecraftNames.method(read.name).equals("getEffect")
                        || !read.desc.equals("(Lnet/minecraft/world/effect/MobEffect;)Lnet/minecraft/world/effect/MobEffectInstance;")) continue;
                var holder = flow.registryOrigin(flow.arguments(read).get(0));
                var host = flow.registryOrigin(flow.receiver(read));
                if (host == null || holder == null || !(holder.instruction() instanceof FieldInsnNode reference)
                        || reference.getOpcode() != Opcodes.GETSTATIC || !reference.owner.equals(effect.registryOwner())
                        || !reference.name.equals(effect.registryField())) continue;
                for (var use : flow.instructions()) {
                    if (!(use.instruction() instanceof MethodInsnNode call) || !flow.reachable(call)
                            || !flow.resultDominates(read, call)) continue;
                    var target = repository.resolveInvocation(event.owner(), call, null);
                    if (!target.complete() || target.target() == null) continue;
                    var implementation = target.target();
                    var id = new FormulaExecutionGraph.MethodId(implementation.owner().name,
                            implementation.method().name, implementation.method().desc);
                    if (!privateHelper(id, implementation.method(), effectClass, incoming)) continue;
                    var arguments = flow.arguments(call);
                    if (arguments.stream().noneMatch(value -> depends(flow, value, read))) continue;
                    int slot = (implementation.method().access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                    Type[] types = Type.getArgumentTypes(implementation.method().desc);
                    for (int index = 0; index < arguments.size(); index++) {
                        if (types[index].getSort() == Type.OBJECT && host.equals(flow.registryOrigin(arguments.get(index))))
                            pending.add(new Work(id, slot));
                        slot += types[index].getSize();
                    }
                }
            }
        }
        Set<Work> visited = new LinkedHashSet<>();
        var candidates = new LinkedHashMap<FormulaExecutionGraph.MethodId, Set<Integer>>();
        while (!pending.isEmpty()) {
            Work work = pending.removeFirst();
            if (!visited.add(work)) continue;
            var resolved = repository.resolveMethod(work.method().owner(), work.method().name(), work.method().descriptor());
            if (resolved == null || !FormulaAutomaticInjector.contextIntact(resolved.method(), work.hostSlot())) continue;
            var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
            if (!flow.successful()) continue;
            candidates.computeIfAbsent(work.method(), ignored -> new LinkedHashSet<>()).add(work.hostSlot());
            for (var operation : flow.instructions()) {
                if (!(operation.instruction() instanceof MethodInsnNode call) || !flow.reachable(call)) continue;
                var target = repository.resolveInvocation(work.method().owner(), call, null);
                if (!target.complete() || target.target() == null) continue;
                var implementation = target.target();
                var id = new FormulaExecutionGraph.MethodId(implementation.owner().name,
                        implementation.method().name, implementation.method().desc);
                if (!privateHelper(id, implementation.method(), effectClass, incoming)) continue;
                propagate(pending, id, implementation.method(), flow.arguments(call), flow, work.hostSlot());
            }
            for (var callback : FormulaExecutionGraph.index(repository).callbacks(work.method())) {
                var target = callback.implementation();
                var implementation = repository.resolveMethod(target.owner(), target.name(), target.descriptor());
                if (implementation == null || !privateHelper(target, implementation.method(), effectClass, incoming)) continue;
                propagate(pending, target, implementation.method(), callback.capturedArguments(), flow, work.hostSlot());
            }
        }
        var result = new ArrayList<Source>();
        candidates.forEach((method, slots) -> {
            if (slots.size() == 1) result.add(new Source(method, slots.iterator().next(), field.desc));
        });
        return List.copyOf(result);
    }

    private static void propagate(ArrayDeque<Work> pending, FormulaExecutionGraph.MethodId target, MethodNode method,
                                  List<FormulaDataFlow.Value> arguments, FormulaDataFlow.Analysis flow, int hostSlot) {
        Type[] types = Type.getArgumentTypes(method.desc);
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int index = 0; index < Math.min(arguments.size(), types.length); index++) {
            if (types[index].getSort() == Type.OBJECT && flow.parameter(arguments.get(index), hostSlot))
                pending.add(new Work(target, slot));
            slot += types[index].getSize();
        }
    }

    private static boolean depends(FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value, MethodInsnNode source) {
        var pending = new ArrayDeque<>(value.sources());
        Set<FormulaDataFlow.Node> seen = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (!seen.add(node)) continue;
            if (node.instruction() == source) return true;
            for (var input : flow.dependencies(node)) pending.addAll(input.sources());
        }
        return false;
    }

    private static boolean privateHelper(FormulaExecutionGraph.MethodId method, MethodNode node, String effectClass,
                                          Map<FormulaExecutionGraph.MethodId, Integer> references) {
        return (node.access & Opcodes.ACC_PRIVATE) != 0 && !node.name.startsWith("<")
                && (method.owner().equals(effectClass) || method.owner().startsWith(effectClass + '$'))
                && references.getOrDefault(method, 0) == 1;
    }

    private static Map<FormulaExecutionGraph.MethodId, Integer> references(FormulaClassRepository repository, String effectClass) {
        Map<FormulaExecutionGraph.MethodId, Integer> result = new LinkedHashMap<>();
        for (var source : repository.sourceClasses()) {
            if (!source.name.equals(effectClass) && !source.name.startsWith(effectClass + '$')) continue;
            for (var method : source.methods) for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call)
                    result.merge(new FormulaExecutionGraph.MethodId(call.owner, call.name, call.desc), 1, Integer::sum);
                else if (instruction instanceof InvokeDynamicInsnNode dynamic)
                    for (Object value : dynamic.bsmArgs) if (value instanceof Handle handle)
                        result.merge(new FormulaExecutionGraph.MethodId(handle.getOwner(), handle.getName(), handle.getDesc()), 1, Integer::sum);
            }
        }
        return result;
    }

    static boolean hostParameter(MethodNode method, int hostSlot) {
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type type : Type.getArgumentTypes(method.desc)) {
            if (slot == hostSlot) return type.getSort() == Type.OBJECT;
            slot += type.getSize();
        }
        return false;
    }
}
