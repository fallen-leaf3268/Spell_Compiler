package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class FormulaConsumerSites {
    public record Definition(String spellClassName, String effectId, String alias, String method, String descriptor) {
        public Definition(String spellClassName, String effectId, String alias, String method) {
            this(spellClassName, effectId, alias, method, VALUE_ARGUMENTS + 'F');
        }
        public String key() { return method + descriptor; }
        public String dispatchKey() { return spellClassName + '#' + key(); }
    }

    private record Rule(String owner, String method, String descriptor, Kind kind, String alias,
                        String calledOwner, String calledMethod, int instanceSlot, float divisor, String effectId) {
        Rule(String owner, String method, String descriptor, Kind kind, String alias,
             String calledOwner, String calledMethod, int instanceSlot, float divisor) {
            this(owner, method, descriptor, kind, alias, calledOwner, calledMethod, instanceSlot, divisor, "");
        }
    }
    private record Selected(MethodNode method, Rule rule, AbstractInsnNode point, AbstractInsnNode input) {}
    private enum Kind { CAPTURE, CALL, PRODUCT, SPIDER_PRODUCT }

    private static final String SPELLS = "io.redspace.ironsspellbooks.spells.";
    private static final String EFFECTS = "io.redspace.ironsspellbooks.effect.";
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaConsumerHooks";
    private static final String VALUE_ARGUMENTS = "(ILnet/minecraft/world/entity/LivingEntity;)";
    private static final String ON_CAST = "(Lnet/minecraft/world/level/Level;ILnet/minecraft/world/entity/LivingEntity;Lio/redspace/ironsspellbooks/api/spells/CastSource;Lio/redspace/ironsspellbooks/api/magic/MagicData;)V";
    private static final Set<String> ATTRIBUTE_ALIASES = Set.of("percent_attack_damage", "percent_speed", "percent_spell_power");
    private static final List<Definition> DEFINITIONS = List.of(
            definition("ice.FrostbiteSpell", "frostbite", "damage", "getDamage"),
            definition("lightning.ChargeSpell", "charged", "percent_attack_damage", "getPercentAttackDamage"),
            definition("lightning.ChargeSpell", "charged", "percent_speed", "getPercentSpeed"),
            definition("lightning.ChargeSpell", "charged", "percent_spell_power", "getPercentSpellPower"),
            definition("nature.GluttonySpell", "gluttony", "mana_ratio", "getManaRatio"),
            definition("nature.OakskinSpell", "oakskin", "percent_damage", "getPercentDamage"),
            definition("nature.SpiderAspectSpell", "spider_aspect", "percent_damage", "getPercentDamage"));
    private static final Map<String, Set<String>> EXCLUDED = Map.of(
            SPELLS + "holy.WispSpell", Set.of("getDistance" + VALUE_ARGUMENTS + 'F', "getDuration" + VALUE_ARGUMENTS + 'F'),
            SPELLS + "holy.SunbeamSpell", Set.of("getDuration" + VALUE_ARGUMENTS + 'I'),
            SPELLS + "evocation.GustSpell", Set.of("getDamage" + VALUE_ARGUMENTS + 'F'));
    private static final List<Rule> RULES = rules();
    private static final Map<String, List<Definition>> FIXED_BY_SPELL = DEFINITIONS.stream().collect(Collectors.groupingBy(Definition::spellClassName,
            Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));
    private static final Map<String, List<Definition>> ATTRIBUTES_BY_SPELL = DEFINITIONS.stream().filter(value -> ATTRIBUTE_ALIASES.contains(value.alias()))
            .collect(Collectors.groupingBy(Definition::spellClassName, Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));
    private static final Map<String, Set<String>> ATTRIBUTE_ALIASES_BY_EFFECT = DEFINITIONS.stream().filter(value -> ATTRIBUTE_ALIASES.contains(value.alias()))
            .collect(Collectors.toUnmodifiableMap(Definition::effectId, value -> Set.of(value.alias()), (left, right) -> {
                var merged = new java.util.HashSet<>(left);
                merged.addAll(right);
                return Set.copyOf(merged);
            }));

    private FormulaConsumerSites() {}

    public static Set<String> requiredRuleOwners(String dispatchKey) {
        var definition = DEFINITIONS.stream().filter(value -> value.dispatchKey().equals(dispatchKey)).findFirst().orElse(null);
        if (definition == null || ATTRIBUTE_ALIASES.contains(definition.alias())) return Set.of();
        return RULES.stream().filter(rule -> rule.kind() == Kind.CAPTURE
                        ? rule.owner().equals(definition.spellClassName())
                        : rule.effectId().equals(definition.effectId()) && definition.alias().equals(rule.alias()))
                .map(Rule::owner).collect(Collectors.toUnmodifiableSet());
    }

    private record EffectIndex(List<Definition> dynamic,
                               Map<String, List<FormulaEffectAttributeControls.Definition>> attributes,
                               Map<String, List<Definition>> definitions, Map<String, Set<String>> aliases,
                               Map<String, List<Definition>> bySpell, Map<Definition, Invocation> invocations) {}
    record Invocation(String dispatchKey, boolean singleArgument, boolean attribute) {}
    private static volatile EffectIndex effectIndex;

    private static EffectIndex effectIndex() {
        var dynamic = FormulaManaRatioBridge.allDefinitions();
        var attributes = FormulaEffectAttributeControls.effectIndex();
        var current = effectIndex;
        if (current != null && current.dynamic() == dynamic && current.attributes() == attributes) return current;
        return rebuildEffectIndex(dynamic, attributes);
    }

    private static synchronized EffectIndex rebuildEffectIndex(List<Definition> dynamic,
            Map<String, List<FormulaEffectAttributeControls.Definition>> attributes) {
        var current = effectIndex;
        if (current != null && current.dynamic() == dynamic && current.attributes() == attributes) return current;
        Map<String, List<Definition>> definitions = new java.util.HashMap<>();
        Map<String, Set<String>> aliases = new java.util.HashMap<>();
        Map<String, List<Definition>> bySpell = new java.util.HashMap<>();
        Map<Definition, Invocation> invocations = new java.util.HashMap<>();
        for (var entries : List.of(DEFINITIONS, dynamic)) for (var definition : entries) {
            var selected = definitions.computeIfAbsent(definition.effectId(), ignored -> new ArrayList<>());
            if (!selected.contains(definition)) selected.add(definition);
            aliases.computeIfAbsent(definition.effectId(), ignored -> new java.util.HashSet<>()).add(definition.alias());
            var spellDefinitions = bySpell.computeIfAbsent(definition.spellClassName().replace('/', '.'), ignored -> new ArrayList<>());
            if (!spellDefinitions.contains(definition)) spellDefinitions.add(definition);
            invocations.computeIfAbsent(definition, FormulaConsumerSites::invocation);
        }
        attributes.forEach((id, entries) -> {
            var selected = aliases.computeIfAbsent(id, ignored -> new java.util.HashSet<>());
            for (var definition : entries) selected.add(definition.numeric().alias());
        });
        definitions.replaceAll((id, entries) -> List.copyOf(entries));
        aliases.replaceAll((id, entries) -> Set.copyOf(entries));
        bySpell.replaceAll((id, entries) -> List.copyOf(entries));
        current = new EffectIndex(dynamic, attributes, Map.copyOf(definitions), Map.copyOf(aliases), Map.copyOf(bySpell), Map.copyOf(invocations));
        effectIndex = current;
        return current;
    }

    public static boolean excludedGetter(String className, String methodKey) {
        return EXCLUDED.getOrDefault(className.replace('/', '.'), Set.of()).contains(methodKey);
    }

    public static List<Definition> definitions(String spellClassName) {
        return effectIndex().bySpell().getOrDefault(spellClassName.replace('/', '.'), List.of());
    }

    static Map<Definition, Invocation> invocations() { return effectIndex().invocations(); }

    static Invocation invocation(Definition value) {
        return new Invocation(value.dispatchKey(), org.objectweb.asm.Type.getArgumentTypes(value.descriptor()).length == 1,
                attributeDefinitions(value.spellClassName()).contains(value));
    }

    static Set<String> attributeControlAliases(String effectId) {
        return ATTRIBUTE_ALIASES_BY_EFFECT.getOrDefault(effectId, Set.of());
    }

    static List<Definition> fixedDefinitions(String spellClassName) {
        return FIXED_BY_SPELL.getOrDefault(spellClassName.replace('/', '.'), List.of());
    }

    public static List<Definition> forEffect(String effectId) {
        return effectIndex().definitions().getOrDefault(effectId, List.of());
    }

    public static Set<String> aliasesForEffect(String effectId) {
        return effectIndex().aliases().getOrDefault(effectId, Set.of());
    }

    public static List<Definition> attributeDefinitions(String spellClassName) {
        return ATTRIBUTES_BY_SPELL.getOrDefault(spellClassName.replace('/', '.'), List.of());
    }

    static boolean hasAttributeControls(FormulaClassRepository repository, String spellClassName) {
        return attributeDefinitions(spellClassName).stream().anyMatch(definition ->
                repository.resolveMethod(definition.spellClassName().replace('.', '/'), definition.method(), VALUE_ARGUMENTS + 'F') != null);
    }

    public static Set<String> targetClasses() {
        return RULES.stream().map(Rule::owner).collect(Collectors.toUnmodifiableSet());
    }

    public static void validate(FormulaClassRepository repository) {
        for (String owner : targetClasses()) {
            var source = repository.classNode(owner.replace('.', '/'));
            if (source == null) throw new IllegalArgumentException("Native formula consumer is unavailable: " + owner);
            select(owner, source);
        }
    }

    public static void apply(String className, ClassNode source) {
        if (!targetClasses().contains(className)) return;
        List<MethodNode> copies = new ArrayList<>();
        for (var method : source.methods) {
            var copy = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc, method.signature,
                    method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
            method.accept(copy);
            copies.add(copy);
        }
        var candidate = new ClassNode();
        candidate.name = source.name;
        candidate.methods = copies;
        for (var selected : select(className, candidate)) inject(selected);
        source.methods = copies;
    }

    private static List<Selected> select(String className, ClassNode source) {
        List<Selected> selected = new ArrayList<>();
        for (var rule : RULES) {
            if (!rule.owner().equals(className)) continue;
            var methods = source.methods.stream().filter(method -> method.name.equals(rule.method())
                    && method.desc.equals(rule.descriptor())).toList();
            if (methods.size() != 1) throw mismatch(rule, "method count=" + methods.size());
            var method = methods.get(0);
            List<AbstractInsnNode> points = new ArrayList<>();
            for (var instruction : method.instructions) if (matches(rule, instruction)) points.add(instruction);
            if (points.size() != 1) throw mismatch(rule, "anchor count=" + points.size());
            AbstractInsnNode point = points.get(0);
            AbstractInsnNode input = null;
            if (rule.kind() == Kind.PRODUCT || rule.kind() == Kind.SPIDER_PRODUCT) {
                var products = executable(method).stream().filter(instruction -> instruction.getOpcode() == Opcodes.FMUL
                        && next(instruction) instanceof MethodInsnNode call && call.desc.equals("(F)V")
                        && call.owner.equals(rule.alias().equals("mana_ratio")
                        ? "io/redspace/ironsspellbooks/api/magic/MagicData"
                        : "net/neoforged/neoforge/event/entity/living/LivingIncomingDamageEvent")
                        && call.name.equals(rule.alias().equals("mana_ratio") ? "addMana" : "setAmount")).toList();
                var inputs = executable(method).stream().filter(instruction -> instruction instanceof MethodInsnNode call
                        && (rule.alias().equals("mana_ratio") ? call.owner.equals("net/minecraft/world/food/FoodProperties")
                        && Set.of("nutrition").contains(call.name) && call.desc.equals("()I")
                        : call.owner.equals("net/neoforged/neoforge/event/entity/living/LivingIncomingDamageEvent")
                        && call.name.equals("getAmount") && call.desc.equals("()F")
                        && next(call) instanceof VarInsnNode load && load.getOpcode() == Opcodes.FLOAD)).toList();
                if (products.size() != 1 || inputs.size() != 1)
                    throw mismatch(rule, "product count=" + products.size() + " input count=" + inputs.size());
                point = products.get(0);
                input = rule.alias().equals("mana_ratio") ? next(inputs.get(0)) : inputs.get(0);
                if (input == null || rule.alias().equals("mana_ratio") && input.getOpcode() != Opcodes.I2F
                        || method.instructions.indexOf(input) >= method.instructions.indexOf(point))
                    throw mismatch(rule, "numeric product input differs");
            }
            selected.add(new Selected(method, rule, point, input));
        }
        return selected;
    }

    private static boolean matches(Rule rule, AbstractInsnNode instruction) {
        if (rule.kind() == Kind.CAPTURE) return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals("net/minecraft/world/effect/MobEffectInstance")
                && call.name.equals("<init>") && call.desc.equals("(Lnet/minecraft/core/Holder;IIZZZ)V");
        if (rule.kind() == Kind.CALL || rule.kind() == Kind.PRODUCT) return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(rule.calledOwner().replace('.', '/'))
                && call.name.equals(rule.calledMethod()) && call.desc.equals(rule.alias().equals("damage")
                || rule.alias().equals("percent_damage") ? "(ILnet/minecraft/world/entity/LivingEntity;)F" : "(I)F");
        var converted = previous(instruction);
        var level = previous(converted);
        var factor = previous(level);
        return instruction.getOpcode() == Opcodes.FMUL && converted != null && converted.getOpcode() == Opcodes.I2F
                && level instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD && load.var == 4
                && factor instanceof LdcInsnNode constant && constant.cst.equals(0.05f)
                && next(instruction) != null && next(instruction).getOpcode() == Opcodes.FADD;
    }

    private static void inject(Selected selected) {
        var rule = selected.rule();
        var code = new InsnList();
        if (rule.kind() == Kind.CAPTURE) {
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ILOAD, 2));
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "capture",
                    "(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;)V", false));
        } else if (selected.input() != null) {
            int inputSlot = selected.method().maxLocals++;
            var capture = new InsnList();
            capture.add(new InsnNode(Opcodes.DUP));
            capture.add(new VarInsnNode(Opcodes.FSTORE, inputSlot));
            selected.method().instructions.insert(selected.input(), capture);
            code.add(new VarInsnNode(Opcodes.ALOAD, rule.instanceSlot()));
            if (rule.kind() == Kind.SPIDER_PRODUCT) code.add(new LdcInsnNode("irons_spellbooks:spider_aspect"));
            code.add(new LdcInsnNode(rule.alias()));
            code.add(new LdcInsnNode(rule.divisor()));
            code.add(new VarInsnNode(Opcodes.FLOAD, inputSlot));
            code.add(new LdcInsnNode(rule.alias().equals("mana_ratio") ? 0 : rule.kind() == Kind.SPIDER_PRODUCT ? 2 : 1));
            code.add(new VarInsnNode(Opcodes.ALOAD, rule.kind() == Kind.SPIDER_PRODUCT ? 2 : 1));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS,
                    rule.kind() == Kind.SPIDER_PRODUCT ? "overrideProductForHolder" : "overrideProduct",
                    rule.kind() == Kind.SPIDER_PRODUCT
                            ? "(FLjava/lang/Object;Ljava/lang/String;Ljava/lang/String;FFILjava/lang/Object;)F"
                            : "(FLjava/lang/Object;Ljava/lang/String;FFILjava/lang/Object;)F", false));
        } else {
            code.add(new VarInsnNode(Opcodes.ALOAD, rule.instanceSlot()));
            code.add(new LdcInsnNode(rule.alias()));
            code.add(new LdcInsnNode(rule.divisor()));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "overrideFloat",
                    "(FLjava/lang/Object;Ljava/lang/String;F)F", false));
        }
        selected.method().instructions.insert(selected.point(), code);
        selected.method().maxStack += 8;
    }

    private static List<Rule> rules() {
        List<Rule> result = new ArrayList<>();
        for (var owner : DEFINITIONS.stream().map(Definition::spellClassName).distinct().toList())
            result.add(new Rule(owner, "onCast", ON_CAST, Kind.CAPTURE, null, null, null, -1, 1));
        result.add(new Rule(EFFECTS + "FrostbiteEffect", "handleFrostbiteDeathEffects",
                "(Lnet/neoforged/neoforge/event/entity/living/LivingDeathEvent;)V", Kind.CALL, "damage",
                EFFECTS + "FrostbiteEffect", "getDamageForAmplifier", 4, 1, "irons_spellbooks:frostbite"));
        result.add(new Rule(EFFECTS + "GluttonyEffect", "finishEating",
                "(Lnet/neoforged/neoforge/event/entity/living/LivingEntityUseItemEvent$Finish;)V", Kind.PRODUCT, "mana_ratio",
                EFFECTS + "GluttonyEffect", "ratioForAmplifier", 3, 1, "irons_spellbooks:gluttony"));
        result.add(new Rule(EFFECTS + "OakskinEffect", "reduceDamage",
                "(Lnet/neoforged/neoforge/event/entity/living/LivingIncomingDamageEvent;)V", Kind.PRODUCT, "percent_damage",
                EFFECTS + "OakskinEffect", "getReductionAmount", 2, 100, "irons_spellbooks:oakskin"));
        result.add(new Rule(EFFECTS + "OakskinEffect", "getDescriptionLine",
                "(Lnet/minecraft/world/effect/MobEffectInstance;)Lnet/minecraft/network/chat/Component;", Kind.CALL, "percent_damage",
                EFFECTS + "OakskinEffect", "getReductionAmount", 1, 100, "irons_spellbooks:oakskin"));
        result.add(new Rule(EFFECTS + "SpiderAspectEffect", "increaseDamage",
                "(Lnet/neoforged/neoforge/event/entity/living/LivingIncomingDamageEvent;)V", Kind.SPIDER_PRODUCT, "percent_damage",
                null, null, 2, 100, "irons_spellbooks:spider_aspect"));
        return List.copyOf(result);
    }

    private static Definition definition(String spell, String effect, String alias, String method) {
        return new Definition(SPELLS + spell, "irons_spellbooks:" + effect, alias, method);
    }

    private static IllegalArgumentException mismatch(Rule rule, String detail) {
        return new IllegalArgumentException("Native formula consumer differs: " + rule.owner() + '#' + rule.method()
                + rule.descriptor() + ' ' + detail);
    }

    private static List<AbstractInsnNode> executable(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    private static AbstractInsnNode next(AbstractInsnNode node) {
        if (node == null) return null;
        do node = node.getNext(); while (node != null && node.getOpcode() < 0);
        return node;
    }

    private static AbstractInsnNode previous(AbstractInsnNode node) {
        if (node == null) return null;
        do node = node.getPrevious(); while (node != null && node.getOpcode() < 0);
        return node;
    }
}
