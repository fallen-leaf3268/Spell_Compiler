package com.example.spell_compiler.formula;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class FormulaManaRatioBridge {
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String INSTANCE = "net/minecraft/world/effect/MobEffectInstance";
    private static final String MAGIC = "io/redspace/ironsspellbooks/api/magic/MagicData";
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaConsumerHooks";
    private static final String CAST = "(Lnet/minecraft/world/level/Level;IL" + LIVING
            + ";Lio/redspace/ironsspellbooks/api/spells/CastSource;L" + MAGIC + ";)V";
    private static final String EVENT = "(Lnet/minecraftforge/event/entity/living/LivingEntityUseItemEvent$Finish;)V";
    record Plan(String spell, FormulaExecutionGraph.EffectLink effect, FormulaExecutionGraph.MethodId helper,
                FormulaExecutionGraph.MethodId cast, FormulaExecutionGraph.MethodId event, FormulaConsumerSites.Definition definition) {}
    record Product(AbstractInsnNode point, AbstractInsnNode nutrition, int effectSlot, int holderSlot) {}
    private record Plans(Map<String, Plan> bySpell, List<FormulaConsumerSites.Definition> definitions,
                         Map<String, List<FormulaConsumerSites.Definition>> definitionsBySpell) {
        static Plans of(Map<String, Plan> bySpell) {
            var stable = Map.copyOf(bySpell);
            Map<String, List<FormulaConsumerSites.Definition>> definitions = new LinkedHashMap<>();
            stable.forEach((spell, plan) -> definitions.put(spell.replace('/', '.'), List.of(plan.definition())));
            return new Plans(stable, stable.values().stream().map(Plan::definition).toList(), Map.copyOf(definitions));
        }
    }
    private static volatile Plans plans = Plans.of(Map.of());
    private static volatile FormulaClassRepository sourceRepository;
    private static final Map<String, String> failures = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> getterAliases = new ConcurrentHashMap<>();

    private FormulaManaRatioBridge() {}

    static boolean hasVerifiedControl(FormulaClassRepository repository, String spell, String alias) {
        String owner = spell.replace('/', '.');
        if (failures.containsKey(owner)) return false;
        var plan = inspect(repository, owner);
        return plan != null && plan.definition().alias().equals(alias);
    }

    static void registerGetters(FormulaClassRepository repository, String spell, com.google.gson.JsonArray formulas) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (var raw : formulas) {
            var formula = raw.getAsJsonObject();
            if (formula.get("declaringClass").getAsString().replace('.', '/').equals(spell.replace('.', '/')))
                aliases.put(formula.get("name").getAsString() + formula.get("descriptor").getAsString(), formula.get("alias").getAsString());
        }
        getterAliases.put(spell.replace('/', '.'), Map.copyOf(aliases));
    }

    static List<FormulaConsumerSites.Definition> definitions(String spell) {
        return plans.definitionsBySpell().getOrDefault(spell.replace('/', '.'), List.of());
    }

    static List<FormulaConsumerSites.Definition> allDefinitions() { return plans.definitions(); }

    static boolean enabled(String spell) { return plans.bySpell().containsKey(spell.replace('/', '.')) && !failures.containsKey(spell.replace('/', '.')); }

    static void initialize(FormulaClassRepository repository) {
        failures.clear();
        sourceRepository = repository;
        Map<String, Plan> selected = new LinkedHashMap<>();
        plans = Plans.of(Map.of());
        for (var owner : repository.sourceClasses()) {
            if ((owner.access & Opcodes.ACC_ABSTRACT) != 0 || !repository.isSubtype(owner.name, "io/redspace/ironsspellbooks/api/spells/AbstractSpell")) continue;
            var plan = inspect(repository, owner.name);
            if (plan != null && !(repository.baselineCoverage() && FormulaConsumerSites.fixedDefinitions(owner.name).contains(plan.definition())))
                selected.put(plan.spell(), plan);
        }
        plans = Plans.of(selected);
    }

    static Set<String> targetClasses() {
        return plans.bySpell().values().stream().flatMap(plan -> java.util.stream.Stream.of(plan.cast().owner(), plan.event().owner()))
                .map(owner -> owner.replace('/', '.')).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static Plan inspect(FormulaClassRepository repository, String spell) {
        String owner = spell.replace('.', '/');
        var source = repository.classNode(owner);
        if (source == null || !repository.isSubtype(owner, "io/redspace/ironsspellbooks/api/spells/AbstractSpell")) return null;
        List<Plan> candidates = new ArrayList<>();
        for (var getter : source.methods) {
        if ((getter.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || !Set.of("(I)F", "(I)D", "(IL" + LIVING + ";)F", "(IL" + LIVING + ";)D").contains(getter.desc)) continue;
        var aliases = getterAliases.get(spell.replace('/', '.'));
        if (aliases != null && !aliases.containsKey(getter.name + getter.desc)) continue;
        if (!AutomaticFormulaExtractor.analyze(source, getter, repository).supported()) continue;
        var getterFlow = FormulaDataFlow.analyze(repository, owner, getter);
        if (!getterFlow.successful()) continue;
        var returns = getterFlow.instructions().stream().filter(value -> value.instruction().getOpcode() == Opcodes.FRETURN
                || value.instruction().getOpcode() == Opcodes.DRETURN).toList();
        if (returns.size() != 1 || returns.get(0).operands().size() != 1) continue;
        var result = getterFlow.registryOrigin(returns.get(0).operands().get(0));
        if (result != null && result.instruction().getOpcode() == Opcodes.F2D && getterFlow.dependencies(result).size() == 1)
            result = getterFlow.registryOrigin(getterFlow.dependencies(result).get(0));
        if (result == null || !(result.instruction() instanceof MethodInsnNode ratio) || ratio.getOpcode() != Opcodes.INVOKESTATIC
                || !ratio.desc.equals("(I)F")) continue;
        var helper = repository.resolveMethod(ratio.owner, ratio.name, ratio.desc);
        if (helper == null) continue;
        var helperId = new FormulaExecutionGraph.MethodId(helper.owner().name, helper.method().name, helper.method().desc);
        String alias = aliases == null ? fallbackAlias(getter.name) : aliases.get(getter.name + getter.desc);
        if (!alias.matches("[a-z][a-z0-9_]{0,63}")) continue;
        for (var effect : FormulaExecutionGraph.analyze(repository, owner).effects()) {
            if (!effect.ownershipComplete() || !effect.spellClasses().equals(Set.of(owner))
                    || !effect.relatedMethods().contains(helperId)) continue;
            var casts = effect.applications().stream().filter(method -> method.owner().equals(owner)
                    && method.name().equals("onCast") && method.descriptor().equals(CAST)).toList();
            if (casts.size() != 1) continue;
            var cast = repository.resolveMethod(owner, casts.get(0).name(), CAST);
            if (cast == null || publication(repository, cast.owner().name, cast.method(), effect) == null) continue;
            List<FormulaExecutionGraph.MethodId> events = new ArrayList<>();
            for (var event : effect.eventMethods()) {
                if (!event.descriptor().equals(EVENT)) continue;
                var method = repository.resolveMethod(event.owner(), event.name(), event.descriptor());
                if (method != null && product(repository, method.owner().name, method.method(), effect, helperId) != null) events.add(event);
            }
            String effectId = FormulaEffectAttributeControls.effectId(repository, effect);
            if (events.size() == 1 && effectId != null) candidates.add(new Plan(owner.replace('/', '.'), effect, helperId, casts.get(0), events.get(0),
                    new FormulaConsumerSites.Definition(owner.replace('/', '.'), effectId, alias, getter.name, getter.desc)));
        }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static String fallbackAlias(String method) {
        String name = method.startsWith("get") && method.length() > 3 ? method.substring(3) : method;
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static MethodInsnNode publication(FormulaClassRepository repository, String owner, MethodNode method,
                                              FormulaExecutionGraph.EffectLink effect) {
        if ((method.access & Opcodes.ACC_STATIC) != 0 || !FormulaAutomaticInjector.contextIntact(method, 0)
                || !FormulaAutomaticInjector.contextIntact(method, 2) || !FormulaAutomaticInjector.contextIntact(method, 3)) return null;
        var flow = FormulaDataFlow.analyze(repository, owner, method);
        if (!flow.successful()) return null;
        List<MethodInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode add) || !FormulaMinecraftNames.method(add.name).equals("addEffect")
                    || !add.desc.equals("(L" + INSTANCE + ";)Z") || !repository.isSubtype(add.owner, LIVING)
                    || !flow.parameter(flow.receiver(add), 3)) continue;
            var allocation = flow.registryOrigin(flow.arguments(add).get(0));
            if (allocation == null || !(allocation.instruction() instanceof TypeInsnNode create)
                    || create.getOpcode() != Opcodes.NEW || !create.desc.equals(INSTANCE)) continue;
            var constructors = flow.instructions().stream().filter(value -> value.instruction() instanceof MethodInsnNode call
                    && call.owner.equals(INSTANCE) && call.name.equals("<init>") && !flow.arguments(call).isEmpty()
                    && allocation.equals(flow.registryOrigin(flow.receiver(call))) && sameHolder(flow, flow.arguments(call).get(0), effect)
                    && flow.dominates(call, add)).toList();
            if (constructors.size() == 1) result.add(add);
        }
        return result.size() == 1 ? result.get(0) : null;
    }

    static Product product(FormulaClassRepository repository, String owner, MethodNode method,
                           FormulaExecutionGraph.EffectLink effect, FormulaExecutionGraph.MethodId helper) {
        return product(repository, owner, method, effect, helper, null, "");
    }

    private static Product product(FormulaClassRepository repository, String owner, MethodNode method,
                                   FormulaExecutionGraph.EffectLink effect, FormulaExecutionGraph.MethodId helper,
                                   MethodInsnNode override, String alias) {
        if ((method.access & Opcodes.ACC_STATIC) == 0 || !method.desc.equals(EVENT)) return null;
        var flow = FormulaDataFlow.analyze(repository, owner, method);
        if (!flow.successful()) return null;
        List<Product> result = new ArrayList<>();
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode add) || !add.owner.equals(MAGIC) || !add.name.equals("addMana")
                    || !add.desc.equals("(F)V") || add.getOpcode() == Opcodes.INVOKESTATIC) continue;
            var magicSource = flow.registryOrigin(flow.receiver(add));
            if (magicSource == null || !(magicSource.instruction() instanceof MethodInsnNode magic)
                    || !magic.owner.equals(MAGIC) || !magic.name.equals("getPlayerMagicData")
                    || !magic.desc.equals("(L" + LIVING + ";)L" + MAGIC + ";")) continue;
            var holder = flow.registryOrigin(flow.arguments(magic).get(0));
            if (holder == null || !(holder.instruction() instanceof MethodInsnNode entity) || !entity.name.equals("getEntity")
                    || !entity.desc.equals("()L" + LIVING + ";") || !flow.parameter(flow.receiver(entity), 0)) continue;
            var amount = flow.registryOrigin(flow.arguments(add).get(0));
            if (override != null) {
                if (!flow.dominates(override, add) || !depends(flow, flow.arguments(add).get(0), override, new HashSet<>())) continue;
                amount = flow.registryOrigin(flow.arguments(override).get(0));
            }
            if (amount == null || amount.instruction() == null || amount.instruction().getOpcode() != Opcodes.FMUL) continue;
            var factors = flow.dependencies(amount);
            if (factors.size() != 2) continue;
            for (int side = 0; side < 2; side++) {
                var converted = flow.registryOrigin(factors.get(side));
                if (converted == null || converted.instruction() == null || converted.instruction().getOpcode() != Opcodes.I2F) continue;
                var conversion = flow.dependencies(converted);
                var food = conversion.size() == 1 ? flow.registryOrigin(conversion.get(0)) : null;
                if (food == null || !(food.instruction() instanceof MethodInsnNode nutrition)
                        || !nutrition.owner.equals("net/minecraft/world/food/FoodProperties")
                        || !Set.of("getNutrition", "m_38744_").contains(FormulaMinecraftNames.method(nutrition.name)) || !nutrition.desc.equals("()I")) continue;
                var ratioNode = flow.registryOrigin(factors.get(1 - side));
                if (ratioNode == null || !(ratioNode.instruction() instanceof MethodInsnNode ratio)
                        || !ratio.owner.equals(helper.owner()) || !ratio.name.equals(helper.name()) || !ratio.desc.equals(helper.descriptor())) continue;
                var amplifierNode = flow.registryOrigin(flow.arguments(ratio).get(0));
                if (amplifierNode == null || !(amplifierNode.instruction() instanceof MethodInsnNode amplifier)
                        || !amplifier.owner.equals(INSTANCE) || !FormulaMinecraftNames.method(amplifier.name).equals("getAmplifier") || !amplifier.desc.equals("()I")) continue;
                var instance = flow.registryOrigin(flow.receiver(amplifier));
                if (instance == null || !(instance.instruction() instanceof MethodInsnNode get) || !FormulaMinecraftNames.method(get.name).equals("getEffect")
                        || !get.desc.equals("(Lnet/minecraft/world/effect/MobEffect;)L" + INSTANCE + ";") || !repository.isSubtype(get.owner, LIVING)
                        || !sameHolder(flow, flow.arguments(get).get(0), effect) || !holder.equals(flow.registryOrigin(flow.receiver(get)))
                        || !flow.dominates(converted.instruction(), amount.instruction()) || !flow.dominates(get, amount.instruction())) continue;
                var frame = flow.frameBefore(amount.instruction());
                int effectSlot = local(flow, frame, instance), holderSlot = local(flow, frame, holder);
                if (override != null) {
                    var arguments = flow.arguments(override);
                    if (arguments.size() != 7 || !instance.equals(flow.registryOrigin(arguments.get(1)))
                            || !literal(flow, arguments.get(2), alias) || !literal(flow, arguments.get(3), 1f)
                            || !converted.equals(flow.registryOrigin(arguments.get(4))) || !literal(flow, arguments.get(5), 0)
                            || !holder.equals(flow.registryOrigin(arguments.get(6)))) continue;
                }
                if (effectSlot >= 0 && holderSlot >= 0) result.add(new Product(amount.instruction(), converted.instruction(), effectSlot, holderSlot));
            }
        }
        return result.size() == 1 ? result.get(0) : null;
    }

    private static boolean sameHolder(FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value, FormulaExecutionGraph.EffectLink effect) {
        var origin = flow.registryOrigin(value);
        return origin != null && origin.instruction() instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(effect.registryOwner()) && field.name.equals(effect.registryField());
    }

    private static int local(FormulaDataFlow.Analysis flow, FormulaDataFlow.FrameState frame, FormulaDataFlow.Node value) {
        if (frame == null) return -1;
        for (int index = 0; index < frame.locals().size(); index++) if (value.equals(flow.registryOrigin(frame.locals().get(index)))) return index;
        return -1;
    }

    static void apply(String owner, ClassNode target, FormulaClassRepository repository) {
        for (Plan plan : plans.bySpell().values()) {
            boolean capture = plan.cast().owner().equals(target.name);
            if (!capture && !plan.event().owner().equals(target.name)) continue;
            var id = capture ? plan.cast() : plan.event();
            var original = target.methods.stream().filter(method -> method.name.equals(id.name()) && method.desc.equals(id.descriptor()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("回魔消费方法丢失：" + id.key()));
            MethodNode method = new MethodNode(Opcodes.ASM9, original.access, original.name, original.desc, original.signature,
                    original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
            original.accept(method);
            InsnList hook = new InsnList();
            if (capture) {
                var point = publication(repository, target.name, method, plan.effect());
                if (point == null) throw new IllegalStateException("回魔效果发布结构变化：" + id.key());
                hook.add(new InsnNode(Opcodes.DUP));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new VarInsnNode(Opcodes.ILOAD, 2));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "captureMana", "(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;)V", false));
                method.instructions.insertBefore(point, hook);
            } else {
                var point = product(repository, target.name, method, plan.effect(), plan.helper());
                if (point == null) throw new IllegalStateException("回魔乘积消费结构变化：" + id.key());
                int nutrition = method.maxLocals++;
                InsnList input = new InsnList();
                input.add(new InsnNode(Opcodes.DUP));
                input.add(new VarInsnNode(Opcodes.FSTORE, nutrition));
                method.instructions.insert(point.nutrition(), input);
                hook.add(new VarInsnNode(Opcodes.ALOAD, point.effectSlot()));
                hook.add(new LdcInsnNode(plan.definition().alias()));
                hook.add(new InsnNode(Opcodes.FCONST_1));
                hook.add(new VarInsnNode(Opcodes.FLOAD, nutrition));
                hook.add(new InsnNode(Opcodes.ICONST_0));
                hook.add(new VarInsnNode(Opcodes.ALOAD, point.holderSlot()));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "overrideProduct", "(FLjava/lang/Object;Ljava/lang/String;FFILjava/lang/Object;)F", false));
                method.instructions.insert(point.point(), hook);
            }
            method.maxStack += 8;
            ClassNode copy = new ClassNode();
            target.accept(copy);
            copy.methods.set(target.methods.indexOf(original), method);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected String getCommonSuperClass(String first, String second) { return repository.commonSuperClass(first, second); }
            };
            copy.accept(writer);
            ClassNode framed = new ClassNode();
            new ClassReader(writer.toByteArray()).accept(framed, ClassReader.EXPAND_FRAMES);
            target.methods = framed.methods;
        }
    }

    static void failed(String owner, String reason) {
        for (Plan plan : plans.bySpell().values()) if (plan.cast().owner().equals(owner.replace('.', '/')) || plan.event().owner().equals(owner.replace('.', '/')))
            failures.put(plan.spell(), reason);
    }

    static void verify(String owner, ClassNode target) {
        for (Plan plan : plans.bySpell().values()) {
            boolean capture = plan.cast().owner().equals(target.name);
            if (!capture && !plan.event().owner().equals(target.name)) continue;
            var id = capture ? plan.cast() : plan.event();
            var methods = target.methods.stream().filter(method -> method.name.equals(id.name()) && method.desc.equals(id.descriptor())).toList();
            if (methods.size() != 1 || !retained(sourceRepository, target.name, methods.get(0), plan, capture))
                failed(owner, "回魔效果消费挂钩未保留：" + id.key());
        }
    }

    private static boolean retained(FormulaClassRepository repository, String owner, MethodNode method, Plan plan, boolean capture) {
        if (repository == null) return false;
        var hooks = java.util.Arrays.stream(method.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(HOOKS) && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.name.equals(capture ? "captureMana" : "overrideProduct")
                && call.desc.equals(capture ? "(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;)V"
                : "(FLjava/lang/Object;Ljava/lang/String;FFILjava/lang/Object;)F")).map(instruction -> (MethodInsnNode) instruction).toList();
        if (hooks.size() != 1) return false;
        var hook = hooks.get(0);
        if (!capture) return product(repository, owner, method, plan.effect(), plan.helper(), hook, plan.definition().alias()) != null;
        var publication = publication(repository, owner, method, plan.effect());
        if (publication == null) return false;
        var flow = FormulaDataFlow.analyze(repository, owner, method);
        var arguments = flow.arguments(hook);
        return arguments.size() == 4 && flow.dominates(hook, publication)
                && java.util.Objects.equals(flow.registryOrigin(arguments.get(0)), flow.registryOrigin(flow.arguments(publication).get(0)))
                && flow.parameter(arguments.get(1), 0) && flow.parameter(arguments.get(2), 2) && flow.parameter(arguments.get(3), 3);
    }

    private static boolean literal(FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value, Object expected) {
        var origin = flow.registryOrigin(value);
        if (origin == null || origin.instruction() == null) return false;
        var instruction = origin.instruction();
        if (instruction instanceof LdcInsnNode constant) return expected.equals(constant.cst);
        return expected.equals(0) && instruction.getOpcode() == Opcodes.ICONST_0
                || expected.equals(1f) && instruction.getOpcode() == Opcodes.FCONST_1;
    }

    private static boolean depends(FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value, MethodInsnNode hook,
                                   Set<FormulaDataFlow.Node> path) {
        if (value.sources().isEmpty()) return false;
        for (var node : value.sources()) {
            if (node.instruction() == hook) continue;
            if (node.instruction() == null || node.instruction() instanceof MethodInsnNode || !path.add(node)) return false;
            try {
                boolean found = false;
                for (var input : flow.dependencies(node)) if (depends(flow, input, hook, path)) { found = true; break; }
                if (!found) return false;
            } finally { path.remove(node); }
        }
        return true;
    }

    static Map<String, String> diagnostics() { return Map.copyOf(failures); }
}
