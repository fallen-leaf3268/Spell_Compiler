package com.example.spell_compiler.formula;

public final class FormulaContextUnavailableException extends RuntimeException {
    private static final FormulaContextUnavailableException INSTANCE = new FormulaContextUnavailableException();

    private FormulaContextUnavailableException() {
        super("当前调用没有可验证的原施法等级", null, false, false);
    }

    public static FormulaContextUnavailableException missing() { return INSTANCE; }
}
