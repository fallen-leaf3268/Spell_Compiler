package com.example.spell_compiler.formula;

final class FormulaSyntaxGuard {
    private static final int MAX_LENGTH = 16384;
    private static final int MAX_TOKENS = 512;
    private static final int MAX_NESTING = 64;
    private static final int MAX_UNARY_CHAIN = 64;

    private FormulaSyntaxGuard() {}

    static void validate(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Empty formula");
        if (source.length() > MAX_LENGTH) throw limit("length", MAX_LENGTH, 0);
        int tokens = 0;
        int nesting = 0;
        int unary = 0;
        boolean operand = true;
        for (int position = 0; position < source.length();) {
            char current = source.charAt(position);
            if (Character.isWhitespace(current)) {
                position++;
                continue;
            }
            if (++tokens > MAX_TOKENS) throw limit("complexity", MAX_TOKENS, position);
            if (Character.isJavaIdentifierStart(current)) {
                do position++; while (position < source.length() && Character.isJavaIdentifierPart(source.charAt(position)));
                operand = false;
                unary = 0;
                continue;
            }
            if (Character.isDigit(current) || current == '.') {
                position++;
                while (position < source.length()) {
                    char next = source.charAt(position);
                    if (Character.isLetterOrDigit(next) || next == '.') position++;
                    else if ((next == '+' || next == '-')
                            && (source.charAt(position - 1) == 'e' || source.charAt(position - 1) == 'E')) position++;
                    else break;
                }
                operand = false;
                unary = 0;
                continue;
            }
            if (current == '(') {
                if (++nesting > MAX_NESTING) throw limit("nesting", MAX_NESTING, position);
                operand = true;
                unary = 0;
            } else if (current == ')') {
                nesting--;
                operand = false;
                unary = 0;
            } else if ((current == '+' || current == '-') && operand) {
                if (++unary > MAX_UNARY_CHAIN) throw limit("unary depth", MAX_UNARY_CHAIN, position);
            } else {
                operand = true;
                unary = 0;
            }
            position++;
        }
    }

    private static IllegalArgumentException limit(String kind, int maximum, int position) {
        return new IllegalArgumentException("Formula " + kind + " limit " + maximum + " exceeded at position " + position);
    }
}
