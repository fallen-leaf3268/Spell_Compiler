package com.example.spell_compiler.formula;

import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class FormulaAddonNumericSites {
    private static volatile FormulaNumericIndex DYNAMIC = new FormulaNumericIndex(List.of());
    private static final String SPELLS = "org.xszb.interlace_spellweaves.spell.";
    private static final String ON_CAST = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/spells/CastSource;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V";
    private static final String SERVER_TICK = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V";
    private static final List<FormulaNumericSites.Rule> RULES = List.of(
            cast("ice.SummonRideablePolarBear", "summon_duration_ticks", 12000, 0),
            cast("evocation.SummonSpellClone", "summon_duration_ticks", 6000, 0),
            cast("blood.CrimsonLaceration", "recast_window_ticks", 120, 0),
            cast("fusion.PureArcaneMissileSpell", "recast_window_ticks", 120, 0),
            cast("evocation.TotemRite", "target_range", 32f, 0),
            tick("evocation.BarrageVolley", "shot_interval_ticks", 2, 1),
            tick("evocation.BarrageVolley", "startup_delay_ticks", 40, 0),
            tick("ice.Blizzard", "shot_interval_ticks", 2, 1),
            tick("ice.Blizzard", "projectiles_per_shot", 3, 0),
            rule("ice.Blizzard", "shootHailStone",
                    "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;)V",
                    "explosion_radius", 3f, 0, 2, 0),
            cast("ice.FrostwhirlSlash", "radius", 3.25f, 0),
            cast("ice.FrostwhirlSlash", "forward_offset", 2.9f, 0),
            cast("ice.FrostwhirlSlash", "chilled_duration_ticks", 60, 0),
            rule("ice.ChargeRayOfFrostSpell", "onCast", ON_CAST, "burst_radius", 6f, 0, 2, -1));

    private static final FormulaNumericIndex INDEX = new FormulaNumericIndex(RULES.stream().map(FormulaNumericSites.Rule::definition).toList());

    private FormulaAddonNumericSites() {}

    public static List<FormulaNumericSites.Definition> definitions(String spellClassName) {
        String owner = spellClassName.replace('/', '.');
        return INDEX.definitions(owner);
    }

    public static FormulaNumericSites.Definition find(String dispatchKey) {
        var fixed = INDEX.find(dispatchKey);
        return fixed != null ? fixed : DYNAMIC.find(dispatchKey);
    }

    public static synchronized void registerDynamic(String spellClassName, List<FormulaNumericSites.Definition> definitions) {
        String owner = spellClassName.replace('/', '.');
        if (definitions.stream().anyMatch(definition -> !definition.spellClassName().equals(owner))
                || definitions.stream().map(FormulaNumericSites.Definition::key).distinct().count() != definitions.size())
            throw new IllegalArgumentException("动态属性入口归属或键重复：" + owner);
        var stable = List.copyOf(definitions);
        var owners = new java.util.LinkedHashMap<>(DYNAMIC.owners());
        owners.put(owner, stable);
        DYNAMIC = new FormulaNumericIndex(owners.values().stream().flatMap(List::stream).toList());
    }

    public static Set<String> targetClasses() {
        return RULES.stream().map(FormulaNumericSites.Rule::owner).collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> apply(String className, ClassNode source) {
        return apply(className, source, RULES);
    }

    static Set<String> apply(String className, ClassNode source, List<FormulaNumericSites.Rule> rules) {
        String owner = className.replace('/', '.');
        List<FormulaNumericSites.Rule> selected = rules.stream().filter(value -> value.owner().equals(owner)).toList();
        if (selected.isEmpty()) return Set.of();
        FormulaNumericSites.apply(owner, source, selected);
        return selected.stream().map(value -> value.definition().key()).collect(Collectors.toUnmodifiableSet());
    }

    private static FormulaNumericSites.Rule cast(String spell, String alias, Number value, double minimum) {
        return rule(spell, "onCast", ON_CAST, alias, value, minimum, 1, -1);
    }

    private static FormulaNumericSites.Rule tick(String spell, String alias, Number value, double minimum) {
        return rule(spell, "onServerCastTick", SERVER_TICK, alias, value, minimum, 1, -1);
    }

    private static FormulaNumericSites.Rule rule(String spell, String method, String descriptor, String alias,
                                                Number value, double minimum, int count, int occurrence) {
        FormulaNumericSites.ValueType type = value instanceof Integer ? FormulaNumericSites.ValueType.INT
                : value instanceof Float ? FormulaNumericSites.ValueType.FLOAT : FormulaNumericSites.ValueType.DOUBLE;
        var definition = new FormulaNumericSites.Definition(SPELLS + spell, alias, value, type, minimum,
                FormulaNumericSites.defaultMaximum(alias, type));
        return new FormulaNumericSites.Rule(SPELLS + spell, method, descriptor, value, count, occurrence, 3, 2, 0, definition);
    }
}
