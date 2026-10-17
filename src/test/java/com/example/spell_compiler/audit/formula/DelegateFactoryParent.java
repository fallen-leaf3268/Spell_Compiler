package com.example.spell_compiler.audit.formula;

public class DelegateFactoryParent {
    protected int base = 13;
    protected float growth = 4;
    protected double bias = 0.125;
    protected long counter = 9;

    protected int protectedHelper(int level) {
        return base + level * 4;
    }

    public void setBase(int value) {
        base = value;
    }
}
