package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

final class FormulaExtractionModel {
    private FormulaExtractionModel() {}

    sealed interface Node permits Constant, Input, Binary, Negation, Conversion, Function, Predicate, Comparison {
        Type type();
        String text();
    }
    record Constant(Type type, Number value) implements Node {
        public String text() {
            if (type.equals(Type.INT_TYPE) && value.intValue() == Integer.MIN_VALUE)
                return "(-int(2147483647) - int(1))";
            if (type.equals(Type.LONG_TYPE) && value.longValue() == Long.MIN_VALUE)
                return "(-long(9223372036854775807L) - long(1))";
            return type.getClassName() + '(' + value + (type.equals(Type.LONG_TYPE) ? "L" : "") + ')';
        }
    }
    record Input(Type type, String name) implements Node {
        public String text() { return name; }
    }
    record Binary(Type type, Node left, String operator, Node right) implements Node {
        public String text() { return '(' + left.text() + ' ' + operator + ' ' + right.text() + ')'; }
    }
    record Negation(Type type, Node input) implements Node {
        public String text() { return "(-" + input.text() + ')'; }
    }
    record Conversion(Type type, Node input) implements Node {
        public String text() { return type.getClassName() + '(' + input.text() + ')'; }
    }
    record Function(Type type, String name, List<Node> arguments) implements Node {
        public String text() { return name + '(' + String.join(", ", arguments.stream().map(Node::text).toList()) + ')'; }
    }
    record Predicate(Type type, Value subject, String target) implements Node {
        public String text() { throw new Rejected("Reference predicates cannot be exported as arithmetic"); }
    }
    record Comparison(Type type, Node left, Node right, int opcode) implements Node {
        public String text() {
            Node ordered = new Function(Type.INT_TYPE, "if", List.of(
                    new Function(Type.INT_TYPE, "lt", List.of(left, right)), new Constant(Type.INT_TYPE, -1),
                    new Function(Type.INT_TYPE, "if", List.of(new Function(Type.INT_TYPE, "gt", List.of(left, right)),
                            new Constant(Type.INT_TYPE, 1), new Constant(Type.INT_TYPE, 0)))));
            if (opcode == Opcodes.LCMP) return ordered.text();
            Node fallback = new Constant(Type.INT_TYPE, opcode == Opcodes.FCMPL || opcode == Opcodes.DCMPL ? -1 : 1);
            return new Function(Type.INT_TYPE, "if", List.of(new Function(Type.INT_TYPE, "eq", List.of(left, left)),
                    new Function(Type.INT_TYPE, "if", List.of(new Function(Type.INT_TYPE, "eq", List.of(right, right)),
                            ordered, fallback)), fallback)).text();
        }
    }
    record Reference(String kind, String identity, String tag, Value subject) {}
    record Value(Type type, Node node, List<AbstractInsnNode> instructions, Reference reference) {}

    static final class Rejected extends RuntimeException {
        Rejected(String message) { super(message); }
    }
}
