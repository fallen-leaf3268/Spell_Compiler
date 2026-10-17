package com.example.spell_compiler.formula;

import java.util.Map;

final class FormulaNames {
    private static final Map<String, String> INPUTS = Map.ofEntries(
            Map.entry("amplifier", "effect_level"),
            Map.entry("amplifier_for_level", "effect_level"),
            Map.entry("rend_amplifier", "effect_level"),
            Map.entry("slowness_amplifier", "effect_level"),
            Map.entry("spellpower", "spell_power"),
            Map.entry("school_spellpower", "school_spell_power"),
            Map.entry("mana_multiplier", "mana_cost_multiplier"),
            Map.entry("native_cast_time", "castTime"),
            Map.entry("summon_damage", "summon_damage_multiplier"));
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("aoedamage", "aoe_damage"),
            Map.entry("shield_hp", "shield_health"),
            Map.entry("hp_bonus", "health_bonus"),
            Map.entry("health_bonus", "health_bonus_ratio"),
            Map.entry("summon_health_bonus", "summon_health_damage_bonus"),
            Map.entry("duration", "duration_ticks"),
            Map.entry("effect_duration", "effect_duration_ticks"),
            Map.entry("portal_duration", "portal_duration_ticks"),
            Map.entry("rend_duration", "rend_duration_ticks"),
            Map.entry("chain_duration", "chain_duration_ticks"),
            Map.entry("recast_duration", "recast_window_ticks"),
            Map.entry("freeze_time", "freeze_ticks"));

    private FormulaNames() {}

    static String input(String name) {
        return INPUTS.getOrDefault(name, name);
    }

    static String alias(String name) {
        if (name.equals("amplifier") || name.equals("amplifier_for_level") || name.endsWith("_amplifier"))
            return "effect_level";
        return ALIASES.getOrDefault(name, name);
    }

    static boolean duration(String alias) {
        return alias.equals("duration_ticks") || alias.endsWith("_duration_ticks")
                || alias.endsWith("_window_ticks") || alias.equals("freeze_ticks");
    }
}
