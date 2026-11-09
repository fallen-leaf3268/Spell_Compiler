package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.*;
import java.util.function.Supplier;

public final class FormulaBackwardSlice {
    public enum Completeness { FULL, PARTIAL, UNSUPPORTED }

    public record Result(String expression, List<FormulaCapturedInput> inputs, Completeness completeness,
                         List<String> diagnostics, boolean nativePower) {
        public Result {
            inputs = List.copyOf(inputs);
            diagnostics = List.copyOf(diagnostics);
        }
        public boolean supported() { return completeness != Completeness.UNSUPPORTED && expression != null; }
    }

    private FormulaBackwardSlice() {}

    public static Result argument(FormulaClassRepository repository, FormulaDataFlow.Analysis analysis,
                                  MethodInsnNode consumer, int argumentIndex, Map<Integer, String> parameterBindings) {
        return argument(repository, analysis, consumer, argumentIndex, parameterBindings, Set.of());
    }

    public static Result argument(FormulaClassRepository repository, FormulaDataFlow.Analysis analysis,
                                  MethodInsnNode consumer, int argumentIndex, Map<Integer, String> parameterBindings,
                                  Set<FormulaDataFlow.MethodId> opaqueMethods) {
        List<FormulaDataFlow.Value> arguments = analysis.arguments(consumer);
        if (argumentIndex < 0 || argumentIndex >= arguments.size())
            return unsupported("CONSUMER_ARGUMENT_UNAVAILABLE " + analysis.method().key() + ':' + argumentIndex);
        return new Session(repository, analysis, consumer, false, parameterBindings, opaqueMethods).run(arguments.get(argumentIndex));
    }

    public static Result value(FormulaClassRepository repository, FormulaDataFlow.Analysis analysis,
                              SourceValue value, AbstractInsnNode consumer, Map<Integer, String> parameterBindings) {
        return value(repository, analysis, value, consumer, parameterBindings, Set.of());
    }

    public static Result value(FormulaClassRepository repository, FormulaDataFlow.Analysis analysis,
                              SourceValue value, AbstractInsnNode consumer, Map<Integer, String> parameterBindings,
                              Set<FormulaDataFlow.MethodId> opaqueMethods) {
        return new Session(repository, analysis, consumer, true, parameterBindings, opaqueMethods).run(value);
    }

    private static Result unsupported(String diagnostic) {
        return new Result(null, List.of(), Completeness.UNSUPPORTED, List.of(diagnostic), false);
    }

    private record Expression(Type type, String text, boolean partial, boolean nativePower, Set<String> inputs) {
        Expression { inputs = Set.copyOf(inputs); }
    }
    private record Choice(AbstractInsnNode instruction, Supplier<Expression> expression) {}
    private record CaptureKey(int producer, int local, String descriptor) {}
    private static final class Rejected extends RuntimeException {
        Rejected(String message) { super(message); }
    }
    private static final class Context {
        final FormulaDataFlow.Analysis analysis;
        final Map<Integer, Supplier<Expression>> parameters;
        final Map<FormulaDataFlow.Node, Expression> cached = new HashMap<>();
        final Set<FormulaDataFlow.Node> active = new HashSet<>();
        Context(FormulaDataFlow.Analysis analysis, Map<Integer, Supplier<Expression>> parameters) {
            this.analysis = analysis;
            this.parameters = parameters;
        }
    }

    private static final class Session {
        private static final String BASE = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
        private final FormulaClassRepository repository;
        private final FormulaDataFlow.Analysis root;
        private final AbstractInsnNode consumer;
        private final boolean afterConsumer;
        private final Context context;
        private final Set<FormulaDataFlow.MethodId> opaqueMethods;
        private final Map<CaptureKey, FormulaCapturedInput> captures = new LinkedHashMap<>();
        private final Set<String> diagnostics = new LinkedHashSet<>();
        private final Set<FormulaDataFlow.MethodId> helpers = new HashSet<>();
        private int operations;
        private int depth;

        Session(FormulaClassRepository repository, FormulaDataFlow.Analysis root, AbstractInsnNode consumer,
                boolean afterConsumer, Map<Integer, String> bindings, Set<FormulaDataFlow.MethodId> opaqueMethods) {
            this.repository = repository;
            this.root = root;
            this.consumer = consumer;
            this.afterConsumer = afterConsumer;
            this.opaqueMethods = Set.copyOf(opaqueMethods);
            Map<Integer, Supplier<Expression>> parameters = new HashMap<>();
            for (var entry : bindings.entrySet()) {
                FormulaDataFlow.Node parameter = root.parameters().stream()
                        .filter(node -> node.parameterSlot() == entry.getKey()).findFirst().orElse(null);
                if (parameter == null || numeric(parameter.type()) == null) continue;
                String name = entry.getValue();
                if (!Set.of("level", "spell_power", "school_spell_power", "power_multiplier").contains(name)) continue;
                Type type = numeric(parameter.type());
                parameters.put(entry.getKey(), () -> new Expression(type, type.getClassName() + '(' + name + ')', false, false, Set.of()));
            }
            context = new Context(root, parameters);
            helpers.add(root.method());
        }

        Result run(SourceValue source) {
            if (!root.successful()) return unsupported("IR_UNAVAILABLE " + root.method().key() + ' ' + root.diagnostics());
            if (!(source instanceof FormulaDataFlow.Value value) || numeric(value.type()) == null)
                return unsupported("NUMERIC_SOURCE_UNAVAILABLE " + root.method().key());
            try {
                Expression expression = render(context, value, consumer);
                List<FormulaCapturedInput> inputs = captures.values().stream()
                        .filter(input -> expression.inputs().contains(input.name())).toList();
                Map<String, Type> variables = new HashMap<>(Map.of("native_value", Type.DOUBLE_TYPE,
                        "level", Type.INT_TYPE, "spell_power", Type.DOUBLE_TYPE,
                        "school_spell_power", Type.DOUBLE_TYPE, "power_multiplier", Type.DOUBLE_TYPE));
                inputs.forEach(input -> variables.put(input.name(), input.type()));
                try {
                    FormulaCompiler.validateExpression(expression.text(), variables);
                    ReadableFormula.display("double(" + expression.text() + ')');
                } catch (IllegalArgumentException rejected) {
                    diagnostics.add("LOWERING_LANGUAGE_REJECTED " + root.method().key() + ' ' + rejected.getMessage());
                    return new Result(null, List.of(), Completeness.UNSUPPORTED, new ArrayList<>(diagnostics), expression.nativePower());
                }
                return new Result(expression.text(), inputs, expression.partial() ? Completeness.PARTIAL : Completeness.FULL,
                        new ArrayList<>(diagnostics), expression.nativePower());
            } catch (Rejected boundary) {
                diagnostics.add("OPAQUE_CONSUMPTION " + root.method().key() + ':' + executableIndex(root, consumer) + ' ' + boundary.getMessage());
                return new Result("native_value", List.of(), Completeness.PARTIAL, new ArrayList<>(diagnostics),
                        nativePower(context, value, new HashSet<>()));
            }
        }

        private Expression render(Context current, FormulaDataFlow.Value value, AbstractInsnNode anchor) {
            if (++operations > 8192) throw new Rejected("SLICE_OPERATION_BOUND");
            if (value == null || numeric(value.type()) == null || value.sources().isEmpty()) throw new Rejected("UNKNOWN_TYPED_VALUE");
            if (value.sources().size() == 1) return node(current, value.sources().iterator().next());
            List<Choice> choices = value.sources().stream().sorted(Comparator.comparingInt(FormulaDataFlow.Node::id))
                    .map(source -> new Choice(source.instruction(), () -> node(current, source))).toList();
            return conditional(current, choices, anchor);
        }

        private Expression node(Context current, FormulaDataFlow.Node node) {
            Expression cached = current.cached.get(node);
            if (cached != null) return cached;
            if (depth >= 128) throw new Rejected("SLICE_DEPTH_BOUND");
            if (!current.active.add(node)) throw new Rejected("LOOP_OR_RECURSIVE_VALUE " + current.analysis.method().key());
            depth++;
            try {
                Expression result = lower(current, node);
                current.cached.put(node, result);
                return result;
            } finally { current.active.remove(node); depth--; }
        }

        private Expression lower(Context current, FormulaDataFlow.Node node) {
            if (node.kind() == FormulaDataFlow.NodeKind.PARAMETER) {
                Supplier<Expression> binding = current.parameters.get(node.parameterSlot());
                if (binding != null) return binding.get();
                return parameter(current, node);
            }
            if (node.kind() != FormulaDataFlow.NodeKind.INSTRUCTION || node.instruction() == null)
                throw new Rejected("NON_NUMERIC_EXCEPTION_SOURCE");
            AbstractInsnNode instruction = node.instruction();
            Type type = numeric(node.type());
            if (type == null) throw new Rejected("REFERENCE_VALUE " + current.analysis.method().key());
            List<FormulaDataFlow.Value> inputs = current.analysis.dependencies(node);
            Number literal = literal(instruction);
            if (literal != null && Double.isFinite(literal.doubleValue())) return constant(type, literal);
            int opcode = instruction.getOpcode();
            try {
                if (alias(opcode) && inputs.size() == 1) return cast(type, render(current, inputs.get(0), instruction));
                if (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM && inputs.size() == 2) {
                    String operator = switch ((opcode - Opcodes.IADD) / 4) {
                        case 0 -> "+";
                        case 1 -> "-";
                        case 2 -> "*";
                        case 3 -> "/";
                        default -> "%";
                    };
                    Expression left = render(current, inputs.get(0), instruction), right = render(current, inputs.get(1), instruction);
                    return binary(type, left, operator, right);
                }
                if (opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG && inputs.size() == 1) {
                    Expression input = render(current, inputs.get(0), instruction);
                    return combine(type, type.getClassName() + "(-(" + input.text() + "))", input);
                }
                if (opcode >= Opcodes.I2L && opcode <= Opcodes.D2F && inputs.size() == 1)
                    return cast(type, render(current, inputs.get(0), instruction));
                if (opcode >= Opcodes.LCMP && opcode <= Opcodes.DCMPG && inputs.size() == 2)
                    return comparison(current, instruction, inputs);
                if (instruction instanceof MethodInsnNode call) return call(current, call);
                if (instruction instanceof FieldInsnNode field) {
                    if (field.getOpcode() == Opcodes.GETSTATIC) {
                        var resolved = repository.resolveField(field.owner, field.name, field.desc);
                        if (resolved != null && (resolved.field().access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                                == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL) && resolved.field().value instanceof Number number
                                && Double.isFinite(number.doubleValue())) return constant(type, number);
                    }
                    return opaque(current, node, "FIELD_SOURCE " + field.owner + '#' + field.name + ':' + field.desc, false, true);
                }
                return opaque(current, node, "JVM_OPERATION " + opcode, nativePower(current, inputs, new HashSet<>()), true);
            } catch (Rejected boundary) {
                return opaque(current, node, boundary.getMessage(), nativePower(current, inputs, new HashSet<>()), true);
            }
        }

        private Expression parameter(Context current, FormulaDataFlow.Node parameter) {
            if (current != context) throw new Rejected("UNBOUND_HELPER_PARAMETER " + current.analysis.method().key() + ':' + parameter.parameterSlot());
            Type type = numeric(parameter.type());
            FormulaDataFlow.FrameState frame = root.frameBefore(consumer);
            int slot = parameter.parameterSlot();
            if (type == null || frame == null || slot >= frame.locals().size() || !root.parameter(frame.locals().get(slot), slot))
                throw new Rejected("PARAMETER_REASSIGNED " + root.method().key() + ':' + slot);
            if (afterConsumer && consumer instanceof IincInsnNode increment && increment.var == slot
                    || afterConsumer && consumer instanceof VarInsnNode variable && variable.var == slot
                    && variable.getOpcode() >= Opcodes.ISTORE && variable.getOpcode() <= Opcodes.ASTORE)
                throw new Rejected("PARAMETER_CHANGED_AT_CONSUMER " + slot);
            diagnostics.add("UNBOUND_PARAMETER " + root.method().key() + ':' + slot);
            return capture(type, -1, slot, false, true, true);
        }

        private Expression opaque(Context current, FormulaDataFlow.Node node, String reason, boolean power, boolean powerUnknown) {
            if (current != context) throw new Rejected(reason + " FOREIGN_METHOD_SOURCE " + current.analysis.method().key());
            AbstractInsnNode producer = node.instruction();
            if (producer == null || !stackProducer(root, producer)
                    || !(afterConsumer && producer == consumer || root.resultDominates(producer, consumer)))
                throw new Rejected(reason + " NON_DOMINATING_OR_NON_STACK_SOURCE");
            Type type = numeric(root.instruction(producer).results().get(0).type());
            int index = executableIndex(root, producer);
            diagnostics.add(reason + " @ " + root.method().key() + ':' + index);
            return capture(type, index, -1, power, powerUnknown && !power, true);
        }

        private Expression capture(Type type, int producer, int local, boolean power, boolean powerUnknown, boolean partial) {
            CaptureKey key = new CaptureKey(producer, local, type.getDescriptor());
            FormulaCapturedInput input = captures.get(key);
            if (input == null) {
                input = new FormulaCapturedInput("input_" + captures.size(), type.getDescriptor(), producer, local, power, powerUnknown);
                captures.put(key, input);
            }
            return new Expression(type, input.name(), partial, input.nativePower(), Set.of(input.name()));
        }

        private Expression call(Context current, MethodInsnNode call) {
            Type type = numeric(Type.getReturnType(call.desc));
            List<FormulaDataFlow.Value> arguments = current.analysis.arguments(call);
            Expression math = math(current, call, arguments);
            if (math != null) return math;
            var declaration = repository.resolveMethod(call.owner, call.name, call.desc);
            var receiver = current.analysis.origin(current.analysis.receiver(call));
            String exactReceiver = receiver != null && receiver.instruction() instanceof TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW ? allocation.desc : null;
            var resolved = repository.resolveInvocation(current.analysis.method().owner(), call, exactReceiver).target();
            boolean power = nativePowerCall(call, declaration);
            boolean methodBoundary = opaqueMethods.contains(new FormulaDataFlow.MethodId(call.owner, call.name, call.desc))
                    || declaration != null && opaqueMethods.contains(new FormulaDataFlow.MethodId(
                            declaration.owner().name, declaration.method().name, declaration.method().desc))
                    || resolved != null && opaqueMethods.contains(new FormulaDataFlow.MethodId(resolved.owner().name, resolved.method().name, resolved.method().desc));
            if (!power && !methodBoundary && resolved != null && !call.name.startsWith("<") && inlineable(call, resolved, exactReceiver)) {
                FormulaDataFlow.Analysis summary = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
                if (summary.successful() && helpers.size() < 12 && helpers.add(summary.method())) {
                    try {
                        Map<Integer, Supplier<Expression>> parameters = new HashMap<>();
                        int slot = (resolved.method().access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                        Type[] types = Type.getArgumentTypes(call.desc);
                        for (int index = 0; index < types.length; index++) {
                            int selected = index;
                            if (numeric(types[index]) != null && index < arguments.size())
                                parameters.put(slot, () -> render(current, arguments.get(selected), call));
                            slot += types[index].getSize();
                        }
                        Context child = new Context(summary, parameters);
                        List<FormulaDataFlow.Instruction> returns = summary.instructions().stream().filter(instruction ->
                                summary.reachable(instruction.instruction()) && instruction.instruction().getOpcode() >= Opcodes.IRETURN
                                        && instruction.instruction().getOpcode() <= Opcodes.DRETURN).toList();
                        if (returns.size() == 1 && returns.get(0).operands().size() == 1)
                            return cast(type, render(child, returns.get(0).operands().get(0), returns.get(0).instruction()));
                        if (returns.size() == 2) {
                            List<Choice> choices = returns.stream().map(result -> new Choice(result.instruction(),
                                    (Supplier<Expression>) () -> render(child, result.operands().get(0), result.instruction()))).toList();
                            return cast(type, conditional(child, choices, null));
                        }
                    } catch (Rejected boundary) {
                        diagnostics.add("HELPER_BOUNDARY " + summary.method().key() + ' ' + boundary.getMessage());
                    } finally { helpers.remove(summary.method()); }
                }
            }
            FormulaDataFlow.Instruction instruction = current.analysis.instruction(call);
            if (instruction == null || instruction.results().isEmpty()) throw new Rejected("CALL_RESULT_UNAVAILABLE");
            var node = instruction.results().get(instruction.results().size() - 1).sources().stream().findFirst().orElseThrow(() -> new Rejected("CALL_SOURCE_UNAVAILABLE"));
            return opaque(current, node, (power ? "NATIVE_POWER " : methodBoundary ? "PUBLISHED_METHOD " : "OPAQUE_CALL ")
                            + call.owner + '#' + call.name + call.desc,
                    power || nativePower(current, arguments, new HashSet<>()), !power);
        }

        private boolean inlineable(MethodInsnNode call, FormulaClassRepository.ResolvedMethod resolved, String exactReceiver) {
            if (call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL
                    || (resolved.method().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0
                    || (resolved.owner().access & Opcodes.ACC_FINAL) != 0) return true;
            return resolved.owner().name.equals(exactReceiver);
        }

        private Expression math(Context current, MethodInsnNode call, List<FormulaDataFlow.Value> arguments) {
            if (call.getOpcode() != Opcodes.INVOKESTATIC) return null;
            String name = call.name;
            boolean mth = call.owner.equals("net/minecraft/util/Mth");
            boolean java = call.owner.equals("java/lang/Math") || call.owner.equals("java/lang/StrictMath");
            if (call.owner.equals("io/redspace/ironsspellbooks/api/util/Utils") && name.equals("softCapFormula") && call.desc.equals("(D)D"))
                return function(current, call, arguments, "softcap");
            if (!mth && !java) return null;
            if (mth) name = switch (name) {
                case "m_14116_" -> "sqrt";
                case "m_14143_", "m_14107_" -> "floor";
                case "m_14167_", "m_14165_" -> "ceil";
                case "m_14154_", "m_14040_" -> "abs";
                case "m_14045_", "m_14036_", "m_14008_" -> "clamp";
                default -> name;
            };
            Type[] types = Type.getArgumentTypes(call.desc);
            Type result = numeric(Type.getReturnType(call.desc));
            if (result == null || Arrays.stream(types).anyMatch(type -> numeric(type) == null)) return null;
            if ((name.equals("min") || name.equals("max")) && types.length == 2 && types[0].equals(types[1]) && result.equals(types[0]))
                return function(current, call, arguments, name);
            if (name.equals("clamp") && mth && types.length == 3 && types[0].equals(types[1]) && types[0].equals(types[2]) && result.equals(types[0]))
                return function(current, call, arguments, name);
            if (name.equals("abs") && types.length == 1 && result.equals(types[0])) return function(current, call, arguments, name);
            if (name.equals("round") && Set.of("(F)I", "(D)J").contains(call.desc)) return function(current, call, arguments, name);
            if (mth && (name.equals("floor") || name.equals("ceil")) && Set.of("(F)I", "(D)I").contains(call.desc))
                return function(current, call, arguments, "mth_" + name);
            if (Set.of("sqrt", "floor", "ceil").contains(name) && call.desc.equals("(D)D")) return function(current, call, arguments, name);
            if (mth && name.equals("sqrt") && call.desc.equals("(F)F")) {
                Expression input = cast(Type.DOUBLE_TYPE, render(current, arguments.get(0), call));
                return combine(Type.FLOAT_TYPE, "float(sqrt(" + input.text() + "))", input);
            }
            if (mth && name.equals("lerp") && Set.of("(FFF)F", "(DDD)D").contains(call.desc)) {
                Expression delta = render(current, arguments.get(0), call), start = render(current, arguments.get(1), call), end = render(current, arguments.get(2), call);
                return binary(result, start, "+", binary(result, delta, "*", binary(result, end, "-", start)));
            }
            return null;
        }

        private Expression function(Context current, MethodInsnNode call, List<FormulaDataFlow.Value> arguments, String name) {
            List<Expression> values = arguments.stream().map(value -> render(current, value, call)).toList();
            Type type = numeric(Type.getReturnType(call.desc));
            return combine(type, type.getClassName() + '(' + name + '(' + String.join(", ", values.stream().map(Expression::text).toList()) + "))",
                    values.toArray(Expression[]::new));
        }

        private Expression comparison(Context current, AbstractInsnNode instruction, List<FormulaDataFlow.Value> inputs) {
            Expression left = render(current, inputs.get(0), instruction), right = render(current, inputs.get(1), instruction);
            String ordered = "if(lt(" + left.text() + ", " + right.text() + "), int(-1), if(gt(" + left.text() + ", " + right.text() + "), int(1), int(0)))";
            if (instruction.getOpcode() == Opcodes.LCMP) return combine(Type.INT_TYPE, ordered, left, right);
            int fallback = instruction.getOpcode() == Opcodes.FCMPL || instruction.getOpcode() == Opcodes.DCMPL ? -1 : 1;
            return combine(Type.INT_TYPE, "if(eq(" + left.text() + ", " + left.text() + "), if(eq(" + right.text() + ", "
                    + right.text() + "), " + ordered + ", int(" + fallback + ")), int(" + fallback + "))", left, right);
        }

        private Expression conditional(Context current, List<Choice> choices, AbstractInsnNode join) {
            if (choices.size() != 2 || choices.stream().anyMatch(choice -> choice.instruction() == null)) throw new Rejected("CONTROL_MERGE_UNPROVEN");
            List<FormulaDataFlow.Instruction> instructions = current.analysis.instructions();
            for (int index = instructions.size() - 1; index >= 0; index--) {
                var instruction = instructions.get(index);
                if (!(instruction.instruction() instanceof JumpInsnNode jump) || jump.getOpcode() == Opcodes.GOTO
                        || jump.getOpcode() == Opcodes.JSR || !current.analysis.reachable(jump)) continue;
                if (join != null && !current.analysis.dominates(jump, join)
                        || join == null && choices.stream().anyMatch(choice -> !current.analysis.dominates(jump, choice.instruction()))) continue;
                int target = rawIndex(current.analysis, jump.label), fallthrough = instruction.index() + 1;
                int stop = join == null ? -1 : rawIndex(current.analysis, join);
                Choice taken = null, ordinary = null;
                boolean ambiguous = false;
                for (Choice choice : choices) {
                    int point = rawIndex(current.analysis, choice.instruction());
                    boolean yes = path(current.analysis, target, point, stop, instruction.index());
                    boolean no = path(current.analysis, fallthrough, point, stop, instruction.index());
                    if (yes == no) { ambiguous = true; break; }
                    if (yes) { if (taken != null) ambiguous = true; taken = choice; }
                    else { if (ordinary != null) ambiguous = true; ordinary = choice; }
                }
                if (ambiguous || taken == null || ordinary == null) continue;
                Expression condition = condition(current, jump);
                Expression yes = taken.expression().get(), no = ordinary.expression().get();
                if (!yes.type().equals(no.type())) throw new Rejected("CONTROL_MERGE_TYPE_MISMATCH");
                return combine(yes.type(), yes.type().getClassName() + "(if(" + condition.text() + ", " + yes.text() + ", " + no.text() + "))", condition, yes, no);
            }
            throw new Rejected("CONTROL_MERGE_UNPROVEN");
        }

        private Expression condition(Context current, JumpInsnNode jump) {
            int opcode = jump.getOpcode();
            List<FormulaDataFlow.Value> inputs = current.analysis.operands(jump);
            int offset = opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE ? opcode - Opcodes.IFEQ
                    : opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ICMPLE ? opcode - Opcodes.IF_ICMPEQ : -1;
            if (offset < 0 || inputs.isEmpty()) throw new Rejected("REFERENCE_OR_UNKNOWN_BRANCH_PREDICATE");
            String operator = List.of("eq", "ne", "lt", "ge", "gt", "le").get(offset);
            Expression left = render(current, inputs.get(0), jump);
            Expression right = inputs.size() == 1 ? constant(Type.INT_TYPE, 0) : render(current, inputs.get(1), jump);
            return combine(Type.INT_TYPE, operator + '(' + left.text() + ", " + right.text() + ')', left, right);
        }

        private boolean nativePower(Context current, List<FormulaDataFlow.Value> values, Set<FormulaDataFlow.Node> visited) {
            ArrayDeque<FormulaDataFlow.Value> pending = new ArrayDeque<>(values);
            while (!pending.isEmpty()) {
                var value = pending.removeFirst();
                for (var node : value.sources()) {
                    if (!visited.add(node)) continue;
                    if (node.instruction() instanceof MethodInsnNode call && nativePowerCall(call,
                            repository.resolveMethod(call.owner, call.name, call.desc))) return true;
                    pending.addAll(current.analysis.dependencies(node));
                }
            }
            return false;
        }

        private boolean nativePower(Context current, FormulaDataFlow.Value value, Set<FormulaDataFlow.Node> visited) {
            return nativePower(current, List.of(value), visited);
        }

        private static boolean nativePowerCall(MethodInsnNode call, FormulaClassRepository.ResolvedMethod resolved) {
            if (resolved != null && resolved.owner().name.equals(BASE)
                    && Set.of("getSpellPower(ILnet/minecraft/world/entity/Entity;)F", "getEntityPowerMultiplier(Lnet/minecraft/world/entity/LivingEntity;)F")
                    .contains(call.name + call.desc)) return true;
            return call.owner.equals("com/example/spell_compiler/formula/FormulaRuntimeHooks")
                    && Set.of("applyPower", "applyEntityPower").contains(call.name)
                    && call.desc.equals("(FLjava/lang/Object;Ljava/lang/Object;)F");
        }
    }

    private static Expression constant(Type type, Number number) {
        String value;
        if (type.equals(Type.INT_TYPE) && number.intValue() == Integer.MIN_VALUE) value = "(-int(2147483647) - int(1))";
        else if (type.equals(Type.LONG_TYPE) && number.longValue() == Long.MIN_VALUE) value = "(-long(9223372036854775807L) - long(1L))";
        else value = type.getClassName() + '(' + (type.equals(Type.LONG_TYPE) ? Long.toString(number.longValue()) + 'L'
                : type.equals(Type.INT_TYPE) ? Integer.toString(number.intValue())
                : type.equals(Type.FLOAT_TYPE) ? Float.toString(number.floatValue()) : Double.toString(number.doubleValue())) + ')';
        return new Expression(type, value, false, false, Set.of());
    }

    private static Expression cast(Type type, Expression input) {
        return type.equals(input.type()) ? input : combine(type, type.getClassName() + '(' + input.text() + ')', input);
    }

    private static Expression binary(Type type, Expression left, String operator, Expression right) {
        return combine(type, type.getClassName() + "((" + left.text() + ' ' + operator + ' ' + right.text() + "))", left, right);
    }

    private static Expression combine(Type type, String text, Expression... inputs) {
        if (text.length() > 32768) throw new Rejected("SLICE_EXPRESSION_BOUND");
        try { FormulaSyntaxGuard.validate(text); }
        catch (IllegalArgumentException rejected) { throw new Rejected("EXPRESSION_LANGUAGE_BOUNDARY " + rejected.getMessage()); }
        Set<String> captures = new LinkedHashSet<>();
        boolean partial = false, power = false;
        for (Expression input : inputs) {
            partial |= input.partial();
            power |= input.nativePower();
            captures.addAll(input.inputs());
        }
        return new Expression(type, text, partial, power, captures);
    }

    private static Type numeric(Type type) {
        if (type == null) return null;
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Type.INT_TYPE;
            case Type.LONG, Type.FLOAT, Type.DOUBLE -> type;
            default -> null;
        };
    }

    private static Number literal(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode literal && literal.cst instanceof Number number) return number;
        if (instruction instanceof IntInsnNode literal && Set.of(Opcodes.BIPUSH, Opcodes.SIPUSH).contains(literal.getOpcode())) return literal.operand;
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (opcode >= Opcodes.LCONST_0 && opcode <= Opcodes.LCONST_1) return (long) (opcode - Opcodes.LCONST_0);
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return (float) (opcode - Opcodes.FCONST_0);
        if (opcode >= Opcodes.DCONST_0 && opcode <= Opcodes.DCONST_1) return (double) (opcode - Opcodes.DCONST_0);
        return null;
    }

    private static boolean alias(int opcode) {
        return opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD || opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE
                || opcode >= Opcodes.DUP && opcode <= Opcodes.SWAP || opcode == Opcodes.CHECKCAST;
    }

    private static boolean stackProducer(FormulaDataFlow.Analysis analysis, AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE || opcode >= Opcodes.DUP && opcode <= Opcodes.SWAP
                || opcode == Opcodes.IINC) return false;
        var result = analysis.instruction(instruction);
        return result != null && result.results().size() == 1 && numeric(result.results().get(0).type()) != null;
    }

    private static int rawIndex(FormulaDataFlow.Analysis analysis, AbstractInsnNode instruction) {
        var value = analysis.instruction(instruction);
        return value == null ? -1 : value.index();
    }

    private static int executableIndex(FormulaDataFlow.Analysis analysis, AbstractInsnNode selected) {
        int index = 0;
        for (var instruction : analysis.instructions()) {
            if (instruction.instruction().getOpcode() < 0) continue;
            if (instruction.instruction() == selected) return index;
            index++;
        }
        return -1;
    }

    private static boolean path(FormulaDataFlow.Analysis analysis, int start, int target, int stop, int forbidden) {
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            int point = pending.removeFirst();
            if (point == target) return true;
            if (point < 0 || point >= analysis.instructions().size() || point == stop || point == forbidden || !visited.add(point)) continue;
            for (var edge : analysis.successors(analysis.instructions().get(point).instruction()))
                if (edge.kind() == FormulaDataFlow.EdgeKind.NORMAL) pending.add(edge.to());
        }
        return false;
    }
}
