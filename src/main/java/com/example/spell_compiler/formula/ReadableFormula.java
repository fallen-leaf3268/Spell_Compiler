package com.example.spell_compiler.formula;

import org.objectweb.asm.Type;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ReadableFormula {
    public enum Strategy { SOURCE_MAPPED, NATURAL_DSL }

    public record Resolution(String expression, Strategy strategy) {}

    private ReadableFormula() {}

    public static String display(String strict) {
        return readable(project(markProjection(new Parser(strict).parse())), 0, false, null);
    }

    public static Resolution resolve(String strict, String edited, Map<String, Type> variableTypes) {
        Node source = markProjection(new Parser(strict).parse());
        Node requested = new Parser(edited).parse();
        if (equivalent(source, requested) || equivalent(visible(source), requested)
                || equivalent(stripNativePower(source), requested))
            return new Resolution(strict, Strategy.SOURCE_MAPPED);
        ProjectedRequest request = projectRequest(finalProjection(source), requested);
        FinalProjection projection = request.projection();
        requested = request.input();
        Node calculation = projection.input();
        Node mapped = rewrite(calculation, requested, variableTypes, null);
        if (mapped != null)
            return new Resolution(exact(restoreFinal(projection, mapped, variableTypes)), Strategy.SOURCE_MAPPED);
        Restored restored = restoreWhole(calculation, requested, variableTypes);
        List<Call> powers = new ArrayList<>();
        collectNativePowers(calculation, powers);
        if (!powers.isEmpty()) {
            Restored nativePowers = restoreNativePowers(powers, restored.node(), variableTypes);
            restored = new Restored(nativePowers.node(), restored.found() || nativePowers.found());
        }
        return restored.found() || !projection.conversions().isEmpty()
                ? new Resolution(exact(restoreFinal(projection, restored.node(), variableTypes)), Strategy.SOURCE_MAPPED)
                : new Resolution(edited, Strategy.NATURAL_DSL);
    }

    private sealed interface Node permits Literal, Variable, Binary, Unary, Call {}
    private record Literal(String token, Type type, boolean projected) implements Node {
        private Literal(String token) {
            this(token, literalType(token), false);
        }
    }
    private record Variable(String name) implements Node {}
    private record Binary(String operator, Node left, Node right) implements Node {}
    private record Unary(String operator, Node input) implements Node {}
    private record Call(String name, List<Node> arguments, boolean hiddenProjection) implements Node {
        private Call(String name, List<Node> arguments) {
            this(name, arguments, false);
        }
    }
    private record Restored(Node node, boolean found) {}
    private record FinalProjection(Node input, List<Call> conversions) {}
    private record ProjectedRequest(FinalProjection projection, Node input) {}

    private static boolean hidden(Call call) {
        if (call.hiddenProjection()) return true;
        if (nativePowerMarker(call.name())) return true;
        if (call.name().equals("float") || call.name().equals("double")) return true;
        if (!call.name().equals("int") && !call.name().equals("long")) return false;
        BigDecimal value = number(call.arguments().get(0));
        if (value == null || value.stripTrailingZeros().scale() > 0) return false;
        BigDecimal minimum = BigDecimal.valueOf(call.name().equals("int") ? Integer.MIN_VALUE : Long.MIN_VALUE);
        BigDecimal maximum = BigDecimal.valueOf(call.name().equals("int") ? Integer.MAX_VALUE : Long.MAX_VALUE);
        return value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    private static Node markProjection(Node source) {
        if (source instanceof Call call && (call.name().equals("int") || call.name().equals("round")))
            return new Call(call.name(), List.of(markFinalInput(call.arguments().get(0))), true);
        if (source instanceof Binary binary && (binary.operator().equals("+") || binary.operator().equals("-"))) {
            Node left = binary.left();
            Node right = binary.right();
            if (integerOffset(right) && left instanceof Call call && call.name().equals("int")) left = markProjection(left);
            if (integerOffset(left) && right instanceof Call call && call.name().equals("int")) right = markProjection(right);
            return new Binary(binary.operator(), left, right);
        }
        return source;
    }

    private static Node markFinalInput(Node source) {
        if (source instanceof Call call && (call.name().equals("float") || call.name().equals("double")))
            return new Call(call.name(), List.of(markFinalInput(call.arguments().get(0))), call.hiddenProjection());
        return markProjection(source);
    }

    private static boolean integerOffset(Node source) {
        Node projected = project(source);
        BigDecimal value = number(projected);
        Type type = constantResultType(projected);
        return value != null && value.stripTrailingZeros().scale() <= 0
                && (Type.INT_TYPE.equals(type) || Type.LONG_TYPE.equals(type));
    }

    private static FinalProjection finalProjection(Node source) {
        List<Call> conversions = new ArrayList<>();
        Node input = source;
        if (source instanceof Call call && call.hiddenProjection()) {
            while (input instanceof Call conversion && (conversion.hiddenProjection()
                    || conversion.name().equals("float") || conversion.name().equals("double"))) {
                conversions.add(conversion);
                input = conversion.arguments().get(0);
            }
        }
        return new FinalProjection(input, List.copyOf(conversions));
    }

    private static ProjectedRequest projectRequest(FinalProjection projection, Node requested) {
        List<Call> conversions = new ArrayList<>();
        Node input = requested;
        for (Call conversion : projection.conversions()) {
            Call restored = conversion;
            if (conversion.name().equals("round")) {
                Node argument = input instanceof Call explicit && explicit.name().equals("round")
                        ? explicit.arguments().get(0) : input;
                if (argument instanceof Call precision && precision(precision))
                    restored = new Call(conversion.name(), List.of(argument), conversion.hiddenProjection());
            }
            if (input instanceof Call explicit && (explicit.name().equals(conversion.name())
                    || precision(explicit) && precision(conversion))) {
                if (precision(conversion))
                    restored = new Call(explicit.name(), conversion.arguments(), conversion.hiddenProjection());
                input = explicit.arguments().get(0);
            }
            conversions.add(restored);
        }
        return new ProjectedRequest(new FinalProjection(projection.input(), List.copyOf(conversions)), input);
    }

    private static boolean precision(Call call) {
        return call.name().equals("float") || call.name().equals("double");
    }

    private static Node restoreFinal(FinalProjection projection, Node calculation, Map<String, Type> types) {
        Node result = calculation;
        for (int index = projection.conversions().size() - 1; index >= 0; index--) {
            Call conversion = projection.conversions().get(index);
            if (conversion.name().equals("round")) {
                Type originalType = expressionType(conversion.arguments().get(0), types);
                if (originalType != null && !originalType.equals(expressionType(result, types)))
                    result = new Call(originalType.getClassName(), List.of(result));
            }
            result = new Call(conversion.name(), List.of(result), conversion.hiddenProjection());
        }
        return result;
    }

    private static Type expressionType(Node node, Map<String, Type> types) {
        if (node instanceof Literal literal) return literal.type();
        if (node instanceof Variable variable) return types.get(variable.name());
        if (node instanceof Unary unary) return expressionType(unary.input(), types);
        if (node instanceof Binary binary)
            return promotedType(expressionType(binary.left(), types), expressionType(binary.right(), types));
        Call call = (Call) node;
        Type cast = castType(call.name(), null);
        if (cast != null) return cast;
        if (call.name().equals("sqrt") || call.name().equals("floor") || call.name().equals("ceil")
                || call.name().equals("softcap")) return Type.DOUBLE_TYPE;
        if (call.name().equals("mth_floor") || call.name().equals("mth_ceil")) return Type.INT_TYPE;
        Type common = expressionType(call.arguments().get(0), types);
        for (int index = 1; index < call.arguments().size(); index++)
            common = promotedType(common, expressionType(call.arguments().get(index), types));
        if (call.name().equals("round")) return Type.FLOAT_TYPE.equals(common) ? Type.INT_TYPE : Type.LONG_TYPE;
        return common;
    }

    private static Type promotedType(Type first, Type second) {
        if (first == null || second == null) return null;
        if (first.equals(Type.DOUBLE_TYPE) || second.equals(Type.DOUBLE_TYPE)) return Type.DOUBLE_TYPE;
        if (first.equals(Type.FLOAT_TYPE) || second.equals(Type.FLOAT_TYPE)) return Type.FLOAT_TYPE;
        if (first.equals(Type.LONG_TYPE) || second.equals(Type.LONG_TYPE)) return Type.LONG_TYPE;
        return Type.INT_TYPE;
    }

    private static boolean nativePowerMarker(String name) {
        return name.matches("__native_power_[0-9]+");
    }

    private static Node stripNativePower(Node source) {
        if (source instanceof Binary binary)
            return new Binary(binary.operator(), stripNativePower(binary.left()), stripNativePower(binary.right()));
        if (source instanceof Unary unary) return new Unary(unary.operator(), stripNativePower(unary.input()));
        if (source instanceof Call call) {
            if (nativePowerMarker(call.name())) return stripNativePower(call.arguments().get(0));
            return new Call(call.name(), call.arguments().stream().map(ReadableFormula::stripNativePower).toList(),
                    call.hiddenProjection());
        }
        return source;
    }

    private static Node project(Node source) {
        if (source instanceof Literal literal) return new Literal(shortLiteral(literal.token()), literal.type(), true);
        if (source instanceof Variable) return source;
        if (source instanceof Unary unary) {
            Node child = project(unary.input());
            if (unary.operator().equals("-") && floatingConstant(unary.input())
                    && BigDecimal.ZERO.equals(number(child)) && child instanceof Literal literal)
                child = new Literal("0.0", literal.type(), literal.projected());
            return new Unary(unary.operator(), child);
        }
        if (source instanceof Binary binary)
            return new Binary(binary.operator(), project(binary.left()), project(binary.right()));
        Call call = (Call) source;
        if (hidden(call)) return projectedConstant(project(call.arguments().get(0)), castType(call.name(), null));
        return new Call(call.name(), call.arguments().stream().map(ReadableFormula::project).toList());
    }

    private static Type castType(String name, Type fallback) {
        return switch (name) {
            case "int" -> Type.INT_TYPE;
            case "long" -> Type.LONG_TYPE;
            case "float" -> Type.FLOAT_TYPE;
            case "double" -> Type.DOUBLE_TYPE;
            default -> fallback;
        };
    }

    private static Node projectedConstant(Node node, Type type) {
        if (type == null) return node;
        if (node instanceof Literal literal) return new Literal(literal.token(), type, true);
        if (node instanceof Unary unary && number(node) != null)
            return new Unary(unary.operator(), projectedConstant(unary.input(), type));
        return node;
    }

    private static Node visible(Node source) {
        Node projected = project(source);
        List<Literal> literals = new ArrayList<>();
        collectLiterals(projected, literals);
        return new Parser(readable(projected, 0, false, null), literals).parse();
    }

    private static void collectLiterals(Node node, List<Literal> literals) {
        if (node instanceof Literal literal) literals.add(literal);
        else if (node instanceof Binary binary) {
            collectLiterals(binary.left(), literals);
            collectLiterals(binary.right(), literals);
        } else if (node instanceof Unary unary) collectLiterals(unary.input(), literals);
        else if (node instanceof Call call) call.arguments().forEach(argument -> collectLiterals(argument, literals));
    }

    private static Type constantResultType(Node node) {
        if (node instanceof Literal literal) return literal.type();
        if (node instanceof Unary unary) return constantResultType(unary.input());
        if (node instanceof Call call && call.arguments().size() == 1
                && constantResultType(call.arguments().get(0)) != null)
            return castType(call.name(), null);
        return null;
    }

    private static boolean isAtom(Node node) {
        return node instanceof Literal || node instanceof Variable || node instanceof Call
                || node instanceof Unary unary && isAtom(unary.input());
    }

    private static void collectAtoms(Node node, List<Node> atoms) {
        if (isAtom(node)) atoms.add(node);
        else if (node instanceof Binary binary) {
            collectAtoms(binary.left(), atoms);
            collectAtoms(binary.right(), atoms);
        } else if (node instanceof Unary unary) collectAtoms(unary.input(), atoms);
        else if (node instanceof Call call) call.arguments().forEach(argument -> collectAtoms(argument, atoms));
    }

    private static boolean sameGrouping(Node first, Node second) {
        if (isAtom(first) && isAtom(second)) return true;
        if (first instanceof Binary a && second instanceof Binary b)
            return a.operator().equals(b.operator()) && sameGrouping(a.left(), b.left()) && sameGrouping(a.right(), b.right());
        if (first instanceof Unary a && second instanceof Unary b)
            return a.operator().equals(b.operator()) && sameGrouping(a.input(), b.input());
        if (first instanceof Call a && second instanceof Call b) {
            if (!a.name().equals(b.name()) || a.arguments().size() != b.arguments().size()) return false;
            for (int index = 0; index < a.arguments().size(); index++)
                if (!sameGrouping(a.arguments().get(index), b.arguments().get(index))) return false;
            return true;
        }
        return false;
    }

    private static Type literalType(String token) {
        char suffix = Character.toLowerCase(token.charAt(token.length() - 1));
        if (suffix == 'l') return Type.LONG_TYPE;
        if (suffix == 'f') return Type.FLOAT_TYPE;
        if (suffix == 'd' || token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0)
            return Type.DOUBLE_TYPE;
        return Type.INT_TYPE;
    }

    private static boolean compatibleLiteralType(Node source, Node edited) {
        Literal first = literal(source);
        Literal second = literal(edited);
        if (first == null || second == null) return false;
        if (first.type().equals(second.type())) return true;
        if (first.projected() && first.token().equalsIgnoreCase(second.token())) return true;
        char suffix = second.token().charAt(second.token().length() - 1);
        return first.projected() && (first.type().equals(Type.FLOAT_TYPE) || first.type().equals(Type.DOUBLE_TYPE))
                && "lLfFdD".indexOf(suffix) < 0;
    }

    private static Literal literal(Node node) {
        while (node instanceof Unary unary) node = unary.input();
        return node instanceof Literal literal ? literal : null;
    }

    private static String shortLiteral(String token) {
        char last = token.charAt(token.length() - 1);
        if (last == 'l' || last == 'L') return token.substring(0, token.length() - 1) + 'L';
        String value = numericToken(token);
        BigDecimal decimal = new BigDecimal(value);
        BigDecimal compact = decimal.stripTrailingZeros();
        String result = compact.precision() - compact.scale() > 18 || compact.scale() > 8
                ? compact.toString() : compact.toPlainString();
        if (result.indexOf('.') < 0 && result.indexOf('E') < 0 && result.indexOf('e') < 0
                && compact.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0)
            result += ".0";
        return result;
    }

    private static String numericToken(String token) {
        char last = token.charAt(token.length() - 1);
        return "lLfFdD".indexOf(last) >= 0 ? token.substring(0, token.length() - 1) : token;
    }

    private static BigDecimal number(Node node) {
        if (node instanceof Literal literal) return new BigDecimal(numericToken(literal.token()));
        if (node instanceof Unary unary && unary.operator().equals("-")) {
            BigDecimal value = number(unary.input());
            return value == null ? null : value.negate();
        }
        return null;
    }

    private static boolean floatingConstant(Node node) {
        if (node instanceof Literal literal) {
            String token = literal.token();
            return token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0
                    || token.endsWith("f") || token.endsWith("F") || token.endsWith("d") || token.endsWith("D");
        }
        if (node instanceof Call call && (call.name().equals("float") || call.name().equals("double"))) return true;
        if (node instanceof Unary unary) return floatingConstant(unary.input());
        return false;
    }

    private static int precedence(Node node) {
        if (node instanceof Binary binary) return binary.operator().equals("+") || binary.operator().equals("-") ? 1 : 2;
        return node instanceof Unary ? 3 : 4;
    }

    private static String readable(Node node, int parent, boolean right, String parentOperator) {
        String text;
        if (node instanceof Literal literal) text = literal.token();
        else if (node instanceof Variable variable) text = variable.name();
        else if (node instanceof Unary unary)
            text = unary.operator() + readable(unary.input(), 3, true, "unary");
        else if (node instanceof Call call)
            text = call.name() + '(' + String.join(", ", call.arguments().stream()
                    .map(argument -> readable(argument, 0, false, null)).toList()) + ')';
        else {
            Binary binary = (Binary) node;
            int own = precedence(node);
            text = readable(binary.left(), own, false, binary.operator()) + ' ' + binary.operator() + ' '
                    + readable(binary.right(), own, true, binary.operator());
        }
        int own = precedence(node);
        boolean matchingAssociative = node instanceof Binary binary
                && binary.operator().equals(parentOperator) && (parentOperator.equals("+") || parentOperator.equals("*"));
        boolean parentheses = own < parent || (right && own == parent && node instanceof Binary && !matchingAssociative);
        return parentheses ? '(' + text + ')' : text;
    }

    private static String exact(Node node) {
        if (node instanceof Literal literal) return literal.token();
        if (node instanceof Variable variable) return variable.name();
        if (node instanceof Unary unary) return '(' + unary.operator() + exact(unary.input()) + ')';
        if (node instanceof Binary binary)
            return '(' + exact(binary.left()) + ' ' + binary.operator() + ' ' + exact(binary.right()) + ')';
        Call call = (Call) node;
        return call.name() + '(' + String.join(", ", call.arguments().stream().map(ReadableFormula::exact).toList()) + ')';
    }

    private static boolean equivalent(Node first, Node second) {
        if (first instanceof Literal && second instanceof Literal)
            return number(first).compareTo(number(second)) == 0 && compatibleLiteralType(first, second);
        if (first instanceof Variable a && second instanceof Variable b) return a.name().equals(b.name());
        if (first instanceof Unary a && second instanceof Unary b)
            return a.operator().equals(b.operator()) && equivalent(a.input(), b.input());
        if (first instanceof Call a && second instanceof Call b) {
            if (!a.name().equals(b.name()) || a.arguments().size() != b.arguments().size()) return false;
            for (int index = 0; index < a.arguments().size(); index++)
                if (!equivalent(a.arguments().get(index), b.arguments().get(index))) return false;
            return true;
        }
        if (first instanceof Binary a && second instanceof Binary b) {
            if (!a.operator().equals(b.operator())) return false;
            return equivalent(a.left(), b.left()) && equivalent(a.right(), b.right());
        }
        return false;
    }

    private static Node rewrite(Node source, Node edited, Map<String, Type> types, Type constantType) {
        Node projected = projectedConstant(visible(source), constantType);
        if (equivalent(projected, edited)) return source;
        if (source instanceof Call call && hidden(call)) {
            Type kind = castType(call.name(), constantType);
            Node requested = edited instanceof Call explicit && call.name().equals(explicit.name())
                    && explicit.arguments().size() == 1 ? explicit.arguments().get(0) : edited;
            Node child = rewrite(call.arguments().get(0), requested, types, kind);
            if (child == null && call.hiddenProjection()) {
                ProjectedRequest request = projectRequest(finalProjection(call), requested);
                Node replacement = request.input();
                List<Call> powers = new ArrayList<>();
                collectNativePowers(call.arguments().get(0), powers);
                if (!powers.isEmpty()) replacement = restoreNativePowers(powers, replacement, types).node();
                Type originalType = expressionType(call.arguments().get(0), types);
                if (Type.FLOAT_TYPE.equals(originalType) && !Type.FLOAT_TYPE.equals(expressionType(replacement, types))
                        && request.projection().conversions().stream().noneMatch(ReadableFormula::precision)
                        && !(replacement instanceof Call explicit && precision(explicit)))
                    replacement = new Call("float", List.of(replacement));
                return restoreFinal(request.projection(), replacement, types);
            }
            return child == null ? null : new Call(call.name(), List.of(child), call.hiddenProjection());
        }
        BigDecimal sourceNumber = number(source);
        BigDecimal editedNumber = number(edited);
        if (sourceNumber != null && editedNumber != null) {
            boolean integral = constantType != null
                    ? constantType.equals(Type.INT_TYPE) || constantType.equals(Type.LONG_TYPE)
                    : !floatingConstant(source);
            if (!compatibleLiteralType(projected, edited)
                    || integral && editedNumber.stripTrailingZeros().scale() > 0) return null;
            Type sourceType = literal(projected).type();
            if (constantType == null && (sourceType.equals(Type.FLOAT_TYPE) || sourceType.equals(Type.DOUBLE_TYPE))
                    && !sourceType.equals(literal(edited).type()))
                return new Call(sourceType.getClassName(), List.of(edited));
            return edited;
        }
        if (source instanceof Variable a && edited instanceof Variable b) {
            Type first = types.get(a.name());
            return first != null && first.equals(types.get(b.name())) ? edited : null;
        }
        if (source instanceof Variable variable) {
            Type type = types.get(variable.name());
            if (editedNumber == null)
                return type != null && type.equals(constantResultType(edited)) ? edited : null;
            if (Type.INT_TYPE.equals(type) || Type.LONG_TYPE.equals(type)) {
                Type requestedType = literal(edited).type();
                if (!requestedType.equals(Type.INT_TYPE) && !requestedType.equals(Type.LONG_TYPE)
                        || type.equals(Type.INT_TYPE) && !requestedType.equals(Type.INT_TYPE)) return null;
                BigDecimal minimum = BigDecimal.valueOf(type.equals(Type.INT_TYPE) ? Integer.MIN_VALUE : Long.MIN_VALUE);
                BigDecimal maximum = BigDecimal.valueOf(type.equals(Type.INT_TYPE) ? Integer.MAX_VALUE : Long.MAX_VALUE);
                if (editedNumber.stripTrailingZeros().scale() > 0
                        || editedNumber.compareTo(minimum) < 0 || editedNumber.compareTo(maximum) > 0) return null;
                return type.equals(requestedType) ? edited : new Call(type.getClassName(), List.of(edited));
            }
            if (Type.FLOAT_TYPE.equals(type) && Float.isFinite(editedNumber.floatValue())
                    || Type.DOUBLE_TYPE.equals(type) && Double.isFinite(editedNumber.doubleValue()))
                return new Call(type.getClassName(), List.of(edited));
            return null;
        }
        if (source instanceof Unary a && edited instanceof Unary b && a.operator().equals(b.operator())) {
            Node child = rewrite(a.input(), b.input(), types, constantType);
            return child == null ? null : new Unary(a.operator(), child);
        }
        if (source instanceof Binary a && edited instanceof Binary b && a.operator().equals(b.operator())) {
            Node offset = rewriteProjectedOffset(a, b, types);
            if (offset != null) return offset;
            if (!sameGrouping(projected, edited)) return null;
            List<Node> desired = new ArrayList<>();
            collectAtoms(edited, desired);
            int[] cursor = {0};
            Node result = rebuildVisible(source, desired, cursor, types, null);
            return cursor[0] == desired.size() ? result : null;
        }
        if (source instanceof Call a && edited instanceof Call b && a.name().equals(b.name())
                && a.arguments().size() == b.arguments().size()) {
            List<Node> arguments = new ArrayList<>();
            for (int index = 0; index < a.arguments().size(); index++) {
                Node argument = rewrite(a.arguments().get(index), b.arguments().get(index), types, null);
                if (argument == null) return null;
                arguments.add(argument);
            }
            return new Call(a.name(), arguments, a.hiddenProjection());
        }
        return null;
    }

    private static Node rewriteProjectedOffset(Binary source, Binary edited, Map<String, Type> types) {
        if (!source.operator().equals("+") && !source.operator().equals("-")) return null;
        boolean leftGrowth = source.left() instanceof Call call && call.hiddenProjection() && call.name().equals("int")
                && integerOffset(source.right());
        boolean rightGrowth = source.right() instanceof Call call && call.hiddenProjection() && call.name().equals("int")
                && integerOffset(source.left());
        if (!leftGrowth && !rightGrowth) return null;
        if (containsEquivalent(visible(source), edited)) return null;
        if (leftGrowth && containsVariable(edited.right()) || rightGrowth && containsVariable(edited.left())) return null;
        Node left = rewrite(source.left(), edited.left(), types, null);
        Node right = rewrite(source.right(), edited.right(), types, null);
        if (left == null && !leftGrowth) left = edited.left();
        if (right == null && !rightGrowth) right = edited.right();
        return left == null || right == null ? null : new Binary(source.operator(), left, right);
    }

    private static boolean containsEquivalent(Node source, Node requested) {
        if (equivalent(source, requested)) return true;
        if (requested instanceof Binary binary)
            return containsEquivalent(source, binary.left()) || containsEquivalent(source, binary.right());
        if (requested instanceof Unary unary) return containsEquivalent(source, unary.input());
        if (requested instanceof Call call)
            return call.arguments().stream().anyMatch(argument -> containsEquivalent(source, argument));
        return false;
    }

    private static Node rebuildVisible(Node source, List<Node> desired, int[] cursor,
                                       Map<String, Type> types, Type constantType) {
        if (isAtom(project(source))) {
            if (cursor[0] >= desired.size()) return null;
            return rewrite(source, desired.get(cursor[0]++), types, constantType);
        }
        if (source instanceof Call call && hidden(call)) {
            Type kind = castType(call.name(), constantType);
            Node child = rebuildVisible(call.arguments().get(0), desired, cursor, types, kind);
            return child == null ? null : new Call(call.name(), List.of(child), call.hiddenProjection());
        }
        if (source instanceof Binary binary) {
            Node left = rebuildVisible(binary.left(), desired, cursor, types, null);
            Node right = rebuildVisible(binary.right(), desired, cursor, types, null);
            return left == null || right == null ? null : new Binary(binary.operator(), left, right);
        }
        if (source instanceof Unary unary) {
            Node child = rebuildVisible(unary.input(), desired, cursor, types, constantType);
            return child == null ? null : new Unary(unary.operator(), child);
        }
        Call call = (Call) source;
        List<Node> arguments = new ArrayList<>();
        for (Node argument : call.arguments()) {
            Node child = rebuildVisible(argument, desired, cursor, types, null);
            if (child == null) return null;
            arguments.add(child);
        }
        return new Call(call.name(), arguments, call.hiddenProjection());
    }

    private static Restored restoreWhole(Node source, Node edited, Map<String, Type> types) {
        if (containsVariable(source) && !containsVariable(edited)) return new Restored(edited, false);
        if (equivalent(visible(source), edited) || equivalent(stripNativePower(source), edited))
            return new Restored(source, true);
        if (containsVariable(source)) {
            Node mapped = rewrite(source, edited, types, null);
            if (mapped != null) return new Restored(mapped, true);
        }
        if (edited instanceof Binary binary) {
            Restored left = restoreWhole(source, binary.left(), types);
            Restored right = restoreWhole(source, binary.right(), types);
            return new Restored(new Binary(binary.operator(), left.node(), right.node()), left.found() || right.found());
        }
        if (edited instanceof Unary unary) {
            Restored child = restoreWhole(source, unary.input(), types);
            return new Restored(new Unary(unary.operator(), child.node()), child.found());
        }
        if (edited instanceof Call call) {
            if (castType(call.name(), null) != null && source instanceof Call sourceCall
                    && castType(sourceCall.name(), null) != null && hidden(sourceCall))
                return new Restored(edited, false);
            List<Node> arguments = new ArrayList<>();
            boolean found = false;
            for (Node argument : call.arguments()) {
                Restored child = restoreWhole(source, argument, types);
                arguments.add(child.node());
                found |= child.found();
            }
            return new Restored(new Call(call.name(), arguments), found);
        }
        return new Restored(edited, false);
    }

    private static boolean containsVariable(Node node) {
        if (node instanceof Variable) return true;
        if (node instanceof Binary binary) return containsVariable(binary.left()) || containsVariable(binary.right());
        if (node instanceof Unary unary) return containsVariable(unary.input());
        if (node instanceof Call call) return call.arguments().stream().anyMatch(ReadableFormula::containsVariable);
        return false;
    }

    private static void collectNativePowers(Node source, List<Call> powers) {
        if (source instanceof Binary binary) {
            collectNativePowers(binary.left(), powers);
            collectNativePowers(binary.right(), powers);
        } else if (source instanceof Unary unary) collectNativePowers(unary.input(), powers);
        else if (source instanceof Call call) {
            if (nativePowerMarker(call.name())) {
                if (powers.stream().noneMatch(power -> power.name().equals(call.name()))) powers.add(call);
            } else call.arguments().forEach(argument -> collectNativePowers(argument, powers));
        }
    }

    private static Restored restoreNativePowers(List<Call> powers, Node edited, Map<String, Type> types) {
        if (edited instanceof Call call && nativePowerMarker(call.name())) return new Restored(edited, false);
        if (!containsVariable(edited)) return new Restored(edited, false);
        Node normalized = new Parser(readable(edited, 0, false, null)).parse();
        Call matched = null;
        Node matchedInput = null;
        for (Call power : powers) {
            Node input = rewrite(power.arguments().get(0), edited, types, null);
            if (input == null) {
                if (rewrite(power.arguments().get(0), normalized, types, null) != null) input = edited;
                else input = restorePowerBasis(power.arguments().get(0), edited, types);
            }
            if (input == null) continue;
            if (matched != null) return new Restored(edited, false);
            matched = power;
            matchedInput = input;
        }
        if (matched != null) return new Restored(new Call(matched.name(), List.of(matchedInput)), true);
        if (edited instanceof Binary binary) {
            Restored left = restoreNativePowers(powers, binary.left(), types);
            Restored right = restoreNativePowers(powers, binary.right(), types);
            return new Restored(new Binary(binary.operator(), left.node(), right.node()), left.found() || right.found());
        }
        if (edited instanceof Unary unary) {
            Restored child = restoreNativePowers(powers, unary.input(), types);
            return new Restored(new Unary(unary.operator(), child.node()), child.found());
        }
        if (edited instanceof Call call) {
            List<Node> arguments = new ArrayList<>();
            boolean found = false;
            for (Node argument : call.arguments()) {
                Restored child = restoreNativePowers(powers, argument, types);
                arguments.add(child.node());
                found |= child.found();
            }
            return new Restored(new Call(call.name(), arguments), found);
        }
        return new Restored(edited, false);
    }

    private static Node restorePowerBasis(Node source, Node edited, Map<String, Type> types) {
        if (containsNativePower(edited)) return null;
        List<Node> sourceFactors = new ArrayList<>();
        collectProduct(visible(source), sourceFactors);
        List<Node> attributes = new ArrayList<>();
        List<Node> bases = new ArrayList<>();
        for (Node factor : sourceFactors) {
            if (factor instanceof Variable variable && (Type.FLOAT_TYPE.equals(types.get(variable.name()))
                    || Type.DOUBLE_TYPE.equals(types.get(variable.name())))) attributes.add(factor);
            else bases.add(factor);
        }
        if (attributes.size() < 2 || bases.size() > 1) return null;
        if (!bases.isEmpty()) {
            Node basis = stripProductTail(edited, attributes);
            if (basis != null) {
                Node template = replacePowerBasis(source, bases.get(0), basis);
                if (equivalent(visible(template), edited)) return template;
            }
        }
        List<Node> factors = new ArrayList<>();
        collectProduct(edited, factors);
        for (Node attribute : attributes) {
            int found = -1;
            for (int index = 0; index < factors.size(); index++) {
                if (equivalent(attribute, factors.get(index))) {
                    found = index;
                    break;
                }
            }
            if (found < 0) return null;
            factors.remove(found);
        }
        if (factors.size() > 1) return null;
        Node basis = factors.isEmpty() ? new Literal("1") : factors.get(0);
        if (!bases.isEmpty()) {
            Node template = replacePowerBasis(source, bases.get(0), basis);
            if (equivalent(visible(template), edited)) return template;
        }
        return new Call("float", List.of(edited));
    }

    private static Node stripProductTail(Node node, List<Node> factors) {
        for (int index = factors.size() - 1; index >= 0; index--) {
            if (!(node instanceof Binary binary) || !binary.operator().equals("*")
                    || !equivalent(binary.right(), factors.get(index))) return null;
            node = binary.left();
        }
        return node;
    }

    private static void collectProduct(Node node, List<Node> factors) {
        if (node instanceof Binary binary && binary.operator().equals("*")) {
            collectProduct(binary.left(), factors);
            collectProduct(binary.right(), factors);
        } else factors.add(node);
    }

    private static Node replacePowerBasis(Node source, Node original, Node replacement) {
        if (source instanceof Call call && hidden(call))
            return new Call(call.name(), List.of(replacePowerBasis(call.arguments().get(0), original, replacement)),
                    call.hiddenProjection());
        if (equivalent(visible(source), original)) return replacement;
        if (source instanceof Binary binary)
            return new Binary(binary.operator(), replacePowerBasis(binary.left(), original, replacement),
                    replacePowerBasis(binary.right(), original, replacement));
        if (source instanceof Unary unary)
            return new Unary(unary.operator(), replacePowerBasis(unary.input(), original, replacement));
        return source;
    }

    private static boolean containsNativePower(Node node) {
        if (node instanceof Binary binary) return containsNativePower(binary.left()) || containsNativePower(binary.right());
        if (node instanceof Unary unary) return containsNativePower(unary.input());
        if (node instanceof Call call)
            return nativePowerMarker(call.name()) || call.arguments().stream().anyMatch(ReadableFormula::containsNativePower);
        return false;
    }

    private static final class Parser {
        private final String source;
        private final List<Literal> originalLiterals;
        private int literalIndex;
        private int position;

        private Parser(String source) {
            this(source, List.of());
        }

        private Parser(String source, List<Literal> originalLiterals) {
            FormulaSyntaxGuard.validate(source);
            this.source = source;
            this.originalLiterals = originalLiterals;
        }

        private Node parse() {
            Node result = sum();
            whitespace();
            if (position != source.length()) throw error("Unexpected token");
            return result;
        }

        private Node sum() {
            Node result = product();
            while (true) {
                if (take('+')) result = new Binary("+", result, product());
                else if (take('-')) result = new Binary("-", result, product());
                else return result;
            }
        }

        private Node product() {
            Node result = unary();
            while (true) {
                if (take('*')) result = new Binary("*", result, unary());
                else if (take('/')) result = new Binary("/", result, unary());
                else if (take('%')) result = new Binary("%", result, unary());
                else return result;
            }
        }

        private Node unary() {
            if (take('+')) return unary();
            if (take('-')) return new Unary("-", unary());
            if (take('(')) {
                Node result = sum();
                require(')');
                return result;
            }
            whitespace();
            if (position < source.length() && (Character.isDigit(source.charAt(position)) || source.charAt(position) == '.'))
                return literal();
            int start = position;
            while (position < source.length() && (Character.isLetterOrDigit(source.charAt(position)) || source.charAt(position) == '_')) position++;
            if (start == position) throw error("Expected expression");
            String name = source.substring(start, position);
            if (!take('(')) return new Variable(name);
            List<Node> arguments = new ArrayList<>();
            if (!take(')')) {
                do arguments.add(sum()); while (take(','));
                require(')');
            }
            int expected = switch (name) {
                case "min", "max" -> 2;
                case "clamp" -> 3;
                case "int", "long", "float", "double", "sqrt", "abs", "floor", "ceil", "round", "mth_floor", "mth_ceil", "softcap" -> 1;
                default -> {
                    if (!nativePowerMarker(name)) throw error("Unknown function " + name);
                    yield 1;
                }
            };
            if (arguments.size() != expected) throw error("Invalid argument count for " + name);
            return new Call(name, List.copyOf(arguments));
        }

        private Node literal() {
            int start = position;
            while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
            if (position < source.length() && source.charAt(position) == '.') {
                position++;
                while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
            }
            if (position < source.length() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                position++;
                if (position < source.length() && (source.charAt(position) == '+' || source.charAt(position) == '-')) position++;
                while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
            }
            if (position < source.length() && "lLfFdD".indexOf(source.charAt(position)) >= 0) position++;
            String token = source.substring(start, position);
            try {
                new BigDecimal(numericToken(token));
            } catch (NumberFormatException invalid) {
                throw error("Invalid number");
            }
            return literalIndex < originalLiterals.size()
                    ? new Literal(token, originalLiterals.get(literalIndex++).type(), true) : new Literal(token);
        }

        private boolean take(char expected) {
            whitespace();
            if (position < source.length() && source.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!take(expected)) throw error("Expected " + expected);
        }

        private void whitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) position++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at " + position);
        }
    }
}
