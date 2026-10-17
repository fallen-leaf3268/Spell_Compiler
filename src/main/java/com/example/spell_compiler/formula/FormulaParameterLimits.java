package com.example.spell_compiler.formula;

import com.google.gson.JsonElement;

import java.util.Map;
import java.util.Set;

final class FormulaParameterLimits {
    private FormulaParameterLimits() {}

    static void validate(SpellFormulaCatalog.Spell spell, Map<String, JsonElement> advanced,
                         Map<String, JsonElement> parameters) {
        boolean levelChanged = advanced.containsKey("max_level");
        JsonElement maximumValue = advanced.getOrDefault("max_level", spell.advanced().get("max_level"));
        int maximum = maximumValue == null ? 1 : maximumValue.getAsInt();
        try {
            if (related(parameters, levelChanged, "baseManaCost", "manaCostPerLevel", "mana_cost_multiplier")
                    && spell.parameters().has("baseManaCost") && spell.parameters().has("manaCostPerLevel")) {
                int base = value(spell, parameters, "baseManaCost").getAsInt();
                int step = value(spell, parameters, "manaCostPerLevel").getAsInt();
                double multiplier = value(spell, parameters, "mana_cost_multiplier").getAsDouble();
                manaCost(base, step, 1, multiplier);
                manaCost(base, step, maximum, multiplier);
            }
            if (related(parameters, levelChanged, "baseSpellPower", "spellPowerPerLevel", "power_multiplier")
                    && spell.parameters().has("baseSpellPower") && spell.parameters().has("spellPowerPerLevel")) {
                long base = value(spell, parameters, "baseSpellPower").getAsLong();
                long step = value(spell, parameters, "spellPowerPerLevel").getAsLong();
                float multiplier = value(spell, parameters, "power_multiplier").getAsFloat();
                FormulaFunctions.requireFinite((float) ((double) linearPower(base, step, 1) * multiplier));
                FormulaFunctions.requireFinite((float) ((double) linearPower(base, step, maximum) * multiplier));
            }
            if (parameters.containsKey("cooldown_in_seconds"))
                FormulaFunctions.toIntExact(value(spell, parameters, "cooldown_in_seconds").getAsDouble() * 20);
            if (related(parameters, levelChanged, "castTime")) FormulaCastSafety.validate(spell, parameters, maximum);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("法术参数组合超出原生数值范围：" + spell.id(), failure);
        }
    }

    static boolean related(Map<String, JsonElement> parameters, boolean levelChanged, String... names) {
        return levelChanged || parameters.keySet().stream().anyMatch(Set.of(names)::contains);
    }

    static int linear(int base, int step, int level) {
        return Math.addExact(base, Math.multiplyExact(step, Math.subtractExact(level, 1)));
    }

    static long linearPower(long base, long step, int level) {
        return Math.addExact(base, Math.multiplyExact(step, (long) level - 1L));
    }

    static int manaCost(int base, int step, int level, double multiplier) {
        int result = FormulaFunctions.toIntExact((double) linear(base, step, level) * multiplier);
        if (result < 0) throw new ArithmeticException("法力消耗不能为负数");
        return result;
    }

    private static JsonElement value(SpellFormulaCatalog.Spell spell, Map<String, JsonElement> parameters, String name) {
        return parameters.getOrDefault(name, spell.parameters().get(name));
    }
}
