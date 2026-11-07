package com.example.spell_compiler.formula;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class FormulaEffectAttributeControls {
    private static final String EFFECT = "net/minecraft/world/effect/MobEffect";
    private static final String MODIFIER = "(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceLocation;DLnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)L" + EFFECT + ";";
    private static final String FUNCTION_MODIFIER = "(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;Lit/unimi/dsi/fastutil/ints/Int2DoubleFunction;)L" + EFFECT + ";";
    private static final String LOCATION = "net/minecraft/resources/ResourceLocation";
    private static final Pattern ALIAS_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH_PATTERN = Pattern.compile("[a-z0-9_./-]+");
    public record Definition(FormulaNumericSites.Definition numeric, String effectId, String registryOwner, String registryField,
                             String attributeOwner, String attributeField, String attributeDescriptor, String modifierId,
                             String operation, double baseAmount, String fingerprint) {}
    private record Registration(String id, FormulaExecutionGraph.MethodId factory, String fingerprint) {}
    private record Template(String owner, String field, String descriptor, String modifier, String operation, double amount) {}
    private record Discovery(List<Definition> definitions, Set<String> completeEffects, Map<String, List<String>> aliases) {
        boolean fullyControlsEffect(String registryOwner, String registryField) {
            return completeEffects.contains(registryOwner.replace('.', '/') + '#' + registryField);
        }
        List<String> controlledAliases(String registryOwner, String registryField) {
            return aliases.getOrDefault(registryOwner.replace('.', '/') + '#' + registryField, List.of());
        }
    }

    static final class Query {
        private final FormulaClassRepository repository;
        private final String spell;
        private FormulaExecutionGraph.Index index;
        private Discovery result;

        Query(FormulaClassRepository repository, String spell) {
            this.repository = repository;
            this.spell = spell;
        }

        boolean fullyControlsEffect(String registryOwner, String registryField) {
            return discovery().fullyControlsEffect(registryOwner, registryField);
        }

        List<String> controlledAliases(String registryOwner, String registryField) {
            return discovery().controlledAliases(registryOwner, registryField);
        }

        private Discovery discovery() {
            var current = FormulaExecutionGraph.index(repository);
            if (index != current) {
                result = discover(repository, spell);
                index = current;
            }
            return result;
        }
    }
    private record Index(Map<String, List<Definition>> bySpell, Map<String, List<Definition>> byEffect,
                         Map<String, Definition> byBinding) {}
    private static volatile Index registered = new Index(Map.of(), Map.of(), Map.of());
    private static final Map<MobEffect, Map<Holder<Attribute>, Definition>> RUNTIME = new ConcurrentHashMap<>();
    private static final Map<String, String> FAILURES = new ConcurrentHashMap<>();

    private FormulaEffectAttributeControls() {}

    public static synchronized List<Definition> register(FormulaClassRepository repository, String spellClassName) {
        String spell = spellClassName.replace('/', '.');
        var definitions = discover(repository, spell).definitions();
        Map<String, List<Definition>> updated = new LinkedHashMap<>(registered.bySpell());
        updated.put(spell, definitions);
        Map<String, List<Definition>> effects = new LinkedHashMap<>();
        Map<String, Definition> bindings = new LinkedHashMap<>();
        for (var entries : updated.values()) for (var definition : entries) {
            effects.computeIfAbsent(definition.effectId(), ignored -> new ArrayList<>()).add(definition);
            bindings.putIfAbsent(definition.numeric().dispatchKey(), definition);
        }
        effects.replaceAll((id, entries) -> List.copyOf(entries));
        registered = new Index(Map.copyOf(updated), Map.copyOf(effects), Map.copyOf(bindings));
        FormulaAddonNumericSites.registerDynamic(spell, definitions.stream().map(Definition::numeric).toList());
        RUNTIME.clear();
        return definitions;
    }

    public static List<Definition> definitions(String spellClassName) { return registered.bySpell().getOrDefault(spellClassName.replace('/', '.'), List.of()); }
    static Map<String, List<Definition>> effectIndex() { return registered.byEffect(); }
    public static boolean hasControls(FormulaClassRepository repository, String spellClassName) { return !discover(repository, spellClassName).definitions().isEmpty(); }
    public static boolean controlsEffect(FormulaClassRepository repository, String spellClassName, String registryOwner, String registryField) {
        return discover(repository, spellClassName).definitions().stream().anyMatch(definition -> definition.registryOwner().equals(registryOwner.replace('.', '/'))
                && definition.registryField().equals(registryField));
    }
    public static boolean fullyControlsEffect(FormulaClassRepository repository, String spellClassName, String registryOwner, String registryField) {
        return discover(repository, spellClassName).fullyControlsEffect(registryOwner, registryField);
    }
    public static List<String> controlledAliases(FormulaClassRepository repository, String spellClassName, String registryOwner, String registryField) {
        return discover(repository, spellClassName).controlledAliases(registryOwner, registryField);
    }
    public static boolean isBinding(String dispatchKey) {
        return registered.byBinding().containsKey(dispatchKey);
    }
    public static boolean availableBinding(String dispatchKey) {
        var definition = registered.byBinding().get(dispatchKey);
        if (definition == null) return false;
        var effect = BuiltInRegistries.MOB_EFFECT.getOptional(net.minecraft.resources.ResourceLocation.parse(definition.effectId())).orElse(null);
        if (effect == null) {
            FAILURES.putIfAbsent(dispatchKey, "实际效果未注册：" + definition.effectId());
            return false;
        }
        return runtime(effect).containsValue(definition);
    }
    public static List<Definition> forEffect(String effectId) {
        return registered.byEffect().getOrDefault(effectId, List.of());
    }
    public static Map<String, String> diagnostics() { return Map.copyOf(FAILURES); }

    private static Discovery discover(FormulaClassRepository repository, String spellClassName) {
        String spell = spellClassName.replace('.', '/');
        var graph = FormulaExecutionGraph.analyze(repository, spell);
        List<Definition> proposed = new ArrayList<>();
        Set<String> complete = new HashSet<>();
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (var effect : graph.effects()) {
            if (!effect.ownershipComplete() || !effect.spellClasses().equals(Set.of(spell))) continue;
            var registration = registration(repository, effect);
            if (registration == null) continue;
            var templates = templates(repository, effect, registration);
            if (templates == null) continue;
            boolean covered = true;
            List<String> controlled = new ArrayList<>();
            for (var template : templates) {
                if (!Set.of("ADD_VALUE", "ADD_MULTIPLIED_BASE", "ADD_MULTIPLIED_TOTAL").contains(template.operation())) { covered = false; continue; }
                String existing = coveredGetter(repository, spell, registration.id(), template);
                if (existing != null) { controlled.add(existing); continue; }
                String alias = "attribute_" + template.field().toLowerCase(Locale.ROOT);
                if (!ALIAS_PATTERN.matcher(alias).matches()) { covered = false; continue; }
                var numeric = new FormulaNumericSites.Definition(spell, alias, (float) FormulaAttributes.Operation.fromNative(template.operation()).fromRaw(template.amount()),
                        FormulaNumericSites.ValueType.FLOAT, -Float.MAX_VALUE, Float.MAX_VALUE);
                String fingerprint = effect.registryOwner() + '#' + effect.registryField() + ':' + template.owner() + '#'
                        + template.field() + ':' + template.descriptor() + ':' + template.modifier() + ':' + template.operation()
                        + ':' + Double.toHexString(template.amount()) + ':' + registration.id() + ':' + registration.fingerprint();
                proposed.add(new Definition(numeric, registration.id(), effect.registryOwner(), effect.registryField(), template.owner(),
                        template.field(), template.descriptor(), template.modifier(), template.operation(), template.amount(), fingerprint));
                controlled.add(alias);
            }
            aliases.put(effect.registryOwner() + '#' + effect.registryField(), List.copyOf(controlled));
            if (covered) complete.add(effect.registryOwner() + '#' + effect.registryField());
        }
        var counts = proposed.stream().collect(java.util.stream.Collectors.groupingBy(definition -> definition.numeric().alias(), java.util.stream.Collectors.counting()));
        for (var definition : proposed) if (counts.get(definition.numeric().alias()) != 1)
            complete.remove(definition.registryOwner() + '#' + definition.registryField());
        proposed.removeIf(definition -> counts.get(definition.numeric().alias()) != 1);
        if (proposed.size() > 32) return new Discovery(List.of(), Set.of(), Map.of());
        return new Discovery(List.copyOf(proposed), Set.copyOf(complete), Map.copyOf(aliases));
    }

    static String effectId(FormulaClassRepository repository, FormulaExecutionGraph.EffectLink effect) {
        var registration = registration(repository, effect);
        return registration == null ? null : registration.id();
    }

    static List<FormulaAttributes.Component> components(FormulaClassRepository repository, String spellClassName) {
        String spell = spellClassName.replace('.', '/');
        List<FormulaAttributes.Component> result = new ArrayList<>();
        var definitions = definitions(spell);
        for (var effect : FormulaExecutionGraph.analyze(repository, spell).effects()) {
            if (!effect.ownershipComplete() || !effect.spellClasses().equals(Set.of(spell))) continue;
            var registration = registration(repository, effect);
            if (registration == null) continue;
            var templates = templates(repository, effect, registration);
            if (templates == null) continue;
            for (var template : templates) {
                if (!Set.of("ADD_VALUE", "ADD_MULTIPLIED_BASE", "ADD_MULTIPLIED_TOTAL").contains(template.operation())) continue;
                String attributeId = FormulaAttributeRegistration.id(repository, template.owner(), template.field());
                if (attributeId == null) continue;
                String alias = coveredGetter(repository, spell, registration.id(), template);
                String binding = null;
                if (alias != null) {
                    final String selected = alias;
                    binding = FormulaConsumerSites.attributeDefinitions(spell).stream().filter(value -> value.alias().equals(selected))
                            .map(FormulaConsumerSites.Definition::key).findFirst().orElse(null);
                } else {
                    var definition = definitions.stream().filter(value -> value.attributeOwner().equals(template.owner())
                            && value.attributeField().equals(template.field()) && value.modifierId().equals(template.modifier())
                            && value.operation().equals(template.operation())).findFirst().orElse(null);
                    if (definition != null) { alias = definition.numeric().alias(); binding = definition.numeric().key(); }
                }
                String key = template.field().toLowerCase(Locale.ROOT);
                if (binding != null && ALIAS_PATTERN.matcher(key).matches())
                    result.add(new FormulaAttributes.Component(key, alias, binding, registration.id(), attributeId,
                            template.modifier(), FormulaAttributes.Operation.fromNative(template.operation())));
            }
        }
        var counts = result.stream().collect(java.util.stream.Collectors.groupingBy(FormulaAttributes.Component::key, java.util.stream.Collectors.counting()));
        var bindings = result.stream().collect(java.util.stream.Collectors.groupingBy(FormulaAttributes.Component::binding, java.util.stream.Collectors.counting()));
        var aliases = result.stream().collect(java.util.stream.Collectors.groupingBy(FormulaAttributes.Component::alias, java.util.stream.Collectors.counting()));
        return result.stream().filter(value -> counts.get(value.key()) == 1 && bindings.get(value.binding()) == 1 && aliases.get(value.alias()) == 1)
                .limit(32).toList();
    }

    private static Registration registration(FormulaClassRepository repository, FormulaExecutionGraph.EffectLink effect) {
        var initialization = repository.resolveMethod(effect.registryOwner(), "<clinit>", "()V");
        if (initialization == null) return null;
        var flow = FormulaDataFlow.analyze(repository, initialization.owner().name, initialization.method());
        if (!flow.successful()) return null;
        for (var instruction : flow.instructions()) {
            if (!(instruction.instruction() instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !field.owner.equals(effect.registryOwner()) || !field.name.equals(effect.registryField())) continue;
            var assigned = flow.origin(instruction.field().value());
            if (assigned == null || !(assigned.instruction() instanceof MethodInsnNode register) || !register.name.equals("register")) return null;
            var arguments = flow.arguments(register);
            if (arguments.size() != 2) return null;
            String path = string(repository, flow, arguments.getFirst(), Map.of());
            var supplier = flow.origin(arguments.get(1));
            var registry = flow.origin(flow.receiver(register));
            if (path == null || registry == null || !(registry.instruction() instanceof FieldInsnNode source)
                    || source.getOpcode() != Opcodes.GETSTATIC || supplier == null || !(supplier.instruction() instanceof InvokeDynamicInsnNode dynamic)) return null;
            String namespace = null;
            for (var candidate : flow.instructions()) {
                if (candidate.field() == null || !candidate.field().write() || !candidate.field().isStatic()
                        || !candidate.field().owner().equals(source.owner) || !candidate.field().name().equals(source.name)) continue;
                var origin = flow.origin(candidate.field().value());
                if (origin == null || !(origin.instruction() instanceof MethodInsnNode creation)
                        || !creation.owner.equals("net/neoforged/neoforge/registries/DeferredRegister") || !creation.name.equals("create")) return null;
                var inputs = flow.arguments(creation);
                if (inputs.size() == 2) namespace = string(repository, flow, inputs.get(1), Map.of());
            }
            if (namespace == null || !NAMESPACE_PATTERN.matcher(namespace).matches() || !PATH_PATTERN.matcher(path).matches()
                    || namespace.length() + path.length() + 1 > 64) return null;
            var handles = java.util.Arrays.stream(dynamic.bsmArgs).filter(Handle.class::isInstance).map(Handle.class::cast).toList();
            if (handles.size() != 1 || handles.getFirst().getTag() != Opcodes.H_INVOKESTATIC) return null;
            var factory = handles.getFirst();
            var implementation = repository.resolveMethod(factory.getOwner(), factory.getName(), factory.getDesc());
            if (implementation == null) return null;
            return new Registration(namespace + ':' + path, new FormulaExecutionGraph.MethodId(factory.getOwner(), factory.getName(), factory.getDesc()),
                    FormulaAutomaticInjector.fingerprint(implementation.method()));
        }
        return null;
    }

    private static List<Template> templates(FormulaClassRepository repository, FormulaExecutionGraph.EffectLink effect, Registration registration) {
        var method = repository.resolveMethod(registration.factory().owner(), registration.factory().name(), registration.factory().descriptor());
        if (method == null) return null;
        var flow = FormulaDataFlow.analyze(repository, method.owner().name, method.method());
        if (!flow.successful()) return null;
        var returns = flow.instructions().stream().filter(value -> value.instruction().getOpcode() == Opcodes.ARETURN).toList();
        if (returns.size() != 1 || returns.getFirst().operands().size() != 1) return null;
        var current = flow.origin(returns.getFirst().operands().getFirst());
        List<Template> result = new ArrayList<>();
        Set<String> attributes = new HashSet<>();
        Set<FormulaDataFlow.Node> visited = new HashSet<>();
        while (current != null && visited.add(current) && current.instruction() instanceof MethodInsnNode modifier
                && modifier.name.equals("addAttributeModifier") && Set.of(MODIFIER, FUNCTION_MODIFIER).contains(modifier.desc)
                && repository.isSubtype(modifier.owner, EFFECT)) {
            var arguments = flow.arguments(modifier);
            var attribute = flow.origin(arguments.get(0));
            boolean function = modifier.desc.equals(FUNCTION_MODIFIER);
            var operation = flow.origin(arguments.get(function ? 2 : 3));
            Double amount = function ? constantFunction(repository, flow, arguments.get(3)) : number(flow, arguments.get(2), new HashSet<>());
            String id = location(repository, flow, arguments.get(1), Map.of(), 0);
            if (attribute == null || !(attribute.instruction() instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC
                    || operation == null || !(operation.instruction() instanceof FieldInsnNode op) || op.getOpcode() != Opcodes.GETSTATIC
                    || !op.owner.equals("net/minecraft/world/entity/ai/attributes/AttributeModifier$Operation")
                    || amount == null || !Double.isFinite(amount) || !Float.isFinite((float) (amount * 100)) || id == null
                    || !attributes.add(field.owner + '#' + field.name)) return null;
            result.add(new Template(field.owner, field.name, field.desc, id, op.name, amount));
            current = flow.origin(flow.receiver(modifier));
        }
        if (current == null || !(current.instruction() instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW
                || !repository.isSubtype(allocation.desc, EFFECT) || !nativeMethods(repository, allocation.desc)) return null;
        if (!effect.effectClass().isEmpty() && !allocation.desc.equals(effect.effectClass().replace('.', '/'))) return null;
        return List.copyOf(result);
    }

    private static boolean nativeMethods(FormulaClassRepository repository, String effect) {
        var add = repository.resolveMethod(effect, "addAttributeModifier", MODIFIER);
        var create = repository.resolveMethod(effect, "createModifiers", "(ILjava/util/function/BiConsumer;)V");
        if (add == null || create == null || !add.owner().name.equals(EFFECT) || !create.owner().name.equals(EFFECT)) return false;
        var function = repository.resolveMethod(effect, "addAttributeModifier", FUNCTION_MODIFIER);
        if (function != null && !function.owner().name.equals(EFFECT)) return false;
        Set<String> visited = new HashSet<>();
        while (!effect.equals(EFFECT) && visited.add(effect)) {
            var owner = repository.classNode(effect);
            if (owner == null || owner.superName == null) return false;
            for (var method : owner.methods) if (method.name.equals("<init>")) for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call && !(call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")
                        && (call.owner.equals(owner.superName) || call.owner.equals(owner.name)))) return false;
            effect = owner.superName;
        }
        return effect.equals(EFFECT);
    }

    private static Double constantFunction(FormulaClassRepository repository, FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value) {
        var origin = flow.origin(value);
        if (origin == null || !(origin.instruction() instanceof InvokeDynamicInsnNode factory)
                || !factory.desc.equals("()Lit/unimi/dsi/fastutil/ints/Int2DoubleFunction;")) return null;
        var handles = java.util.Arrays.stream(factory.bsmArgs).filter(Handle.class::isInstance).map(Handle.class::cast).toList();
        if (handles.size() != 1 || handles.getFirst().getTag() != Opcodes.H_INVOKESTATIC || !handles.getFirst().getDesc().equals("(I)D")) return null;
        var target = handles.getFirst();
        var method = repository.resolveMethod(target.getOwner(), target.getName(), target.getDesc());
        if (method == null) return null;
        var instructions = FormulaAutomaticInjector.executable(method.method());
        if (instructions.size() != 2 || instructions.getLast().getOpcode() != Opcodes.DRETURN) return null;
        if (instructions.getFirst() instanceof LdcInsnNode literal && literal.cst instanceof Double number && Double.isFinite(number)) return number;
        int opcode = instructions.getFirst().getOpcode();
        return opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1 ? (double) (opcode - Opcodes.DCONST_0) : null;
    }

    private static String coveredGetter(FormulaClassRepository repository, String spell, String effectId, Template template) {
        if (template.operation().equals("ADD_VALUE")) return null;
        String alias = template.owner().equals("net/minecraft/world/entity/ai/attributes/Attributes")
                ? switch (template.field()) { case "ATTACK_DAMAGE" -> "percent_attack_damage"; case "MOVEMENT_SPEED" -> "percent_speed"; default -> ""; }
                : template.owner().equals("io/redspace/ironsspellbooks/api/registry/AttributeRegistry")
                && (template.field().equals("SPELL_POWER") || template.field().endsWith("_SPELL_POWER")) ? "percent_spell_power" : "";
        if (alias.isEmpty()) return null;
        for (var definition : FormulaConsumerSites.attributeDefinitions(spell)) {
            if (!definition.alias().equals(alias) || !definition.effectId().equals(effectId)) continue;
            var method = repository.resolveMethod(spell, definition.method(), "(ILnet/minecraft/world/entity/LivingEntity;)F");
            if (method != null && AutomaticFormulaExtractor.analyze(method.owner(), method.method(), repository).supported()) return alias;
        }
        return null;
    }

    private static String string(FormulaClassRepository repository, FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value, Map<Integer, String> parameters) {
        var origin = flow.origin(value);
        if (origin == null) return null;
        if (origin.kind() == FormulaDataFlow.NodeKind.PARAMETER) return parameters.get(origin.parameterSlot());
        if (origin.instruction() instanceof LdcInsnNode literal && literal.cst instanceof String text) return text;
        if (origin.instruction() instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
            var resolved = repository.resolveField(field.owner, field.name, field.desc);
            if (resolved != null && resolved.field().value instanceof String text) return text;
        }
        return null;
    }

    private static String location(FormulaClassRepository repository, FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value,
                                   Map<Integer, String> parameters, int depth) {
        if (depth > 4) return null;
        var origin = flow.origin(value);
        if (origin != null && origin.instruction() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                && allocation.desc.equals(LOCATION)) {
            var constructors = flow.instructions().stream().filter(instruction -> instruction.instruction() instanceof MethodInsnNode call
                    && call.owner.equals(LOCATION) && call.name.equals("<init>") && call.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V")
                    && origin.equals(flow.origin(flow.receiver(call)))).toList();
            if (constructors.size() != 1) return null;
            var constructor = (MethodInsnNode) constructors.getFirst().instruction();
            var inputs = flow.arguments(constructor);
            String namespace = string(repository, flow, inputs.get(0), parameters), path = string(repository, flow, inputs.get(1), parameters);
            return namespace != null && NAMESPACE_PATTERN.matcher(namespace).matches() && path != null && PATH_PATTERN.matcher(path).matches() ? namespace + ':' + path : null;
        }
        if (origin == null || !(origin.instruction() instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) return null;
        var arguments = flow.arguments(call);
        if (call.owner.equals(LOCATION) && call.name.equals("fromNamespaceAndPath") && arguments.size() == 2) {
            String namespace = string(repository, flow, arguments.get(0), parameters), path = string(repository, flow, arguments.get(1), parameters);
            return namespace != null && NAMESPACE_PATTERN.matcher(namespace).matches() && path != null && PATH_PATTERN.matcher(path).matches() ? namespace + ':' + path : null;
        }
        var resolved = repository.resolveMethod(call.owner, call.name, call.desc);
        if (resolved == null) return null;
        Map<Integer, String> bound = new HashMap<>();
        int slot = 0;
        var types = Type.getArgumentTypes(call.desc);
        for (int index = 0; index < arguments.size(); index++) {
            String text = string(repository, flow, arguments.get(index), parameters);
            if (text == null) return null;
            bound.put(slot, text);
            slot += types[index].getSize();
        }
        var body = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
        if (!body.successful()) return null;
        var returns = body.instructions().stream().filter(instruction -> instruction.instruction().getOpcode() == Opcodes.ARETURN).toList();
        return returns.size() == 1 && returns.getFirst().operands().size() == 1
                ? location(repository, body, returns.getFirst().operands().getFirst(), bound, depth + 1) : null;
    }

    private static Double number(FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value, Set<FormulaDataFlow.Node> path) {
        var origin = flow.origin(value);
        if (origin == null || !path.add(origin)) return null;
        try {
            var instruction = origin.instruction();
            if (instruction instanceof LdcInsnNode literal && literal.cst instanceof Number number) return number.doubleValue();
            if (instruction == null) return null;
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.DCONST_0 && opcode <= Opcodes.DCONST_1) return (double) (opcode - Opcodes.DCONST_0);
            if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return (double) (opcode - Opcodes.FCONST_0);
            var inputs = flow.dependencies(origin);
            if (Set.of(Opcodes.F2D, Opcodes.I2D, Opcodes.DNEG, Opcodes.FNEG).contains(opcode) && inputs.size() == 1) {
                Double input = number(flow, inputs.getFirst(), path);
                return input == null ? null : opcode == Opcodes.DNEG || opcode == Opcodes.FNEG ? -input : input;
            }
            return null;
        } finally { path.remove(origin); }
    }

    public static String alias(MobEffectInstance effect, Holder<Attribute> attribute) {
        var definition = runtime(effect.getEffect().value()).get(attribute);
        return definition == null ? null : definition.numeric().alias();
    }

    public static Holder<Attribute> attribute(Definition definition) {
        var effect = BuiltInRegistries.MOB_EFFECT.getOptional(net.minecraft.resources.ResourceLocation.parse(definition.effectId())).orElse(null);
        if (effect == null) return null;
        return runtime(effect).entrySet().stream().filter(entry -> entry.getValue().equals(definition)).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    static Definition match(MobEffectInstance effect, Holder<Attribute> attribute, AttributeModifier modifier) {
        var definition = runtime(effect.getEffect().value()).get(attribute);
        return definition != null && modifier.id().toString().equals(definition.modifierId()) && modifier.operation().name().equals(definition.operation())
                ? definition : null;
    }

    private static Map<Holder<Attribute>, Definition> runtime(MobEffect effect) {
        return RUNTIME.computeIfAbsent(effect, value -> {
            var id = BuiltInRegistries.MOB_EFFECT.getKey(value);
            if (id == null) return Map.of();
            Map<Holder<Attribute>, Definition> result = new LinkedHashMap<>();
            Set<Holder<Attribute>> conflicts = new HashSet<>();
            for (var definition : forEffect(id.toString())) {
                try {
                    Class<?> owner = Class.forName(definition.attributeOwner().replace('/', '.'), false, value.getClass().getClassLoader());
                    Object field = owner.getField(definition.attributeField()).get(null);
                    if (!(field instanceof Holder<?> holder) || !(holder.value() instanceof Attribute attribute)
                            || BuiltInRegistries.ATTRIBUTE.getKey(attribute) == null) {
                        FAILURES.putIfAbsent(definition.numeric().dispatchKey(), "实际属性Holder没有注册的属性ID");
                        continue;
                    }
                    int[] matches = {0};
                    int[] layers = {0};
                    value.createModifiers(0, (actual, modifier) -> {
                        if (actual.value() != attribute) return;
                        layers[0]++;
                        if (!modifier.id().toString().equals(definition.modifierId())
                                || !modifier.operation().name().equals(definition.operation()) || !Double.isFinite(modifier.amount())) return;
                        matches[0]++;
                        if (result.putIfAbsent(actual, definition) != null) conflicts.add(actual);
                    });
                    if (matches[0] != 1 || layers[0] != 1) {
                        result.keySet().stream().filter(actual -> actual.value() == attribute).forEach(conflicts::add);
                        FAILURES.putIfAbsent(definition.numeric().dispatchKey(), "实际属性模板缺失、重复或modifier ID/operation不符");
                    }
                } catch (ReflectiveOperationException | LinkageError failure) {
                    FAILURES.putIfAbsent(definition.numeric().dispatchKey(), "实际属性模板不可用：" + failure);
                }
            }
            conflicts.forEach(attribute -> {
                var definition = result.remove(attribute);
                if (definition != null) FAILURES.putIfAbsent(definition.numeric().dispatchKey(), "同一属性存在多个不独立的模板入口");
            });
            return Map.copyOf(result);
        });
    }
}
