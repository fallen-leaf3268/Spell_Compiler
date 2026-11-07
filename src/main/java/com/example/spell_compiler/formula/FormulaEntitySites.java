package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
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
import java.util.stream.Collectors;

public final class FormulaEntitySites {
    record Point(FormulaNumericSites.Definition definition, String entityClassName) {
        String pointKey() { return entityClassName + '#' + definition.alias(); }
    }

    private enum Kind { CAPTURE, INHERIT, CONSTANT, FIELD }
    private record Rule(String owner, Set<String> methods, String descriptor, Kind kind,
                        String key, int constant, int expectedCount, int occurrence) {}
    private record Selected(MethodNode method, Rule rule, AbstractInsnNode point) {}

    private static final String SPELLS = "io.redspace.ironsspellbooks.spells.";
    private static final String ENTITIES = "io.redspace.ironsspellbooks.entity.spells.";
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaEntityHooks";
    private static final String ON_CAST = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/spells/CastSource;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V";
    private static final List<Point> POINTS = List.of(
            point("lightning.BallLightningSpell", "ball_lightning.BallLightning", "hit_interval_ticks", 10, 1),
            point("nature.FireflySwarmSpell", "firefly_swarm.FireflySwarmProjectile", "attack_interval_ticks", 15, 1),
            point("eldritch.SculkTentaclesSpell", "void_tentacle.VoidTentacle", "entity_duration_ticks", 300, 0),
            point("eldritch.SculkTentaclesSpell", "void_tentacle.VoidTentacle", "attack_window_ticks", 280, 0),
            point("eldritch.SculkTentaclesSpell", "void_tentacle.VoidTentacle", "attack_interval_ticks", 20, 1),
            point("eldritch.SculkTentaclesSpell", "void_tentacle.VoidTentacle", "blindness_duration_ticks", 100, 0),
            point("nature.PoisonArrowSpell", "poison_cloud.PoisonCloud", "cloud_duration_ticks", 200, 0),
            point("nature.PoisonArrowSpell", "poison_cloud.PoisonCloud", "poison_duration_ticks", 120, 0),
            point("nature.PoisonArrowSpell", "poison_cloud.PoisonCloud", "hit_interval_ticks", 10, 1),
            point("nature.PoisonSplashSpell", "poison_cloud.PoisonCloud", "poison_duration_ticks", 120, 0),
            point("nature.PoisonSplashSpell", "poison_cloud.PoisonCloud", "hit_interval_ticks", 10, 1),
            point("fire.MagmaBombSpell", "magma_ball.FireField", "hit_interval_ticks", 10, 1),
            point("fire.ScorchSpell", "magma_ball.FireField", "hit_interval_ticks", 10, 1),
            point("nature.EarthquakeSpell", "EarthquakeAoe", "hit_interval_ticks", 25, 1));
    private static final List<Rule> RULES = rules();
    private static final FormulaNumericIndex INDEX = new FormulaNumericIndex(POINTS.stream().map(Point::definition).toList());

    private FormulaEntitySites() {}

    public static List<FormulaNumericSites.Definition> definitions(String spellClassName) {
        return INDEX.definitions(spellClassName.replace('/', '.'));
    }

    static List<Point> points(String spellClassName) {
        return POINTS.stream().filter(value -> value.definition().spellClassName()
                .equals(spellClassName.replace('/', '.'))).toList();
    }

    static FormulaNumericSites.Definition pointDefinition(String pointKey) {
        return POINTS.stream().filter(value -> value.pointKey().equals(pointKey)).map(Point::definition)
                .findFirst().orElse(null);
    }

    static Set<String> pointKeys() {
        return POINTS.stream().map(Point::pointKey).collect(Collectors.toUnmodifiableSet());
    }

    public static FormulaNumericSites.Definition find(String dispatchKey) {
        return INDEX.find(dispatchKey);
    }

    public static Set<String> targetClasses() {
        return RULES.stream().map(Rule::owner).collect(Collectors.toUnmodifiableSet());
    }

    public static void validate(FormulaClassRepository repository) {
        for (String owner : targetClasses()) {
            ClassNode source = repository.classNode(owner.replace('.', '/'));
            if (source == null) throw new IllegalArgumentException("找不到实体数值消费类：" + owner);
            select(owner, source);
        }
    }

    public static void apply(String className, ClassNode source) {
        String owner = className.replace('/', '.');
        if (!targetClasses().contains(owner)) return;
        List<MethodNode> copies = new ArrayList<>();
        for (MethodNode method : source.methods) {
            MethodNode copy = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc, method.signature,
                    method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
            method.accept(copy);
            copies.add(copy);
        }
        ClassNode candidate = new ClassNode();
        candidate.name = source.name;
        candidate.methods = copies;
        List<Selected> selected = select(owner, candidate);
        selected.forEach(FormulaEntitySites::inject);
        source.methods = copies;
    }

    private static List<Selected> select(String owner, ClassNode source) {
        List<Selected> result = new ArrayList<>();
        for (Rule rule : RULES) {
            if (!rule.owner().equals(owner)) continue;
            List<MethodNode> methods = source.methods.stream().filter(method -> rule.methods().contains(method.name)
                    && rule.descriptor().equals(method.desc)).toList();
            if (methods.size() != 1) throw mismatch(rule, "method count=" + methods.size());
            MethodNode method = methods.get(0);
            if ((method.access & Opcodes.ACC_STATIC) != 0) throw mismatch(rule, "static context");
            List<AbstractInsnNode> points = new ArrayList<>();
            for (AbstractInsnNode instruction : method.instructions) if (matches(rule, instruction)) points.add(instruction);
            if (points.size() != rule.expectedCount()) throw mismatch(rule, "anchor count=" + points.size());
            if (rule.occurrence() < 0) {
                for (AbstractInsnNode point : points) result.add(new Selected(method, rule, point));
            } else {
                if (rule.occurrence() >= points.size()) throw mismatch(rule, "anchor occurrence=" + rule.occurrence());
                result.add(new Selected(method, rule, points.get(rule.occurrence())));
            }
        }
        return result;
    }

    private static boolean matches(Rule rule, AbstractInsnNode instruction) {
        if (rule.kind() == Kind.CAPTURE || rule.kind() == Kind.INHERIT)
            return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/world/level/Level")
                    && Set.of("addFreshEntity", "m_7967_").contains(call.name)
                    && call.desc.equals("(Lnet/minecraft/world/entity/Entity;)Z");
        if (rule.kind() == Kind.FIELD)
            return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals((ENTITIES + "AoeEntity").replace('.', '/'))
                    && field.name.equals(rule.key().equals("cloud_duration_ticks") ? "duration" : "reapplicationDelay")
                    && field.desc.equals("I");
        Integer value = integer(instruction);
        return value != null && value == rule.constant();
    }

    private static void inject(Selected selected) {
        Rule rule = selected.rule();
        InsnList code = new InsnList();
        if (rule.kind() == Kind.CAPTURE || rule.kind() == Kind.INHERIT) {
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            if (rule.kind() == Kind.CAPTURE) {
                code.add(new VarInsnNode(Opcodes.ILOAD, 2));
                code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            }
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    rule.kind() == Kind.CAPTURE ? "capture" : "inherit",
                    rule.kind() == Kind.CAPTURE ? "(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;)V"
                            : "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            selected.method().instructions.insertBefore(selected.point(), code);
        } else {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new LdcInsnNode(rule.key()));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    rule.kind() == Kind.FIELD ? "readFieldInt" : "readInt",
                    "(ILjava/lang/Object;Ljava/lang/String;)I", false));
            selected.method().instructions.insert(selected.point(), code);
        }
        selected.method().maxStack += 4;
    }

    private static List<Rule> rules() {
        List<Rule> result = new ArrayList<>();
        for (String owner : POINTS.stream().map(value -> value.definition().spellClassName()).distinct().toList())
            result.add(new Rule(owner, Set.of("onCast"), ON_CAST, Kind.CAPTURE, null, 0, 1, -1));
        result.add(inherit("poison_arrow.PoisonArrow", "createPoisonCloud", "(Lnet/minecraft/world/phys/Vec3;)V"));
        result.add(inherit("poison_cloud.PoisonSplash", "createPoisonCloud", "()V"));
        result.add(inherit("magma_ball.FireBomb", "createFireField", "(Lnet/minecraft/world/phys/Vec3;)V"));
        result.add(constant("ball_lightning.BallLightning", Set.of("canHitVictim"),
                "(Lnet/minecraft/world/entity/Entity;)Z", "hit_interval_ticks", 10, 1, -1));
        result.add(constant("firefly_swarm.FireflySwarmProjectile", Set.of("customServerAiStep", "m_8024_"),
                "()V", "attack_interval_ticks", 15, 1, -1));
        result.add(constant("void_tentacle.VoidTentacle", Set.of("tick", "m_8119_"),
                "()V", "entity_duration_ticks", 300, 1, -1));
        result.add(constant("void_tentacle.VoidTentacle", Set.of("tick", "m_8119_"),
                "()V", "attack_window_ticks", 280, 2, 0));
        result.add(constant("void_tentacle.VoidTentacle", Set.of("tick", "m_8119_"),
                "()V", "attack_interval_ticks", 20, 1, -1));
        result.add(constant("void_tentacle.VoidTentacle", Set.of("dealDamage"),
                "(Lnet/minecraft/world/entity/LivingEntity;)Z", "blindness_duration_ticks", 100, 1, -1));
        result.add(constant("poison_cloud.PoisonCloud", Set.of("applyEffect"),
                "(Lnet/minecraft/world/entity/LivingEntity;)V", "poison_duration_ticks", 120, 1, -1));
        result.add(new Rule(ENTITIES + "AoeEntity", Set.of("tick", "m_8119_"), "()V", Kind.FIELD,
                "cloud_duration_ticks", 0, 1, -1));
        result.add(new Rule(ENTITIES + "AoeEntity", Set.of("tick", "m_8119_"), "()V", Kind.FIELD,
                "hit_interval_ticks", 0, 1, -1));
        return List.copyOf(result);
    }

    private static Rule inherit(String entity, String method, String descriptor) {
        return new Rule(ENTITIES + entity, Set.of(method), descriptor, Kind.INHERIT, null, 0, 1, -1);
    }

    private static Rule constant(String entity, Set<String> methods, String descriptor, String alias,
                                 int value, int count, int occurrence) {
        String owner = ENTITIES + entity;
        return new Rule(owner, methods, descriptor, Kind.CONSTANT, owner + '#' + alias, value, count, occurrence);
    }

    private static Point point(String spell, String entity, String alias, int value, int minimum) {
        return new Point(new FormulaNumericSites.Definition(SPELLS + spell, alias, value,
                FormulaNumericSites.ValueType.INT, minimum, Integer.MAX_VALUE), ENTITIES + entity);
    }

    private static Integer integer(AbstractInsnNode instruction) {
        if (instruction instanceof IntInsnNode value && (value.getOpcode() == Opcodes.BIPUSH
                || value.getOpcode() == Opcodes.SIPUSH)) return value.operand;
        if (instruction instanceof LdcInsnNode value && value.cst instanceof Integer integer) return integer;
        int opcode = instruction.getOpcode();
        return opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5 ? opcode - Opcodes.ICONST_0 : null;
    }

    private static IllegalArgumentException mismatch(Rule rule, String detail) {
        return new IllegalArgumentException("实体数值消费锚点不匹配：" + rule.owner() + '#' + rule.methods()
                + rule.descriptor() + ' ' + detail);
    }
}
