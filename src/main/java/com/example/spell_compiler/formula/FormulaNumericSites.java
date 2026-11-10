package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FormulaNumericSites {
    public static final String VALUE_DESCRIPTOR = "(DIDDDLjava/lang/Object;Ljava/lang/Object;)D";
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaNumericHooks";
    private static final String SPELLS = "io.redspace.ironsspellbooks.spells.";
    private static final String ON_CAST = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/spells/CastSource;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V";
    private static final String CAST_TICK = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V";
    private static final String PRE_CAST = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/magic/MagicData;)Z";

    public enum ValueType {
        INT("I", "overrideInt"), FLOAT("F", "overrideFloat"), DOUBLE("D", "overrideDouble");

        private final String descriptor;
        private final String hookMethod;

        ValueType(String descriptor, String hookMethod) {
            this.descriptor = descriptor;
            this.hookMethod = hookMethod;
        }

        public String descriptor() { return descriptor; }
        public String hookMethod() { return hookMethod; }

        public static ValueType of(Number value) {
            if (value instanceof Integer) return INT;
            if (value instanceof Float) return FLOAT;
            if (value instanceof Double) return DOUBLE;
            throw new IllegalArgumentException("不支持的原生数值类型：" + value);
        }
    }

    public record Definition(String spellClassName, String alias, Number defaultValue, ValueType valueType,
                             double minimum, double maximum) {
        public Definition {
            Objects.requireNonNull(spellClassName);
            Objects.requireNonNull(alias);
            Objects.requireNonNull(defaultValue);
            Objects.requireNonNull(valueType);
            spellClassName = spellClassName.replace('/', '.');
            double numeric = defaultValue.doubleValue();
            if (spellClassName.isBlank() || !alias.matches("[a-z][a-z0-9_]*")
                    || !Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum
                    || !Double.isFinite(numeric) || numeric < minimum || numeric > maximum
                    || valueType == ValueType.INT && numeric != defaultValue.intValue()
                    || valueType == ValueType.FLOAT && !Float.isFinite(defaultValue.floatValue()))
                throw new IllegalArgumentException("无效的原生数值入口定义：" + spellClassName + '#' + alias);
        }

        public String helperMethod() { return "numeric$" + alias; }
        public String descriptor() { return VALUE_DESCRIPTOR; }
        public String key() { return helperMethod() + descriptor(); }
        public String declaringClass() { return FormulaNumericValues.class.getName(); }
        public String dispatchKey() { return FormulaRuntimeDispatcher.key(spellClassName, key()); }
    }

    public record Rule(String owner, String method, String descriptor, Number constant, int expectedCount,
                       int occurrence, int casterSlot, int levelSlot, int spellSlot, Definition definition) {
        public Rule {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(method);
            Objects.requireNonNull(descriptor);
            Objects.requireNonNull(constant);
            Objects.requireNonNull(definition);
            owner = owner.replace('/', '.');
            Type.getMethodType(descriptor);
            ValueType.of(constant);
            if (owner.isBlank() || method.isBlank() || expectedCount < 1 || occurrence < -1
                    || occurrence >= expectedCount || casterSlot < -1 || levelSlot < -1 || spellSlot < -1
                    || !Double.isFinite(constant.doubleValue()))
                throw new IllegalArgumentException("无效的原生数值锚点：" + owner + '#' + method + descriptor);
        }
    }

    private record Selected(MethodNode method, Rule rule, List<AbstractInsnNode> points) {}

    private static final List<Rule> RULES = rules();
    private static final FormulaNumericIndex INDEX = new FormulaNumericIndex(RULES.stream().map(Rule::definition).toList());

    private static List<Rule> rules() {
        List<Rule> result = new ArrayList<>(List.of(
            cast("blood.RaiseDeadSpell", "summon_duration_ticks", 12000),
            cast("ender.SummonSwordsSpell", "summon_duration_ticks", 12000),
            cast("evocation.SummonHorseSpell", "summon_duration_ticks", 12000),
            cast("evocation.SummonVexSpell", "summon_duration_ticks", 12000),
            cast("ender.EvasionSpell", "duration_ticks", 1200),
            cast("blood.BloodStepSpell", "invisibility_duration_ticks", 100),
            new Rule(SPELLS + "holy.FortifySpell", "lambda$onCast$0",
                    "(Lnet/minecraft/world/entity/LivingEntity;ILnet/minecraft/world/entity/LivingEntity;)V",
                    2400, 1, -1, 1, 2, 0,
                    definition("holy.FortifySpell", "duration_ticks", 2400))));
        result.add(cast("fire.FlamingBarrageSpell", "recast_window_ticks", 120));
        result.add(cast("fire.RaiseHellSpell", "recast_window_ticks", 80));
        result.add(cast("fire.WallOfFireSpell", "recast_window_ticks", 40));
        result.add(cast("eldritch.PocketDimensionSpell", "portal_duration_ticks", 1200));
        result.add(cast("fire.BurningDashSpell", "dash_duration_ticks", 15));
        result.add(cast("ender.ShadowSlashSpell", "fall_immunity_ticks", 20));
        result.add(cast("fire.ScorchSpell", "field_duration_ticks", 200));
        result.add(cast("ice.FrostStepSpell", "clone_duration_ticks", 100));
        result.add(cast("nature.EarthquakeSpell", "duration_ticks", 240));
        result.add(source("ice.FrostStepSpell", "taunt_radius", 10f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("evocation.SlowSpell", "max_targets", 5, "lambda$onCast$0",
                "(Ljava/util/concurrent/atomic/AtomicInteger;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;FILnet/minecraft/world/entity/LivingEntity;)V",
                1, -1, 3, 5, 0));
        result.add(source("holy.HasteSpell", "max_targets", 5, "lambda$onCast$0",
                "(Ljava/util/concurrent/atomic/AtomicInteger;Lnet/minecraft/world/entity/LivingEntity;FLnet/minecraft/world/entity/LivingEntity;ILnet/minecraft/world/entity/LivingEntity;)V",
                1, -1, 4, 5, 0));
        for (String spell : List.of("evocation.SlowSpell", "holy.HasteSpell"))
            result.add(source(spell, "max_targets", 5, "getUniqueInfo",
                    "(ILnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;", 1, -1, 2, 1, 0));
        var slowRadius = definition("evocation.SlowSpell", "radius", 3f);
        result.add(new Rule(slowRadius.spellClassName(), "checkPreCastConditions", PRE_CAST,
                3f, 1, -1, 3, 2, 0, slowRadius));
        result.add(new Rule(slowRadius.spellClassName(), "onCast", ON_CAST,
                3f, 1, -1, 3, 2, 0, slowRadius));
        result.add(source("evocation.SlowSpell", "target_range", 32, "checkPreCastConditions", PRE_CAST, 1, -1, 3, 2, 0));
        result.add(source("holy.HasteSpell", "target_range", 32, "checkPreCastConditions", PRE_CAST, 1, -1, 3, 2, 0));
        result.add(source("blood.AcupunctureSpell", "target_range", 32, "checkPreCastConditions", PRE_CAST, 1, -1, 3, 2, 0));
        result.add(source("blood.DevourSpell", "target_range", 9, "checkPreCastConditions", PRE_CAST, 1, -1, 3, 2, 0));
        result.add(source("blood.BloodNeedlesSpell", "target_range", 32f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("blood.SacrificeSpell", "target_range", 25f, "checkPreCastConditions", PRE_CAST, 1, -1, 3, 2, 0));
        result.add(source("ender.CounterspellSpell", "target_range", 80d, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("holy.WispSpell", "target_range", 48, "checkPreCastConditions", PRE_CAST, 1, -1, 3, 2, 0));
        result.add(source("holy.BlessingOfLifeSpell", "target_range", 64, "checkPreCastConditions", PRE_CAST, 1, -1, 3, 2, 0));
        result.add(source("fire.ScorchSpell", "target_range", 32f, "checkPreCastConditions", PRE_CAST, 1, -1, 3, 2, 0));
        pairedRange(result, "holy.HealingCircleSpell", 32, 32f);
        pairedRange(result, "ice.BlizzardSpell", 32, 32f);
        pairedRange(result, "nature.EarthquakeSpell", 32, 32f);
        pairedRange(result, "holy.SunbeamSpell", 48, 48f);
        pairedRange(result, "evocation.SpectralHammerSpell", 16d, 16d);
        result.add(source("ender.StarfallSpell", "target_range", 40f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("lightning.LightningBoltSpell", "target_range", 64f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("lightning.LightningBoltSpell", "radius", 4f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("ender.ShadowSlashSpell", "range", 12f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("ender.ShadowSlashSpell", "radius", 2.5f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("fire.FlamingStrikeSpell", "radius", 3.25f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("fire.FlamingStrikeSpell", "forward_distance", 1.9f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("holy.DivineSmiteSpell", "radius", 2.2f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("holy.DivineSmiteSpell", "forward_distance", 1.7f, "onCast", ON_CAST, 1, -1, 3, 2, 0));
        result.add(source("fire.ScorchSpell", "field_damage_multiplier", 0.1f, "onCast", ON_CAST, 2, 1, 3, 2, 0));
        result.add(interval("fire.BlazeStormSpell", "shot_interval_ticks", 5));
        result.add(interval("ender.StarfallSpell", "spawn_interval_ticks", 4));
        result.add(interval("ender.StarfallSpell", "tracking_interval_ticks", 20));
        result.add(source("ender.StarfallSpell", "projectiles_per_wave", 2, "onServerCastTick", CAST_TICK, 1, -1, 3, 2, 0));
        Map<String, Definition> definitions = new LinkedHashMap<>();
        for (Rule rule : result) {
            Definition previous = definitions.putIfAbsent(rule.definition().dispatchKey(), rule.definition());
            if (previous != null && !previous.equals(rule.definition()))
                throw new IllegalArgumentException("原生数值入口别名定义冲突：" + rule.definition().dispatchKey());
        }
        return List.copyOf(result);
    }

    private FormulaNumericSites() {}

    public static List<Definition> definitions(String spellClassName) {
        return INDEX.definitions(spellClassName);
    }

    public static Set<String> targetClasses() {
        return Set.copyOf(RULES.stream().map(Rule::owner).toList());
    }

    public static Definition find(String dispatchKey) {
        return INDEX.find(dispatchKey);
    }

    public static Set<String> requiredRuleOwners(String dispatchKey) {
        return RULES.stream().filter(rule -> rule.definition().dispatchKey().equals(dispatchKey)).map(Rule::owner)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static void validate(FormulaClassRepository repository) {
        for (String owner : targetClasses()) {
            ClassNode source = repository.classNode(owner.replace('.', '/'));
            if (source == null) throw new IllegalArgumentException("原生数值入口类不可用：" + owner);
            select(owner, source, RULES);
        }
    }

    public static void apply(String targetClassName, ClassNode targetClass) {
        apply(targetClassName, targetClass, RULES);
    }

    static void apply(ClassNode targetClass, List<Rule> rules) {
        apply(targetClass.name.replace('/', '.'), targetClass, rules);
    }

    static void apply(String targetClassName, ClassNode targetClass, List<Rule> rules) {
        String owner = targetClassName.replace('/', '.');
        if (rules.stream().noneMatch(rule -> rule.owner().equals(owner))) return;
        if (!owner.equals(targetClass.name.replace('/', '.')))
            throw new IllegalArgumentException("原生数值入口与目标类不符：" + owner + " -> " + targetClass.name);
        List<MethodNode> copies = new ArrayList<>();
        for (MethodNode method : targetClass.methods) {
            MethodNode copy = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc,
                    method.signature, method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
            method.accept(copy);
            copies.add(copy);
        }
        ClassNode candidate = new ClassNode();
        candidate.name = targetClass.name;
        candidate.methods = copies;
        List<Selected> selections = select(owner, candidate, rules);
        for (Selected selected : selections)
            for (AbstractInsnNode point : selected.points()) inject(selected.method(), selected.rule(), point);
        targetClass.methods = copies;
    }

    private static List<Selected> select(String owner, ClassNode source, List<Rule> rules) {
        List<Selected> selected = new ArrayList<>();
        Map<AbstractInsnNode, Rule> used = new IdentityHashMap<>();
        for (Rule rule : rules) {
            if (!rule.owner().equals(owner)) continue;
            List<MethodNode> methods = source.methods.stream()
                    .filter(method -> method.name.equals(rule.method()) && method.desc.equals(rule.descriptor())).toList();
            if (methods.size() != 1) throw mismatch(rule, "method count=" + methods.size());
            MethodNode method = methods.get(0);
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
                throw mismatch(rule, "method has no body");
            validateSlots(method, rule);
            List<AbstractInsnNode> matches = new ArrayList<>();
            for (AbstractInsnNode instruction : method.instructions)
                if (matches(instruction, rule.constant())) matches.add(instruction);
            if (matches.size() != rule.expectedCount()) throw mismatch(rule, "constant count=" + matches.size());
            List<AbstractInsnNode> points = rule.occurrence() < 0 ? List.copyOf(matches)
                    : List.of(matches.get(rule.occurrence()));
            for (AbstractInsnNode point : points)
                if (used.putIfAbsent(point, rule) != null) throw mismatch(rule, "overlapping numeric anchors");
            selected.add(new Selected(method, rule, points));
        }
        return selected;
    }

    private static void validateSlots(MethodNode method, Rule rule) {
        Map<Integer, Type> parameters = new LinkedHashMap<>();
        int slot = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            parameters.put(slot++, Type.getType(Object.class));
            if (method.name.equals("<init>") && (rule.spellSlot() == 0 || rule.casterSlot() == 0))
                throw mismatch(rule, "constructor receiver is not initialized");
        }
        for (Type parameter : Type.getArgumentTypes(method.desc)) {
            parameters.put(slot, parameter);
            slot += parameter.getSize();
        }
        if (rule.levelSlot() >= 0 && !Type.INT_TYPE.equals(parameters.get(rule.levelSlot())))
            throw mismatch(rule, "level slot is not an integer parameter: " + rule.levelSlot());
        for (int reference : new int[]{rule.spellSlot(), rule.casterSlot()}) {
            if (reference < 0) continue;
            Type type = parameters.get(reference);
            if (type == null || type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY)
                throw mismatch(rule, "context slot is not a reference parameter: " + reference);
        }
    }

    private static boolean matches(AbstractInsnNode instruction, Number expected) {
        Number actual = null;
        if (instruction instanceof LdcInsnNode literal && literal.cst instanceof Number number) actual = number;
        else if (instruction instanceof IntInsnNode literal
                && (literal.getOpcode() == Opcodes.BIPUSH || literal.getOpcode() == Opcodes.SIPUSH)) actual = literal.operand;
        else if (instruction.getOpcode() >= Opcodes.ICONST_M1 && instruction.getOpcode() <= Opcodes.ICONST_5)
            actual = instruction.getOpcode() - Opcodes.ICONST_0;
        else if (instruction.getOpcode() >= Opcodes.FCONST_0 && instruction.getOpcode() <= Opcodes.FCONST_2)
            actual = (float) (instruction.getOpcode() - Opcodes.FCONST_0);
        else if (instruction.getOpcode() >= Opcodes.DCONST_0 && instruction.getOpcode() <= Opcodes.DCONST_1)
            actual = (double) (instruction.getOpcode() - Opcodes.DCONST_0);
        if (actual == null || actual.getClass() != expected.getClass()) return false;
        if (actual instanceof Float value)
            return Float.floatToRawIntBits(value) == Float.floatToRawIntBits(expected.floatValue());
        if (actual instanceof Double value)
            return Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(expected.doubleValue());
        return actual.equals(expected);
    }

    private static void inject(MethodNode method, Rule rule, AbstractInsnNode point) {
        ValueType type = ValueType.of(rule.constant());
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(rule.definition().dispatchKey()));
        reference(code, rule.spellSlot());
        code.add(rule.levelSlot() < 0 ? new InsnNode(Opcodes.ICONST_M1) : new VarInsnNode(Opcodes.ILOAD, rule.levelSlot()));
        reference(code, rule.casterSlot());
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, type.hookMethod(),
                '(' + type.descriptor() + "Ljava/lang/String;Ljava/lang/Object;ILjava/lang/Object;)" + type.descriptor(), false));
        method.instructions.insert(point, code);
        method.maxStack += 6;
    }

    private static void reference(InsnList code, int slot) {
        code.add(slot < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, slot));
    }

    private static IllegalArgumentException mismatch(Rule rule, String detail) {
        return new IllegalArgumentException("原生数值常量锚点已变化：" + rule.owner() + '#' + rule.method()
                + rule.descriptor() + " alias=" + rule.definition().alias() + ' ' + detail);
    }

    private static Rule cast(String spell, String alias, int value) {
        return new Rule(SPELLS + spell, "onCast", ON_CAST, value, 1, -1, 3, 2, 0,
                definition(spell, alias, value));
    }

    private static Definition definition(String spell, String alias, int value) {
        return new Definition(SPELLS + spell, alias, value, ValueType.INT, 0,
                defaultMaximum(alias, ValueType.INT));
    }

    private static Definition definition(String spell, String alias, Number value) {
        ValueType type = ValueType.of(value);
        return new Definition(SPELLS + spell, alias, value, type, 0, defaultMaximum(alias, type));
    }

    static double defaultMaximum(String alias, ValueType type) {
        if (alias.equals("max_targets") || alias.startsWith("projectiles_per_")) return 256;
        if (alias.equals("range") || alias.endsWith("_range") || alias.equals("radius")
                || alias.endsWith("_radius") || alias.equals("forward_distance") || alias.equals("forward_offset")) return 1024;
        return switch (type) {
            case INT -> Integer.MAX_VALUE;
            case FLOAT -> Math.sqrt(Float.MAX_VALUE);
            case DOUBLE -> Math.sqrt(Double.MAX_VALUE);
        };
    }

    private static Rule source(String spell, String alias, Number value, String method, String descriptor,
            int count, int occurrence, int caster, int level, int self) {
        return new Rule(SPELLS + spell, method, descriptor, value, count, occurrence, caster, level, self,
                definition(spell, alias, value));
    }

    private static void pairedRange(List<Rule> rules, String spell, Number preCast, Number onCast) {
        Definition definition = definition(spell, "target_range", onCast);
        rules.add(new Rule(SPELLS + spell, "checkPreCastConditions", PRE_CAST, preCast, 1, -1, 3, 2, 0, definition));
        rules.add(new Rule(SPELLS + spell, "onCast", ON_CAST, onCast, 1, -1, 3, 2, 0, definition));
    }

    private static Rule interval(String spell, String alias, int value) {
        Definition definition = new Definition(SPELLS + spell, alias, value, ValueType.INT, 1, Integer.MAX_VALUE);
        return new Rule(SPELLS + spell, "onServerCastTick", CAST_TICK, value, 1, -1, 3, 2, 0, definition);
    }
}
