package com.example.spell_compiler.formula;

import java.util.Map;

public interface FormulaConsumerCarrier {
    default Map<String, FormulaAttributes.Value> apoth$getAttributeValues() { return Map.of(); }
    default void apoth$setAttributeValues(Map<String, FormulaAttributes.Value> values) {}
    Map<String, Float> apoth$getFormulaValues();
    void apoth$setFormulaValues(Map<String, Float> values);
}
