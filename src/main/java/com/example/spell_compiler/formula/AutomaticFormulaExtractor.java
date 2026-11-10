package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

import static com.example.spell_compiler.formula.FormulaExtractionModel.*;
import static com.example.spell_compiler.formula.FormulaExtractionBytecode.*;

public final class AutomaticFormulaExtractor {
    private static final String ABSTRACT_SPELL = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String SCHOOL = "io/redspace/ironsspellbooks/api/spells/SchoolType";
    private static final String CONFIG_MANAGER = "io/redspace/ironsspellbooks/api/config/SpellConfigManager";
    private static final String CONFIG_PARAMETER = "io/redspace/ironsspellbooks/api/config/SpellConfigParameter";
    private static final String LEGACY_CONFIG = "io/redspace/ironsspellbooks/config/ServerConfigs";
    private static final String LEGACY_PARAMETERS = LEGACY_CONFIG + "$SpellConfigParameters";
    private static final String ATTRIBUTE_REGISTRY = "io/redspace/ironsspellbooks/api/registry/AttributeRegistry";
    private static final String UTILS = "io/redspace/ironsspellbooks/api/util/Utils";
    private static final String MOB_TYPE = "net/minecraft/world/entity/MobType";
    private static final String SPELL_REGISTRY = "io/redspace/ironsspellbooks/api/registry/SpellRegistry";
    private static final String DEFERRED_HOLDER = "net/minecraftforge/registries/RegistryObject";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String SITE_VALUES = "com/example/spell_compiler/formula/FormulaSiteValues";
    private static final String INPUT_ANNOTATION = "Lcom/example/spell_compiler/formula/FormulaInput;";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String DATA_COMPONENTS = "net/minecraft/core/component/DataComponents";
    private static final String COMPONENT_TYPE = "net/minecraft/core/component/DataComponentType";
    private static final String ENCHANTMENTS = "net/minecraft/world/item/enchantment/Enchantments";
    private static final String ENCHANTMENT_COMPONENTS = "net/minecraft/world/item/enchantment/EnchantmentEffectComponents";
    private static final String ITEM_ENCHANTMENTS = "net/minecraft/world/item/enchantment/ItemEnchantments";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    private static final String LEVEL = "net/minecraft/world/level/Level";

    public record Variable(String name, Type type, List<AbstractInsnNode> instructions, int localSlot) {
        public Variable { instructions = List.copyOf(instructions); }
        public Variable(String name, Type type, List<AbstractInsnNode> instructions) {
            this(name, type, instructions, -1);
        }
    }

    public enum PowerSource { SPELL_POWER, ENTITY_MULTIPLIER }

    public record NativePower(List<AbstractInsnNode> receiver, List<AbstractInsnNode> caster, PowerSource source) {
        public NativePower {
            receiver = List.copyOf(receiver);
            caster = List.copyOf(caster);
            Objects.requireNonNull(source);
        }
    }

    public record Formula(String owner, String name, String descriptor, String expression,
                          Type returnType, Map<String, Variable> variables,
                          List<AbstractInsnNode> inputPrelude, int requiredLocals,
                          Map<String, NativePower> nativePowers) {
        public Formula {
            variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
            inputPrelude = List.copyOf(inputPrelude);
            nativePowers = Collections.unmodifiableMap(new LinkedHashMap<>(nativePowers));
        }
        public Formula(String owner, String name, String descriptor, String expression,
                       Type returnType, Map<String, Variable> variables,
                       List<AbstractInsnNode> inputPrelude, int requiredLocals) {
            this(owner, name, descriptor, expression, returnType, variables, inputPrelude, requiredLocals, Map.of());
        }
        public Formula(String owner, String name, String descriptor, String expression,
                       Type returnType, Map<String, Variable> variables) {
            this(owner, name, descriptor, expression, returnType, variables, List.of(), 0);
        }
        public String readableExpression() {
            return ReadableFormula.display(expression);
        }
        public boolean requiresFrames() {
            return inputPrelude.stream().anyMatch(value -> value instanceof JumpInsnNode)
                    || variables.values().stream().flatMap(value -> value.instructions().stream())
                    .anyMatch(value -> value instanceof JumpInsnNode);
        }
    }

    public record Analysis(Formula formula, String rejection) {
        public boolean supported() { return formula != null; }
    }

    private AutomaticFormulaExtractor() {}

    public static Analysis analyze(ClassNode owner, MethodNode method) {
        return analyze(owner, method, FormulaClassRepository.of(owner));
    }

    public static Analysis analyze(ClassNode owner, MethodNode method, FormulaClassRepository repository) {
        return analyze(owner, method, repository, false, null);
    }

    public static Analysis analyze(ClassNode owner, MethodNode method, FormulaClassRepository repository,
                                   String receiverClass) {
        return analyze(owner, method, repository, false, receiverClass);
    }

    public static Analysis analyzeRuntime(ClassNode owner, MethodNode method, FormulaClassRepository repository) {
        return analyze(owner, method, repository, true, null);
    }

    public static Analysis analyzeRuntime(ClassNode owner, MethodNode method, FormulaClassRepository repository,
                                          String receiverClass) {
        return analyze(owner, method, repository, true, receiverClass);
    }

    static String nativeCastType(FormulaClassRepository repository, String receiverClass) {
        return FormulaNativeLowering.nativeCastType(repository, receiverClass);
    }

    private static Analysis analyze(ClassNode owner, MethodNode method, FormulaClassRepository repository,
                                    boolean preserveNativePower, String receiverClass) {
        try {
            repository.ensureSource(owner);
            Session session = new Session(owner, method, repository, preserveNativePower, receiverClass);
            Value value = session.root();
            return new Analysis(session.inputs.finish(value, session.nativePowers), null);
        } catch (Rejected rejection) {
            return new Analysis(null, rejection.getMessage());
        }
    }

    private static final class Session {
        private final ClassNode rootOwner;
        private final MethodNode rootMethod;
        private final FormulaClassRepository repository;
        private final boolean preserveNativePower;
        private final String receiverClass;
        private final Map<String, NativePower> nativePowers = new LinkedHashMap<>();
        private final FormulaExtractionInputs inputs;
        private final Set<String> active = new HashSet<>();
        private final Set<String> initializedHelpers = new HashSet<>();
        private int instructionsVisited;
        private boolean legacyConfiguration;

        private Session(ClassNode owner, MethodNode method, FormulaClassRepository repository, boolean preserveNativePower,
                        String receiverClass) {
            this.rootOwner = owner;
            this.rootMethod = method;
            this.repository = repository;
            this.preserveNativePower = preserveNativePower;
            this.receiverClass = receiverClass == null ? null : receiverClass.replace('.', '/');
            if (this.receiverClass != null && (method.access & Opcodes.ACC_STATIC) == 0
                    && !repository.isSubtype(this.receiverClass, owner.name))
                throw new Rejected("Concrete receiver does not inherit the formula owner: " + this.receiverClass + " -> " + owner.name);
            inputs = new FormulaExtractionInputs(owner, method, this.receiverClass);
        }

        private Value root() {
            Type[] types = Type.getArgumentTypes(rootMethod.desc);
            int ints = 0;
            for (Type type : types) if (type.equals(Type.INT_TYPE)) ints++;
            int slot = (rootMethod.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
            var arguments = new ArrayList<Value>();
            for (int index = 0; index < types.length; index++) {
                Type type = types[index];
                if (numeric(type)) {
                    String name = FormulaNames.input(parameterName(index,
                            ints == 1 && type.equals(Type.INT_TYPE) ? "level" : "arg" + index));
                    arguments.add(inputs.parameter(name, type, slot));
                } else if (reference(type)) {
                    arguments.add(new Value(type, null, List.of(new VarInsnNode(Opcodes.ALOAD, slot)),
                            new Reference("parameter", "parameter:" + slot, "arg" + index, null)));
                } else throw new Rejected("Unsupported parameter type " + type);
                slot += type.getSize();
            }
            Value receiver = null;
            if ((rootMethod.access & Opcodes.ACC_STATIC) == 0) {
                String concrete = receiverClass == null ? rootOwner.name : receiverClass;
                var code = new ArrayList<AbstractInsnNode>();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                if (!concrete.equals(rootOwner.name)) code.add(new TypeInsnNode(Opcodes.CHECKCAST, concrete));
                receiver = new Value(Type.getObjectType(concrete), null, code, new Reference("this", "this", null, null));
            }
            if (receiver != null && ints == 0 && repository.isSubtype(rootOwner.name, ABSTRACT_SPELL)) {
                var casters = arguments.stream().filter(argument -> argument.type().getSort() == Type.OBJECT
                        && repository.isSubtype(argument.type().getInternalName(), LIVING_ENTITY)).toList();
                if (casters.size() == 1) inputs.contextualLevel(receiver, casters.get(0),
                        FormulaContextualLevelProof.owningRoot(repository, rootOwner, rootMethod, receiverClass));
            }
            return method(rootOwner, rootMethod, receiver, arguments);
        }

        private String parameterName(int index, String fallback) {
            if ((!rootOwner.name.equals(SITE_VALUES)
                    && !rootOwner.name.equals("com/example/spell_compiler/formula/FormulaNumericValues"))
                    || rootMethod.invisibleParameterAnnotations == null
                    || index >= rootMethod.invisibleParameterAnnotations.length
                    || rootMethod.invisibleParameterAnnotations[index] == null) return fallback;
            String result = null;
            for (AnnotationNode annotation : rootMethod.invisibleParameterAnnotations[index]) {
                if (!annotation.desc.equals(INPUT_ANNOTATION)) continue;
                if (result != null || annotation.values == null || annotation.values.size() != 2
                        || !annotation.values.get(0).equals("value") || !(annotation.values.get(1) instanceof String name)
                        || !name.matches("[A-Za-z_][A-Za-z0-9_]*"))
                    throw new Rejected("Invalid formula parameter annotation");
                result = name;
            }
            return result == null ? fallback : result;
        }

        private Value method(ClassNode owner, MethodNode method, Value receiver, List<Value> arguments) {
            String key = owner.name + '.' + method.name + method.desc;
            if (active.size() >= 32 || !active.add(key)) throw new Rejected("Recursive formula call: " + key);
            try {
                return new GetterLowering(this, owner, method, receiver, arguments).run();
            } catch (Rejected rejected) {
                throw new Rejected(key + " -> " + rejected.getMessage());
            } finally {
                active.remove(key);
            }
        }

        private boolean safeInitialization(String name, Set<String> seen) {
            if (!seen.add(name) || name.equals(rootOwner.name)) return true;
            ClassNode node = repository.classNode(name);
            if (node == null || node.methods.stream().anyMatch(value -> value.name.equals("<clinit>"))) return false;
            if (node.superName != null && !safeInitialization(node.superName, seen)) return false;
            for (String implemented : node.interfaces) if (!safeInitialization(implemented, seen)) return false;
            return true;
        }
    }

    private static final class GetterLowering {
        private final Session session;
        private final ClassNode owner;
        private final MethodNode method;
        private final Map<Integer, Value> parameters = new HashMap<>();
        private final FormulaDataFlow.Analysis flow;
        private AbstractInsnNode[] instructions;
        private final Map<FormulaDataFlow.Node, Value> values = new HashMap<>();
        private final Map<FormulaDataFlow.Value, FormulaDataFlow.Value> narrowed = new HashMap<>();
        private final Map<FormulaDataFlow.Value, Value> replacements = new HashMap<>();
        private AbstractInsnNode current;
        private Value produced;
        private int consumedInputs;
        private final boolean nativePower;
        private Value guard;

        private GetterLowering(Session session, ClassNode owner, MethodNode method, Value receiver, List<Value> arguments) {
            this.session = session;
            this.owner = owner;
            this.method = method;
            requireNumeric(numericReturn(method));
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0)
                throw new Rejected("Abstract, native and synchronized methods are not supported");
            if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty())
                throw new Rejected("Exception handlers are not supported");
            nativePower = owner.name.equals(ABSTRACT_SPELL)
                    && (method.name.equals("getSpellPower") && method.desc.equals("(ILnet/minecraft/world/entity/Entity;)F")
                    || method.name.equals("getEntityPowerMultiplier") && method.desc.equals("(Lnet/minecraft/world/entity/LivingEntity;)F"));
            if (nativePower) FormulaNativeLowering.validateNativePower(method);
            flow = FormulaDataFlow.analyze(session.repository, owner.name, method);
            if (!flow.successful()) throw new Rejected("Shared data flow is unavailable: " + flow.diagnostics());
            int slot = 0;
            if ((method.access & Opcodes.ACC_STATIC) == 0) parameters.put(slot++, receiver);
            Type[] types = Type.getArgumentTypes(method.desc);
            for (int index = 0; index < types.length; index++) {
                parameters.put(slot, arguments.get(index));
                slot += types[index].getSize();
            }
        }

        private GetterLowering(GetterLowering source) {
            session = source.session;
            owner = source.owner;
            method = source.method;
            nativePower = source.nativePower;
            guard = source.guard;
            parameters.putAll(source.parameters);
            flow = source.flow;
            instructions = source.instructions;
            values.putAll(source.values);
            narrowed.putAll(source.narrowed);
            replacements.putAll(source.replacements);
            current = source.current;
            consumedInputs = source.consumedInputs;
        }

        private Value run() {
            Value cast = FormulaNativeLowering.lower(session.inputs, session.repository, session.receiverClass,
                    owner, method, parameters);
            if (cast != null) return cast;
            instructions = method.instructions.toArray();
            return walk(0, -1, new HashSet<>());
        }

        private Value walk(int start, int predecessor, Set<Integer> visited) {
            for (int pc = start; pc < instructions.length; pc++) {
                if (++session.instructionsVisited > 20000 || !visited.add(pc))
                    throw new Rejected("Cyclic or oversized formula");
                AbstractInsnNode instruction = instructions[pc];
                enter(instruction, predecessor);
                predecessor = pc;
                int opcode = instruction.getOpcode();
                if (opcode < 0) continue;
                produced = null;
                consumedInputs = 0;
                switch (opcode) {
                    case Opcodes.NOP -> {}
                    case Opcodes.ACONST_NULL -> emit(new Value(Type.getObjectType("java/lang/Object"), null,
                            List.of(instruction), new Reference("null", "null", null, null)));
                    case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                            Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 ->
                            constant(Type.INT_TYPE, opcode - Opcodes.ICONST_0, instruction);
                    case Opcodes.LCONST_0, Opcodes.LCONST_1 ->
                            constant(Type.LONG_TYPE, (long) (opcode - Opcodes.LCONST_0), instruction);
                    case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2 ->
                            constant(Type.FLOAT_TYPE, (float) (opcode - Opcodes.FCONST_0), instruction);
                    case Opcodes.DCONST_0, Opcodes.DCONST_1 ->
                            constant(Type.DOUBLE_TYPE, (double) (opcode - Opcodes.DCONST_0), instruction);
                    case Opcodes.BIPUSH, Opcodes.SIPUSH ->
                            constant(Type.INT_TYPE, ((IntInsnNode) instruction).operand, instruction);
                    case Opcodes.LDC -> {
                        Object value = ((LdcInsnNode) instruction).cst;
                        if (value instanceof Integer number) constant(Type.INT_TYPE, number, instruction);
                        else if (value instanceof Long number) constant(Type.LONG_TYPE, number, instruction);
                        else if (value instanceof Float number && Float.isFinite(number)) constant(Type.FLOAT_TYPE, number, instruction);
                        else if (value instanceof Double number && Double.isFinite(number)) constant(Type.DOUBLE_TYPE, number, instruction);
                        else throw new Rejected("Only finite numeric constants are supported");
                    }
                    case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD,
                            Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE,
                            Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2, Opcodes.DUP2, Opcodes.DUP2_X1,
                            Opcodes.DUP2_X2, Opcodes.SWAP -> {
                        boolean store = opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE;
                        if (opcode >= Opcodes.DUP && opcode <= Opcodes.SWAP) flushOperandValues();
                        var outputs = flow.instruction(instruction).results();
                        if (outputs.isEmpty()) throw new Rejected("Shared data flow lacks a copy output");
                        for (var output : outputs) {
                            var node = unique(output);
                            var inputs = flow.dependencies(node);
                            if (inputs.size() != 1) throw new Rejected("Shared data flow lacks an exact copy dependency");
                            Value value = value(inputs.get(0));
                            if (store) {
                                consumedInputs = 1;
                                flushOperandValues();
                                value = reference(value.type()) && nativePower && guard != null ? value : session.inputs.capture(value);
                            }
                            values.put(node, value);
                        }
                    }
                    case Opcodes.POP, Opcodes.POP2 -> flushOperandValues();
                    case Opcodes.GETFIELD, Opcodes.GETSTATIC -> field((FieldInsnNode) instruction);
                    case Opcodes.CHECKCAST -> {
                        Value value = operand(0);
                        if (!reference(value.type())) throw new Rejected("CHECKCAST requires a reference");
                        Type target = Type.getObjectType(((TypeInsnNode) instruction).desc);
                        if (value.reference() != null && value.reference().kind().equals("registered_spell")
                                && session.repository.isSubtype(value.type().getInternalName(), target.getInternalName())) target = value.type();
                        emit(new Value(target, null, append(instruction, value), value.reference()));
                    }
                    case Opcodes.INSTANCEOF -> {
                        Value value = operand(0);
                        emit(new Value(Type.INT_TYPE, new Predicate(Type.INT_TYPE, value, ((TypeInsnNode) instruction).desc),
                                append(instruction, value), null));
                    }
                    case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC, Opcodes.INVOKEINTERFACE ->
                            call((MethodInsnNode) instruction);
                    case Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD -> binary(instruction, "+");
                    case Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB -> binary(instruction, "-");
                    case Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL -> binary(instruction, "*");
                    case Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV -> binary(instruction, "/");
                    case Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM -> binary(instruction, "%");
                    case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG -> {
                        Type type = arithmeticType(opcode - Opcodes.INEG);
                        Value value = numericOperand(0, type);
                        emit(new Value(type, new Negation(type, value.node()), append(instruction, value), null));
                    }
                    case Opcodes.I2L -> conversion(instruction, Type.INT_TYPE, Type.LONG_TYPE);
                    case Opcodes.I2F -> conversion(instruction, Type.INT_TYPE, Type.FLOAT_TYPE);
                    case Opcodes.I2D -> conversion(instruction, Type.INT_TYPE, Type.DOUBLE_TYPE);
                    case Opcodes.L2I -> conversion(instruction, Type.LONG_TYPE, Type.INT_TYPE);
                    case Opcodes.L2F -> conversion(instruction, Type.LONG_TYPE, Type.FLOAT_TYPE);
                    case Opcodes.L2D -> conversion(instruction, Type.LONG_TYPE, Type.DOUBLE_TYPE);
                    case Opcodes.F2I -> conversion(instruction, Type.FLOAT_TYPE, Type.INT_TYPE);
                    case Opcodes.F2L -> conversion(instruction, Type.FLOAT_TYPE, Type.LONG_TYPE);
                    case Opcodes.F2D -> conversion(instruction, Type.FLOAT_TYPE, Type.DOUBLE_TYPE);
                    case Opcodes.D2I -> conversion(instruction, Type.DOUBLE_TYPE, Type.INT_TYPE);
                    case Opcodes.D2L -> conversion(instruction, Type.DOUBLE_TYPE, Type.LONG_TYPE);
                    case Opcodes.D2F -> conversion(instruction, Type.DOUBLE_TYPE, Type.FLOAT_TYPE);
                    case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> {
                        Type type = opcode == Opcodes.LCMP ? Type.LONG_TYPE
                                : opcode == Opcodes.FCMPL || opcode == Opcodes.FCMPG ? Type.FLOAT_TYPE : Type.DOUBLE_TYPE;
                        Value left = numericOperand(0, type), right = numericOperand(1, type);
                        emit(new Value(Type.INT_TYPE, new Comparison(Type.INT_TYPE, left.node(), right.node(), opcode),
                                append(instruction, left, right), null));
                    }
                    case Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.IFEQ, Opcodes.IFNE,
                            Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> {
                        Value subject = operand(0);
                        consumedInputs = 1;
                        if (!nativePower) {
                            int end = nullableInput(instructions, pc, subject);
                            if (end >= 0) {
                                var frame = flow.frameBefore(instructions[end]);
                                if (produced == null || frame == null || frame.stack().isEmpty())
                                    throw new Rejected("Nullable normalization lacks its merged IR value");
                                replacements.put(frame.stack().get(frame.stack().size() - 1), produced);
                                pc = end;
                                predecessor = end;
                                continue;
                            }
                            flushOperandValues();
                            return conditional(pc, condition(opcode, subject), visited);
                        }
                        if (branch(opcode, subject)) {
                            int target = method.instructions.indexOf(((JumpInsnNode) instruction).label);
                            if (target <= pc) throw new Rejected("Backward branches are not supported");
                            pc = target - 1;
                        }
                    }
                    case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT,
                            Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE -> {
                        Value left = numericOperand(0, Type.INT_TYPE), right = numericOperand(1, Type.INT_TYPE);
                        consumedInputs = 2;
                        String relation = relation(opcode - Opcodes.IF_ICMPEQ + Opcodes.IFEQ);
                        return conditional(pc, new Value(Type.INT_TYPE,
                                new Function(Type.INT_TYPE, relation, List.of(left.node(), right.node())),
                                booleanCode(opcode, left, right), null), visited);
                    }
                    case Opcodes.GOTO -> {
                        int target = method.instructions.indexOf(((JumpInsnNode) instruction).label);
                        if (target <= pc) throw new Rejected("Backward branches are not supported");
                        pc = target - 1;
                    }
                    case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN -> {
                        Type type = arithmeticType(opcode - Opcodes.IRETURN);
                        var frame = flow.frameBefore(instruction);
                        if (!type.equals(numericReturn(method)) || frame == null || frame.stack().size() != 1)
                            throw new Rejected("Invalid numeric return stack");
                        return numericOperand(0, type);
                    }
                    case Opcodes.PUTFIELD, Opcodes.PUTSTATIC, Opcodes.IINC ->
                            throw new Rejected("Writes and mutations are not supported");
                    default -> throw new Rejected("Unsupported instruction opcode " + opcode);
                }
                if (produced != null) {
                    var outputs = flow.instruction(instruction).results();
                    if (outputs.size() != 1) throw new Rejected("Shared data flow lacks a unique lowered result");
                    values.put(unique(outputs.get(0)), produced);
                }
            }
            throw new Rejected("Formula has no numeric return");
        }

        private void enter(AbstractInsnNode instruction, int predecessor) {
            current = instruction;
            if (!flow.reachable(instruction)) throw new Rejected("Execution region is unreachable in the shared IR");
            if (predecessor < 0) return;
            FormulaDataFlow.IncomingFrame incoming = null;
            for (var candidate : flow.incoming(instruction)) {
                if (candidate.edge().kind() != FormulaDataFlow.EdgeKind.NORMAL || candidate.edge().from() != predecessor) continue;
                if (incoming != null) throw new Rejected("Execution region lacks an exact incoming IR edge");
                incoming = candidate;
            }
            if (incoming == null) throw new Rejected("Execution region lacks an exact incoming IR edge");
            var frame = flow.frameBefore(instruction);
            Map<FormulaDataFlow.Value, FormulaDataFlow.Value> updates = new HashMap<>();
            refine(frame.locals(), incoming.frame().locals(), updates);
            refine(frame.stack(), incoming.frame().stack(), updates);
            narrowed.putAll(updates);
        }

        private void refine(List<FormulaDataFlow.Value> merged, List<FormulaDataFlow.Value> incoming,
                            Map<FormulaDataFlow.Value, FormulaDataFlow.Value> updates) {
            if (merged.size() != incoming.size()) throw new Rejected("Shared IR incoming frame size mismatch");
            for (int index = 0; index < merged.size(); index++) {
                var key = merged.get(index);
                if (key.sources().isEmpty()) continue;
                var selected = refined(incoming.get(index));
                if (key.equals(selected)) continue;
                if (!key.sources().containsAll(selected.sources()))
                    throw new Rejected("Incoming IR value is not a proven merge alternative");
                var previous = updates.putIfAbsent(key, selected);
                if (previous != null && !previous.equals(selected))
                    throw new Rejected("Incoming IR values have ambiguous slot provenance");
            }
        }

        private FormulaDataFlow.Value refined(FormulaDataFlow.Value source) {
            if (!narrowed.containsKey(source)) return source;
            Set<FormulaDataFlow.Value> seen = new HashSet<>();
            while (narrowed.containsKey(source)) {
                if (!seen.add(source)) throw new Rejected("Cyclic shared IR merge binding");
                source = narrowed.get(source);
            }
            return source;
        }

        private static FormulaDataFlow.Node unique(FormulaDataFlow.Value source) {
            if (source.sources().size() != 1) throw new Rejected("Shared IR value has no exact producer");
            return source.sources().iterator().next();
        }

        private Value value(FormulaDataFlow.Value source) {
            Value replacement = replacements.get(source);
            if (replacement != null) return replacement;
            source = refined(source);
            replacement = replacements.get(source);
            if (replacement != null) return replacement;
            var node = unique(source);
            Value value = node.kind() == FormulaDataFlow.NodeKind.PARAMETER
                    ? parameters.get(node.parameterSlot()) : values.get(node);
            if (value == null) throw new Rejected("Shared IR source was not evaluated on the selected path: " + node.id());
            return value;
        }

        private Value operand(int index) {
            var operands = flow.operands(current);
            if (index < 0 || index >= operands.size()) throw new Rejected("Shared IR operand is unavailable");
            return value(operands.get(index));
        }

        private Value numericOperand(int index, Type type) {
            Value value = operand(index);
            if (!value.type().equals(type) || value.node() == null) throw new Rejected("Numeric operand type mismatch");
            return value;
        }

        private void emit(Value value) { produced = value; }

        private Value conditional(int pc, Value condition, Set<Integer> visited) {
            session.inputs.checkConditionalBudget();
            int target = method.instructions.indexOf(((JumpInsnNode) method.instructions.get(pc)).label);
            if (target <= pc) throw new Rejected("Backward branches are not supported");
            flushOperandValues();
            condition = session.inputs.capture(condition);
            Set<String> initialized = new HashSet<>(session.initializedHelpers);
            Value taken;
            Value other;
            List<AbstractInsnNode> takenPrelude;
            List<AbstractInsnNode> otherPrelude;
            int prefix = session.inputs.beginConditional();
            try {
                taken = new GetterLowering(this).walk(target, pc, new HashSet<>(visited));
                takenPrelude = session.inputs.takeBranchPrelude(prefix);
                session.initializedHelpers.clear();
                session.initializedHelpers.addAll(initialized);
                other = new GetterLowering(this).walk(pc + 1, pc, new HashSet<>(visited));
                otherPrelude = session.inputs.takeBranchPrelude(prefix);
            } finally {
                session.inputs.endConditional();
                session.initializedHelpers.clear();
                session.initializedHelpers.addAll(initialized);
            }
            if (!taken.type().equals(other.type()) || taken.node() == null || other.node() == null)
                throw new Rejected("Conditional paths must return the same numeric type");
            session.inputs.appendBranchPrelude(selectCode(condition.instructions(), takenPrelude, otherPrelude));
            return new Value(taken.type(), new Function(taken.type(), "if", List.of(condition.node(), taken.node(), other.node())),
                    selectCode(condition.instructions(), taken.instructions(), other.instructions()), null);
        }

        private Value condition(int opcode, Value subject) {
            if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) {
                if (!reference(subject.type()) || subject.reference() == null)
                    throw new Rejected("Null branch requires a proven reference source");
                String tag = referenceName(subject.reference());
                Value present = session.inputs.bind(tag + "_present", Type.INT_TYPE,
                        booleanCode(Opcodes.IFNONNULL, subject), "nonnull:" + subject.reference().identity());
                Node node = opcode == Opcodes.IFNONNULL ? present.node()
                        : new Function(Type.INT_TYPE, "eq", List.of(present.node(), new Constant(Type.INT_TYPE, 0)));
                return new Value(Type.INT_TYPE, node, opcode == Opcodes.IFNONNULL
                        ? present.instructions() : booleanCode(Opcodes.IFEQ, present), null);
            }
            if (!subject.type().equals(Type.INT_TYPE) || subject.node() == null)
                throw new Rejected("Numeric branch requires an integer condition");
            if (subject.node() instanceof Predicate predicate) {
                Value actor = predicate.subject();
                if (actor.reference() == null) throw new Rejected("Type condition lacks a proven reference source");
                String tag = referenceName(actor.reference());
                String target = predicate.target().substring(predicate.target().lastIndexOf('/') + 1);
                subject = session.inputs.bind(tag + "_is_" + sourceName(target), Type.INT_TYPE,
                        subject.instructions(), "instanceof:" + actor.reference().identity() + ':' + predicate.target());
            }
            Node node;
            if (subject.node() instanceof Comparison comparison) {
                node = new Function(Type.INT_TYPE, relation(opcode), List.of(comparison.left(), comparison.right()));
                if (comparison.opcode() != Opcodes.LCMP && opcode != Opcodes.IFEQ && opcode != Opcodes.IFNE) {
                    int nan = comparison.opcode() == Opcodes.FCMPL || comparison.opcode() == Opcodes.DCMPL ? -1 : 1;
                    boolean take = switch (opcode) {
                        case Opcodes.IFLT -> nan < 0;
                        case Opcodes.IFLE -> nan <= 0;
                        case Opcodes.IFGT -> nan > 0;
                        case Opcodes.IFGE -> nan >= 0;
                        default -> throw new Rejected("Invalid comparison branch");
                    };
                    Node fallback = new Constant(Type.INT_TYPE, take ? 1 : 0);
                    node = new Function(Type.INT_TYPE, "if", List.of(
                            new Function(Type.INT_TYPE, "eq", List.of(comparison.left(), comparison.left())),
                            new Function(Type.INT_TYPE, "if", List.of(
                                    new Function(Type.INT_TYPE, "eq", List.of(comparison.right(), comparison.right())), node, fallback)), fallback));
                }
            } else node = new Function(Type.INT_TYPE, relation(opcode), List.of(subject.node(), new Constant(Type.INT_TYPE, 0)));
            return new Value(Type.INT_TYPE, node, booleanCode(opcode, subject), null);
        }

        private static String relation(int opcode) {
            return switch (opcode) {
                case Opcodes.IFEQ -> "eq";
                case Opcodes.IFNE -> "ne";
                case Opcodes.IFLT -> "lt";
                case Opcodes.IFLE -> "le";
                case Opcodes.IFGT -> "gt";
                case Opcodes.IFGE -> "ge";
                default -> throw new Rejected("Unsupported comparison opcode " + opcode);
            };
        }

        private static List<AbstractInsnNode> booleanCode(int opcode, Value... values) {
            var result = new ArrayList<AbstractInsnNode>();
            for (Value value : values) result.addAll(copyCode(value.instructions()));
            LabelNode yes = new LabelNode();
            LabelNode done = new LabelNode();
            result.add(new JumpInsnNode(opcode, yes));
            result.add(new InsnNode(Opcodes.ICONST_0));
            result.add(new JumpInsnNode(Opcodes.GOTO, done));
            result.add(yes);
            result.add(new InsnNode(Opcodes.ICONST_1));
            result.add(done);
            return result;
        }

        private static List<AbstractInsnNode> selectCode(List<AbstractInsnNode> condition, List<AbstractInsnNode> yes,
                                                          List<AbstractInsnNode> no) {
            var result = copyCode(condition);
            LabelNode otherwise = new LabelNode();
            LabelNode done = new LabelNode();
            result.add(new JumpInsnNode(Opcodes.IFEQ, otherwise));
            result.addAll(copyCode(yes));
            result.add(new JumpInsnNode(Opcodes.GOTO, done));
            result.add(otherwise);
            result.addAll(copyCode(no));
            result.add(done);
            return result;
        }

        private boolean branch(int opcode, Value value) {
            if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) {
                if (!reference(value.type())) throw new Rejected("Null branch requires a reference");
                boolean isNull = value.reference().kind().equals("null");
                if (!isNull && !value.reference().kind().equals("this")) guard = value;
                return opcode == Opcodes.IFNULL ? isNull : !isNull;
            }
            if (!(value.node() instanceof Predicate predicate) || !predicate.target().equals(LIVING_ENTITY))
                throw new Rejected("Only native LivingEntity type guards are supported");
            Value subject = predicate.subject();
            boolean matches = !subject.reference().kind().equals("null");
            if (subject.reference().kind().equals("this"))
                matches = session.repository.isSubtype(subject.type().getInternalName(), predicate.target());
            else if (matches) guard = subject;
            return opcode == Opcodes.IFEQ ? !matches : matches;
        }

        private int nullableInput(AbstractInsnNode[] instructions, int pc, Value subject) {
            if (instructions[pc].getOpcode() != Opcodes.IFNONNULL || subject.reference() == null) return -1;
            JumpInsnNode branch = (JumpInsnNode) instructions[pc];
            int present = method.instructions.indexOf(branch.label);
            if (present <= pc) return -1;
            List<AbstractInsnNode> fallback = executable(instructions, pc + 1, present);
            if (fallback.size() != 2 || fallback.get(0).getOpcode() != Opcodes.FCONST_1
                    || !(fallback.get(1) instanceof JumpInsnNode merge) || merge.getOpcode() != Opcodes.GOTO) return -1;
            int end = method.instructions.indexOf(merge.label);
            if (end <= present) return -1;
            List<AbstractInsnNode> nonnull = executable(instructions, present, end);
            String name;
            AbstractInsnNode actor;
            if (nonnull.size() == 6 && sameActor(nonnull.get(0), subject)
                    && fieldMatches(nonnull.get(1), ATTRIBUTE_REGISTRY, "SUMMON_DAMAGE", 'L' + DEFERRED_HOLDER + ';')
                    && callMatches(nonnull.get(2), Opcodes.INVOKEVIRTUAL, DEFERRED_HOLDER, "get", "()Ljava/lang/Object;")
                    && castMatches(nonnull.get(3), "net/minecraft/world/entity/ai/attributes/Attribute")
                    && attributeValueCall(nonnull.get(4)) && nonnull.get(5).getOpcode() == Opcodes.D2F) {
                name = "summon_damage";
                actor = nonnull.get(0);
            } else return -1;
            flushOperandValues();
            var code = new ArrayList<AbstractInsnNode>(subject.instructions());
            for (int index = pc; index <= end; index++) {
                AbstractInsnNode instruction = instructions[index];
                if (instruction == actor) code.addAll(subject.instructions());
                else if (!(instruction instanceof FrameNode) && !(instruction instanceof LineNumberNode)) code.add(instruction);
            }
            privateLabels(code);
            emit(session.inputs.bind(name, Type.FLOAT_TYPE, code, "nullable:" + name + ':' + subject.reference().identity()));
            return end;
        }

        private boolean sameActor(AbstractInsnNode instruction, Value subject) {
            if (!(instruction instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ALOAD) return false;
            var frame = flow.frameBefore(instruction);
            if (frame == null || variable.var >= frame.locals().size()) return false;
            Value value = value(frame.locals().get(variable.var));
            return value != null && value.reference() != null
                    && value.reference().identity().equals(subject.reference().identity());
        }

        private void constant(Type type, Number value, AbstractInsnNode instruction) {
            emit(new Value(type, new Constant(type, value), List.of(instruction), null));
        }

        private void field(FieldInsnNode instruction) {
            Type type = Type.getType(instruction.desc);
            Value receiver = instruction.getOpcode() == Opcodes.GETFIELD ? value(flow.instruction(instruction).field().receiver()) : null;
            consumedInputs = receiver == null ? 0 : 1;
            if (receiver != null && !reference(receiver.type()))
                throw new Rejected("Field receiver is not a reference");
            List<AbstractInsnNode> code = receiver == null ? List.of(instruction) : append(instruction, receiver);
            if (instruction.getOpcode() == Opcodes.GETSTATIC && instruction.owner.equals(MOB_TYPE)
                    && instruction.desc.equals('L' + MOB_TYPE + ';')
                    && Set.of("UNDEFINED", "UNDEAD", "f_21640_", "f_21641_").contains(instruction.name)) {
                emit(new Value(type, null, code,
                        new Reference("field", instruction.owner + '.' + instruction.name, instruction.name, null)));
                return;
            }
            var declaration = session.repository.resolveField(instruction.owner, instruction.name, instruction.desc);
            if (declaration == null) throw new Rejected("Field metadata is unavailable: " + instruction.owner + '.' + instruction.name);
            if ((declaration.field().access & Opcodes.ACC_VOLATILE) != 0)
                throw new Rejected("Volatile fields are not supported");
            if (numeric(type)) {
                flushOperandValues();
                String identity = receiver == null ? "static" : receiver.reference().identity();
                String declaring = declaration.owner().name;
                emit(session.inputs.bind(instruction.name, type, code,
                        "field:" + declaring + '.' + instruction.name + instruction.desc + ':' + identity));
            } else if (reference(type) && instruction.getOpcode() == Opcodes.GETSTATIC
                    && (instruction.owner.equals(ATTRIBUTE_REGISTRY) || instruction.owner.equals(CONFIG_PARAMETER)
                    || instruction.owner.equals(ENCHANTMENTS) && type.equals(Type.getObjectType("net/minecraft/world/item/enchantment/Enchantment"))
                    || (instruction.owner.equals(DATA_COMPONENTS) || instruction.owner.equals(ENCHANTMENT_COMPONENTS))
                    && type.equals(Type.getObjectType(COMPONENT_TYPE)))) {
                emit(new Value(type, null, code,
                        new Reference("field", instruction.owner + '.' + instruction.name, instruction.name, null)));
            } else if (receiver != null && receiver.reference() != null && FormulaMinecraftNames.method(instruction.name).equals("level")
                    && type.equals(Type.getObjectType(LEVEL))
                    && (instruction.owner.equals(LIVING_ENTITY) || session.repository.isSubtype(instruction.owner, LIVING_ENTITY))) {
                emit(new Value(type, null, code, new Reference("level",
                        "level:" + receiver.reference().identity(), "level", receiver)));
            } else if (instruction.getOpcode() == Opcodes.GETSTATIC && instruction.owner.equals(SPELL_REGISTRY)
                    && instruction.desc.equals("L" + DEFERRED_HOLDER + ";")
                    && (declaration.field().access & Opcodes.ACC_FINAL) != 0) {
                String spellType = registeredSpellType(instruction);
                if (spellType == null) throw new Rejected("Registry supplier is not a proven concrete spell: " + instruction.name);
                emit(new Value(type, null, code,
                        new Reference("registered_supplier", instruction.owner + '.' + instruction.name, spellType, null)));
            } else if (instruction.getOpcode() == Opcodes.GETSTATIC && type.equals(Type.getObjectType(DEFERRED_HOLDER))
                    && (declaration.field().access & Opcodes.ACC_FINAL) != 0 && declaration.field().signature != null
                    && declaration.field().signature.startsWith("L" + DEFERRED_HOLDER
                    + "<Lnet/minecraftforge/attachment/AttachmentType<*>;")) {
                emit(new Value(type, null, code,
                        new Reference("attachment", instruction.owner + '.' + instruction.name, instruction.name, null)));
            } else throw new Rejected("Unsupported reference field " + instruction.owner + '.' + instruction.name);
        }

        private String registeredSpellType(FieldInsnNode field) {
            ClassNode registry = session.repository.classNode(field.owner);
            if (registry == null) return null;
            MethodNode initializer = registry.methods.stream().filter(value -> value.name.equals("<clinit>"))
                    .findFirst().orElse(null);
            MethodNode register = registry.methods.stream().filter(value -> value.name.equals("registerSpell")
                    && value.desc.equals("(L" + ABSTRACT_SPELL + ";)L" + DEFERRED_HOLDER + ";"))
                    .findFirst().orElse(null);
            if (initializer == null || register == null || initializer.instructions.size() > 20000) return null;
            List<AbstractInsnNode> registration = executable(register.instructions.toArray(), 0, register.instructions.size());
            if (registration.size() != 7 || !variableMatches(registration.get(1), Opcodes.ALOAD, 0)
                    || !callMatches(registration.get(2), Opcodes.INVOKEVIRTUAL, ABSTRACT_SPELL, "getSpellName", "()Ljava/lang/String;")
                    || !variableMatches(registration.get(3), Opcodes.ALOAD, 0)
                    || !(registration.get(4) instanceof InvokeDynamicInsnNode supplier)
                    || !supplier.desc.equals("(L" + ABSTRACT_SPELL + ";)Ljava/util/function/Supplier;")
                    || !supplier.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                    || supplier.bsmArgs.length != 3 || !(supplier.bsmArgs[1] instanceof org.objectweb.asm.Handle implementation)
                    || implementation.getTag() != Opcodes.H_INVOKESTATIC || !implementation.getOwner().equals(field.owner)
                    || !implementation.getDesc().equals("(L" + ABSTRACT_SPELL + ";)L" + ABSTRACT_SPELL + ';')
                    || !callMatches(registration.get(5), Opcodes.INVOKEVIRTUAL, "net/minecraftforge/registries/DeferredRegister",
                    "register", "(Ljava/lang/String;Ljava/util/function/Supplier;)L" + DEFERRED_HOLDER + ';')
                    || registration.get(6).getOpcode() != Opcodes.ARETURN) return null;
            MethodNode lambda = registry.methods.stream().filter(value -> value.name.equals(implementation.getName())
                    && value.desc.equals(implementation.getDesc())).findFirst().orElse(null);
            if (lambda == null) return null;
            List<AbstractInsnNode> lambdaCode = executable(lambda.instructions.toArray(), 0, lambda.instructions.size());
            if (lambdaCode.size() != 2 || !variableMatches(lambdaCode.get(0), Opcodes.ALOAD, 0)
                    || lambdaCode.get(1).getOpcode() != Opcodes.ARETURN) return null;
            List<AbstractInsnNode> code = executable(initializer.instructions.toArray(), 0, initializer.instructions.size());
            String concrete = null;
            for (int index = 4; index < code.size(); index++) {
                if (!(code.get(index) instanceof FieldInsnNode write) || write.getOpcode() != Opcodes.PUTSTATIC
                        || !write.owner.equals(field.owner) || !write.name.equals(field.name) || !write.desc.equals(field.desc)) continue;
                if (concrete != null || !(code.get(index - 4) instanceof TypeInsnNode creation)
                        || creation.getOpcode() != Opcodes.NEW || code.get(index - 3).getOpcode() != Opcodes.DUP
                        || !callMatches(code.get(index - 2), Opcodes.INVOKESPECIAL, creation.desc, "<init>", "()V")
                        || !callMatches(code.get(index - 1), Opcodes.INVOKESTATIC, field.owner, register.name, register.desc)
                        || !session.repository.isSubtype(creation.desc, ABSTRACT_SPELL)) return null;
                concrete = creation.desc;
            }
            return concrete;
        }

        private void call(MethodInsnNode instruction) {
            flushOperandValues();
            Type[] parameterTypes = Type.getArgumentTypes(instruction.desc);
            var arguments = new ArrayList<Value>();
            var sources = flow.arguments(instruction);
            if (sources.size() != parameterTypes.length) throw new Rejected("Shared IR call argument count mismatch");
            for (int index = 0; index < parameterTypes.length; index++) {
                Value argument = value(sources.get(index));
                if (reference(parameterTypes[index]) ? !reference(argument.type()) : !parameterTypes[index].equals(argument.type()))
                    throw new Rejected("Call argument type mismatch");
                arguments.add(argument);
            }
            Value receiver = instruction.getOpcode() == Opcodes.INVOKESTATIC ? null : value(flow.receiver(instruction));
            consumedInputs = arguments.size() + (receiver == null ? 0 : 1);
            if (receiver != null && !reference(receiver.type()))
                throw new Rejected("Call receiver is not a reference");
            Type result = Type.getReturnType(instruction.desc);
            List<AbstractInsnNode> code = append(instruction, callInputs(receiver, arguments));
            if (api(instruction, receiver, arguments, result, code)) return;
            if (math(instruction, parameterTypes, arguments, result, code)) return;
            if (!numeric(result) && !result.equals(Type.BOOLEAN_TYPE))
                throw new Rejected("Unknown reference call " + instruction.owner + '.' + instruction.name);
            String target = instruction.owner;
            var symbolic = session.repository.resolveMethod(target, instruction.name, instruction.desc);
            boolean privateCall = symbolic != null && (symbolic.method().access & Opcodes.ACC_PRIVATE) != 0;
            if (receiver != null) {
                if (!Set.of("this", "registered_spell").contains(receiver.reference().kind()))
                    throw new Rejected("Unresolved virtual receiver " + instruction.owner + '.' + instruction.name);
                if (instruction.getOpcode() != Opcodes.INVOKESPECIAL && !privateCall) target = receiver.type().getInternalName();
            }
            var resolved = session.repository.resolveInvocation(owner.name, instruction, receiver == null ? null : target).target();
            if (resolved == null)
                throw new Rejected("Method bytecode is unavailable: " + target + '.' + instruction.name + instruction.desc);
            if ((resolved.owner().access & Opcodes.ACC_INTERFACE) != 0)
                throw new Rejected("Interface helper dispatch is not supported: " + resolved.owner().name);
            if (receiver != null && receiver.reference().kind().equals("this")
                    && instruction.getOpcode() != Opcodes.INVOKESPECIAL && !privateCall
                    && (session.receiverClass != null && !session.receiverClass.equals(session.rootOwner.name)
                    || (resolved.method().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) == 0
                    && (session.rootOwner.access & Opcodes.ACC_FINAL) == 0))
                session.inputs.requireExactReceiver();
            boolean initialize = instruction.getOpcode() == Opcodes.INVOKESTATIC
                    && !session.safeInitialization(resolved.owner().name, new HashSet<>());
            if (initialize) flushOperandValues();
            for (int index = 0; index < arguments.size(); index++) {
                Value argument = arguments.get(index);
                if (needsCapture(argument))
                    arguments.set(index, session.inputs.capture(argument));
            }
            if (initialize && session.initializedHelpers.add(resolved.owner().name)) {
                session.inputs.initializeHelper(resolved.owner().name);
            }
            Value expanded = session.method(resolved.owner(), resolved.method(), receiver, arguments);
            if (session.preserveNativePower && resolved.owner().name.equals(ABSTRACT_SPELL)
                    && (resolved.method().name.equals("getSpellPower")
                    && resolved.method().desc.equals("(ILnet/minecraft/world/entity/Entity;)F")
                    || resolved.method().name.equals("getEntityPowerMultiplier")
                    && resolved.method().desc.equals("(Lnet/minecraft/world/entity/LivingEntity;)F"))) {
                String marker = "__native_power_" + session.nativePowers.size();
                session.nativePowers.put(marker, new NativePower(receiver.instructions(),
                        arguments.get(arguments.size() - 1).instructions(), resolved.method().name.equals("getSpellPower")
                        ? PowerSource.SPELL_POWER : PowerSource.ENTITY_MULTIPLIER));
                expanded = new Value(expanded.type(), new Function(expanded.type(), marker, List.of(expanded.node())),
                        expanded.instructions(), expanded.reference());
            }
            emit(expanded);
        }

        private boolean api(MethodInsnNode instruction, Value receiver, List<Value> arguments, Type result,
                            List<AbstractInsnNode> code) {
            if (weaponApi(instruction, receiver, arguments, result, code)) return true;
            if (receiver != null && receiver.reference() != null && receiver.reference().kind().equals("registered_supplier")
                    && instruction.owner.equals(DEFERRED_HOLDER) && FormulaMinecraftNames.method(instruction.name).equals("get")
                    && instruction.desc.equals("()Ljava/lang/Object;")) {
                String concrete = receiver.reference().tag();
                var concreteCode = copyCode(code);
                concreteCode.add(new TypeInsnNode(Opcodes.CHECKCAST, concrete));
                emit(new Value(Type.getObjectType(concrete), null, concreteCode,
                        new Reference("registered_spell", receiver.reference().identity(), concrete, null)));
                return true;
            }
            if (receiver != null && receiver.reference() != null
                    && (instruction.owner.equals(LIVING_ENTITY) || session.repository.isSubtype(instruction.owner, LIVING_ENTITY))
                    && instruction.name.equals("hasData") && instruction.desc.equals("(Ljava/util/function/Supplier;)Z")
                    && arguments.size() == 1 && arguments.get(0).reference() != null
                    && arguments.get(0).reference().kind().equals("attachment")) {
                Reference attachment = arguments.get(0).reference();
                emit(session.inputs.bind("has_" + sourceName(attachment.tag()), Type.INT_TYPE, code,
                        "hasData:" + receiver.reference().identity() + ':' + attachment.identity()));
                return true;
            }
            if (receiver != null && receiver.reference().kind().equals("this")
                    && instruction.getOpcode() == Opcodes.INVOKEVIRTUAL && instruction.name.equals("getCastTime")
                    && instruction.desc.equals("(I)I") && (instruction.owner.equals(ABSTRACT_SPELL)
                    || session.repository.isSubtype(instruction.owner, ABSTRACT_SPELL))) {
                emit(session.inputs.bind("native_cast_time", Type.INT_TYPE, code,
                        "castTime:" + receiver.reference().identity() + ':' + arguments.get(0).node().text()));
                return true;
            }
            if (session.rootOwner.name.equals(SITE_VALUES) && instruction.getOpcode() == Opcodes.INVOKESTATIC
                    && instruction.owner.equals("com/example/spell_compiler/formula/FormulaRuntimeHooks")
                    && (instruction.name.equals("applyPower") || instruction.name.equals("applyEntityPower"))
                    && instruction.desc.equals("(FLjava/lang/Object;Ljava/lang/Object;)F")) {
                Value input = arguments.get(0);
                Node node = input.node();
                if (session.preserveNativePower) {
                    String marker = "__native_power_" + session.nativePowers.size();
                    session.nativePowers.put(marker, new NativePower(arguments.get(1).instructions(), arguments.get(2).instructions(),
                            instruction.name.equals("applyEntityPower") || session.rootMethod.name.equals("thunderstormDamage")
                                    ? PowerSource.ENTITY_MULTIPLIER : PowerSource.SPELL_POWER));
                    node = new Function(result, marker, List.of(node));
                }
                emit(new Value(result, node, input.instructions(), null));
                return true;
            }
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC && instruction.owner.equals(UTILS)
                    && instruction.name.equals("softCapFormula") && instruction.desc.equals("(D)D")) {
                emit(new Value(result, new Function(result, "softcap", List.of(arguments.get(0).node())), code, null));
                return true;
            }
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC && instruction.owner.equals(UTILS)
                    && instruction.name.equals("getWeaponDamage")
                    && (instruction.desc.equals("(L" + LIVING_ENTITY + ";)F")
                    || instruction.desc.equals("(L" + LIVING_ENTITY + ";L" + MOB_TYPE + ";)F"))) {
                String source = instruction.desc + ':' + arguments.get(0).reference().identity();
                if (arguments.size() == 2) source += ':' + arguments.get(1).reference().identity();
                emit(session.inputs.bind("weapon_damage", result, code, "weapon:" + source));
                return true;
            }
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC
                    && instruction.owner.equals("net/minecraft/world/item/enchantment/EnchantmentHelper")
                    && Set.of("getFireAspect", "m_44914_").contains(instruction.name)
                    && instruction.desc.equals("(L" + LIVING_ENTITY + ";)I")) {
                emit(session.inputs.bind("fire_aspect_level", result, code,
                        "fireAspect:" + arguments.get(0).reference().identity()));
                return true;
            }
            if (receiver != null && (instruction.owner.equals(LIVING_ENTITY)
                    || session.repository.isSubtype(instruction.owner, LIVING_ENTITY))
                    && instruction.desc.equals("()F") && Set.of("getHealth", "getMaxHealth", "m_21223_", "m_21233_").contains(FormulaMinecraftNames.method(instruction.name))) {
                String name = Set.of("getHealth", "m_21223_").contains(FormulaMinecraftNames.method(instruction.name)) ? "entity_health" : "entity_max_health";
                emit(session.inputs.bind(name, result, code, "entityInput:" + name + ':' + receiver.reference().identity()));
                return true;
            }
            if ((instruction.owner.equals(DEFERRED_HOLDER) || instruction.owner.equals(HOLDER))
                    && (FormulaMinecraftNames.method(instruction.name).equals("get") || instruction.name.equals("value"))
                    && receiver != null && receiver.reference().kind().equals("field")) {
                emit(new Value(result, null, code, receiver.reference()));
                return true;
            }
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC && instruction.owner.equals(LEGACY_CONFIG)
                    && instruction.name.equals("getSpellConfig")
                    && instruction.desc.equals("(L" + ABSTRACT_SPELL + ";)L" + LEGACY_PARAMETERS + ';')
                    && arguments.size() == 1 && arguments.get(0).reference() != null) {
                Value subject = arguments.get(0);
                session.legacyConfiguration = true;
                emit(new Value(result, null, code, new Reference("legacy_config", "legacyConfig:"
                        + subject.reference().identity(), null, subject)));
                return true;
            }
            if (receiver != null && receiver.reference() != null && receiver.reference().kind().equals("legacy_config")
                    && instruction.owner.equals(LEGACY_PARAMETERS) && arguments.isEmpty()) {
                String key = switch (instruction.name + instruction.desc) {
                    case "powerMultiplier()D" -> "power_multiplier";
                    case "manaMultiplier()D" -> "mana_cost_multiplier";
                    case "cooldownInTicks()I" -> "cooldown_in_seconds";
                    default -> null;
                };
                if (key != null) {
                    var source = copyCode(receiver.reference().subject().instructions());
                    source.add(new LdcInsnNode(key));
                    source.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/example/spell_compiler/formula/FormulaNativeValues",
                            "readConfig", "(L" + ABSTRACT_SPELL + ";Ljava/lang/Object;)Ljava/lang/Object;", false));
                    source.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
                    source.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false));
                    flushOperandValues();
                    Value value = session.inputs.bind(registeredInput(receiver.reference().subject(),
                                    key.equals("mana_cost_multiplier") ? "mana_multiplier" : key),
                            Type.DOUBLE_TYPE, source, "config:" + receiver.reference().identity() + ':' + key);
                    if (instruction.name.equals("cooldownInTicks")) {
                        var converted = new ArrayList<AbstractInsnNode>(value.instructions());
                        converted.add(new LdcInsnNode(20.0));
                        converted.add(new InsnNode(Opcodes.DMUL));
                        converted.add(new InsnNode(Opcodes.D2I));
                        emit(new Value(Type.INT_TYPE, new Conversion(Type.INT_TYPE,
                                new Binary(Type.DOUBLE_TYPE, value.node(), "*", new Constant(Type.DOUBLE_TYPE, 20.0))), converted, null));
                    } else emit(value);
                    return true;
                }
            }
            if (instruction.owner.equals(CONFIG_MANAGER) && instruction.name.equals("getSpellConfigValue")
                    && arguments.size() == 2 && arguments.get(1).reference() != null
                    && arguments.get(1).reference().identity().startsWith(CONFIG_PARAMETER + '.')) {
                emit(new Value(result, null, code,
                        new Reference("config", arguments.get(1).reference().identity(),
                                arguments.get(1).reference().tag(), arguments.get(0))));
                return true;
            }
            if (receiver != null && receiver.reference().kind().equals("config") && numeric(result)
                    && arguments.isEmpty() && (instruction.owner.equals("java/lang/Double")
                    || instruction.owner.equals("java/lang/Float") || instruction.owner.equals("java/lang/Number"))
                    && List.of("floatValue", "doubleValue", "intValue", "longValue").contains(instruction.name)) {
                String name = receiver.reference().tag().toLowerCase(Locale.ROOT);
                flushOperandValues();
                emit(session.inputs.bind(name, result, code, "config:" + receiver.reference().identity()));
                return true;
            }
            if (receiver != null && Set.of("this", "registered_spell").contains(receiver.reference().kind())
                    && instruction.name.equals("getSchoolType")
                    && instruction.desc.equals("()L" + SCHOOL + ';')) {
                emit(new Value(result, null, code, new Reference("school", "school:" + receiver.reference().identity(), null, receiver)));
                return true;
            }
            if (instruction.owner.equals(SCHOOL) && instruction.name.equals("getPowerFor")
                    && instruction.desc.equals("(L" + LIVING_ENTITY + ";)D") && receiver != null
                    && receiver.reference().kind().equals("school")) {
                Value actor = arguments.get(0);
                flushOperandValues();
                emit(session.inputs.bind(session.legacyConfiguration ? registeredInput(receiver.reference().subject(), "school_spellpower")
                                : "school_spellpower", result, guarded(code, result, actor),
                        "school:" + receiver.reference().identity() + ':' + actor.reference().identity()));
                return true;
            }
            if ((instruction.owner.equals(LIVING_ENTITY) || session.repository.isSubtype(instruction.owner, LIVING_ENTITY))
                    && (FormulaMinecraftNames.method(instruction.name).equals("getAttributeValue") || instruction.name.equals("m_21133_"))
                    && instruction.desc.equals("(Lnet/minecraft/world/entity/ai/attributes/Attribute;)D")
                    && receiver != null && arguments.size() == 1 && arguments.get(0).reference() != null
                    && arguments.get(0).reference().kind().equals("field")
                    && arguments.get(0).reference().identity().startsWith(ATTRIBUTE_REGISTRY + '.')) {
                Reference attribute = arguments.get(0).reference();
                boolean nativePower = attribute.identity().equals(ATTRIBUTE_REGISTRY + ".SPELL_POWER");
                var declaration = session.repository.resolveField(ATTRIBUTE_REGISTRY, attribute.tag(), 'L' + DEFERRED_HOLDER + ';');
                if (!nativePower && (declaration == null || (declaration.field().access & Opcodes.ACC_FINAL) == 0
                        || declaration.field().signature == null || !declaration.field().signature.startsWith("L" + DEFERRED_HOLDER
                        + "<Lnet/minecraft/world/entity/ai/attributes/Attribute;"))) return false;
                flushOperandValues();
                emit(session.inputs.bind(nativePower ? "spellpower" : sourceName(attribute.tag()), result, guarded(code, result, receiver),
                        "attribute:" + attribute.tag() + ':' + receiver.reference().identity()));
                return true;
            }
            return false;
        }

        private String registeredInput(Value subject, String name) {
            if (subject == null || subject.reference() == null || !subject.reference().kind().equals("registered_spell")) return name;
            String identity = subject.reference().identity();
            String field = identity.substring(identity.lastIndexOf('.') + 1);
            if (field.endsWith("_SPELL")) field = field.substring(0, field.length() - 6);
            return "registered_" + sourceName(field) + '_' + FormulaNames.input(name);
        }

        private boolean weaponApi(MethodInsnNode instruction, Value receiver, List<Value> arguments, Type result,
                                  List<AbstractInsnNode> code) {
            if (receiver != null && receiver.reference() != null
                    && (instruction.owner.equals(LIVING_ENTITY) || session.repository.isSubtype(instruction.owner, LIVING_ENTITY))
                    && Set.of("getMainHandItem", "m_21205_").contains(FormulaMinecraftNames.method(instruction.name)) && instruction.desc.equals("()L" + ITEM_STACK + ';')) {
                emit(new Value(result, null, code, new Reference("weapon",
                        "weapon:" + receiver.reference().identity(), "weapon", receiver)));
                return true;
            }
            if (receiver != null && receiver.reference() != null && receiver.reference().kind().equals("weapon")
                    && instruction.owner.equals(ITEM_STACK)) {
                if (FormulaMinecraftNames.method(instruction.name).equals("isEmpty") && instruction.desc.equals("()Z")) {
                    emit(session.inputs.bind("weapon_is_empty", Type.INT_TYPE, code,
                            "weaponEmpty:" + receiver.reference().identity()));
                    return true;
                }
                if (arguments.size() == 1 && arguments.get(0).reference() != null
                        && arguments.get(0).reference().identity().equals(DATA_COMPONENTS + ".ENCHANTMENTS")) {
                    if (instruction.name.equals("has") && instruction.desc.equals("(L" + COMPONENT_TYPE + ";)Z")) {
                        emit(session.inputs.bind("weapon_has_enchantments", Type.INT_TYPE, code,
                                "weaponHasEnchantments:" + receiver.reference().identity()));
                        return true;
                    }
                    if (FormulaMinecraftNames.method(instruction.name).equals("get") && instruction.desc.equals("(L" + COMPONENT_TYPE + ";)Ljava/lang/Object;")) {
                        emit(new Value(result, null, code, new Reference("enchantments",
                                "enchantments:" + receiver.reference().identity(), "enchantments", receiver)));
                        return true;
                    }
                }
            }
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC && instruction.owner.equals(UTILS)
                    && FormulaMinecraftNames.method(instruction.name).equals("getEnchantmentLevel")
                    && instruction.desc.equals("(L" + LEVEL + ";L" + ITEM_STACK + ";Lnet/minecraft/world/item/enchantment/Enchantment;)I")
                    && arguments.get(1).reference() != null && arguments.get(1).reference().kind().equals("weapon")
                    && arguments.get(2).reference() != null && arguments.get(2).reference().kind().equals("field")
                    && arguments.get(2).reference().identity().startsWith(ENCHANTMENTS + '.')) {
                var enchantment = arguments.get(2).reference();
                emit(session.inputs.bind(sourceName(enchantment.tag()) + "_level", result, code,
                        "enchantment:" + enchantment.identity() + ':' + arguments.get(1).reference().identity()));
                return true;
            }
            if (instruction.getOpcode() != Opcodes.INVOKESTATIC || !instruction.owner.equals(UTILS)) return false;
            boolean enchantmentLevel = FormulaMinecraftNames.method(instruction.name).equals("getEnchantmentLevel")
                    && instruction.desc.equals("(L" + LEVEL + ";L" + RESOURCE_KEY + ";L" + ITEM_ENCHANTMENTS + ";)I");
            boolean enchantmentDamage = instruction.name.equals("processEnchantment")
                    && instruction.desc.equals("(L" + LEVEL + ";L" + RESOURCE_KEY + ";L" + COMPONENT_TYPE + ";L" + ITEM_ENCHANTMENTS + ";)F");
            if (!enchantmentLevel && !enchantmentDamage) return false;
            Reference world = arguments.get(0).reference();
            Reference enchantment = arguments.get(1).reference();
            Reference values = arguments.get(arguments.size() - 1).reference();
            if (world == null || !world.kind().equals("level") || enchantment == null
                    || !enchantment.kind().equals("field") || !enchantment.identity().startsWith(ENCHANTMENTS + '.')
                    || values == null || !values.kind().equals("enchantments")) return false;
            String suffix = "level";
            String componentSource = "";
            if (enchantmentDamage) {
                Reference component = arguments.get(2).reference();
                if (component == null || !component.identity().equals(ENCHANTMENT_COMPONENTS + ".DAMAGE")) return false;
                suffix = "damage";
                componentSource = ':' + component.identity();
            }
            String name = sourceName(enchantment.tag()) + '_' + suffix;
            emit(session.inputs.bind(name, result, code,
                    "enchantment:" + world.identity() + ':' + enchantment.identity() + componentSource + ':' + values.identity()));
            return true;
        }

        private boolean needsCapture(Value value) {
            if (numeric(value.type())) return !(value.node() instanceof Input) && !(value.node() instanceof Constant);
            if (nativePower && guard != null) return false;
            return value.instructions().stream().anyMatch(instruction -> instruction.getOpcode() != Opcodes.ALOAD
                    && instruction.getOpcode() != Opcodes.ACONST_NULL);
        }

        private void flushOperandValues() {
            var frame = flow.frameBefore(current);
            if (frame == null || consumedInputs > frame.stack().size())
                throw new Rejected("Shared IR operand frame is unavailable");
            for (int index = 0; index < frame.stack().size() - consumedInputs; index++) {
                var source = frame.stack().get(index);
                Value value = value(source);
                if (needsCapture(value)) replacements.put(refined(source), session.inputs.capture(value));
            }
        }

        private List<AbstractInsnNode> guarded(List<AbstractInsnNode> code, Type type, Value actor) {
            if (guard == null || !guard.reference().identity().equals(actor.reference().identity())) return code;
            var result = new ArrayList<AbstractInsnNode>(guard.instructions());
            LabelNode absent = new LabelNode();
            LabelNode done = new LabelNode();
            result.add(new TypeInsnNode(Opcodes.INSTANCEOF, LIVING_ENTITY));
            result.add(new JumpInsnNode(Opcodes.IFEQ, absent));
            result.addAll(code);
            result.add(new JumpInsnNode(Opcodes.GOTO, done));
            result.add(absent);
            Number fallback;
            if (type.equals(Type.DOUBLE_TYPE)) fallback = Double.valueOf(1);
            else if (type.equals(Type.FLOAT_TYPE)) fallback = Float.valueOf(1);
            else throw new Rejected("Unsupported native input fallback type " + type);
            result.add(new LdcInsnNode(fallback));
            result.add(done);
            return result;
        }

        private boolean math(MethodInsnNode instruction, Type[] expected, List<Value> arguments, Type result,
                             List<AbstractInsnNode> code) {
            if (instruction.getOpcode() != Opcodes.INVOKESTATIC) return false;
            String name = instruction.name;
            boolean mth = instruction.owner.equals("net/minecraft/util/Mth");
            if (!mth && !instruction.owner.equals("java/lang/Math")) return false;
            if (mth) name = switch (name) {
                case "m_14116_" -> "sqrt";
                case "m_14143_", "m_14107_" -> "floor";
                case "m_14167_", "m_14165_" -> "ceil";
                case "m_14154_", "m_14040_" -> "abs";
                case "m_14045_", "m_14036_", "m_14008_" -> "clamp";
                default -> name;
            };
            if (!List.of("min", "max", "clamp", "sqrt", "abs", "floor", "ceil", "round").contains(name)) return false;
            for (Value argument : arguments) requireNumeric(argument.type());
            requireNumeric(result);
            boolean javaIntClamp = !mth && name.equals("clamp") && instruction.desc.equals("(JII)I")
                    && arguments.get(1).node() instanceof Constant minimum
                    && arguments.get(2).node() instanceof Constant maximum
                    && minimum.value().intValue() <= maximum.value().intValue();
            if (name.equals("min") || name.equals("max")) {
                if (arguments.size() != 2 || !expected[0].equals(expected[1]) || !result.equals(expected[0])) return false;
            } else if (name.equals("clamp")) {
                if (!javaIntClamp && (!mth || arguments.size() != 3 || !expected[0].equals(expected[1])
                        || !expected[0].equals(expected[2]) || !result.equals(expected[0]))) return false;
            } else if (arguments.size() != 1) return false;
            List<Node> nodes = arguments.stream().map(Value::node).toList();
            Node node;
            if (javaIntClamp) {
                node = new Conversion(Type.INT_TYPE, new Function(Type.LONG_TYPE, "clamp", nodes));
            } else if (mth && name.equals("sqrt")) {
                if (!instruction.desc.equals("(F)F")) return false;
                node = new Conversion(Type.FLOAT_TYPE, new Function(Type.DOUBLE_TYPE, "sqrt",
                        List.of(new Conversion(Type.DOUBLE_TYPE, nodes.get(0)))));
            } else if (mth && (name.equals("floor") || name.equals("ceil"))) {
                if (!instruction.desc.equals("(F)I") && !instruction.desc.equals("(D)I")) return false;
                node = new Function(Type.INT_TYPE, "mth_" + name, nodes);
            } else {
                if ((name.equals("sqrt") || name.equals("floor") || name.equals("ceil"))
                        && !instruction.desc.equals("(D)D")) return false;
                if (name.equals("round") && !instruction.desc.equals("(F)I") && !instruction.desc.equals("(D)J")) return false;
                if (name.equals("abs") && !result.equals(expected[0])) return false;
                node = new Function(result, name, nodes);
            }
            emit(new Value(result, node, code, null));
            return true;
        }

        private void binary(AbstractInsnNode instruction, String operator) {
            Type type = arithmeticType((instruction.getOpcode() - Opcodes.IADD) % 4);
            Value left = numericOperand(0, type), right = numericOperand(1, type);
            emit(new Value(type, new Binary(type, left.node(), operator, right.node()),
                    append(instruction, left, right), null));
        }

        private void conversion(AbstractInsnNode instruction, Type from, Type to) {
            Value value = numericOperand(0, from);
            emit(new Value(to, new Conversion(to, value.node()), append(instruction, value), null));
        }
    }

    private static String sourceName(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    private static String referenceName(Reference source) {
        String tag = source.tag() == null ? "reference" : source.tag();
        return sourceName(tag.substring(tag.lastIndexOf('/') + 1));
    }


}
