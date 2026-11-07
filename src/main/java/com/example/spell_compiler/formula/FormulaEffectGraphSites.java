package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class FormulaEffectGraphSites {
    private static final String ALIAS = "effect_damage_ratio";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String HELPER = "(IL" + LIVING + ";)F";
    private record Parameter(int slot) {}
    private record Field(String owner, String name, String descriptor) {}
    private record Candidate(FormulaExecutionGraph.MethodId id, MethodNode method, AbstractInsnNode coefficient) {}
    private final FormulaClassRepository repository;
    private final String spell;
    private final Map<FormulaExecutionGraph.MethodId, Data> data = new HashMap<>();
    private final Map<String, Boolean> storedDamage = new HashMap<>();
    private final Set<FormulaExecutionGraph.MethodId> broken = new HashSet<>();
    private final List<String> diagnostics = new ArrayList<>();

    private FormulaEffectGraphSites(FormulaClassRepository repository, String spell) {
        this.repository = repository;
        this.spell = spell.replace('.', '/');
    }

    static FormulaAutomaticSites.Scan scan(FormulaClassRepository repository, String spell, Set<String> reserved) {
        return new FormulaEffectGraphSites(repository, spell).scan(reserved);
    }

    private FormulaAutomaticSites.Scan scan(Set<String> reserved) {
        var graph = FormulaExecutionGraph.index(repository).analyze(spell);
        List<Candidate> candidates = new ArrayList<>();
        for (var effect : graph.effects()) {
            if (effect.eventMethods().isEmpty()) continue;
            if (!effect.ownershipComplete()) {
                diagnostics.add("效果施加归属尚未完整证明，保留原生系数：" + effect.registryOwner() + '.' + effect.registryField());
                continue;
            }
            if (effect.spellClasses().size() != 1 || !effect.spellClasses().contains(spell)) {
                diagnostics.add("效果归属不唯一，保留原生系数：" + effect.registryOwner() + '.' + effect.registryField());
                continue;
            }
            Map<FormulaExecutionGraph.MethodId, Candidate> selected = new HashMap<>();
            Set<FormulaExecutionGraph.MethodId> verifiedHelpers = new HashSet<>();
            for (var event : effect.eventMethods()) {
                Data body = body(event);
                if (body == null) continue;
                for (var instruction : body.method.instructions) {
                    if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                            || !call.desc.equals(HELPER)) continue;
                    var resolved = repository.resolveMethod(call.owner, call.name, call.desc);
                    if (resolved == null) continue;
                    var id = id(resolved.owner().name, resolved.method());
                    if (!effect.relatedMethods().contains(id) || !amplifierInput(body, call, effect)) continue;
                    Data helper = body(id);
                    var coefficient = helper == null ? null : coefficient(helper);
                    if (coefficient == null) continue;
                    if (!verifiedHelpers.contains(id)) {
                        if (!allUsesKnown(id, effect)) continue;
                        verifiedHelpers.add(id);
                    }
                    if (manuallyCovered(body, call)) continue;
                    if (!damageProduct(body, call, effect.relatedMethods())) {
                        diagnostics.add("效果系数未证明进入实际伤害，保留原生值：" + id.key());
                        continue;
                    }
                    selected.putIfAbsent(id, new Candidate(id, resolved.method(), coefficient));
                }
            }
            candidates.addAll(selected.values());
        }
        if (candidates.isEmpty()) return result(List.of());
        if (candidates.size() != 1 || reserved.contains(ALIAS)) {
            diagnostics.add("效果伤害系数存在多个来源或已有同名入口，保留原生值：" + spell);
            return result(List.of());
        }
        Candidate candidate = candidates.get(0);
        if (!FormulaAutomaticInjector.contextIntact(candidate.method(), 1, -1, -1)) {
            diagnostics.add("效果辅助方法复用原施法者参数槽，保留原生系数：" + candidate.id().key());
            return result(List.of());
        }
        float value = constant(candidate.coefficient());
        var definition = new FormulaNumericSites.Definition(spell, ALIAS, value, FormulaNumericSites.ValueType.FLOAT,
                0, Math.sqrt(Float.MAX_VALUE));
        var site = new FormulaAutomaticSites.Site(definition, candidate.id().owner(), candidate.id().name(),
                candidate.id().descriptor(), FormulaAutomaticInjector.executable(candidate.method()).indexOf(candidate.coefficient()),
                FormulaAutomaticInjector.fingerprint(candidate.method()), 1, -1, -1,
                FormulaAutomaticSites.Kind.DAMAGE_RATIO, Float.toString(value));
        return result(List.of(site));
    }

    private FormulaAutomaticSites.Scan result(List<FormulaAutomaticSites.Site> sites) {
        return new FormulaAutomaticSites.Scan(spell.replace('/', '.'), sites, diagnostics);
    }

    private boolean amplifierInput(Data body, MethodInsnNode helper, FormulaExecutionGraph.EffectLink effect) {
        Object source = body.origin(body.argument(helper, 0));
        if (!(source instanceof MethodInsnNode amplifier) || !amplifier.owner.equals(INSTANCE)
                || !amplifier.name.equals("getAmplifier") || !amplifier.desc.equals("()I")) return false;
        Object instance = body.origin(body.receiver(amplifier));
        if (!(instance instanceof MethodInsnNode get) || !get.name.equals("getEffect")
                || !get.desc.equals("(Lnet/minecraft/core/Holder;)L" + INSTANCE + ";")
                || !repository.isSubtype(get.owner, LIVING)) return false;
        Object holder = body.origin(body.argument(get, 0));
        return holder instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(effect.registryOwner()) && field.name.equals(effect.registryField())
                && java.util.Objects.equals(body.origin(body.receiver(get)), body.origin(body.argument(helper, 1)));
    }

    private AbstractInsnNode coefficient(Data source) {
        MethodNode method = source.method;
        if ((method.access & Opcodes.ACC_STATIC) == 0 || !method.desc.equals(HELPER)) return null;
        var returns = FormulaAutomaticInjector.executable(method).stream()
                .filter(instruction -> instruction.getOpcode() == Opcodes.FRETURN).toList();
        if (returns.size() != 1) return null;
        var product = previous(returns.get(0));
        var coefficient = previous(product);
        if (product == null || product.getOpcode() != Opcodes.FMUL) return null;
        Float value = constant(coefficient);
        if (value == null || !Float.isFinite(value) || value < 0 || value > Math.sqrt(Float.MAX_VALUE)) return null;
        int uses = 0;
        for (var instruction : method.instructions)
            for (var input : source.inputs(instruction))
                if (source.origin(input) == coefficient) uses++;
        return uses == 1 ? coefficient : null;
    }

    private boolean allUsesKnown(FormulaExecutionGraph.MethodId helper, FormulaExecutionGraph.EffectLink effect) {
        for (var owner : repository.sourceClasses()) for (var method : owner.methods) {
            var caller = id(owner.name, method);
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && matchesHelper(call.owner, call.name, call.desc, helper)) {
                    if (!effect.eventMethods().contains(caller) && !spellTooltip(owner, method)) {
                        diagnostics.add("效果系数辅助方法另有未关联调用，保留原生值：" + caller.key());
                        return false;
                    }
                    if (effect.eventMethods().contains(caller)) {
                        Data source = body(caller);
                        if (source == null || !amplifierInput(source, call, effect)
                                || !damageProduct(source, call, effect.relatedMethods())
                                || !knownDamageUses(source, value -> source.mayDepend(value, node -> node == call),
                                effect.relatedMethods(), new HashSet<>(), 0)) {
                            diagnostics.add("效果辅助方法存在其他效果来源或未证明的伤害用途，保留原生值：" + caller.key());
                            return false;
                        }
                    }
                }
                if (instruction instanceof InvokeDynamicInsnNode dynamic) for (Object argument : dynamic.bsmArgs)
                    if (argument instanceof org.objectweb.asm.Handle handle
                            && matchesHelper(handle.getOwner(), handle.getName(), handle.getDesc(), helper)) return false;
            }
        }
        return true;
    }

    private boolean matchesHelper(String owner, String name, String descriptor, FormulaExecutionGraph.MethodId helper) {
        if (!name.equals(helper.name()) || !descriptor.equals(helper.descriptor())) return false;
        var resolved = repository.resolveMethod(owner, name, descriptor);
        return resolved != null && id(resolved.owner().name, resolved.method()).equals(helper);
    }

    private boolean spellTooltip(ClassNode owner, MethodNode method) {
        if (!owner.name.equals(spell) || !method.name.equals("getUniqueInfo")
                || !method.desc.equals("(IL" + LIVING + ";)Ljava/util/List;")
                || (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0
                || (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) return false;
        var parent = repository.resolveMethod(owner.superName, method.name, method.desc);
        return parent != null && (parent.method().access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                && (parent.method().access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) == 0
                && ((parent.method().access & Opcodes.ACC_PUBLIC) == 0 || (method.access & Opcodes.ACC_PUBLIC) != 0);
    }

    private boolean manuallyCovered(Data source, MethodInsnNode helper) {
        if (!repository.baselineCoverage()) return false;
        String owner = source.owner.replace('/', '.');
        if (!FormulaConsumerSites.targetClasses().contains(owner) && !FormulaSites.targetClasses().contains(owner)) return false;
        var original = repository.classNode(source.owner);
        var copy = new ClassNode();
        original.accept(copy);
        try {
            FormulaConsumerSites.apply(owner, copy);
            FormulaSites.apply(owner, copy);
            var method = copy.methods.stream().filter(value -> value.name.equals(source.method.name)
                    && value.desc.equals(source.method.desc)).findFirst().orElseThrow();
            Data transformed = new Data(repository, copy.name, method);
            var calls = new ArrayList<MethodInsnNode>();
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call && call.owner.equals(helper.owner)
                        && call.name.equals(helper.name) && call.desc.equals(helper.desc)) calls.add(call);
            if (calls.size() != 1) throw new IllegalArgumentException("人工入口后的辅助调用不唯一");
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && Set.of("com/example/spell_compiler/formula/FormulaConsumerHooks",
                        "com/example/spell_compiler/formula/FormulaSiteHooks").contains(call.owner)
                        && call.name.startsWith("override")) {
                    Type[] arguments = Type.getArgumentTypes(call.desc);
                    if (arguments.length > 0 && arguments[0].equals(Type.FLOAT_TYPE)
                            && transformed.depends(transformed.argument(call, 0), node -> node == calls.get(0))) {
                        diagnostics.add("效果辅助数值已在具体消费点覆盖，保留既有入口：" + source.owner + '#' + helper.name + helper.desc);
                        return true;
                    }
                }
            return false;
        } catch (AnalyzerException | RuntimeException failure) {
            diagnostics.add("无法核对具体效果辅助数值的人工覆盖，保留原生值：" + source.owner + '#'
                    + helper.name + helper.desc + ": " + failure.getMessage());
            return true;
        }
    }

    private boolean damageProduct(Data source, MethodInsnNode helper, Set<FormulaExecutionGraph.MethodId> related) {
        for (var instruction : source.method.instructions) if (instruction.getOpcode() == Opcodes.FMUL) {
            var inputs = source.inputs(instruction);
            if (inputs.size() != 2) continue;
            boolean forward = source.depends(inputs.get(0), value -> value == helper) && eventAmount(source, inputs.get(1));
            boolean reverse = source.depends(inputs.get(1), value -> value == helper) && eventAmount(source, inputs.get(0));
            if ((forward || reverse) && reachesDamage(source, value -> source.depends(value, node -> node == instruction),
                    related, new HashSet<>(), 0)) return true;
        }
        return false;
    }

    private boolean eventAmount(Data source, SourceValue value) {
        Object origin = source.origin(value);
        if (!(origin instanceof MethodInsnNode call) || !call.desc.equals("()F")
                || !Set.of("getAmount", "getNewDamage").contains(call.name)) return false;
        Type[] types = Type.getArgumentTypes(source.method.desc);
        return (source.method.access & Opcodes.ACC_STATIC) != 0 && types.length == 1
                && types[0].getSort() == Type.OBJECT && repository.isSubtype(types[0].getInternalName(), call.owner)
                && source.parameter(source.receiver(call), 0);
    }

    private boolean reachesDamage(Data source, Predicate<SourceValue> tainted,
                                  Set<FormulaExecutionGraph.MethodId> related, Set<String> visited, int depth) {
        if (depth > 8) return false;
        for (var instruction : source.method.instructions) if (instruction instanceof MethodInsnNode call) {
            Type[] arguments = Type.getArgumentTypes(call.desc);
            for (int index = 0; index < arguments.length; index++) {
                if (!arguments[index].equals(Type.FLOAT_TYPE) || !tainted.test(source.argument(call, index))) continue;
                if (call.name.equals("setDamage") && call.desc.equals("(F)V") && repository.isSubtype(call.owner, ENTITY)) {
                    String entity = publishedEntity(source, call);
                    if (entity != null && storedDamageConsumed(entity)) return true;
                }
                if (call.getOpcode() != Opcodes.INVOKESTATIC) continue;
                var resolved = repository.resolveMethod(call.owner, call.name, call.desc);
                if (resolved == null) continue;
                var id = id(resolved.owner().name, resolved.method());
                int slot = argumentSlot(call.desc, index);
                if (!related.contains(id) || !visited.add(id.key() + ':' + slot)) continue;
                Data callee = body(id);
                if (callee != null && reachesDamage(callee, value -> callee.depends(value, node -> node.equals(new Parameter(slot))),
                        related, visited, depth + 1)) return true;
            }
        }
        return false;
    }

    private String publishedEntity(Data source, MethodInsnNode setter) {
        Object receiver = source.origin(source.receiver(setter));
        if (!(receiver instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                || !repository.isSubtype(allocation.desc, ENTITY)) return null;
        for (var instruction : source.method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals("addFreshEntity")
                    && call.desc.equals("(L" + ENTITY + ";)Z")
                    && source.method.instructions.indexOf(call) > source.method.instructions.indexOf(setter)
                    && source.origin(source.argument(call, 0)) == allocation) {
                for (var later = setter.getNext(); later != call; later = later.getNext())
                    if (later instanceof MethodInsnNode overwrite && overwrite.name.equals("setDamage")
                            && overwrite.desc.equals("(F)V") && source.origin(source.receiver(overwrite)) == allocation) return null;
                return allocation.desc;
            }
        return null;
    }

    private boolean knownDamageUses(Data source, Predicate<SourceValue> tainted,
                                    Set<FormulaExecutionGraph.MethodId> related, Set<String> visited, int depth) {
        if (depth > 8) return false;
        boolean damage = false;
        for (var instruction : source.method.instructions) {
            if (source.inputs(instruction).stream().noneMatch(tainted)) continue;
            if (Data.copy(instruction) || Data.arithmetic(instruction.getOpcode())) continue;
            if (!(instruction instanceof MethodInsnNode call)) return false;
            Type[] arguments = Type.getArgumentTypes(call.desc);
            for (int index = 0; index < arguments.length; index++) {
                if (!tainted.test(source.argument(call, index))) continue;
                if (!arguments[index].equals(Type.FLOAT_TYPE)) return false;
                if (call.name.equals("setDamage") && call.desc.equals("(F)V") && repository.isSubtype(call.owner, ENTITY)) {
                    String entity = publishedEntity(source, call);
                    if (entity == null || !storedDamageConsumed(entity)) return false;
                    damage = true;
                    continue;
                }
                if (call.getOpcode() != Opcodes.INVOKESTATIC) return false;
                var resolved = repository.resolveMethod(call.owner, call.name, call.desc);
                if (resolved == null) return false;
                var id = id(resolved.owner().name, resolved.method());
                int slot = argumentSlot(call.desc, index);
                var path = new HashSet<>(visited);
                if (!related.contains(id) || !path.add(id.key() + ':' + slot)) return false;
                Data callee = body(id);
                if (callee == null || !knownDamageUses(callee, value -> callee.mayDepend(value, node -> node.equals(new Parameter(slot))),
                        related, path, depth + 1)) return false;
                damage = true;
            }
        }
        return damage;
    }

    private boolean storedDamageConsumed(String entity) {
        return storedDamage.computeIfAbsent(entity, this::analyzeStoredDamage);
    }

    private boolean analyzeStoredDamage(String entity) {
        var setter = repository.resolveMethod(entity, "setDamage", "(F)V");
        if (setter == null) return false;
        Data writes = body(id(setter.owner().name, setter.method()));
        if (writes == null) return false;
        Set<Field> fields = new HashSet<>();
        for (var instruction : setter.method().instructions)
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && field.desc.equals("F")
                    && writes.parameter(writes.input(field, 1), 0) && writes.parameter(writes.input(field, 0), 1)) fields.add(field(field));
        for (var instruction : setter.method().instructions)
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && field.desc.equals("F")
                    && writes.parameter(writes.input(field, 1), 0) && !writes.parameter(writes.input(field, 0), 1)) fields.remove(field(field));
        if (fields.isEmpty()) return false;
        for (var reachable : entityCallbacks(entity)) {
            Data source = body(reachable);
            if (source == null) continue;
            for (var instruction : source.method.instructions) if (instruction instanceof MethodInsnNode call) {
                Type[] arguments = Type.getArgumentTypes(call.desc);
                for (int index = 0; index < arguments.length; index++) {
                    if (!arguments[index].equals(Type.FLOAT_TYPE) || !source.depends(source.argument(call, index), node ->
                            node instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETFIELD && fields.contains(field(read))
                                    && source.parameter(source.receiver(read), 0))) continue;
                    var implementation = source.parameter(source.receiver(call), 0)
                            ? FormulaInterproceduralFlow.resolveCall(repository, source.owner, call, entity)
                            : repository.resolveInvocation(source.owner, call, null).target();
                    if (damage(call, index, implementation)) return true;
                    if (call.getOpcode() == Opcodes.INVOKESTATIC && parameterReachesHurt(call, index, new HashSet<>(), 0)) return true;
                }
            }
        }
        return false;
    }

    private Set<FormulaExecutionGraph.MethodId> entityCallbacks(String entity) {
        var pending = new ArrayDeque<FormulaExecutionGraph.MethodId>();
        for (var callback : Map.of("tick", "()V", "onHit", "(Lnet/minecraft/world/phys/HitResult;)V",
                "onHitEntity", "(Lnet/minecraft/world/phys/EntityHitResult;)V", "onHitBlock",
                "(Lnet/minecraft/world/phys/BlockHitResult;)V", "customServerAiStep", "()V").entrySet()) {
            var resolved = repository.resolveMethod(entity, callback.getKey(), callback.getValue());
            if (resolved != null) pending.add(id(resolved.owner().name, resolved.method()));
        }
        Set<FormulaExecutionGraph.MethodId> result = new LinkedHashSet<>();
        while (!pending.isEmpty() && result.size() < 128) {
            var selected = pending.removeFirst();
            if (selected.owner().startsWith("net/minecraft/") || !result.add(selected)) continue;
            Data source = body(selected);
            if (source == null) continue;
            for (var instruction : source.method.instructions)
                if (instruction instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC
                        && !call.name.equals("<init>") && repository.isSubtype(entity, call.owner)
                        && source.parameter(source.receiver(call), 0)) {
                    var resolved = repository.resolveInvocation(source.owner, call, entity).target();
                    if (resolved != null) pending.add(id(resolved.owner().name, resolved.method()));
                }
        }
        return result;
    }

    private boolean parameterReachesHurt(MethodInsnNode invocation, int argument, Set<String> visited, int depth) {
        if (depth > 5) return false;
        var resolved = repository.resolveMethod(invocation.owner, invocation.name, invocation.desc);
        if (resolved == null) return false;
        var id = id(resolved.owner().name, resolved.method());
        int slot = argumentSlot(invocation.desc, argument);
        if (!visited.add(id.key() + ':' + slot)) return false;
        Data source = body(id);
        if (source == null) return false;
        for (var instruction : source.method.instructions) if (instruction instanceof MethodInsnNode call) {
            Type[] arguments = Type.getArgumentTypes(call.desc);
            for (int index = 0; index < arguments.length; index++)
                if (arguments[index].equals(Type.FLOAT_TYPE)
                        && source.depends(source.argument(call, index), node -> node.equals(new Parameter(slot)))) {
                    if (damage(call, index, repository.resolveMethod(call.owner, call.name, call.desc))) return true;
                    if (call.getOpcode() == Opcodes.INVOKESTATIC && parameterReachesHurt(call, index, visited, depth + 1)) return true;
                }
        }
        return false;
    }

    private boolean damage(MethodInsnNode call, int argument, FormulaClassRepository.ResolvedMethod implementation) {
        return FormulaConsumptionModel.classify(repository, call, implementation).stream().anyMatch(value ->
                value.kind() == FormulaAutomaticSites.Kind.DAMAGE && value.proof() == FormulaConsumptionModel.Proof.TERMINAL
                        && value.argument() == argument);
    }

    private Field field(FieldInsnNode node) {
        var resolved = repository.resolveField(node.owner, node.name, node.desc);
        return new Field(resolved == null ? node.owner : resolved.owner().name, node.name, node.desc);
    }

    private Data body(FormulaExecutionGraph.MethodId id) {
        if (broken.contains(id)) return null;
        if (data.containsKey(id)) return data.get(id);
        var resolved = repository.resolveMethod(id.owner(), id.name(), id.descriptor());
        if (resolved == null || resolved.method().instructions.size() == 0) return null;
        try {
            Data result = new Data(repository, resolved.owner().name, resolved.method());
            data.put(id, result);
            return result;
        } catch (AnalyzerException | RuntimeException failure) {
            broken.add(id);
            diagnostics.add("效果数值流无法确认：" + id.key() + ": " + failure.getMessage());
            return null;
        }
    }

    private static FormulaExecutionGraph.MethodId id(String owner, MethodNode method) {
        return new FormulaExecutionGraph.MethodId(owner, method.name, method.desc);
    }

    private static int argumentSlot(String descriptor, int index) {
        int slot = 0;
        Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int prior = 0; prior < index; prior++) slot += arguments[prior].getSize();
        return slot;
    }

    private static AbstractInsnNode previous(AbstractInsnNode node) {
        if (node == null) return null;
        do node = node.getPrevious(); while (node != null && node.getOpcode() < 0);
        return node;
    }

    private static Float constant(AbstractInsnNode node) {
        if (node instanceof LdcInsnNode value && value.cst instanceof Float number) return number;
        if (node != null && node.getOpcode() >= Opcodes.FCONST_0 && node.getOpcode() <= Opcodes.FCONST_2)
            return (float) (node.getOpcode() - Opcodes.FCONST_0);
        return null;
    }

    private static final class Data {
        private final String owner;
        private final MethodNode method;
        private final FormulaDataFlow.Analysis flow;
        private Data(FormulaClassRepository repository, String owner, MethodNode method) throws AnalyzerException {
            this.owner = owner;
            this.method = method;
            flow = FormulaDataFlow.analyze(repository, owner, method);
            if (!flow.successful()) throw new AnalyzerException(null, flow.diagnostics().toString());
        }
        private SourceValue input(AbstractInsnNode instruction, int top) {
            var frame = flow.frameBefore(instruction);
            return frame == null || frame.stack().size() <= top ? null : frame.stack().get(frame.stack().size() - top - 1);
        }
        private SourceValue argument(MethodInsnNode call, int index) {
            var arguments = flow.arguments(call);
            return index < 0 || index >= arguments.size() ? null : arguments.get(index);
        }
        private SourceValue receiver(MethodInsnNode call) { return flow.receiver(call); }
        private SourceValue receiver(FieldInsnNode field) { return input(field, 0); }
        private boolean parameter(SourceValue value, int slot) { return flow.parameter(value, slot); }
        private Object origin(SourceValue value) {
            FormulaDataFlow.Node source = flow.origin(value);
            return source == null ? null : source.kind() == FormulaDataFlow.NodeKind.PARAMETER
                    ? new Parameter(source.parameterSlot()) : source.instruction() == null ? source : source.instruction();
        }
        private Set<Object> origins(SourceValue value) {
            Set<Object> result = new LinkedHashSet<>();
            for (var source : flow.origins(value).nodes()) result.add(source.kind() == FormulaDataFlow.NodeKind.PARAMETER
                    ? new Parameter(source.parameterSlot()) : source.instruction() == null ? source : source.instruction());
            return result;
        }
        private boolean depends(SourceValue value, Predicate<Object> target) { return depends(value, target, new HashSet<>()); }
        private boolean mayDepend(SourceValue value, Predicate<Object> target) {
            return mayDepend(value, target, new HashSet<>());
        }
        private boolean mayDepend(SourceValue value, Predicate<Object> target, Set<AbstractInsnNode> visited) {
            for (Object origin : origins(value)) {
                if (target.test(origin)) return true;
                if (!(origin instanceof AbstractInsnNode instruction) || !visited.add(instruction)
                        || !arithmetic(instruction.getOpcode())) continue;
                for (var input : inputs(instruction))
                    if (mayDepend(input, target, new HashSet<>(visited))) return true;
            }
            return false;
        }
        private boolean depends(SourceValue value, Predicate<Object> target, Set<AbstractInsnNode> visited) {
            Object origin = origin(value);
            if (origin == null) return false;
            if (target.test(origin)) return true;
            if (!(origin instanceof AbstractInsnNode node) || !visited.add(node)) return false;
            int opcode = node.getOpcode();
            if (!arithmetic(opcode)) return false;
            List<SourceValue> inputs = inputs(node);
            if (inputs.size() == 2 && (opcode == Opcodes.FMUL || opcode == Opcodes.DMUL || opcode == Opcodes.IMUL))
                for (var operand : inputs) {
                    Object source = origin(operand);
                    if (source instanceof LdcInsnNode literal && literal.cst instanceof Number number && number.doubleValue() == 0
                            || source instanceof InsnNode zero && Set.of(Opcodes.FCONST_0, Opcodes.DCONST_0, Opcodes.ICONST_0).contains(zero.getOpcode())) return false;
                }
            boolean first = !inputs.isEmpty() && depends(inputs.get(0), target, new HashSet<>(visited));
            boolean second = inputs.size() == 2 && depends(inputs.get(1), target, new HashSet<>(visited));
            if (Set.of(Opcodes.ISUB, Opcodes.FSUB, Opcodes.DSUB, Opcodes.LSUB).contains(opcode) && first && second) return false;
            return first || second;
        }
        private List<SourceValue> inputs(AbstractInsnNode instruction) {
            return List.copyOf(flow.operands(instruction));
        }
        private static boolean copy(AbstractInsnNode node) {
            return node.getOpcode() >= Opcodes.ILOAD && node.getOpcode() <= Opcodes.ALOAD
                    || node.getOpcode() >= Opcodes.ISTORE && node.getOpcode() <= Opcodes.ASTORE
                    || node.getOpcode() == Opcodes.CHECKCAST || node.getOpcode() == Opcodes.DUP;
        }
        private static boolean binary(int opcode) {
            return opcode >= Opcodes.IADD && opcode <= Opcodes.DREM || opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR;
        }
        private static boolean arithmetic(int opcode) {
            return binary(opcode) || opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG
                    || opcode >= Opcodes.I2L && opcode <= Opcodes.I2S;
        }
    }
}
