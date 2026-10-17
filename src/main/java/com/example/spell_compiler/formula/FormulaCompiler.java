package com.example.spell_compiler.formula;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class FormulaCompiler {
    public static byte[] transform(byte[] original, Map<String, String> overrides) {
        if (overrides.isEmpty()) return original;
        ClassNode owner = new ClassNode();
        new ClassReader(original).accept(owner, 0);
        return transform(original, overrides, FormulaClassRepository.of(owner));
    }

    public static byte[] transform(byte[] original, Map<String, String> overrides, FormulaClassRepository repository) {
        return compile(original, overrides, repository, false, false);
    }

    public static byte[] transformRuntime(byte[] original, Map<String, String> overrides, FormulaClassRepository repository) {
        return transformRuntime(original, overrides, repository, false);
    }

    public static byte[] transformRuntime(byte[] original, Map<String, String> overrides,
                                          FormulaClassRepository repository, boolean widePower) {
        return transformRuntime(original, overrides, repository, widePower, null);
    }

    static byte[] transformRuntime(byte[] original, Map<String, String> overrides,
                                  FormulaClassRepository repository, boolean widePower, String receiverClass) {
        return compile(original, overrides, repository, false, true, widePower, receiverClass);
    }

    public static Type validateExpression(String expression, Map<String, Type> variableTypes) {
        return validateExpression(expression, variableTypes, false);
    }

    public static Type validateExpression(String expression, Map<String, Type> variableTypes, boolean widePower) {
        Map<String, AutomaticFormulaExtractor.Variable> variables = new LinkedHashMap<>();
        variableTypes.forEach((name, type) -> variables.put(name,
                new AutomaticFormulaExtractor.Variable(name, type, List.of())));
        return new Parser(expression, variables, new IdentityHashMap<>(), Map.of(), widePower).parse().type();
    }

    public static byte[] compileForVerification(byte[] original, Map<String, String> overrides,
                                                FormulaClassRepository repository) {
        return compile(original, overrides, repository, true, false);
    }

    public static byte[] compileForVerification(byte[] original, Map<String, String> overrides,
                                                FormulaClassRepository repository, String receiverClass) {
        return compile(original, overrides, repository, true, false, false, receiverClass);
    }

    private static byte[] compile(byte[] original, Map<String, String> overrides,
                                  FormulaClassRepository repository, boolean force, boolean runtime) {
        return compile(original, overrides, repository, force, runtime, false);
    }

    private static byte[] compile(byte[] original, Map<String, String> overrides,
                                  FormulaClassRepository repository, boolean force, boolean runtime, boolean widePower) {
        return compile(original, overrides, repository, force, runtime, widePower, null);
    }

    private static byte[] compile(byte[] original, Map<String, String> overrides,
                                  FormulaClassRepository repository, boolean force, boolean runtime,
                                  boolean widePower, String receiverClass) {
        if (overrides.isEmpty()) return original;
        ClassReader reader = new ClassReader(original);
        ClassNode owner = new ClassNode();
        reader.accept(owner, 0);
        var unmatched = new HashSet<>(overrides.keySet());
        Map<String, MethodNode> changed = new LinkedHashMap<>();
        for (var method : owner.methods) {
            String key = method.name + method.desc;
            if (!overrides.containsKey(key)) continue;
            var analysis = runtime ? AutomaticFormulaExtractor.analyzeRuntime(owner, method, repository, receiverClass)
                    : AutomaticFormulaExtractor.analyze(owner, method, repository, receiverClass);
            if (!analysis.supported()) {
                throw new IllegalArgumentException(key + ": " + analysis.rejection());
            }
            var labels = new IdentityHashMap<LabelNode, LabelNode>();
            collectLabels(analysis.formula().inputPrelude(), labels);
            analysis.formula().variables().values().forEach(variable -> collectLabels(variable.instructions(), labels));
            analysis.formula().nativePowers().values().forEach(power -> {
                collectLabels(power.receiver(), labels);
                collectLabels(power.caster(), labels);
            });
            boolean widened = runtime && widePower && FormulaWidePower.applies(analysis.formula().variables());
            var variables = widened ? FormulaWidePower.variables(method, repository, analysis.formula().variables())
                    : analysis.formula().variables();
            variables.values().forEach(variable -> collectLabels(variable.instructions(), labels));
            Expression expression;
            String resolved;
            try {
                Map<String, Type> variableTypes = new LinkedHashMap<>();
                analysis.formula().variables().forEach((name, variable) -> variableTypes.put(name, variable.type()));
                resolved = ReadableFormula.resolve(analysis.formula().expression(), overrides.get(key), variableTypes).expression();
                Parser parser = new Parser(resolved, variables, labels, analysis.formula().nativePowers(), widePower);
                expression = parser.parse();
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(owner.name + '#' + key + ": " + failure.getMessage(), failure);
            }
            unmatched.remove(key);
            if (!force && !widened && normalized(resolved).equals(normalized(analysis.formula().expression()))) continue;
            InsnList replacement = new InsnList();
            cloneInto(analysis.formula().inputPrelude(), replacement, labels);
            expression.emit().accept(replacement);
            convert(replacement, expression.type(), analysis.formula().returnType());
            if (runtime) guardRuntimeValues(replacement, analysis.formula().returnType());
            replacement.add(new InsnNode(analysis.formula().returnType().getOpcode(Opcodes.IRETURN)));
            MethodNode compiled = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc,
                    method.signature, method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
            method.accept(compiled);
            compiled.instructions = replacement;
            compiled.tryCatchBlocks.clear();
            if (compiled.localVariables != null) compiled.localVariables.clear();
            compiled.visibleLocalVariableAnnotations = null;
            compiled.invisibleLocalVariableAnnotations = null;
            compiled.maxStack = 0;
            compiled.maxLocals = Math.max(method.maxLocals, analysis.formula().requiredLocals());
            changed.put(key, compiled);
        }
        if (!unmatched.isEmpty()) {
            throw new IllegalArgumentException("Unknown formula targets: " + unmatched);
        }
        if (changed.isEmpty()) return original;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return repository.commonSuperClass(first, second);
            }
        };
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodNode replacement = changed.get(name + descriptor);
                if (replacement == null) return super.visitMethod(access, name, descriptor, signature, exceptions);
                replacement.accept(writer);
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static String normalized(String expression) {
        StringBuilder result = new StringBuilder(expression.length());
        for (int index = 0; index < expression.length(); index++) {
            char value = expression.charAt(index);
            if (!Character.isWhitespace(value)) result.append(value);
        }
        return result.toString();
    }

    private static void collectLabels(List<AbstractInsnNode> instructions, Map<LabelNode, LabelNode> labels) {
        for (AbstractInsnNode instruction : instructions) {
            if (instruction instanceof LabelNode label) labels.putIfAbsent(label, new LabelNode());
        }
    }

    private static void cloneInto(List<AbstractInsnNode> instructions, InsnList destination,
                                  Map<LabelNode, LabelNode> labels) {
        for (AbstractInsnNode instruction : instructions) destination.add(instruction.clone(labels));
    }

    private record Expression(Type type, Consumer<InsnList> emit) {}

    private static void guardRuntimeValues(InsnList instructions, Type returnType) {
        for (AbstractInsnNode instruction : instructions.toArray()) {
            MethodInsnNode replacement = switch (instruction.getOpcode()) {
                case Opcodes.IADD -> exactMath("addExact", "(II)I");
                case Opcodes.LADD -> exactMath("addExact", "(JJ)J");
                case Opcodes.ISUB -> exactMath("subtractExact", "(II)I");
                case Opcodes.LSUB -> exactMath("subtractExact", "(JJ)J");
                case Opcodes.IMUL -> exactMath("multiplyExact", "(II)I");
                case Opcodes.LMUL -> exactMath("multiplyExact", "(JJ)J");
                case Opcodes.INEG -> exactMath("negateExact", "(I)I");
                case Opcodes.LNEG -> exactMath("negateExact", "(J)J");
                case Opcodes.IDIV -> exactFunction("divideExact", "(II)I");
                case Opcodes.LDIV -> exactFunction("divideExact", "(JJ)J");
                case Opcodes.L2I -> exactMath("toIntExact", "(J)I");
                case Opcodes.F2I -> exactFunction("toIntExact", "(F)I");
                case Opcodes.D2I -> exactFunction("toIntExact", "(D)I");
                case Opcodes.F2L -> exactFunction("toLongExact", "(F)J");
                case Opcodes.D2L -> exactFunction("toLongExact", "(D)J");
                default -> null;
            };
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("java/lang/Math")) {
                    if (call.name.equals("round")) replacement = exactFunction("roundExact", call.desc);
                    else if (call.name.equals("abs") && (call.desc.equals("(I)I") || call.desc.equals("(J)J")))
                        replacement = exactMath("absExact", call.desc);
                } else if (call.owner.equals("com/example/spell_compiler/formula/FormulaFunctions")) {
                    if (call.name.equals("mth_floor")) replacement = exactFunction("floorToIntExact", call.desc);
                    else if (call.name.equals("mth_ceil")) replacement = exactFunction("ceilToIntExact", call.desc);
                }
            }
            if (replacement != null) instructions.set(instruction, replacement);
        }
        if (floating(returnType)) instructions.add(finiteCheck(returnType));
    }

    private static MethodInsnNode exactMath(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", name, descriptor, false);
    }

    private static MethodInsnNode exactFunction(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/example/spell_compiler/formula/FormulaFunctions", name, descriptor, false);
    }

    private static boolean floating(Type type) {
        return type.equals(Type.FLOAT_TYPE) || type.equals(Type.DOUBLE_TYPE);
    }

    private static MethodInsnNode finiteCheck(Type type) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/example/spell_compiler/formula/FormulaFunctions", "requireFinite",
                Type.getMethodDescriptor(type, type), false);
    }

    private static Type promote(Type left, Type right) {
        if (left.equals(Type.DOUBLE_TYPE) || right.equals(Type.DOUBLE_TYPE)) return Type.DOUBLE_TYPE;
        if (left.equals(Type.FLOAT_TYPE) || right.equals(Type.FLOAT_TYPE)) return Type.FLOAT_TYPE;
        if (left.equals(Type.LONG_TYPE) || right.equals(Type.LONG_TYPE)) return Type.LONG_TYPE;
        return Type.INT_TYPE;
    }

    private static void convert(InsnList instructions, Type from, Type to) {
        if (from.equals(to)) return;
        int opcode = switch (from.getSort()) {
            case Type.INT -> switch (to.getSort()) {
                case Type.LONG -> Opcodes.I2L;
                case Type.FLOAT -> Opcodes.I2F;
                case Type.DOUBLE -> Opcodes.I2D;
                default -> throw new IllegalArgumentException("Unsupported conversion: " + from + " -> " + to);
            };
            case Type.LONG -> switch (to.getSort()) {
                case Type.INT -> Opcodes.L2I;
                case Type.FLOAT -> Opcodes.L2F;
                case Type.DOUBLE -> Opcodes.L2D;
                default -> throw new IllegalArgumentException("Unsupported conversion: " + from + " -> " + to);
            };
            case Type.FLOAT -> switch (to.getSort()) {
                case Type.INT -> Opcodes.F2I;
                case Type.LONG -> Opcodes.F2L;
                case Type.DOUBLE -> Opcodes.F2D;
                default -> throw new IllegalArgumentException("Unsupported conversion: " + from + " -> " + to);
            };
            case Type.DOUBLE -> switch (to.getSort()) {
                case Type.INT -> Opcodes.D2I;
                case Type.LONG -> Opcodes.D2L;
                case Type.FLOAT -> Opcodes.D2F;
                default -> throw new IllegalArgumentException("Unsupported conversion: " + from + " -> " + to);
            };
            default -> throw new IllegalArgumentException("Non-numeric input: " + from);
        };
        instructions.add(new InsnNode(opcode));
    }

    private static final class Parser {
        private final String source;
        private final Map<String, AutomaticFormulaExtractor.Variable> variables;
        private final Map<LabelNode, LabelNode> labels;
        private final Map<String, AutomaticFormulaExtractor.NativePower> nativePowers;
        private final boolean wideNumbers;
        private int position;

        private Parser(String source, Map<String, AutomaticFormulaExtractor.Variable> variables,
                       Map<LabelNode, LabelNode> labels, Map<String, AutomaticFormulaExtractor.NativePower> nativePowers,
                       boolean wideNumbers) {
            FormulaSyntaxGuard.validate(source);
            this.source = source;
            this.variables = variables;
            this.labels = labels;
            this.nativePowers = nativePowers;
            this.wideNumbers = wideNumbers;
        }

        private Expression parse() {
            Expression result = sum();
            whitespace();
            if (position != source.length()) throw error("Unexpected token");
            return result;
        }

        private Expression sum() {
            Expression result = product();
            while (true) {
                if (take('+')) result = binary(result, product(), Opcodes.IADD);
                else if (take('-')) result = binary(result, product(), Opcodes.ISUB);
                else return result;
            }
        }

        private Expression product() {
            Expression result = unary();
            while (true) {
                if (take('*')) result = binary(result, unary(), Opcodes.IMUL);
                else if (take('/')) result = binary(result, unary(), Opcodes.IDIV);
                else if (take('%')) result = binary(result, unary(), Opcodes.IREM);
                else return result;
            }
        }

        private Expression unary() {
            if (take('+')) return unary();
            if (take('-')) {
                Expression minimum = minimumIntegerLiteral();
                if (minimum != null) return minimum;
                Expression input = unary();
                return new Expression(input.type(), instructions -> {
                    input.emit().accept(instructions);
                    instructions.add(new InsnNode(input.type().getOpcode(Opcodes.INEG)));
                });
            }
            return atom();
        }

        private Expression minimumIntegerLiteral() {
            whitespace();
            Expression integer = minimumLiteral("2147483648", Type.INT_TYPE, Integer.MIN_VALUE);
            if (integer != null) return integer;
            Expression wide = minimumLiteral("9223372036854775808l", Type.LONG_TYPE, Long.MIN_VALUE);
            return wide != null || !wideNumbers ? wide
                    : minimumLiteral("9223372036854775808", Type.LONG_TYPE, Long.MIN_VALUE);
        }

        private Expression minimumLiteral(String literal, Type type, Number value) {
            if (!source.regionMatches(true, position, literal, 0, literal.length())) return null;
            int end = position + literal.length();
            if (end < source.length() && (Character.isJavaIdentifierPart(source.charAt(end)) || source.charAt(end) == '.'))
                return null;
            position = end;
            return new Expression(type, instructions -> instructions.add(new LdcInsnNode(value)));
        }

        private Expression atom() {
            if (take('(')) {
                Expression result = sum();
                expect(')');
                return result;
            }
            whitespace();
            if (position == source.length()) throw error("Expected expression");
            char first = source.charAt(position);
            if (Character.isDigit(first) || first == '.') return number();
            if (!Character.isJavaIdentifierStart(first)) throw error("Expected number or variable");
            int start = position++;
            while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position))) position++;
            String name = source.substring(start, position);
            if (take('(')) {
                List<Expression> arguments = new ArrayList<>();
                if (!take(')')) {
                    do arguments.add(sum()); while (take(','));
                    expect(')');
                }
                return function(name, arguments);
            }
            var variable = variables.get(name);
            if (variable == null) throw error("Unknown variable: " + name);
            return new Expression(variable.type(), instructions -> {
                if (variable.localSlot() >= 0) {
                    instructions.add(new VarInsnNode(variable.type().getOpcode(Opcodes.ILOAD), variable.localSlot()));
                } else {
                    cloneInto(variable.instructions(), instructions, labels);
                }
            });
        }

        private Expression function(String name, List<Expression> arguments) {
            var nativePower = nativePowers.get(name);
            if (nativePower != null) {
                if (arguments.size() != 1) throw error(name + " expects 1 argument");
                Expression input = arguments.get(0);
                return new Expression(Type.FLOAT_TYPE, instructions -> {
                    input.emit().accept(instructions);
                    convert(instructions, input.type(), Type.FLOAT_TYPE);
                    cloneInto(nativePower.receiver(), instructions, labels);
                    cloneInto(nativePower.caster(), instructions, labels);
                    instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "com/example/spell_compiler/formula/FormulaRuntimeHooks",
                            nativePower.source() == AutomaticFormulaExtractor.PowerSource.SPELL_POWER
                                    ? "applyPower" : "applyEntityPower",
                            "(FLjava/lang/Object;Ljava/lang/Object;)F", false));
                });
            }
            int arity = switch (name) {
                case "min", "max" -> 2;
                case "clamp" -> 3;
                case "int", "long", "float", "double", "sqrt", "abs", "floor", "ceil", "round",
                        "mth_floor", "mth_ceil", "softcap" -> 1;
                default -> throw error("Unsupported function: " + name);
            };
            if (arguments.size() != arity) throw error(name + " expects " + arity + " arguments, got " + arguments.size());
            Type common = arguments.get(0).type();
            for (int index = 1; index < arguments.size(); index++) common = promote(common, arguments.get(index).type());
            Type target = switch (name) {
                case "int" -> Type.INT_TYPE;
                case "long" -> Type.LONG_TYPE;
                case "float" -> Type.FLOAT_TYPE;
                case "double" -> Type.DOUBLE_TYPE;
                default -> null;
            };
            if (target != null) {
                Expression input = arguments.get(0);
                return new Expression(target, instructions -> {
                    input.emit().accept(instructions);
                    convert(instructions, input.type(), target);
                });
            }
            if (name.equals("round") && (common.equals(Type.INT_TYPE) || common.equals(Type.LONG_TYPE))) {
                return arguments.get(0);
            }
            Type inputType = switch (name) {
                case "sqrt", "floor", "ceil", "softcap" -> Type.DOUBLE_TYPE;
                case "mth_floor", "mth_ceil" -> common.equals(Type.FLOAT_TYPE) ? Type.FLOAT_TYPE : Type.DOUBLE_TYPE;
                default -> common;
            };
            Type returnType = switch (name) {
                case "round" -> inputType.equals(Type.FLOAT_TYPE) ? Type.INT_TYPE : Type.LONG_TYPE;
                case "mth_floor", "mth_ceil" -> Type.INT_TYPE;
                default -> inputType;
            };
            String owner = name.equals("softcap") ? "io/redspace/ironsspellbooks/api/util/Utils"
                    : name.equals("clamp") || name.startsWith("mth_")
                    ? FormulaFunctions.class.getName().replace('.', '/') : "java/lang/Math";
            String methodName = name.equals("softcap") ? "softCapFormula" : name;
            Type[] parameterTypes = new Type[arity];
            java.util.Arrays.fill(parameterTypes, inputType);
            String descriptor = Type.getMethodDescriptor(returnType, parameterTypes);
            return new Expression(returnType, instructions -> {
                for (Expression argument : arguments) {
                    argument.emit().accept(instructions);
                    convert(instructions, argument.type(), inputType);
                }
                instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, methodName, descriptor, false));
            });
        }

        private Expression number() {
            int start = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
            boolean decimal = false;
            if (position < source.length() && source.charAt(position) == '.') {
                decimal = true;
                position++;
                while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
            }
            if (position < source.length() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (position < source.length() && (source.charAt(position) == '+' || source.charAt(position) == '-')) position++;
                while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
            }
            String text = source.substring(start, position);
            char suffix = position < source.length() ? Character.toLowerCase(source.charAt(position)) : '\0';
            if (suffix == 'f' || suffix == 'd' || suffix == 'l') position++;
            Type type;
            Number value;
            try {
                if (suffix == 'f') {
                    type = Type.FLOAT_TYPE;
                    value = Float.parseFloat(text);
                } else if (suffix == 'd' || decimal) {
                    if (suffix == 'l') throw error("Decimal long literal");
                    type = Type.DOUBLE_TYPE;
                    value = Double.parseDouble(text);
                } else if (suffix == 'l') {
                    type = Type.LONG_TYPE;
                    value = Long.parseLong(text);
                } else {
                    try {
                        type = Type.INT_TYPE;
                        value = Integer.parseInt(text);
                    } catch (NumberFormatException outsideInteger) {
                        if (!wideNumbers) throw outsideInteger;
                        type = Type.LONG_TYPE;
                        value = Long.parseLong(text);
                    }
                }
            } catch (NumberFormatException failure) {
                throw error("Invalid number: " + text);
            }
            if ((value instanceof Float && !Float.isFinite(value.floatValue()))
                    || (value instanceof Double && !Double.isFinite(value.doubleValue()))) {
                throw error("Non-finite literal");
            }
            Number constant = value;
            return new Expression(type, instructions -> instructions.add(new LdcInsnNode(constant)));
        }

        private Expression binary(Expression left, Expression right, int operation) {
            Type type = promote(left.type(), right.type());
            return new Expression(type, instructions -> {
                left.emit().accept(instructions);
                convert(instructions, left.type(), type);
                right.emit().accept(instructions);
                convert(instructions, right.type(), type);
                instructions.add(new InsnNode(type.getOpcode(operation)));
            });
        }

        private boolean take(char token) {
            whitespace();
            if (position < source.length() && source.charAt(position) == token) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char token) {
            if (!take(token)) throw error("Expected '" + token + "'");
        }

        private void whitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + position);
        }
    }
}
