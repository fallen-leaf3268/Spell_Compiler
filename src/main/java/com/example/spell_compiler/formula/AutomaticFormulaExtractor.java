package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

public final class AutomaticFormulaExtractor {
    private static final String ABSTRACT_SPELL = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String SCHOOL = "io/redspace/ironsspellbooks/api/spells/SchoolType";
    private static final String CONFIG_MANAGER = "io/redspace/ironsspellbooks/api/config/SpellConfigManager";
    private static final String CONFIG_PARAMETER = "io/redspace/ironsspellbooks/api/config/SpellConfigParameter";
    private static final String ATTRIBUTE_REGISTRY = "io/redspace/ironsspellbooks/api/registry/AttributeRegistry";
    private static final String UTILS = "io/redspace/ironsspellbooks/api/util/Utils";
    private static final String MOB_TYPE = "net/minecraft/world/entity/MobType";
    private static final String SPELL_REGISTRY = "io/redspace/ironsspellbooks/api/registry/SpellRegistry";
    private static final String REGISTRY_OBJECT = "net/minecraftforge/registries/RegistryObject";
    private static final String SITE_VALUES = "com/example/spell_compiler/formula/FormulaSiteValues";
    private static final String INPUT_ANNOTATION = "Lcom/example/spell_compiler/formula/FormulaInput;";
    private static final String CAST_TYPE = "io/redspace/ironsspellbooks/api/spells/CastType";
    private static final String ATTRIBUTE = "net/minecraft/world/entity/ai/attributes/Attribute";

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

    private sealed interface Node permits Constant, Input, Binary, Negation, Conversion, Function, Predicate {
        Type type();
        String text();
    }
    private record Constant(Type type, Number value) implements Node {
        public String text() {
            if (type.equals(Type.INT_TYPE) && value.intValue() == Integer.MIN_VALUE)
                return "(-int(2147483647) - int(1))";
            if (type.equals(Type.LONG_TYPE) && value.longValue() == Long.MIN_VALUE)
                return "(-long(9223372036854775807L) - long(1))";
            return type.getClassName() + '(' + value + (type.equals(Type.LONG_TYPE) ? "L" : "") + ')';
        }
    }
    private record Input(Type type, String name) implements Node {
        public String text() { return name; }
    }
    private record Binary(Type type, Node left, String operator, Node right) implements Node {
        public String text() { return '(' + left.text() + ' ' + operator + ' ' + right.text() + ')'; }
    }
    private record Negation(Type type, Node input) implements Node {
        public String text() { return "(-" + input.text() + ')'; }
    }
    private record Conversion(Type type, Node input) implements Node {
        public String text() { return type.getClassName() + '(' + input.text() + ')'; }
    }
    private record Function(Type type, String name, List<Node> arguments) implements Node {
        public String text() { return name + '(' + String.join(", ", arguments.stream().map(Node::text).toList()) + ')'; }
    }
    private record Predicate(Type type, Value subject, String target) implements Node {
        public String text() { throw new Rejected("Reference predicates cannot be exported as arithmetic"); }
    }
    private record Reference(String kind, String identity, String tag, Value subject) {}
    private record Value(Type type, Node node, List<AbstractInsnNode> instructions, Reference reference) {}

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
        String receiver = receiverClass.replace('.', '/');
        var resolved = repository.resolveMethod(receiver, "getCastType", "()L" + CAST_TYPE + ';');
        if (resolved == null) throw new IllegalArgumentException("Cannot resolve native cast type: " + receiver);
        List<AbstractInsnNode> instructions = Frame.executable(resolved.method().instructions.toArray(), 0,
                resolved.method().instructions.size());
        if (instructions.size() != 2 || !(instructions.get(0) instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETSTATIC || !field.owner.equals(CAST_TYPE)
                || !field.desc.equals('L' + CAST_TYPE + ';')
                || !Set.of("INSTANT", "LONG", "CONTINUOUS", "NONE").contains(field.name)
                || instructions.get(1).getOpcode() != Opcodes.ARETURN)
            throw new IllegalArgumentException("Native cast type is not a fixed enum: " + receiver);
        return field.name;
    }

    private static Analysis analyze(ClassNode owner, MethodNode method, FormulaClassRepository repository,
                                    boolean preserveNativePower, String receiverClass) {
        try {
            repository.add(owner);
            Session session = new Session(owner, method, repository, preserveNativePower, receiverClass);
            Value value = session.root();
            return new Analysis(new Formula(owner.name, method.name, method.desc, value.node().text(),
                    value.type(), session.variables, session.finishedPrelude(), session.nextLocal, session.nativePowers), null);
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
        private final Map<String, Variable> variables = new LinkedHashMap<>();
        private final Map<String, String> families = new HashMap<>();
        private final List<AbstractInsnNode> prelude = new ArrayList<>();
        private final Set<String> active = new HashSet<>();
        private int nextLocal;
        private int instructionsVisited;
        private boolean exactReceiverGuard;

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
            nextLocal = Math.max(method.maxLocals, argumentSlots(method));
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
                    if (variables.containsKey(name)) throw new Rejected("Duplicate formula parameter name: " + name);
                    List<AbstractInsnNode> code = List.of(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot));
                    variables.put(name, new Variable(name, type, code));
                    families.put(name, "parameter:" + slot);
                    arguments.add(new Value(type, new Input(type, name), code, null));
                } else if (reference(type)) {
                    arguments.add(new Value(type, null, List.of(new VarInsnNode(Opcodes.ALOAD, slot)),
                            new Reference("parameter", "parameter:" + slot, null, null)));
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
            return method(rootOwner, rootMethod, receiver, arguments);
        }

        private String parameterName(int index, String fallback) {
            if (!rootOwner.name.equals(SITE_VALUES) || rootMethod.invisibleParameterAnnotations == null
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
                return new Frame(this, owner, method, receiver, arguments).run();
            } catch (Rejected rejected) {
                throw new Rejected(key + " -> " + rejected.getMessage());
            } finally {
                active.remove(key);
            }
        }

        private Value bind(String requested, Type type, List<AbstractInsnNode> code, String family) {
            requested = FormulaNames.input(requested);
            String previous = families.putIfAbsent(requested, family);
            if (previous != null && !previous.equals(family))
                throw new Rejected("Variable name has different sources: " + requested);
            String name = requested;
            int suffix = 2;
            while (variables.containsKey(name)) name = requested + '_' + suffix++;
            int slot = allocate(type);
            variables.put(name, new Variable(name, type, code, slot));
            prelude.addAll(code);
            prelude.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), slot));
            return new Value(type, new Input(type, name),
                    List.of(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), slot)), null);
        }

        private int allocate(Type type) {
            int slot = nextLocal;
            nextLocal += type.getSize();
            return slot;
        }

        private Value capture(Value value) {
            int slot = allocate(value.type());
            prelude.addAll(value.instructions());
            int store = reference(value.type()) ? Opcodes.ASTORE : value.type().getOpcode(Opcodes.ISTORE);
            int load = reference(value.type()) ? Opcodes.ALOAD : value.type().getOpcode(Opcodes.ILOAD);
            prelude.add(new VarInsnNode(store, slot));
            return new Value(value.type(), value.node(),
                    List.of(new VarInsnNode(load, slot)), value.reference());
        }

        private List<AbstractInsnNode> finishedPrelude() {
            if (!exactReceiverGuard) return prelude;
            var result = new ArrayList<AbstractInsnNode>();
            LabelNode exact = new LabelNode();
            result.add(new VarInsnNode(Opcodes.ALOAD, 0));
            result.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
            result.add(new LdcInsnNode(Type.getObjectType(receiverClass == null ? rootOwner.name : receiverClass)));
            result.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, exact));
            for (AbstractInsnNode instruction : rootMethod.instructions) {
                if (!(instruction instanceof FrameNode) && !(instruction instanceof LineNumberNode)) result.add(instruction);
            }
            result.add(exact);
            result.addAll(prelude);
            return result;
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

    private static final class Frame {
        private final Session session;
        private final ClassNode owner;
        private final MethodNode method;
        private final Map<Integer, Value> locals = new HashMap<>();
        private final ArrayDeque<Value> stack = new ArrayDeque<>();
        private final boolean nativePower;
        private Value guard;

        private Frame(Session session, ClassNode owner, MethodNode method, Value receiver, List<Value> arguments) {
            this.session = session;
            this.owner = owner;
            this.method = method;
            requireNumeric(Type.getReturnType(method.desc));
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0)
                throw new Rejected("Abstract, native and synchronized methods are not supported");
            if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty())
                throw new Rejected("Exception handlers are not supported");
            nativePower = owner.name.equals(ABSTRACT_SPELL)
                    && (method.name.equals("getSpellPower") && method.desc.equals("(ILnet/minecraft/world/entity/Entity;)F")
                    || method.name.equals("getEntityPowerMultiplier") && method.desc.equals("(Lnet/minecraft/world/entity/LivingEntity;)F"));
            if (nativePower) validateNativePower(method);
            int slot = 0;
            if ((method.access & Opcodes.ACC_STATIC) == 0) locals.put(slot++, receiver);
            Type[] types = Type.getArgumentTypes(method.desc);
            for (int index = 0; index < types.length; index++) {
                locals.put(slot, arguments.get(index));
                slot += types[index].getSize();
            }
        }

        private Value run() {
            Value cast = nativeCast();
            if (cast != null) return cast;
            AbstractInsnNode[] instructions = method.instructions.toArray();
            var visited = new HashSet<Integer>();
            for (int pc = 0; pc < instructions.length; pc++) {
                if (++session.instructionsVisited > 20000 || !visited.add(pc))
                    throw new Rejected("Cyclic or oversized formula");
                AbstractInsnNode instruction = instructions[pc];
                int opcode = instruction.getOpcode();
                if (opcode < 0) continue;
                switch (opcode) {
                    case Opcodes.NOP -> {}
                    case Opcodes.ACONST_NULL -> stack.push(new Value(Type.getObjectType("java/lang/Object"), null,
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
                    case Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> {
                        Value value = locals.get(((VarInsnNode) instruction).var);
                        if (value == null) throw new Rejected("Uninitialized local variable");
                        if (opcode == Opcodes.ALOAD ? !reference(value.type()) : value.type().getOpcode(Opcodes.ILOAD) != opcode)
                            throw new Rejected("Local load type mismatch");
                        stack.push(value);
                    }
                    case Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
                        Value value = pop();
                        if (opcode == Opcodes.ASTORE ? !reference(value.type()) : value.type().getOpcode(Opcodes.ISTORE) != opcode)
                            throw new Rejected("Local store type mismatch");
                        flushStack();
                        locals.put(((VarInsnNode) instruction).var,
                                reference(value.type()) && nativePower && guard != null ? value : session.capture(value));
                    }
                    case Opcodes.GETFIELD, Opcodes.GETSTATIC -> field((FieldInsnNode) instruction);
                    case Opcodes.CHECKCAST -> {
                        Value value = pop();
                        if (!reference(value.type())) throw new Rejected("CHECKCAST requires a reference");
                        stack.push(new Value(Type.getObjectType(((TypeInsnNode) instruction).desc), null,
                                append(instruction, value), value.reference()));
                    }
                    case Opcodes.INSTANCEOF -> {
                        Value value = pop();
                        stack.push(new Value(Type.INT_TYPE, new Predicate(Type.INT_TYPE, value, ((TypeInsnNode) instruction).desc),
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
                        Value value = pop(type);
                        stack.push(new Value(type, new Negation(type, value.node()), append(instruction, value), null));
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
                    case Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.IFEQ, Opcodes.IFNE -> {
                        Value subject = pop();
                        if (!nativePower) {
                            int end = nullableInput(instructions, pc, subject);
                            if (end < 0) throw new Rejected("General conditional control flow is not supported");
                            pc = end;
                            continue;
                        }
                        boolean take = branch(opcode, subject);
                        if (take) {
                            int target = method.instructions.indexOf(((JumpInsnNode) instruction).label);
                            if (target <= pc) throw new Rejected("Backward branches are not supported");
                            pc = target - 1;
                        }
                    }
                    case Opcodes.GOTO -> {
                        if (!nativePower) throw new Rejected("General jumps are not supported");
                        int target = method.instructions.indexOf(((JumpInsnNode) instruction).label);
                        if (target <= pc) throw new Rejected("Backward branches are not supported");
                        pc = target - 1;
                    }
                    case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN -> {
                        Type type = arithmeticType(opcode - Opcodes.IRETURN);
                        if (!type.equals(Type.getReturnType(method.desc)) || stack.size() != 1)
                            throw new Rejected("Invalid numeric return stack");
                        Value result = pop(type);
                        if (!nativePower) {
                            for (int next = pc + 1; next < instructions.length; next++)
                                if (instructions[next].getOpcode() >= 0)
                                    throw new Rejected("Executable instruction after return");
                        }
                        return result;
                    }
                    case Opcodes.PUTFIELD, Opcodes.PUTSTATIC, Opcodes.IINC ->
                            throw new Rejected("Writes and mutations are not supported");
                    default -> throw new Rejected("Unsupported instruction opcode " + opcode);
                }
            }
            throw new Rejected("No numeric return");
        }

        private Value nativeCast() {
            if (!owner.name.equals(ABSTRACT_SPELL)) return null;
            boolean base = method.name.equals("getCastTime") && method.desc.equals("(I)I");
            boolean effective = method.name.equals("getEffectiveCastTime") && method.desc.equals("(IL" + LIVING_ENTITY + ";)I");
            if (!base && !effective) return null;
            if ((method.access & Opcodes.ACC_STATIC) != 0)
                throw new Rejected("Native cast methods must retain instance dispatch");
            AbstractInsnNode[] original = method.instructions.toArray();
            List<AbstractInsnNode> code = executable(original, 0, original.length);
            Value receiver = locals.get(0);
            if (base) {
                if (code.size() != 9 || !variableMatches(code.get(0), Opcodes.ALOAD, 0)
                        || !callMatches(code.get(1), Opcodes.INVOKEVIRTUAL, ABSTRACT_SPELL, "getCastType", "()L" + CAST_TYPE + ';')
                        || !fieldMatches(code.get(2), CAST_TYPE, "INSTANT", 'L' + CAST_TYPE + ';')
                        || !jumpMatches(code.get(3), Opcodes.IF_ACMPNE, code.get(6))
                        || code.get(4).getOpcode() != Opcodes.ICONST_0 || code.get(5).getOpcode() != Opcodes.IRETURN
                        || !variableMatches(code.get(6), Opcodes.ALOAD, 0)
                        || !(code.get(7) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                        || !field.owner.equals(ABSTRACT_SPELL) || !field.name.equals("castTime") || !field.desc.equals("I")
                        || code.get(8).getOpcode() != Opcodes.IRETURN)
                    throw new Rejected("Native cast time differs from the audited 3.16.1 structure");
                var typeCode = new ArrayList<AbstractInsnNode>(receiver.instructions());
                LabelNode nonInstant = new LabelNode();
                LabelNode done = new LabelNode();
                typeCode.add(code.get(1));
                typeCode.add(code.get(2));
                typeCode.add(new JumpInsnNode(Opcodes.IF_ACMPNE, nonInstant));
                typeCode.add(new InsnNode(Opcodes.ICONST_1));
                typeCode.add(new JumpInsnNode(Opcodes.GOTO, done));
                typeCode.add(nonInstant);
                typeCode.add(new InsnNode(Opcodes.ICONST_0));
                typeCode.add(done);
                Value instant = session.bind("is_instant", Type.INT_TYPE, typeCode, "castType:instant:" + receiver.reference().identity());
                Value time = session.bind("castTime", Type.INT_TYPE, append(code.get(7), receiver),
                        "field:" + ABSTRACT_SPELL + ".castTimeI:" + receiver.reference().identity());
                Node difference = new Binary(Type.INT_TYPE, new Constant(Type.INT_TYPE, 1), "-", instant.node());
                var resultCode = new ArrayList<AbstractInsnNode>(time.instructions());
                resultCode.add(new InsnNode(Opcodes.ICONST_1));
                resultCode.addAll(instant.instructions());
                resultCode.add(new InsnNode(Opcodes.ISUB));
                resultCode.add(new InsnNode(Opcodes.IMUL));
                return new Value(Type.INT_TYPE, new Binary(Type.INT_TYPE, time.node(), "*", difference), resultCode, null);
            }
            validateEffectiveCast(code);
            Value caster = locals.get(2);
            if (session.receiverClass != null) return contextualCast(code, receiver, caster);
            int scratch = session.allocate(Type.DOUBLE_TYPE);
            int boundary = method.instructions.indexOf(code.get(24));
            var multiplierCode = new ArrayList<AbstractInsnNode>();
            for (int index = 0; index < boundary; index++) {
                AbstractInsnNode instruction = original[index];
                if (instruction instanceof FrameNode || instruction instanceof LineNumberNode) continue;
                if (variableMatches(instruction, Opcodes.ALOAD, 0)) multiplierCode.addAll(receiver.instructions());
                else if (variableMatches(instruction, Opcodes.ALOAD, 2)) multiplierCode.addAll(caster.instructions());
                else if (variableMatches(instruction, Opcodes.DSTORE, 3)) multiplierCode.add(new VarInsnNode(Opcodes.DSTORE, scratch));
                else multiplierCode.add(instruction);
            }
            multiplierCode.add(new VarInsnNode(Opcodes.DLOAD, scratch));
            privateLabels(multiplierCode);
            Value multiplier = session.bind("cast_time_multiplier", Type.DOUBLE_TYPE, multiplierCode,
                    "castMultiplier:" + receiver.reference().identity() + ':' + caster.reference().identity());
            Value time = session.bind("native_cast_time", Type.INT_TYPE, append(code.get(26), receiver, locals.get(1)),
                    "castTime:" + receiver.reference().identity() + ':' + locals.get(1).node().text());
            Node product = new Binary(Type.FLOAT_TYPE, new Conversion(Type.FLOAT_TYPE, time.node()), "*",
                    new Conversion(Type.FLOAT_TYPE, multiplier.node()));
            var resultCode = new ArrayList<AbstractInsnNode>(time.instructions());
            resultCode.add(new InsnNode(Opcodes.I2F));
            resultCode.addAll(multiplier.instructions());
            resultCode.add(new InsnNode(Opcodes.D2F));
            resultCode.add(new InsnNode(Opcodes.FMUL));
            resultCode.add(code.get(31));
            return new Value(Type.INT_TYPE, new Function(Type.INT_TYPE, "round", List.of(product)), resultCode, null);
        }

        private Value contextualCast(List<AbstractInsnNode> code, Value receiver, Value caster) {
            String castType = fixedCastType();
            LabelNode absent = new LabelNode();
            LabelNode done = new LabelNode();
            var attributeCode = new ArrayList<AbstractInsnNode>(caster.instructions());
            attributeCode.add(new JumpInsnNode(Opcodes.IFNULL, absent));
            attributeCode.addAll(caster.instructions());
            attributeCode.addAll(code.subList(19, 23));
            attributeCode.add(new JumpInsnNode(Opcodes.GOTO, done));
            attributeCode.add(absent);
            attributeCode.add(new InsnNode(Opcodes.DCONST_1));
            attributeCode.add(done);
            Value reduction = session.bind("cast_time_reduction", Type.DOUBLE_TYPE, attributeCode,
                    "castReduction:" + receiver.reference().identity() + ':' + caster.reference().identity());
            Node multiplier = reduction.node();
            var multiplierCode = new ArrayList<AbstractInsnNode>(reduction.instructions());
            if (!castType.equals("CONTINUOUS")) {
                Node one = new Constant(Type.DOUBLE_TYPE, 1.0);
                Node two = new Constant(Type.DOUBLE_TYPE, 2.0);
                Node denominator = new Binary(Type.DOUBLE_TYPE,
                        new Function(Type.DOUBLE_TYPE, "max", List.of(reduction.node(), new Constant(Type.DOUBLE_TYPE, 1.5))),
                        "-", one);
                Node reciprocal = new Binary(Type.DOUBLE_TYPE, one, "/", denominator);
                Node curve = new Binary(Type.DOUBLE_TYPE,
                        new Binary(Type.DOUBLE_TYPE, new Constant(Type.DOUBLE_TYPE, -0.25), "*", reciprocal), "+", two);
                multiplier = new Binary(Type.DOUBLE_TYPE, two, "-",
                        new Function(Type.DOUBLE_TYPE, "min", List.of(reduction.node(), curve)));
                multiplierCode.clear();
                multiplierCode.add(new LdcInsnNode(2.0));
                multiplierCode.addAll(reduction.instructions());
                multiplierCode.add(new LdcInsnNode(-0.25));
                multiplierCode.add(new InsnNode(Opcodes.DCONST_1));
                multiplierCode.addAll(reduction.instructions());
                multiplierCode.add(new LdcInsnNode(1.5));
                multiplierCode.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false));
                multiplierCode.add(new InsnNode(Opcodes.DCONST_1));
                multiplierCode.add(new InsnNode(Opcodes.DSUB));
                multiplierCode.add(new InsnNode(Opcodes.DDIV));
                multiplierCode.add(new InsnNode(Opcodes.DMUL));
                multiplierCode.add(new LdcInsnNode(2.0));
                multiplierCode.add(new InsnNode(Opcodes.DADD));
                multiplierCode.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false));
                multiplierCode.add(new InsnNode(Opcodes.DSUB));
            }
            Value time = session.bind("native_cast_time", Type.INT_TYPE, append(code.get(26), receiver, locals.get(1)),
                    "castTime:" + receiver.reference().identity() + ':' + locals.get(1).node().text());
            Node product = new Binary(Type.FLOAT_TYPE, new Conversion(Type.FLOAT_TYPE, time.node()), "*",
                    new Conversion(Type.FLOAT_TYPE, multiplier));
            var resultCode = new ArrayList<AbstractInsnNode>(time.instructions());
            resultCode.add(new InsnNode(Opcodes.I2F));
            resultCode.addAll(multiplierCode);
            resultCode.add(new InsnNode(Opcodes.D2F));
            resultCode.add(new InsnNode(Opcodes.FMUL));
            resultCode.add(code.get(31));
            return new Value(Type.INT_TYPE, new Function(Type.INT_TYPE, "round", List.of(product)), resultCode, null);
        }

        private String fixedCastType() {
            try {
                return nativeCastType(session.repository, session.receiverClass);
            } catch (IllegalArgumentException failure) {
                throw new Rejected(failure.getMessage());
            }
        }

        private static void validateEffectiveCast(List<AbstractInsnNode> code) {
            int[] opcodes = {Opcodes.DCONST_1, Opcodes.DSTORE, Opcodes.ALOAD, Opcodes.IFNULL, Opcodes.ALOAD,
                    Opcodes.INVOKEVIRTUAL, Opcodes.GETSTATIC, Opcodes.IF_ACMPEQ, Opcodes.LDC, Opcodes.ALOAD,
                    Opcodes.GETSTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.CHECKCAST, Opcodes.INVOKEVIRTUAL,
                    Opcodes.INVOKESTATIC, Opcodes.DSUB, Opcodes.DSTORE, Opcodes.GOTO, Opcodes.ALOAD,
                    Opcodes.GETSTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.CHECKCAST, Opcodes.INVOKEVIRTUAL,
                    Opcodes.DSTORE, Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.INVOKEVIRTUAL, Opcodes.I2F,
                    Opcodes.DLOAD, Opcodes.D2F, Opcodes.FMUL, Opcodes.INVOKESTATIC, Opcodes.IRETURN};
            if (code.size() != opcodes.length)
                throw new Rejected("Native effective cast time differs from the audited 3.16.1 structure");
            for (int index = 0; index < opcodes.length; index++)
                if (code.get(index).getOpcode() != opcodes[index])
                    throw new Rejected("Native effective cast instruction structure is not supported");
            for (int index : new int[]{1, 16, 23})
                if (!variableMatches(code.get(index), Opcodes.DSTORE, 3))
                    throw new Rejected("Native effective cast multiplier slot is not supported");
            for (int index : new int[]{2, 9, 18})
                if (!variableMatches(code.get(index), Opcodes.ALOAD, 2))
                    throw new Rejected("Native effective cast caster slot is not supported");
            if (!variableMatches(code.get(4), Opcodes.ALOAD, 0) || !variableMatches(code.get(24), Opcodes.ALOAD, 0)
                    || !variableMatches(code.get(25), Opcodes.ILOAD, 1) || !variableMatches(code.get(28), Opcodes.DLOAD, 3)
                    || !callMatches(code.get(5), Opcodes.INVOKEVIRTUAL, ABSTRACT_SPELL, "getCastType", "()L" + CAST_TYPE + ';')
                    || !fieldMatches(code.get(6), CAST_TYPE, "CONTINUOUS", 'L' + CAST_TYPE + ';')
                    || !jumpMatches(code.get(3), Opcodes.IFNULL, code.get(24))
                    || !jumpMatches(code.get(7), Opcodes.IF_ACMPEQ, code.get(18))
                    || !jumpMatches(code.get(17), Opcodes.GOTO, code.get(24))
                    || !(code.get(8) instanceof LdcInsnNode constant) || !Double.valueOf(2).equals(constant.cst)
                    || !callMatches(code.get(14), Opcodes.INVOKESTATIC, UTILS, "softCapFormula", "(D)D")
                    || !callMatches(code.get(26), Opcodes.INVOKEVIRTUAL, ABSTRACT_SPELL, "getCastTime", "(I)I")
                    || !callMatches(code.get(31), Opcodes.INVOKESTATIC, "java/lang/Math", "round", "(F)I"))
                throw new Rejected("Native effective cast control flow is not supported");
            for (int index : new int[]{10, 19})
                if (!fieldMatches(code.get(index), ATTRIBUTE_REGISTRY, "CAST_TIME_REDUCTION", 'L' + REGISTRY_OBJECT + ';')
                        || !callMatches(code.get(index + 1), Opcodes.INVOKEVIRTUAL, REGISTRY_OBJECT, "get", "()Ljava/lang/Object;")
                        || !castMatches(code.get(index + 2), ATTRIBUTE) || !attributeValueCall(code.get(index + 3)))
                    throw new Rejected("Native effective cast attribute source is not supported");
        }

        private static boolean variableMatches(AbstractInsnNode instruction, int opcode, int slot) {
            return instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode && variable.var == slot;
        }

        private static boolean jumpMatches(AbstractInsnNode instruction, int opcode, AbstractInsnNode expected) {
            if (!(instruction instanceof JumpInsnNode jump) || jump.getOpcode() != opcode) return false;
            AbstractInsnNode target = jump.label;
            while (target != null && target.getOpcode() < 0) target = target.getNext();
            return target == expected;
        }

        private static void privateLabels(List<AbstractInsnNode> code) {
            var labels = new IdentityHashMap<LabelNode, LabelNode>();
            for (AbstractInsnNode instruction : code)
                if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
            code.replaceAll(instruction -> instruction.clone(labels));
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
                    && fieldMatches(nonnull.get(1), ATTRIBUTE_REGISTRY, "SUMMON_DAMAGE", 'L' + REGISTRY_OBJECT + ';')
                    && callMatches(nonnull.get(2), Opcodes.INVOKEVIRTUAL, REGISTRY_OBJECT, "get", "()Ljava/lang/Object;")
                    && castMatches(nonnull.get(3), "net/minecraft/world/entity/ai/attributes/Attribute")
                    && attributeValueCall(nonnull.get(4)) && nonnull.get(5).getOpcode() == Opcodes.D2F) {
                name = "summon_damage";
                actor = nonnull.get(0);
            } else if (nonnull.size() == 5
                    && fieldMatches(nonnull.get(0), SPELL_REGISTRY, "FROSTBITE_SPELL", 'L' + REGISTRY_OBJECT + ';')
                    && callMatches(nonnull.get(1), Opcodes.INVOKEVIRTUAL, REGISTRY_OBJECT, "get", "()Ljava/lang/Object;")
                    && castMatches(nonnull.get(2), ABSTRACT_SPELL) && sameActor(nonnull.get(3), subject)
                    && callMatches(nonnull.get(4), Opcodes.INVOKEVIRTUAL, ABSTRACT_SPELL,
                    "getEntityPowerMultiplier", "(L" + LIVING_ENTITY + ";)F")) {
                name = "frostbite_power_multiplier";
                actor = nonnull.get(3);
            } else return -1;
            flushStack();
            var code = new ArrayList<AbstractInsnNode>(subject.instructions());
            for (int index = pc; index <= end; index++) {
                AbstractInsnNode instruction = instructions[index];
                if (instruction == actor) code.addAll(subject.instructions());
                else if (!(instruction instanceof FrameNode) && !(instruction instanceof LineNumberNode)) code.add(instruction);
            }
            privateLabels(code);
            stack.push(session.bind(name, Type.FLOAT_TYPE, code, "nullable:" + name + ':' + subject.reference().identity()));
            return end;
        }

        private boolean sameActor(AbstractInsnNode instruction, Value subject) {
            if (!(instruction instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ALOAD) return false;
            Value value = locals.get(variable.var);
            return value != null && value.reference() != null
                    && value.reference().identity().equals(subject.reference().identity());
        }

        private static List<AbstractInsnNode> executable(AbstractInsnNode[] instructions, int start, int end) {
            var result = new ArrayList<AbstractInsnNode>();
            for (int index = start; index < end; index++) if (instructions[index].getOpcode() >= 0) result.add(instructions[index]);
            return result;
        }

        private static boolean fieldMatches(AbstractInsnNode instruction, String owner, String name, String descriptor) {
            return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(owner) && field.name.equals(name) && field.desc.equals(descriptor);
        }

        private static boolean callMatches(AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
            return instruction instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor);
        }

        private static boolean castMatches(AbstractInsnNode instruction, String owner) {
            return instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals(owner);
        }

        private static boolean attributeValueCall(AbstractInsnNode instruction) {
            return callMatches(instruction, Opcodes.INVOKEVIRTUAL, LIVING_ENTITY, "getAttributeValue",
                    "(Lnet/minecraft/world/entity/ai/attributes/Attribute;)D")
                    || callMatches(instruction, Opcodes.INVOKEVIRTUAL, LIVING_ENTITY, "m_21133_",
                    "(Lnet/minecraft/world/entity/ai/attributes/Attribute;)D");
        }

        private void constant(Type type, Number value, AbstractInsnNode instruction) {
            stack.push(new Value(type, new Constant(type, value), List.of(instruction), null));
        }

        private void field(FieldInsnNode instruction) {
            Type type = Type.getType(instruction.desc);
            Value receiver = instruction.getOpcode() == Opcodes.GETFIELD ? pop() : null;
            if (receiver != null && !reference(receiver.type()))
                throw new Rejected("Field receiver is not a reference");
            List<AbstractInsnNode> code = receiver == null ? List.of(instruction) : append(instruction, receiver);
            if (instruction.getOpcode() == Opcodes.GETSTATIC && instruction.owner.equals(MOB_TYPE)
                    && instruction.desc.equals('L' + MOB_TYPE + ';')
                    && Set.of("UNDEFINED", "UNDEAD", "f_21640_", "f_21641_").contains(instruction.name)) {
                stack.push(new Value(type, null, code,
                        new Reference("field", instruction.owner + '.' + instruction.name, instruction.name, null)));
                return;
            }
            var declaration = session.repository.resolveField(instruction.owner, instruction.name, instruction.desc);
            if (declaration == null) throw new Rejected("Field metadata is unavailable: " + instruction.owner + '.' + instruction.name);
            if (declaration != null && (declaration.field().access & Opcodes.ACC_VOLATILE) != 0)
                throw new Rejected("Volatile fields are not supported");
            if (numeric(type)) {
                flushStack();
                String identity = receiver == null ? "static" : receiver.reference().identity();
                String declaring = declaration == null ? instruction.owner : declaration.owner().name;
                stack.push(session.bind(instruction.name, type, code,
                        "field:" + declaring + '.' + instruction.name + instruction.desc + ':' + identity));
            } else if (reference(type) && instruction.getOpcode() == Opcodes.GETSTATIC
                    && (instruction.owner.equals(ATTRIBUTE_REGISTRY) || instruction.owner.equals(CONFIG_PARAMETER))) {
                stack.push(new Value(type, null, code,
                        new Reference("field", instruction.owner + '.' + instruction.name, instruction.name, null)));
            } else throw new Rejected("Unsupported reference field " + instruction.owner + '.' + instruction.name);
        }

        private void call(MethodInsnNode instruction) {
            flushStack();
            Type[] parameterTypes = Type.getArgumentTypes(instruction.desc);
            var arguments = new ArrayList<Value>(Collections.nCopies(parameterTypes.length, null));
            for (int index = parameterTypes.length - 1; index >= 0; index--) {
                Value argument = pop();
                if (reference(parameterTypes[index]) ? !reference(argument.type()) : !parameterTypes[index].equals(argument.type()))
                    throw new Rejected("Call argument type mismatch");
                arguments.set(index, argument);
            }
            Value receiver = instruction.getOpcode() == Opcodes.INVOKESTATIC ? null : pop();
            if (receiver != null && !reference(receiver.type()))
                throw new Rejected("Call receiver is not a reference");
            Type result = Type.getReturnType(instruction.desc);
            List<AbstractInsnNode> code = append(instruction, callInputs(receiver, arguments));
            if (api(instruction, receiver, arguments, result, code)) return;
            if (math(instruction, arguments, result, code)) return;
            if (!numeric(result))
                throw new Rejected("Unknown reference call " + instruction.owner + '.' + instruction.name);
            String target = instruction.owner;
            var symbolic = session.repository.resolveMethod(target, instruction.name, instruction.desc);
            boolean privateCall = symbolic != null && (symbolic.method().access & Opcodes.ACC_PRIVATE) != 0;
            if (receiver != null) {
                if (!receiver.reference().kind().equals("this"))
                    throw new Rejected("Unresolved virtual receiver " + instruction.owner + '.' + instruction.name);
                if (instruction.getOpcode() != Opcodes.INVOKESPECIAL && !privateCall) target = receiver.type().getInternalName();
            }
            var resolved = receiver != null && instruction.getOpcode() != Opcodes.INVOKESPECIAL && !privateCall
                    && symbolic != null && (symbolic.method().access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0
                    ? packageVirtualHelper(target, symbolic)
                    : session.repository.resolveMethod(target, instruction.name, instruction.desc);
            if (resolved == null)
                throw new Rejected("Method bytecode is unavailable: " + target + '.' + instruction.name + instruction.desc);
            if ((resolved.owner().access & Opcodes.ACC_INTERFACE) != 0)
                throw new Rejected("Interface helper dispatch is not supported: " + resolved.owner().name);
            if (receiver != null && instruction.getOpcode() != Opcodes.INVOKESPECIAL && !privateCall
                    && (session.receiverClass != null && !session.receiverClass.equals(session.rootOwner.name)
                    || (resolved.method().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) == 0
                    && (session.rootOwner.access & Opcodes.ACC_FINAL) == 0))
                session.exactReceiverGuard = true;
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC
                    && !session.safeInitialization(resolved.owner().name, new HashSet<>()))
                throw new Rejected("Static helper initialization is not proven safe: " + resolved.owner().name);
            for (int index = 0; index < arguments.size(); index++) {
                Value argument = arguments.get(index);
                if (needsCapture(argument))
                    arguments.set(index, session.capture(argument));
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
            stack.push(expanded);
        }

        private FormulaClassRepository.ResolvedMethod packageVirtualHelper(String receiver,
                                                                          FormulaClassRepository.ResolvedMethod symbolic) {
            var lineage = new ArrayList<ClassNode>();
            var seen = new HashSet<String>();
            String current = receiver;
            while (!symbolic.owner().name.equals(current)) {
                if (current == null || !seen.add(current))
                    throw new Rejected("Virtual helper hierarchy is unavailable: " + receiver + " -> " + symbolic.owner().name);
                ClassNode owner = session.repository.classNode(current);
                if (owner == null)
                    throw new Rejected("Virtual helper hierarchy is unavailable: " + current);
                lineage.add(owner);
                current = owner.superName;
            }
            var selected = symbolic;
            for (int index = lineage.size() - 1; index >= 0; index--) {
                ClassNode owner = lineage.get(index);
                for (MethodNode candidate : owner.methods) {
                    if (!candidate.name.equals(symbolic.method().name) || !candidate.desc.equals(symbolic.method().desc)
                            || (candidate.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) continue;
                    String declaring = selected.owner().name;
                    if ((selected.method().access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                            || owner.name.substring(0, owner.name.lastIndexOf('/') + 1)
                            .equals(declaring.substring(0, declaring.lastIndexOf('/') + 1)))
                        selected = new FormulaClassRepository.ResolvedMethod(owner, candidate);
                }
            }
            return selected;
        }

        private boolean api(MethodInsnNode instruction, Value receiver, List<Value> arguments, Type result,
                            List<AbstractInsnNode> code) {
            if (receiver != null && receiver.reference().kind().equals("this")
                    && instruction.getOpcode() == Opcodes.INVOKEVIRTUAL && instruction.name.equals("getCastTime")
                    && instruction.desc.equals("(I)I") && (instruction.owner.equals(ABSTRACT_SPELL)
                    || session.repository.isSubtype(instruction.owner, ABSTRACT_SPELL))) {
                stack.push(session.bind("native_cast_time", Type.INT_TYPE, code,
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
                stack.push(new Value(result, node, input.instructions(), null));
                return true;
            }
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC && instruction.owner.equals(UTILS)
                    && instruction.name.equals("softCapFormula") && instruction.desc.equals("(D)D")) {
                stack.push(new Value(result, new Function(result, "softcap", List.of(arguments.get(0).node())), code, null));
                return true;
            }
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC && instruction.owner.equals(UTILS)
                    && instruction.name.equals("getWeaponDamage")
                    && (instruction.desc.equals("(L" + LIVING_ENTITY + ";)F")
                    || instruction.desc.equals("(L" + LIVING_ENTITY + ";L" + MOB_TYPE + ";)F"))) {
                String source = instruction.desc + ':' + arguments.get(0).reference().identity();
                if (arguments.size() == 2) source += ':' + arguments.get(1).reference().identity();
                stack.push(session.bind("weapon_damage", result, code, "weapon:" + source));
                return true;
            }
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC
                    && instruction.owner.equals("net/minecraft/world/item/enchantment/EnchantmentHelper")
                    && Set.of("getFireAspect", "m_44914_").contains(instruction.name)
                    && instruction.desc.equals("(L" + LIVING_ENTITY + ";)I")) {
                stack.push(session.bind("fire_aspect_level", result, code,
                        "fireAspect:" + arguments.get(0).reference().identity()));
                return true;
            }
            if (receiver != null && (instruction.owner.equals(LIVING_ENTITY)
                    || session.repository.isSubtype(instruction.owner, LIVING_ENTITY))
                    && instruction.desc.equals("()F") && Set.of("getHealth", "getMaxHealth", "m_21223_", "m_21233_").contains(instruction.name)) {
                String name = Set.of("getHealth", "m_21223_").contains(instruction.name) ? "entity_health" : "entity_max_health";
                stack.push(session.bind(name, result, code, "entityInput:" + name + ':' + receiver.reference().identity()));
                return true;
            }
            if (instruction.owner.equals("net/minecraftforge/registries/RegistryObject") && instruction.name.equals("get")
                    && receiver != null && receiver.reference().kind().equals("field")) {
                stack.push(new Value(result, null, code, receiver.reference()));
                return true;
            }
            if (instruction.owner.equals(CONFIG_MANAGER) && instruction.name.equals("getSpellConfigValue")
                    && arguments.size() == 2 && arguments.get(1).reference() != null
                    && arguments.get(1).reference().identity().startsWith(CONFIG_PARAMETER + '.')) {
                stack.push(new Value(result, null, code,
                        new Reference("config", arguments.get(1).reference().identity(),
                                arguments.get(1).reference().tag(), arguments.get(0))));
                return true;
            }
            if (receiver != null && receiver.reference().kind().equals("config") && numeric(result)
                    && arguments.isEmpty() && (instruction.owner.equals("java/lang/Double")
                    || instruction.owner.equals("java/lang/Float") || instruction.owner.equals("java/lang/Number"))
                    && List.of("floatValue", "doubleValue", "intValue", "longValue").contains(instruction.name)) {
                String name = receiver.reference().tag().toLowerCase(Locale.ROOT);
                flushStack();
                stack.push(session.bind(name, result, code, "config:" + receiver.reference().identity()));
                return true;
            }
            if (receiver != null && receiver.reference().kind().equals("this") && instruction.name.equals("getSchoolType")
                    && instruction.desc.equals("()L" + SCHOOL + ';')) {
                stack.push(new Value(result, null, code, new Reference("school", "school:this", null, receiver)));
                return true;
            }
            if (instruction.owner.equals(SCHOOL) && instruction.name.equals("getPowerFor")
                    && instruction.desc.equals("(L" + LIVING_ENTITY + ";)D") && receiver != null
                    && receiver.reference().kind().equals("school")) {
                Value actor = arguments.get(0);
                flushStack();
                stack.push(session.bind("school_spellpower", result, guarded(code, result, actor),
                        "school:" + receiver.reference().identity() + ':' + actor.reference().identity()));
                return true;
            }
            if ((instruction.owner.equals(LIVING_ENTITY) || session.repository.isSubtype(instruction.owner, LIVING_ENTITY))
                    && (instruction.name.equals("getAttributeValue") || instruction.name.equals("m_21133_"))
                    && instruction.desc.equals("(Lnet/minecraft/world/entity/ai/attributes/Attribute;)D")
                    && receiver != null && arguments.size() == 1 && arguments.get(0).reference() != null
                    && arguments.get(0).reference().identity().equals(ATTRIBUTE_REGISTRY + ".SPELL_POWER")) {
                flushStack();
                stack.push(session.bind("spellpower", result, guarded(code, result, receiver),
                        "attribute:SPELL_POWER:" + receiver.reference().identity()));
                return true;
            }
            return false;
        }

        private boolean needsCapture(Value value) {
            if (numeric(value.type())) return !(value.node() instanceof Input) && !(value.node() instanceof Constant);
            if (nativePower && guard != null) return false;
            return value.instructions().stream().anyMatch(instruction -> instruction.getOpcode() != Opcodes.ALOAD
                    && instruction.getOpcode() != Opcodes.ACONST_NULL);
        }

        private void flushStack() {
            var values = new ArrayList<>(stack);
            stack.clear();
            for (int index = values.size() - 1; index >= 0; index--) {
                Value value = values.get(index);
                stack.push(needsCapture(value) ? session.capture(value) : value);
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

        private boolean math(MethodInsnNode instruction, List<Value> arguments, Type result,
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
            Type[] expected = Type.getArgumentTypes(instruction.desc);
            if (name.equals("min") || name.equals("max")) {
                if (arguments.size() != 2 || !expected[0].equals(expected[1]) || !result.equals(expected[0])) return false;
            } else if (name.equals("clamp")) {
                if (!mth || arguments.size() != 3 || !expected[0].equals(expected[1])
                        || !expected[0].equals(expected[2]) || !result.equals(expected[0])) return false;
            } else if (arguments.size() != 1) return false;
            List<Node> nodes = arguments.stream().map(Value::node).toList();
            Node node;
            if (mth && name.equals("sqrt")) {
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
            stack.push(new Value(result, node, code, null));
            return true;
        }

        private void binary(AbstractInsnNode instruction, String operator) {
            Type type = arithmeticType((instruction.getOpcode() - Opcodes.IADD) % 4);
            Value right = pop(type);
            Value left = pop(type);
            stack.push(new Value(type, new Binary(type, left.node(), operator, right.node()),
                    append(instruction, left, right), null));
        }

        private void conversion(AbstractInsnNode instruction, Type from, Type to) {
            Value value = pop(from);
            stack.push(new Value(to, new Conversion(to, value.node()), append(instruction, value), null));
        }

        private Value pop() {
            if (stack.isEmpty()) throw new Rejected("Invalid instruction stack");
            return stack.pop();
        }

        private Value pop(Type type) {
            Value value = pop();
            if (!value.type().equals(type) || value.node() == null) throw new Rejected("Numeric operand type mismatch");
            return value;
        }
    }

    private static Value[] callInputs(Value receiver, List<Value> arguments) {
        var values = new ArrayList<Value>();
        if (receiver != null) values.add(receiver);
        values.addAll(arguments);
        return values.toArray(Value[]::new);
    }

    private static List<AbstractInsnNode> append(AbstractInsnNode instruction, Value... values) {
        var instructions = new ArrayList<AbstractInsnNode>();
        for (Value value : values) instructions.addAll(value.instructions());
        instructions.add(instruction);
        return instructions;
    }

    private static void validateNativePower(MethodNode method) {
        boolean power = method.name.equals("getSpellPower");
        int[] expected = power
                ? new int[]{15,57,15,57,25,178,184,192,182,56,25,193,153,25,192,58,25,178,182,192,182,144,141,57,
                25,182,25,182,57,25,180,25,180,21,4,100,104,96,135,24,107,24,107,23,141,107,144,174}
                : new int[]{25,178,184,192,182,56,25,199,23,174,25,178,182,192,182,144,56,25,182,25,182,57,
                23,23,106,141,24,107,144,174};
        int[] expectedSlots = power
                ? new int[]{3,5,0,7,2,2,8,8,3,0,8,5,0,0,1,3,5,7}
                : new int[]{0,2,1,2,1,3,0,1,4,2,3,4};
        var executable = new ArrayList<AbstractInsnNode>();
        var slots = new ArrayList<Integer>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) executable.add(instruction);
            if (instruction instanceof VarInsnNode variable) slots.add(variable.var);
        }
        if (executable.size() != expected.length || slots.size() != expectedSlots.length)
            throw new Rejected("Native power control flow differs from the audited 3.16.1 structure");
        for (int index = 0; index < expected.length; index++)
            if (executable.get(index).getOpcode() != expected[index])
                throw new Rejected("Native power instruction structure is not supported");
        for (int index = 0; index < expectedSlots.length; index++)
            if (slots.get(index) != expectedSlots[index])
                throw new Rejected("Native power local structure is not supported");
        JumpInsnNode jump = (JumpInsnNode) executable.get(power ? 12 : 7);
        AbstractInsnNode target = jump.label;
        while (target != null && target.getOpcode() < 0) target = target.getNext();
        if (target != executable.get(power ? 29 : 10))
            throw new Rejected("Native power branch target is not supported");
    }

    private static int argumentSlots(MethodNode method) {
        int slots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) slots += argument.getSize();
        return slots;
    }

    private static Type arithmeticType(int category) {
        return switch (category) {
            case 0 -> Type.INT_TYPE;
            case 1 -> Type.LONG_TYPE;
            case 2 -> Type.FLOAT_TYPE;
            case 3 -> Type.DOUBLE_TYPE;
            default -> throw new Rejected("Invalid numeric opcode category " + category);
        };
    }

    private static boolean numeric(Type type) {
        return type.equals(Type.INT_TYPE) || type.equals(Type.LONG_TYPE)
                || type.equals(Type.FLOAT_TYPE) || type.equals(Type.DOUBLE_TYPE);
    }

    private static void requireNumeric(Type type) {
        if (!numeric(type)) throw new Rejected("Unsupported numeric type " + type.getDescriptor());
    }

    private static boolean reference(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private static final class Rejected extends RuntimeException {
        private Rejected(String message) { super(message); }
    }
}
