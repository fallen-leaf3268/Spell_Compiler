package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class FormulaSites {
    public record Definition(String spellClassName, String alias, String helperMethod, String descriptor,
                             double minimum, double maximum) {
        public String key() { return helperMethod + descriptor; }
        public String declaringClass() { return FormulaSiteValues.class.getName(); }
        public String dispatchKey() { return FormulaRuntimeDispatcher.key(spellClassName, key()); }
    }

    private enum Kind { INT, FLOAT, DOUBLE, INT_AS_DOUBLE, SQUARED_INT, CREATED_AOE, STORM_DAMAGE, SUMMON_BONUS, SUMMON_DURATION }

    private record Rule(String owner, Set<String> methods, String descriptor, Kind kind,
                        Definition definition, Number constant, int matches, int actorSlot) {}

    private record Selected(MethodNode method, Rule rule, List<AbstractInsnNode> points) {}

    private static final String SPELLS = "io.redspace.ironsspellbooks.spells.";
    private static final String EFFECT = "io.redspace.ironsspellbooks.effect.ThunderstormEffect";
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaSiteHooks";
    private static final String HEALING_AOE = "io/redspace/ironsspellbooks/entity/spells/HealingAoe";
    private static final String AOE = "io/redspace/ironsspellbooks/entity/spells/AoeEntity";
    private static final String ON_CAST = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/spells/CastSource;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V";
    private static final String PRE_CAST = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/magic/MagicData;)Z";
    private static final String UNIQUE_INFO = "(ILnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;";
    private static final String EFFECT_TICK = "(Lnet/minecraft/world/entity/LivingEntity;I)V";
    private static final Definition HEALING = definition("holy.HealingCircleSpell", "healing_interval_ticks", "healingInterval", "()I", 1, Integer.MAX_VALUE);
    private static final Definition SUMMON = definition("ice.SummonPolarBearSpell", "summon_duration_ticks", "summonDuration", "(Ljava/lang/Object;)I", 0, Integer.MAX_VALUE);
    private static final Definition FIRE = definition("fire.FireboltSpell", "fire_duration_ticks", "fireTicks", "()I", 0, Integer.MAX_VALUE);
    private static final Definition STORM = definition("lightning.ThunderstormSpell", "effect_damage", "thunderstormDamage",
            "(IFDFLjava/lang/Object;Ljava/lang/Object;)F", 0, Float.MAX_VALUE);
    private static final Definition INTERVAL = definition("lightning.ThunderstormSpell", "strike_interval_ticks", "strikeInterval", "()I", 1, Integer.MAX_VALUE);
    private static final Definition HORIZONTAL = definition("lightning.ThunderstormSpell", "horizontal_range", "horizontalRange", "()I", 0, 46340);
    private static final Definition VERTICAL = definition("lightning.ThunderstormSpell", "vertical_range", "verticalRange", "()D", 0, Double.MAX_VALUE);
    private static final Definition HASTE = definition("holy.HasteSpell", "radius", "hasteRadius", "()F", 0, Math.sqrt(Float.MAX_VALUE));
    private static final Definition BONUS = definition("blood.SacrificeSpell", "summon_health_bonus", "sacrificeHealthBonus", "(F)F", 0, Float.MAX_VALUE);
    private static final List<Definition> DEFINITIONS = List.of(HEALING, SUMMON, FIRE, STORM, INTERVAL, HORIZONTAL, VERTICAL, HASTE, BONUS);
    private static final List<Rule> RULES = List.of(
            rule(HEALING, "onCast", ON_CAST, Kind.CREATED_AOE, null, 1, 3),
            rule(SUMMON, "onCast", ON_CAST, Kind.SUMMON_DURATION, 12000, 1, 3),
            rule(FIRE, "getDamageSource", "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)Lio/redspace/ironsspellbooks/damage/SpellDamageSource;", Kind.INT, 60, 1, 2),
            new Rule(EFFECT, Set.of("getDamageFromAmplifier"), "(ILnet/minecraft/world/entity/LivingEntity;)F", Kind.STORM_DAMAGE, STORM, null, 1, 1),
            new Rule(EFFECT, Set.of("isDurationEffectTick", "m_6584_"), "(II)Z", Kind.INT, INTERVAL, 40, 1, -1),
            new Rule(EFFECT, Set.of("applyEffectTick", "m_6742_"), EFFECT_TICK, Kind.INT_AS_DOUBLE, HORIZONTAL, 20d, 2, 1),
            new Rule(EFFECT, Set.of("applyEffectTick", "m_6742_"), EFFECT_TICK, Kind.SQUARED_INT, HORIZONTAL, 400, 1, 1),
            new Rule(EFFECT, Set.of("applyEffectTick", "m_6742_"), EFFECT_TICK, Kind.DOUBLE, VERTICAL, 12d, 1, 1),
            rule(HORIZONTAL, "getUniqueInfo", UNIQUE_INFO, Kind.INT, 20, 1, 2),
            rule(HASTE, "checkPreCastConditions", PRE_CAST, Kind.FLOAT, 3f, 1, 3),
            rule(HASTE, "onCast", ON_CAST, Kind.FLOAT, 3f, 1, 3),
            rule(BONUS, "onCast", ON_CAST, Kind.SUMMON_BONUS, null, 1, 3));

    private FormulaSites() {}

    public static List<Definition> definitions(String spellClassName) {
        return DEFINITIONS.stream().filter(value -> value.spellClassName().equals(spellClassName)).toList();
    }

    public static Set<String> targetClasses() {
        return Set.copyOf(RULES.stream().map(Rule::owner).toList());
    }

    public static Definition find(String dispatchKey) {
        return DEFINITIONS.stream().filter(value -> value.dispatchKey().equals(dispatchKey)).findFirst().orElse(null);
    }

    public static Number validateValue(String helperKey, Number value) {
        var definition = DEFINITIONS.stream().filter(entry -> entry.key().equals(helperKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown numeric site: " + helperKey));
        double numeric = value.doubleValue();
        if (!Double.isFinite(numeric) || numeric < definition.minimum() || numeric > definition.maximum())
            throw new ArithmeticException("Invalid numeric site value: " + definition.alias() + '=' + value
                    + " allowed=" + definition.minimum() + ".." + definition.maximum());
        Type result = Type.getReturnType(definition.descriptor());
        if (result.equals(Type.INT_TYPE) && numeric != value.intValue()
                || result.equals(Type.FLOAT_TYPE) && !Float.isFinite(value.floatValue()))
            throw new ArithmeticException("Numeric site result has an invalid type: " + definition.alias() + '=' + value);
        return value;
    }

    public static void validate(FormulaClassRepository repository) {
        for (String name : targetClasses()) {
            ClassNode source = repository.classNode(name.replace('.', '/'));
            if (source == null) throw new IllegalArgumentException("Numeric site owner is unavailable: " + name);
            select(name, source);
        }
        ClassNode aoe = repository.classNode(AOE);
        if (aoe == null) throw new IllegalArgumentException("Native AOE constructor is unavailable");
        MethodNode constructor = aoe.methods.stream().filter(method -> method.name.equals("<init>")
                && method.desc.equals("(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Native AOE constructor differs"));
        long initializations = executable(constructor).stream().filter(node -> numeric(node, 10)
                && next(node) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                && field.owner.equals(AOE) && field.name.equals("reapplicationDelay") && field.desc.equals("I")).count();
        if (initializations != 1) throw new IllegalArgumentException("Native healing interval constructor differs");
    }

    public static void apply(String targetClassName, ClassNode targetClass) {
        if (!targetClasses().contains(targetClassName)) return;
        List<MethodNode> updated = new ArrayList<>();
        for (MethodNode method : targetClass.methods) {
            MethodNode copy = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc,
                    method.signature, method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
            method.accept(copy);
            updated.add(copy);
        }
        ClassNode candidate = new ClassNode();
        candidate.name = targetClass.name;
        candidate.methods = updated;
        List<Selected> selected = select(targetClassName, candidate);
        for (Selected selection : selected) {
            for (AbstractInsnNode point : selection.points()) inject(selection.method(), selection.rule(), point);
        }
        targetClass.methods = updated;
    }

    private static List<Selected> select(String owner, ClassNode source) {
        List<Selected> selected = new ArrayList<>();
        for (Rule rule : RULES) {
            if (!rule.owner().equals(owner)) continue;
            List<MethodNode> methods = source.methods.stream().filter(method -> rule.methods().contains(method.name)
                    && method.desc.equals(rule.descriptor())).toList();
            if (methods.size() != 1) throw mismatch(rule, "method count=" + methods.size());
            MethodNode method = methods.get(0);
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) throw mismatch(rule, "method has no body");
            List<AbstractInsnNode> points = executable(method).stream().filter(node -> matches(rule, method, node)).toList();
            if (points.size() != rule.matches()) throw mismatch(rule, "anchor count=" + points.size());
            selected.add(new Selected(method, rule, points));
        }
        return selected;
    }

    private static boolean matches(Rule rule, MethodNode method, AbstractInsnNode node) {
        if (rule.kind() == Kind.CREATED_AOE) {
            return node instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals(HEALING_AOE) && call.name.equals("<init>")
                    && call.desc.equals("(Lnet/minecraft/world/level/Level;)V")
                    && next(node) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE;
        }
        if (rule.kind() == Kind.STORM_DAMAGE) {
            return node.getOpcode() == Opcodes.FRETURN && opcode(previous(node)) == Opcodes.FADD
                    && numeric(previous(previous(node)), 7f)
                    && opcode(previous(previous(previous(node)))) == Opcodes.FMUL
                    && executable(method).stream().anyMatch(instruction -> instruction instanceof MethodInsnNode call
                    && call.name.equals("getEntityPowerMultiplier")
                    && call.desc.equals("(Lnet/minecraft/world/entity/LivingEntity;)F"));
        }
        if (rule.kind() == Kind.SUMMON_BONUS) {
            AbstractInsnNode factor = previous(node);
            AbstractInsnNode health = previous(factor);
            return node.getOpcode() == Opcodes.FMUL && numeric(factor, 0.5f)
                    && health instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/world/entity/LivingEntity")
                    && Set.of("getHealth", "m_21223_").contains(call.name) && call.desc.equals("()F")
                    && previous(health) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                    && opcode(next(node)) == Opcodes.FADD;
        }
        if (!numeric(node, rule.constant())) return false;
        if (rule.definition() == FIRE)
            return next(node) instanceof MethodInsnNode call && call.name.equals("setFireTicks")
                    && call.owner.equals("io/redspace/ironsspellbooks/damage/SpellDamageSource")
                    && call.desc.equals("(I)Lio/redspace/ironsspellbooks/damage/SpellDamageSource;");
        if (rule.definition() == INTERVAL) return opcode(next(node)) == Opcodes.IREM;
        if (rule.definition() == SUMMON || rule.kind() == Kind.SQUARED_INT)
            return next(node) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE;
        if (rule.definition() == HASTE)
            return next(node) instanceof VarInsnNode store && store.getOpcode() == Opcodes.FSTORE;
        if (rule.methods().contains("getUniqueInfo"))
            return next(node) instanceof MethodInsnNode call && call.owner.equals("java/lang/Integer")
                    && call.name.equals("valueOf") && call.desc.equals("(I)Ljava/lang/Integer;");
        return executable(method).stream().filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals("net/minecraft/world/phys/AABB") && call.desc.equals("(DDD)Lnet/minecraft/world/phys/AABB;")).count() == 1;
    }

    private static void inject(MethodNode method, Rule rule, AbstractInsnNode point) {
        InsnList code = new InsnList();
        String key = rule.definition().dispatchKey();
        if (rule.kind() == Kind.CREATED_AOE) {
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new LdcInsnNode(key));
            actor(code, rule.actorSlot());
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "initializeHealingInterval",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)V", false));
            method.instructions.insert(point, code);
        } else if (rule.kind() == Kind.STORM_DAMAGE) {
            code.add(new LdcInsnNode(key));
            code.add(new VarInsnNode(Opcodes.ILOAD, 0));
            actor(code, rule.actorSlot());
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "overrideThunderstormDamage",
                    "(FLjava/lang/String;ILjava/lang/Object;)F", false));
            method.instructions.insertBefore(point, code);
        } else if (rule.kind() == Kind.SUMMON_BONUS) {
            AbstractInsnNode health = previous(previous(point));
            int healthSlot = method.maxLocals++;
            InsnList capture = new InsnList();
            capture.add(new InsnNode(Opcodes.DUP));
            capture.add(new VarInsnNode(Opcodes.FSTORE, healthSlot));
            method.instructions.insert(health, capture);
            code.add(new LdcInsnNode(key));
            actor(code, rule.actorSlot());
            code.add(new VarInsnNode(Opcodes.FLOAD, healthSlot));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "overrideFloat",
                    "(FLjava/lang/String;Ljava/lang/Object;F)F", false));
            method.instructions.insert(point, code);
        } else {
            code.add(new LdcInsnNode(key));
            actor(code, rule.actorSlot());
            String name = switch (rule.kind()) {
                case INT -> "overrideInt";
                case FLOAT -> "overrideFloat";
                case DOUBLE -> "overrideDouble";
                case INT_AS_DOUBLE -> "overrideIntAsDouble";
                case SQUARED_INT -> "overrideSquaredInt";
                case SUMMON_DURATION -> "overrideSummonDuration";
                default -> throw new IllegalArgumentException("Unexpected site kind " + rule.kind());
            };
            String type = rule.kind() == Kind.FLOAT ? "F"
                    : rule.kind() == Kind.DOUBLE || rule.kind() == Kind.INT_AS_DOUBLE ? "D" : "I";
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, name,
                    '(' + type + "Ljava/lang/String;Ljava/lang/Object;)" + type, false));
            method.instructions.insert(point, code);
        }
        method.maxStack += 5;
    }

    private static void actor(InsnList code, int slot) {
        code.add(slot < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, slot));
    }

    private static Definition definition(String spell, String alias, String method, String descriptor, double minimum, double maximum) {
        return new Definition(SPELLS + spell, FormulaNames.alias(alias), method, descriptor, minimum, maximum);
    }

    private static Rule rule(Definition definition, String method, String descriptor, Kind kind, Number value, int matches, int actor) {
        return new Rule(definition.spellClassName(), Set.of(method), descriptor, kind, definition, value, matches, actor);
    }

    private static IllegalArgumentException mismatch(Rule rule, String detail) {
        return new IllegalArgumentException("Native numeric site differs: " + rule.owner() + '#' + rule.methods()
                + rule.descriptor() + " alias=" + rule.definition().alias() + ' ' + detail);
    }

    private static List<AbstractInsnNode> executable(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do instruction = instruction.getNext(); while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do instruction = instruction.getPrevious(); while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static boolean numeric(AbstractInsnNode instruction, Number expected) {
        if (instruction == null || expected == null) return false;
        Object actual;
        if (instruction instanceof LdcInsnNode constant) actual = constant.cst;
        else if (instruction instanceof IntInsnNode constant
                && (constant.getOpcode() == Opcodes.BIPUSH || constant.getOpcode() == Opcodes.SIPUSH)) actual = constant.operand;
        else if (instruction.getOpcode() >= Opcodes.ICONST_M1 && instruction.getOpcode() <= Opcodes.ICONST_5)
            actual = instruction.getOpcode() - Opcodes.ICONST_0;
        else if (instruction.getOpcode() >= Opcodes.FCONST_0 && instruction.getOpcode() <= Opcodes.FCONST_2)
            actual = (float) (instruction.getOpcode() - Opcodes.FCONST_0);
        else if (instruction.getOpcode() >= Opcodes.DCONST_0 && instruction.getOpcode() <= Opcodes.DCONST_1)
            actual = (double) (instruction.getOpcode() - Opcodes.DCONST_0);
        else actual = null;
        return expected.equals(actual);
    }

    private static int opcode(AbstractInsnNode node) {
        return node == null ? -1 : node.getOpcode();
    }
}
