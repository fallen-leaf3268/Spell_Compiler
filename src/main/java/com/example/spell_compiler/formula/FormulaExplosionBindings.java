package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public final class FormulaExplosionBindings {
    public enum Mode { EXPLICIT, LEVEL_FACTORY }
    public enum SourceProof { NATIVE_SOURCE, ORIGIN_POSITION }
    public record Binding(FormulaNumericSites.Definition definition, String sourceEntityClass, String owner,
                          String methodName, String methodDescriptor, int instruction,
                          String methodFingerprint, Mode mode, SourceProof sourceProof) {
        public String physicalKey() { return owner + '#' + methodName + methodDescriptor + '@' + instruction + ':' + methodFingerprint + ':' + mode + ':' + sourceProof; }
    }
    public record Discovery(List<Binding> bindings, List<FormulaEntityFlow.Capture> captures, List<String> diagnostics) {
        public Discovery { bindings=List.copyOf(bindings);captures=List.copyOf(captures);diagnostics=List.copyOf(diagnostics); }
        public List<FormulaNumericSites.Definition> definitions(){return bindings.stream().map(Binding::definition).distinct().toList();}
    }
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String EXPLOSION = "net/minecraft/world/level/Explosion";
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaExplosionHooks";
    private static final Map<String, Discovery> REGISTERED = new LinkedHashMap<>();
    private static final Set<Binding> VERIFIED = new HashSet<>();
    private static volatile Map<String, FormulaNumericSites.Definition> definitions = Map.of();
    private static volatile Map<String, List<Binding>> points = Map.of();
    private static volatile Map<String, List<Binding>> expectedBindings = Map.of();
    private static volatile Set<Binding> verifiedBindings = Set.of();
    private static volatile Map<String, List<FormulaAutomaticSites.Site>> radiusControls = Map.of();
    private static volatile boolean explosionTargetVerified;

    private FormulaExplosionBindings() {}

    public static Discovery discover(FormulaClassRepository repository, String spellClass) {
        String spell = spellClass.replace('/', '.');
        List<String> diagnostics = new ArrayList<>();
        if (!nativeConsumer(repository)) return new Discovery(List.of(), List.of(), List.of("原生爆炸伤害消费契约不匹配"));
        Set<FormulaEntityFlow.Capture> captures = new LinkedHashSet<>(
                FormulaEntityFlow.entityEffectPublications(repository, spell).parentCaptures());
        for (var root : FormulaExecutionGraph.index(repository).synchronousExecutionHelpers(spell).roots()) {
            var resolved = repository.resolveMethod(root.owner(), root.name(), root.descriptor());
            if (resolved == null || !rootContext(resolved.method())) continue;
            MethodNode method = resolved.method();
            var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, method);
            if (!flow.successful()) continue;
            var snapshot = new FormulaMethodSnapshot(method);
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !spawn(call)
                        || !repository.isSubtype(call.owner, LEVEL)) continue;
                var arguments = flow.arguments(call);
                var origin = arguments.size() == 1 ? flow.origin(arguments.get(0)) : null;
                if (origin == null || !(origin.instruction() instanceof TypeInsnNode allocation)
                        || allocation.getOpcode() != Opcodes.NEW || !repository.isSubtype(allocation.desc, ENTITY)) continue;
                captures.add(new FormulaEntityFlow.Capture(resolved.owner().name, method.name, method.desc,
                        snapshot.indexOf(call), snapshot.fingerprint(), 3, 2, 0, allocation.desc,
                        ordinal(method, allocation, true), ordinal(method, call, false)));
            }
        }
        Set<String> sources = new LinkedHashSet<>();
        for (var capture : captures) if (capture.mode() == FormulaEntityFlow.CaptureMode.ENTITY)
            sources.add(capture.entityClassName().replace('.', '/'));
        var definition = new FormulaNumericSites.Definition(spell, "explosion_damage", 0f,
                FormulaNumericSites.ValueType.FLOAT, 0, Math.sqrt(Float.MAX_VALUE));
        List<Binding> bindings = new ArrayList<>();
        for (var id : FormulaExecutionGraph.analyze(repository, spell).methods()) {
            var resolved = repository.resolveMethod(id.owner(), id.name(), id.descriptor());
            if (resolved == null) continue;
            MethodNode method = resolved.method();
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || method.name.startsWith("<") || !FormulaAutomaticInjector.contextIntact(method, 0)) continue;
            var applicable = sources.stream().filter(source -> repository.isSubtype(source, resolved.owner().name)).toList();
            if (applicable.isEmpty()) continue;
            var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, method);
            if (!flow.successful()) continue;
            var snapshot = new FormulaMethodSnapshot(method);
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !flow.reachable(call)) continue;
                Mode mode = null;
                SourceProof proof = SourceProof.NATIVE_SOURCE;
                if (call.owner.equals(EXPLOSION) && call.name.equals("explode")
                        && call.desc.equals("()V") && call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    var receiver = flow.origin(flow.receiver(call));
                    if (receiver != null && receiver.instruction() instanceof TypeInsnNode allocation
                            && allocation.getOpcode() == Opcodes.NEW && allocation.desc.equals(EXPLOSION)
                            && explicitSource(repository, method, flow, allocation, spell) != null) {
                        mode = Mode.EXPLICIT;
                        proof = explicitSource(repository, method, flow, allocation, spell);
                    }
                } else if (Type.getReturnType(call.desc).getDescriptor().equals('L' + EXPLOSION + ';')
                        && repository.isSubtype(call.owner, LEVEL) && factorySource(repository, call)
                        && sourceArgument(flow, call, 0)) mode = Mode.LEVEL_FACTORY;
                if (mode != null && unreachableZeroRadius(repository, flow, method, call, mode, spell, snapshot)) {
                    diagnostics.add("排除没有同点可修改半径的零范围爆炸：" + resolved.owner().name + '#' + method.name + method.desc + '@' + snapshot.indexOf(call));
                    continue;
                }
                if (mode != null) for (String source : applicable)
                    bindings.add(new Binding(definition, source.replace('/', '.'), resolved.owner().name.replace('/', '.'),
                            method.name, method.desc, snapshot.indexOf(call), snapshot.fingerprint(), mode, proof));
            }
        }
        if (bindings.isEmpty()) return new Discovery(List.of(), List.of(), diagnostics);
        return new Discovery(bindings.stream().distinct().toList(), List.copyOf(captures), diagnostics);
    }

    private static SourceProof explicitSource(FormulaClassRepository repository, MethodNode method,
                                          FormulaDataFlow.Analysis flow, TypeInsnNode allocation, String spell) {
        int matches = 0;
        SourceProof proof = null;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(EXPLOSION) && call.name.equals("<init>")) {
            var receiver = flow.origin(flow.receiver(call));
            if (receiver != null && receiver.instruction() == allocation) {
                if (sourceArgument(flow, call, 0) || damageSourceArgument(repository, flow, call, spell)) proof = SourceProof.NATIVE_SOURCE;
                else if (anonymousOrigin(repository, flow, call)) proof = SourceProof.ORIGIN_POSITION;
                else return null;
                matches++;
            }
        }
        return matches == 1 ? proof : null;
    }

    private static boolean anonymousOrigin(FormulaClassRepository repository, FormulaDataFlow.Analysis flow, MethodInsnNode constructor) {
        var arguments = flow.arguments(constructor);
        Type[] types = Type.getArgumentTypes(constructor.desc);
        if (types.length < 7 || arguments.size() != types.length || !types[0].getDescriptor().equals('L' + LEVEL + ';')
                || !types[1].getDescriptor().equals('L' + ENTITY + ';')
                || !types[2].getDescriptor().equals("Lnet/minecraft/world/damagesource/DamageSource;")) return false;
        for (int index : new int[]{1, 2}) {
            var origin = flow.origin(arguments.get(index));
            if (origin == null || origin.instruction() == null || origin.instruction().getOpcode() != Opcodes.ACONST_NULL) return false;
        }
        var level = flow.origin(arguments.get(0));
        if (level == null) return false;
        boolean levelProven = false;
        if (level.instruction() instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && field.desc.equals('L' + LEVEL + ';')) {
            var access = flow.instruction(field).field();
            levelProven = access != null && flow.parameter(access.receiver(), 0);
        } else if (level.instruction() instanceof MethodInsnNode getter && getter.desc.equals("()L" + LEVEL + ';')
                && getter.name.equals("level") && flow.parameter(flow.receiver(getter), 0)) {
            var declared = repository.resolveMethod(getter.owner, getter.name, getter.desc);
            levelProven = declared != null && declared.owner().name.equals(ENTITY);
        }
        if (!levelProven) return false;
        String[] names = {"getX", "getY", "getZ"};
        for (int index = 0; index < 3; index++) {
            var origin = flow.origin(arguments.get(index + 4));
            if (origin == null || !(origin.instruction() instanceof MethodInsnNode getter) || !getter.desc.equals("()D")
                    || !getter.name.equals(names[index]) || !flow.parameter(flow.receiver(getter), 0)) return false;
            var declared = repository.resolveMethod(getter.owner, getter.name, getter.desc);
            if (declared == null || !declared.owner().name.equals(ENTITY)) return false;
        }
        return true;
    }

    private static boolean damageSourceArgument(FormulaClassRepository repository, FormulaDataFlow.Analysis flow,
                                                 MethodInsnNode constructor, String spell) {
        Type[] types = Type.getArgumentTypes(constructor.desc);
        var arguments = flow.arguments(constructor);
        for (int index = 0; index < types.length && index < arguments.size(); index++)
            if (types[index].getDescriptor().equals("Lnet/minecraft/world/damagesource/DamageSource;"))
                return damageFrom(repository, flow, arguments.get(index), 0, spell, new HashSet<>(), 0);
        return false;
    }

    private static boolean damageFrom(FormulaClassRepository repository, FormulaDataFlow.Analysis flow,
                                       FormulaDataFlow.Value value, int sourceSlot, String spell, Set<String> visited, int depth) {
        if (depth > 12) return false;
        var origin = flow.origin(value);
        if (origin == null) return false;
        if (origin.instruction() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                && repository.isSubtype(allocation.desc, "net/minecraft/world/damagesource/DamageSource")) {
            int matches = 0;
            for (var instruction : flow.instructions()) if (instruction.instruction() instanceof MethodInsnNode constructor
                    && constructor.name.equals("<init>") && constructor.owner.equals(allocation.desc)) {
                var receiver = flow.origin(flow.receiver(constructor));
                if (receiver == null || receiver.instruction() != allocation) continue;
                var arguments = flow.arguments(constructor);
                Type[] types = Type.getArgumentTypes(constructor.desc);
                int local = 1;
                boolean proven = false;
                for (int index = 0; index < types.length && index < arguments.size(); index++) {
                    if (flow.parameter(arguments.get(index), sourceSlot)
                            && constructorUses(repository, constructor.owner, constructor.desc, local, new HashSet<>(), 0)) proven = true;
                    local += types[index].getSize();
                }
                if (!proven) return false;
                matches++;
            }
            return matches == 1;
        }
        if (!(origin.instruction() instanceof MethodInsnNode call)) return false;
        String owner = call.owner;
        if (repository.isSubtype(owner, "io/redspace/ironsspellbooks/api/spells/AbstractSpell")) owner = spell.replace('.', '/');
        String key = owner + '#' + call.name + call.desc;
        if (!visited.add(key)) return false;
        var resolved = repository.resolveMethod(owner, call.name, call.desc);
        if (resolved == null) return false;
        var nested = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
        if (!nested.successful()) return false;
        List<Integer> slots = new ArrayList<>();
        int local = (resolved.method().access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        Type[] types = Type.getArgumentTypes(call.desc);
        var arguments = flow.arguments(call);
        for (int index = 0; index < types.length && index < arguments.size(); index++) {
            if (flow.parameter(arguments.get(index), sourceSlot)) slots.add(local);
            local += types[index].getSize();
        }
        int returns = 0;
        for (var instruction : resolved.method().instructions) if (instruction.getOpcode() == Opcodes.ARETURN && nested.reachable(instruction)) {
            var operands = nested.operands(instruction);
            if (operands.size() != 1) return false;
            boolean proven = false;
            if ((resolved.method().access & Opcodes.ACC_STATIC) == 0 && nested.parameter(operands.get(0), 0))
                proven = damageFrom(repository, flow, flow.receiver(call), sourceSlot, spell, new HashSet<>(visited), depth + 1);
            for (int slot : slots) if (damageFrom(repository, nested, operands.get(0), slot, spell, new HashSet<>(visited), depth + 1)) proven = true;
            if (!proven) return false;
            returns++;
        }
        return returns > 0;
    }

    private static boolean constructorUses(FormulaClassRepository repository, String owner, String descriptor,
                                           int sourceSlot, Set<String> visited, int depth) {
        if (depth > 8 || !visited.add(owner + descriptor)) return false;
        if (owner.equals("net/minecraft/world/damagesource/DamageSource")) {
            Type[] types = Type.getArgumentTypes(descriptor);
            return types.length >= 3 && types[0].getDescriptor().equals("Lnet/minecraft/core/Holder;")
                    && types[1].getDescriptor().equals('L' + ENTITY + ';') && sourceSlot == 2;
        }
        var resolved = repository.resolveMethod(owner, "<init>", descriptor);
        if (resolved == null) return false;
        var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
        if (!flow.successful()) return false;
        int matches = 0;
        for (var instruction : resolved.method().instructions) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")
                && repository.isSubtype(call.owner, "net/minecraft/world/damagesource/DamageSource")
                && flow.parameter(flow.receiver(call), 0)) {
            int local = 1;
            Type[] types = Type.getArgumentTypes(call.desc);
            var arguments = flow.arguments(call);
            boolean proven = false;
            for (int index = 0; index < types.length && index < arguments.size(); index++) {
                if (flow.parameter(arguments.get(index), sourceSlot)
                        && constructorUses(repository, call.owner, call.desc, local, new HashSet<>(visited), depth + 1)) proven = true;
                local += types[index].getSize();
            }
            if (!proven) return false;
            matches++;
        }
        return matches == 1;
    }

    private static boolean sourceArgument(FormulaDataFlow.Analysis flow, MethodInsnNode call, int slot) {
        return argumentParameter(flow, call, slot, 'L' + ENTITY + ';');
    }

    private static boolean argumentParameter(FormulaDataFlow.Analysis flow, MethodInsnNode call, int slot, String descriptor) {
        Type[] types = Type.getArgumentTypes(call.desc);
        var arguments = flow.arguments(call);
        int found = -1;
        for (int index = 0; index < types.length; index++) if (types[index].getDescriptor().equals(descriptor)) {
            if (found >= 0) return false;
            found = index;
        }
        return found >= 0 && found < arguments.size() && flow.parameter(arguments.get(found), slot);
    }

    private static boolean factorySource(FormulaClassRepository repository, MethodInsnNode call) {
        return factoryParameter(repository, call.owner, call.name, call.desc, 'L' + ENTITY + ';', new HashSet<>(), 0);
    }

    private static boolean factoryParameter(FormulaClassRepository repository, String owner, String name, String descriptor, String parameterType,
                                         Set<String> visited, int depth) {
        if (depth > 8 || !visited.add(owner + '#' + name + descriptor)) return false;
        var resolved = repository.resolveMethod(owner, name, descriptor);
        if (resolved == null || !resolved.owner().name.startsWith("net/minecraft/")) return false;
        MethodNode method = resolved.method();
        Type[] types = Type.getArgumentTypes(descriptor);
        int sourceSlot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        int sourceCount = 0;
        for (Type type : types) {
            if (type.getDescriptor().equals(parameterType)) sourceCount++;
            else if (sourceCount == 0) sourceSlot += type.getSize();
        }
        if (sourceCount != 1) return false;
        var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, method);
        if (!flow.successful()) return false;
        int returns = 0;
        for (var instruction : method.instructions) if (instruction.getOpcode() == Opcodes.ARETURN && flow.reachable(instruction)) {
            var operands = flow.operands(instruction);
            var origin = operands.size() == 1 ? flow.origin(operands.get(0)) : null;
            if (origin == null) return false;
            if (origin.instruction() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && allocation.desc.equals(EXPLOSION)) {
                boolean valid = false;
                for (var candidate : method.instructions) if (candidate instanceof MethodInsnNode constructor
                        && constructor.owner.equals(EXPLOSION) && constructor.name.equals("<init>")) {
                    var receiver = flow.origin(flow.receiver(constructor));
                    if (receiver != null && receiver.instruction() == allocation && argumentParameter(flow, constructor, sourceSlot, parameterType)) valid = true;
                }
                if (!valid) return false;
            } else if (origin.instruction() instanceof MethodInsnNode forward
                    && Type.getReturnType(forward.desc).getDescriptor().equals('L' + EXPLOSION + ';')
                    && argumentParameter(flow, forward, sourceSlot, parameterType)
                    && factoryParameter(repository, forward.owner, forward.name, forward.desc, parameterType, new HashSet<>(visited), depth + 1)) {
            } else return false;
            returns++;
        }
        return returns > 0;
    }

    private static boolean unreachableZeroRadius(FormulaClassRepository repository, FormulaDataFlow.Analysis flow,
                                                   MethodNode method, MethodInsnNode point, Mode mode, String spell,
                                                   FormulaMethodSnapshot snapshot) {
        MethodInsnNode emission = point;
        if (mode == Mode.EXPLICIT) {
            var allocation = flow.origin(flow.receiver(point));
            if (allocation == null) return false;
            emission = null;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode constructor
                    && constructor.owner.equals(EXPLOSION) && constructor.name.equals("<init>")) {
                var receiver = flow.origin(flow.receiver(constructor));
                if (receiver != null && receiver.instruction() == allocation.instruction()) {
                    if (emission != null) return false;
                    emission = constructor;
                }
            }
            if (emission == null) return false;
        }
        Type[] types = Type.getArgumentTypes(emission.desc);
        int radiusIndex = -1;
        for (int index = 0; index < types.length; index++) if (types[index].equals(Type.FLOAT_TYPE)) {
            if (radiusIndex >= 0) return false;
            radiusIndex = index;
        }
        var arguments = flow.arguments(emission);
        if (radiusIndex < 0 || radiusIndex >= arguments.size()) return false;
        var radius = flow.origin(arguments.get(radiusIndex));
        if (radius == null || !(radius.instruction() != null && radius.instruction().getOpcode() == Opcodes.FCONST_0
                || radius.instruction() instanceof LdcInsnNode literal && literal.cst instanceof Float number && number == 0f)) return false;
        for (var control : radiusControls.getOrDefault(spell, List.of()))
            if (control.kind() == FormulaAutomaticSites.Kind.RADIUS && control.definition().spellClassName().equals(spell)
                    && control.definition().maximum() > 0 && control.owner().replace('.', '/').equals(flow.method().owner())
                    && control.methodName().equals(method.name) && control.methodDescriptor().equals(method.desc)
                    && control.methodFingerprint().equals(snapshot.fingerprint()) && control.producerIndex() == snapshot.indexOf(emission)
                    && control.argumentIndex() == radiusIndex) return false;
        return mode == Mode.EXPLICIT || factoryParameter(repository, emission.owner, emission.name, emission.desc, "F", new HashSet<>(), 0);
    }

    private static boolean nativeConsumer(FormulaClassRepository repository) {
        var type = repository.classNode(EXPLOSION);
        if (type == null) return false;
        return type.methods.stream().filter(method -> method.name.equals("explode") && method.desc.equals("()V"))
                .anyMatch(method -> {
                    int sites = 0;
                    for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                            && repository.isSubtype(call.owner, ENTITY) && call.name.equals("hurt")
                            && call.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;F)Z")) sites++;
                    return sites == 1;
                });
    }

    private static boolean rootContext(MethodNode method) {
        Type[] types = Type.getArgumentTypes(method.desc);
        return (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                && types.length >= 3 && types[0].getDescriptor().equals('L' + LEVEL + ';')
                && types[1].equals(Type.INT_TYPE) && types[2].getDescriptor().equals('L' + LIVING + ';')
                && FormulaAutomaticInjector.contextIntact(method, 0, 2, 3);
    }

    private static boolean spawn(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.name.equals("addFreshEntity")
                && call.desc.equals("(L" + ENTITY + ";)Z");
    }

    private static int ordinal(MethodNode method, AbstractInsnNode selected, boolean allocation) {
        int ordinal = 0;
        for (var instruction : method.instructions) if (allocation
                ? instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.NEW
                : instruction instanceof MethodInsnNode call && spawn(call)) {
            if (instruction == selected) return ordinal;
            ordinal++;
        }
        return -1;
    }

    public static synchronized Discovery register(FormulaClassRepository repository, String spellClass) {
        Discovery result = discover(repository, spellClass);
        Discovery previous = REGISTERED.put(spellClass.replace('/', '.'), result);
        if (previous != null) for (Binding binding : previous.bindings())
            if (!result.bindings().contains(binding)) VERIFIED.remove(binding);
        Map<String, FormulaNumericSites.Definition> nextDefinitions = new LinkedHashMap<>();
        Map<String, List<Binding>> nextPoints = new LinkedHashMap<>();
        for (Discovery discovery : REGISTERED.values()) for (Binding binding : discovery.bindings()) {
            nextDefinitions.put(binding.definition().dispatchKey(), binding.definition());
            nextPoints.computeIfAbsent(binding.physicalKey(), ignored -> new ArrayList<>()).add(binding);
        }
        definitions = Map.copyOf(nextDefinitions);
        Map<String, List<Binding>> immutable = new LinkedHashMap<>();
        nextPoints.forEach((key, values) -> immutable.put(key, List.copyOf(values)));
        points = Map.copyOf(immutable);
        Map<String, List<Binding>> byDefinition = new LinkedHashMap<>();
        for (var values : points.values()) for (Binding binding : values)
            byDefinition.computeIfAbsent(binding.definition().dispatchKey(), ignored -> new ArrayList<>()).add(binding);
        Map<String, List<Binding>> nextExpected = new LinkedHashMap<>();
        byDefinition.forEach((key, values) -> nextExpected.put(key, List.copyOf(values)));
        expectedBindings = Map.copyOf(nextExpected);
        verifiedBindings = Set.copyOf(VERIFIED);
        return result;
    }

    public static synchronized Discovery register(FormulaClassRepository repository, String spellClass, List<FormulaAutomaticSites.Site> controls) {
        Map<String, List<FormulaAutomaticSites.Site>> next = new LinkedHashMap<>(radiusControls);
        next.put(spellClass.replace('/', '.'), controls.stream().filter(control -> control.kind() == FormulaAutomaticSites.Kind.RADIUS).toList());
        radiusControls = Map.copyOf(next);
        return register(repository, spellClass);
    }

    public static Set<String> targetClasses() {
        Set<String> result = new LinkedHashSet<>();
        for (List<Binding> bindings : points.values()) for (Binding binding : bindings) result.add(binding.owner());
        return Set.copyOf(result);
    }

    public static FormulaNumericSites.Definition find(String key) { return definitions.get(key); }
    public static List<FormulaNumericSites.Definition> registeredDefinitions() { return List.copyOf(definitions.values()); }
    public static List<Binding> registeredBindings() { return points.values().stream().flatMap(List::stream).distinct().toList(); }
    public static boolean isDamage(String key) { return definitions.containsKey(key); }
    static List<Binding> bindings(String physicalKey) { return points.getOrDefault(physicalKey, List.of()); }

    public static boolean available(String key) {
        return explosionTargetVerified && sourceHooksVerified(key);
    }

    public static boolean explosionTargetVerified() { return explosionTargetVerified; }

    public static boolean sourceHooksVerified(String key) {
        List<Binding> expected = expectedBindings.get(key);
        return expected != null && !expected.isEmpty() && verifiedBindings.containsAll(expected);
    }

    public static Runnable prepare(String owner, List<MethodNode> methods) {
        String selected = owner.replace('/', '.');
        Map<String, Binding> unique = new LinkedHashMap<>();
        for (var values : points.values()) for (Binding binding : values)
            if (binding.owner().equals(selected)) unique.putIfAbsent(binding.physicalKey(), binding);
        List<Runnable> changes = new ArrayList<>();
        for (Binding binding : unique.values()) {
            var candidates = methods.stream().filter(method -> method.name.equals(binding.methodName()) && method.desc.equals(binding.methodDescriptor())).toList();
            if (candidates.size() != 1) throw new IllegalArgumentException("爆炸来源方法不唯一：" + binding.physicalKey());
            MethodNode method = candidates.get(0);
            if (hooked(method, binding)) continue;
            var snapshot = new FormulaMethodSnapshot(method);
            if (!snapshot.fingerprint().equals(binding.methodFingerprint()) || binding.instruction() >= snapshot.instructions().size()
                    || !(snapshot.instructions().get(binding.instruction()) instanceof MethodInsnNode point))
                throw new IllegalArgumentException("爆炸来源指纹已变化：" + binding.physicalKey());
            changes.add(() -> inject(method, point, binding));
        }
        return () -> changes.forEach(Runnable::run);
    }

    public static void apply(String owner, ClassNode target) { prepare(owner, target.methods).run(); }

    private static void inject(MethodNode method, MethodInsnNode point, Binding binding) {
        InsnList prefix = new InsnList();
        if (binding.mode() == Mode.EXPLICIT) prefix.add(new InsnNode(Opcodes.DUP));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new LdcInsnNode(binding.physicalKey()));
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, binding.mode() == Mode.EXPLICIT ? "bind" : "enter",
                binding.mode() == Mode.EXPLICIT ? "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V" : "(Ljava/lang/Object;Ljava/lang/String;)V", false));
        method.instructions.insertBefore(point, prefix);
        if (binding.mode() == Mode.LEVEL_FACTORY) {
            LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
            method.instructions.insertBefore(point, start);
            InsnList suffix = new InsnList();
            suffix.add(end);
            suffix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "exit", "()V", false));
            method.instructions.insert(point, suffix);
            boolean expanded = false;
            for (var instruction : method.instructions) if (instruction instanceof FrameNode frame && frame.type == Opcodes.F_NEW) expanded = true;
            method.instructions.add(handler);
            method.instructions.add(new FrameNode(expanded ? Opcodes.F_NEW : Opcodes.F_FULL, 0, null, 1, new Object[]{"java/lang/Throwable"}));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "exit", "()V", false));
            method.instructions.add(new InsnNode(Opcodes.ATHROW));
            method.tryCatchBlocks.add(0, new TryCatchBlockNode(start, end, handler, null));
        }
        method.maxStack += 3;
    }

    public static synchronized boolean verifyHook(String owner, ClassNode target) {
        String selected = owner.replace('/', '.');
        boolean complete = true;
        for (var values : points.values()) for (Binding binding : values) if (binding.owner().equals(selected)) {
            VERIFIED.remove(binding);
            var methods = target.methods.stream().filter(method -> method.name.equals(binding.methodName()) && method.desc.equals(binding.methodDescriptor())).toList();
            if (target.name.replace('/', '.').equals(selected) && methods.size() == 1 && hooked(methods.get(0), binding)) VERIFIED.add(binding);
            else complete = false;
        }
        verifiedBindings = Set.copyOf(VERIFIED);
        return complete;
    }

    public static String validateExplosionTarget(ClassNode target) {
        if (target == null || !target.name.equals(EXPLOSION)) return "原生爆炸目标不存在或名称不匹配";
        if (target.fields.stream().filter(field -> field.desc.equals("Lnet/minecraft/world/damagesource/DamageSource;")).count() != 1)
            return "原生爆炸伤害来源字段不唯一";
        var methods = target.methods.stream().filter(method -> method.name.equals("explode") && method.desc.equals("()V")).toList();
        if (methods.size() != 1) return "原生爆炸执行方法不唯一";
        int consumers = 0;
        for (var instruction : methods.get(0).instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals(ENTITY) && call.name.equals("hurt")
                && call.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;F)Z")) consumers++;
        return consumers == 1 ? null : "原生爆炸生命伤害消费点数量发生变化";
    }

    public static boolean observeExplosionTarget(ClassNode target) {
        boolean retained = validateExplosionTarget(target) == null
                && target.interfaces.contains("com/example/spell_compiler/formula/FormulaExplosionSource");
        if (retained) {
            var repository = FormulaClassRepository.of(target);
            for (var method : target.methods) {
                if (method.name.equals("explode") && method.desc.equals("()V")) {
                    var flow = FormulaDataFlow.analyze(repository, target.name, method);
                    boolean wrapped = false;
                    if (flow.successful()) for (var instruction : method.instructions)
                        if (instruction instanceof MethodInsnNode hurt && hurt.owner.equals(ENTITY)
                                && hurt.name.equals("hurt")
                                && hurt.desc.equals("(Lnet/minecraft/world/damagesource/DamageSource;F)Z")) {
                            var arguments = flow.arguments(hurt);
                            var origin = arguments.size() == 2 ? flow.origin(arguments.get(1)) : null;
                            if (origin != null && origin.instruction() instanceof MethodInsnNode wrapper
                                    && wrapper.owner.equals(target.name) && wrapper.desc.equals("(F)F")
                                    && flow.parameter(flow.receiver(wrapper), 0))
                                wrapped = target.methods.stream().anyMatch(candidate -> candidate.name.equals(wrapper.name)
                                        && candidate.desc.equals(wrapper.desc) && delegates(repository, target.name, candidate, "modify", "(Ljava/lang/Object;F)F"));
                        }
                    retained &= wrapped;
                }
                if (method.name.equals("<init>")) {
                    var flow = FormulaDataFlow.analyze(repository, target.name, method);
                    List<MethodInsnNode> attachments = new ArrayList<>();
                    for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                            && call.owner.equals(target.name) && flow.parameter(flow.receiver(call), 0)
                            && target.methods.stream().anyMatch(candidate -> candidate.name.equals(call.name)
                                && candidate.desc.equals(call.desc) && delegates(repository, target.name, candidate, "attachPending", "(Ljava/lang/Object;)V")))
                        attachments.add(call);
                    boolean attached = flow.successful();
                    int returns = 0;
                    for (var instruction : method.instructions) if (instruction.getOpcode() == Opcodes.RETURN && flow.reachable(instruction)) {
                        attached &= attachments.stream().anyMatch(call -> flow.dominates(call, instruction));
                        returns++;
                    }
                    retained &= attached && returns > 0;
                }
            }
        }
        explosionTargetVerified = retained;
        return retained;
    }

    private static boolean delegates(FormulaClassRepository repository, String owner, MethodNode method, String name, String descriptor) {
        var flow = FormulaDataFlow.analyze(repository, owner, method);
        if (!flow.successful()) return false;
        MethodInsnNode selected = null;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(HOOKS)
                && call.name.equals(name) && call.desc.equals(descriptor)) {
            if (selected != null) return false;
            selected = call;
        }
        if (selected == null) return false;
        var arguments = flow.arguments(selected);
        if (arguments.isEmpty() || !flow.parameter(arguments.get(0), 0)
                || name.equals("modify") && (arguments.size() != 2 || !flow.parameter(arguments.get(1), 1))) return false;
        int returns = 0;
        for (var instruction : method.instructions) if (instruction.getOpcode() >= Opcodes.IRETURN
                && instruction.getOpcode() <= Opcodes.RETURN && flow.reachable(instruction)) {
            if (!flow.dominates(selected, instruction)) return false;
            if (name.equals("modify")) {
                var operands = flow.operands(instruction);
                var origin = operands.size() == 1 ? flow.origin(operands.get(0)) : null;
                if (origin == null || origin.instruction() != selected) return false;
            }
            returns++;
        }
        return returns > 0;
    }

    private static boolean hooked(MethodNode method, Binding binding) {
        List<AbstractInsnNode> instructions = FormulaAutomaticInjector.executable(method);
        int found = 0;
        for (int index = 0; index < instructions.size(); index++) if (instructions.get(index) instanceof LdcInsnNode key
                && binding.physicalKey().equals(key.cst) && index > 0 && index + 2 < instructions.size()
                && instructions.get(index - 1) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0
                && instructions.get(index + 1) instanceof MethodInsnNode hook && hook.owner.equals(HOOKS)
                && hook.name.equals(binding.mode() == Mode.EXPLICIT ? "bind" : "enter")
                && instructions.get(index + 2) instanceof MethodInsnNode target) {
            if (binding.mode() == Mode.EXPLICIT && index >= 2 && instructions.get(index - 2).getOpcode() == Opcodes.DUP
                    && target.owner.equals(EXPLOSION) && target.name.equals("explode") && target.desc.equals("()V")) found++;
            if (binding.mode() == Mode.LEVEL_FACTORY && Type.getReturnType(target.desc).getDescriptor().equals('L' + EXPLOSION + ';')
                    && index + 3 < instructions.size() && instructions.get(index + 3) instanceof MethodInsnNode exit
                    && exit.owner.equals(HOOKS) && exit.name.equals("exit") && exit.desc.equals("()V")) {
                for (var block : method.tryCatchBlocks) if (block.start.getNext() == target && block.end == target.getNext()
                        && block.type == null) {
                    var handler = block.handler.getNext();
                    while (handler != null && handler.getOpcode() < 0) handler = handler.getNext();
                    if (handler instanceof MethodInsnNode cleanup && cleanup.owner.equals(HOOKS) && cleanup.name.equals("exit")
                            && cleanup.desc.equals("()V") && cleanup.getNext() != null && cleanup.getNext().getOpcode() == Opcodes.ATHROW) found++;
                }
            }
        }
        return found == 1;
    }
}
