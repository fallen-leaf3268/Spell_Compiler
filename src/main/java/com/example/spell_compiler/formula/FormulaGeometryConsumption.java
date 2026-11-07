package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.function.Function;

public final class FormulaGeometryConsumption {
    private static final String VECTOR = "net/minecraft/world/phys/Vec3";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final Map<FormulaDataFlow.Analysis, Map<AbstractInsnNode, List<FormulaDataFlow.Value>>> CONSTRUCTORS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private FormulaGeometryConsumption() {}

    public static List<FormulaConsumptionModel.Consumption> classify(FormulaClassRepository repository,
            FormulaDataFlow.Analysis analysis, MethodInsnNode operation) {
        return classify(repository, analysis, operation, call -> implementation(repository, analysis, call));
    }

    public static List<FormulaConsumptionModel.Consumption> classify(FormulaClassRepository repository,
            FormulaDataFlow.Analysis analysis, MethodInsnNode operation,
            Function<MethodInsnNode, FormulaClassRepository.ResolvedMethod> implementationResolver) {
        if (!analysis.successful() || !analysis.reachable(operation) || !operation.owner.equals(VECTOR)) return List.of();
        List<FormulaConsumptionModel.Consumption> arguments = switch (operation.name + operation.desc) {
            case "scale(D)L" + VECTOR + ";" -> List.of(consumption(FormulaAutomaticSites.Kind.MOVEMENT_SCALE, 0));
            case "multiply(DDD)L" + VECTOR + ";" -> axes(FormulaAutomaticSites.Kind.MOVEMENT_X_SCALE,
                    FormulaAutomaticSites.Kind.MOVEMENT_Y_SCALE, FormulaAutomaticSites.Kind.MOVEMENT_Z_SCALE);
            case "add(DDD)L" + VECTOR + ";" -> axes(FormulaAutomaticSites.Kind.MOVEMENT_X_OFFSET,
                    FormulaAutomaticSites.Kind.MOVEMENT_Y_OFFSET, FormulaAutomaticSites.Kind.MOVEMENT_Z_OFFSET);
            case "<init>(DDD)V" -> axes(FormulaAutomaticSites.Kind.MOVEMENT_X,
                    FormulaAutomaticSites.Kind.MOVEMENT_Y, FormulaAutomaticSites.Kind.MOVEMENT_Z);
            default -> List.of();
        };
        if (arguments.isEmpty()) return arguments;
        AbstractInsnNode output = operation;
        if (operation.name.equals("<init>")) {
            var origin = analysis.origin(analysis.receiver(operation));
            if (origin == null || !(origin.instruction() instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW || !allocation.desc.equals(VECTOR)) return List.of();
            output = allocation;
        }
        var constructors = constructors(analysis);
        if (!reachesMovement(repository, analysis, output, constructors, implementationResolver)
                || !isolated(repository, analysis, output, constructors, implementationResolver)) return List.of();
        return arguments;
    }

    private static FormulaConsumptionModel.Consumption consumption(FormulaAutomaticSites.Kind kind, int argument) {
        return new FormulaConsumptionModel.Consumption(kind, argument, FormulaConsumptionModel.Proof.TERMINAL);
    }

    private static List<FormulaConsumptionModel.Consumption> axes(FormulaAutomaticSites.Kind x,
            FormulaAutomaticSites.Kind y, FormulaAutomaticSites.Kind z) {
        return List.of(consumption(x, 0), consumption(y, 1), consumption(z, 2));
    }

    private static FormulaClassRepository.ResolvedMethod implementation(FormulaClassRepository repository,
            FormulaDataFlow.Analysis analysis, MethodInsnNode call) {
        var receiver = analysis.origin(analysis.receiver(call));
        String exact = null;
        if (receiver != null && receiver.instruction() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW)
            exact = allocation.desc;
        else if (receiver != null && receiver.kind() == FormulaDataFlow.NodeKind.PARAMETER && receiver.parameterSlot() == 0
                && repository.isSubtype(analysis.method().owner(), ENTITY)) {
            var caller = repository.resolveMethod(analysis.method().owner(), analysis.method().name(), analysis.method().descriptor());
            if (caller != null && (caller.method().access & Opcodes.ACC_STATIC) == 0) exact = analysis.method().owner();
        }
        return exact == null ? repository.resolveMethod(call.owner, call.name, call.desc)
                : FormulaInterproceduralFlow.resolveCall(repository, analysis.method().owner(), call, exact);
    }

    private static Map<AbstractInsnNode, List<FormulaDataFlow.Value>> constructors(FormulaDataFlow.Analysis analysis) {
        synchronized (CONSTRUCTORS) {
            return CONSTRUCTORS.computeIfAbsent(analysis, flow -> {
                Map<AbstractInsnNode, List<FormulaDataFlow.Value>> result = new IdentityHashMap<>();
                Set<AbstractInsnNode> ambiguous = Collections.newSetFromMap(new IdentityHashMap<>());
                for (var instruction : flow.instructions()) {
                    if (!(instruction.instruction() instanceof MethodInsnNode call) || !flow.reachable(call)
                            || call.getOpcode() != Opcodes.INVOKESPECIAL || !call.owner.equals(VECTOR) || !call.name.equals("<init>")) continue;
                    var receiver = flow.origin(flow.receiver(call));
                    if (receiver == null || !(receiver.instruction() instanceof TypeInsnNode allocation)
                            || allocation.getOpcode() != Opcodes.NEW || !allocation.desc.equals(VECTOR)) continue;
                    if (result.putIfAbsent(allocation, List.copyOf(flow.arguments(call))) != null) ambiguous.add(allocation);
                }
                ambiguous.forEach(result::remove);
                return Collections.unmodifiableMap(result);
            });
        }
    }

    private static boolean reachesMovement(FormulaClassRepository repository, FormulaDataFlow.Analysis analysis,
            AbstractInsnNode output, Map<AbstractInsnNode, List<FormulaDataFlow.Value>> constructors,
            Function<MethodInsnNode, FormulaClassRepository.ResolvedMethod> implementationResolver) {
        for (var instruction : analysis.instructions()) {
            if (!(instruction.instruction() instanceof MethodInsnNode call) || !analysis.reachable(call)) continue;
            var implementation = implementationResolver.apply(call);
            if (FormulaConsumptionModel.vectorMovementTerminal(repository, call, implementation)
                    && depends(analysis, analysis.arguments(call).getFirst(), output, constructors)) return true;
            for (var consumer : FormulaConsumptionModel.classify(repository, call, implementation))
                if (movement(consumer.kind()) && depends(analysis, analysis.arguments(call).get(consumer.argument()), output, constructors)) return true;
        }
        return false;
    }

    private static boolean isolated(FormulaClassRepository repository, FormulaDataFlow.Analysis analysis,
            AbstractInsnNode output, Map<AbstractInsnNode, List<FormulaDataFlow.Value>> constructors,
            Function<MethodInsnNode, FormulaClassRepository.ResolvedMethod> implementationResolver) {
        for (var operation : analysis.instructions()) {
            AbstractInsnNode consumer = operation.instruction();
            if (!analysis.reachable(consumer) || operation.operands().stream().noneMatch(value -> depends(analysis, value, output, constructors))) continue;
            int opcode = consumer.getOpcode();
            if (copy(consumer) || opcode == Opcodes.POP || opcode == Opcodes.POP2) continue;
            if (consumer instanceof MethodInsnNode call) {
                if (call.owner.equals(VECTOR)) continue;
                var implementation = implementationResolver.apply(call);
                if (FormulaConsumptionModel.vectorMovementTerminal(repository, call, implementation)
                        || FormulaConsumptionModel.classify(repository, call, implementation).stream().anyMatch(value -> movement(value.kind()))) continue;
                if (Set.of("java/lang/Math", "java/lang/StrictMath", "net/minecraft/util/Mth").contains(call.owner)
                        && numeric(Type.getReturnType(call.desc))
                        && Arrays.stream(Type.getArgumentTypes(call.desc)).allMatch(FormulaGeometryConsumption::numeric)) continue;
                return false;
            }
            if (consumer instanceof FieldInsnNode field) {
                if (field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(VECTOR)) continue;
                return false;
            }
            if (consumer instanceof JumpInsnNode || consumer instanceof TableSwitchInsnNode || consumer instanceof LookupSwitchInsnNode)
                return false;
            if (FormulaAutomaticInjector.resultType(consumer) == null) return false;
        }
        return true;
    }

    private static boolean depends(FormulaDataFlow.Analysis analysis, FormulaDataFlow.Value value,
            AbstractInsnNode output, Map<AbstractInsnNode, List<FormulaDataFlow.Value>> constructors) {
        if (value == null) return false;
        ArrayDeque<FormulaDataFlow.Value> pending = new ArrayDeque<>();
        Set<FormulaDataFlow.Node> visited = new HashSet<>();
        pending.add(value);
        while (!pending.isEmpty()) {
            for (var source : pending.removeFirst().sources()) {
                if (source.instruction() == output) return true;
                if (!visited.add(source)) continue;
                if (source.instruction() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                        && allocation.desc.equals(VECTOR)) pending.addAll(constructors.getOrDefault(allocation, List.of()));
                pending.addAll(analysis.dependencies(source));
            }
        }
        return false;
    }

    private static boolean movement(FormulaAutomaticSites.Kind kind) { return kind.name().startsWith("MOVEMENT_"); }

    private static boolean numeric(Type type) {
        return type.equals(Type.INT_TYPE) || type.equals(Type.FLOAT_TYPE) || type.equals(Type.DOUBLE_TYPE);
    }

    private static boolean copy(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return instruction instanceof VarInsnNode || opcode >= Opcodes.DUP && opcode <= Opcodes.SWAP || opcode == Opcodes.CHECKCAST;
    }
}
