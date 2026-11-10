package com.example.spell_compiler.formula;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class FormulaDamageTooltips {
    private static final String OWNER = "com/example/spell_compiler/formula/FormulaDamageTooltips";
    private static final String INFO = "(ILnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;";
    private static final Set<String> DAMAGE_LABELS = Set.of("ui.irons_spellbooks.damage", "ui.irons_spellbooks.base_damage",
            "ui.irons_spellbooks.percent_damage", "ui.irons_spellbooks.impact_damage", "ui.irons_spellbooks.aoe_damage",
            "ui.irons_spellbooks.additional_poisoned_damage", "ui.irons_spellbooks.reduced_damage");
    private static final Set<String> PREVIEW_VARIABLES = Set.of("level", "spell_power", "school_spell_power", "power_multiplier");
    private static final Set<String> ATTRIBUTE_MODIFIER_LABELS = Set.of("attribute.modifier.plus.0", "attribute.modifier.plus.1",
            "attribute.modifier.plus.2", "attribute.modifier.take.0", "attribute.modifier.take.1", "attribute.modifier.take.2");
    private record PreviewPlan(String dispatch, boolean contextual) {}
    private record AttributeIds(net.minecraft.resources.ResourceLocation source, net.minecraft.resources.ResourceLocation target) {}
    private record PreviewPlans(SpellFormulaFiles.SpellOverride override, Map<String, PreviewPlan> bindings,
                                Map<String, AttributeIds> attributes) {}
    private static final class PreviewCache {
        private PreviewPlans current;
        private PreviewPlans previous;

        private PreviewPlans select(SpellFormulaFiles.SpellOverride override) {
            if (current == null || current.override() != override) {
                var selected = previous != null && previous.override() == override ? previous : new PreviewPlans(override, new HashMap<>(), new HashMap<>());
                previous = current;
                current = selected;
            }
            return current;
        }

        synchronized PreviewPlan get(SpellFormulaFiles.SpellOverride override, String key) {
            return select(override).bindings().computeIfAbsent(key, binding -> plan(override, binding));
        }

        synchronized AttributeIds attributeIds(SpellFormulaFiles.SpellOverride override, FormulaAttributes.Component component) {
            return select(override).attributes().computeIfAbsent(component.key(), key -> new AttributeIds(
                    new net.minecraft.resources.ResourceLocation(component.sourceAttribute()),
                    new net.minecraft.resources.ResourceLocation(override.attributes().get(key).targetAttribute())));
        }
    }
    private static final ClassValue<PreviewCache> PREVIEWS = new ClassValue<>() {
        @Override protected PreviewCache computeValue(Class<?> type) { return new PreviewCache(); }
    };
    private record Provenance(Set<String> components, boolean complete) {}
    record AttributePreview(String description, MutableComponent component) {}

    private FormulaDamageTooltips() {}

    private static PreviewPlan plan(SpellFormulaFiles.SpellOverride override, String key) {
        String expression = override.formulas().get(key);
        for (var component : override.spell().attributeComponents()) {
            var config = override.attributes().get(component.key());
            if (config != null && component.binding().equals(key)) {
                expression = config.formula();
                break;
            }
        }
        var binding = override.spell().formulas().stream().filter(method -> method.key().equals(key)).findFirst().orElseThrow();
        Map<String, Type> variables = new LinkedHashMap<>();
        binding.variables().forEach((name, type) -> variables.put(name, Type.getType(type)));
        String resolved = ReadableFormula.resolve(binding.strictExpression(), expression, variables).expression();
        boolean contextual = variables.keySet().stream().anyMatch(variable -> !PREVIEW_VARIABLES.contains(variable)
                && Pattern.compile("\\b" + Pattern.quote(variable) + "\\b").matcher(resolved).find());
        return new PreviewPlan(FormulaRuntimeDispatcher.key(override.spell().className(), key), contextual);
    }

    static boolean requiresContext(Class<?> spellType, SpellFormulaFiles.SpellOverride override, String key) {
        return PREVIEWS.get(spellType).get(override, key).contextual();
    }

    static void apply(ClassNode target, SpellFormulaCatalog.Spell spell, FormulaClassRepository repository) {
        FormulaTooltipFormatting.apply(target, repository);
        if (spell == null || spell.attributeComponents().isEmpty() && spell.damageComponents().isEmpty() && spell.formulas().stream()
                .noneMatch(binding -> binding.consumptionFingerprint().startsWith("EFFECT_ATTRIBUTE:"))) return;
        var original = target.methods.stream().filter(method -> method.name.equals("getUniqueInfo") && method.desc.equals(INFO)
                && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0).findFirst().orElse(null);
        boolean inherited = original == null;
        if (inherited) {
            var parent = repository.resolveMethod(target.superName, "getUniqueInfo", INFO);
            if (parent == null || (parent.method().access & Opcodes.ACC_PUBLIC) == 0
                    || (parent.method().access & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
                throw new IllegalArgumentException("无法安全覆写继承的法术说明：" + target.name);
            original = new MethodNode(Opcodes.ACC_PUBLIC, "getUniqueInfo", INFO, null, null);
            original.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            original.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            original.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            original.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, target.superName, "getUniqueInfo", INFO, false));
            original.instructions.add(new InsnNode(Opcodes.ARETURN));
            original.maxLocals = 3;
            original.maxStack = 3;
        }
        if (!FormulaAutomaticInjector.contextIntact(original, 0) || !FormulaAutomaticInjector.contextIntact(original, 1)
                || !FormulaAutomaticInjector.contextIntact(original, 2)) return;
        for (var instruction : original.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(OWNER)) return;
        MethodNode method = new MethodNode(Opcodes.ASM9, original.access, original.name, original.desc, original.signature,
                original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        original.accept(method);
        Map<MethodInsnNode, Set<String>> rows = rows(repository, target.name, method, spell);
        int local = method.maxLocals++;
        InsnList enter = new InsnList();
        enter.add(new VarInsnNode(Opcodes.ALOAD, 0));
        enter.add(new VarInsnNode(Opcodes.ALOAD, 2));
        enter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "begin", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;", false));
        enter.add(new VarInsnNode(Opcodes.ASTORE, local));
        FormulaCastContext.insertAtBodyStart(method, enter);
        rows.forEach((call, components) -> {
            InsnList mark = new InsnList();
            for (String component : components) {
                mark.add(new VarInsnNode(Opcodes.ALOAD, local));
                mark.add(new LdcInsnNode(component));
                mark.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "mark", "(Ljava/lang/Object;Ljava/util/Map;Ljava/lang/String;)Ljava/lang/Object;", false));
                mark.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getReturnType(call.desc).getInternalName()));
            }
            method.instructions.insert(call, mark);
        });
        for (var instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.ARETURN) {
            InsnList exit = new InsnList();
            exit.add(new VarInsnNode(Opcodes.ALOAD, local));
            exit.add(new VarInsnNode(Opcodes.ALOAD, 0));
            exit.add(new VarInsnNode(Opcodes.ILOAD, 1));
            exit.add(new VarInsnNode(Opcodes.ALOAD, 2));
            exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "finish", "(Ljava/util/List;Ljava/util/Map;Ljava/lang/Object;ILjava/lang/Object;)Ljava/util/List;", false));
            FormulaCastContext.insertBeforeReturnCleanup(method, instruction, exit);
        }
        method.maxStack += 8;
        ClassNode copy = new ClassNode();
        target.accept(copy);
        if (inherited) copy.methods.add(method);
        else copy.methods.set(target.methods.indexOf(original), method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String first, String second) {
                return repository.commonSuperClass(first, second);
            }
        };
        copy.accept(writer);
        ClassNode framed = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(framed, ClassReader.EXPAND_FRAMES);
        target.methods = framed.methods;
    }

    static Map<MethodInsnNode, Set<String>> rows(FormulaClassRepository repository, String owner, MethodNode method,
                                           SpellFormulaCatalog.Spell spell) {
        var flow = FormulaDataFlow.analyze(repository, owner, method);
        if (!flow.successful()) return Map.of();
        Map<String, Set<String>> previews = new LinkedHashMap<>();
        for (var component : spell.damageComponents()) for (String key : component.previewMethods()) {
            if (key.contains("#")) {
                int split = key.indexOf('#');
                previews.computeIfAbsent(key.substring(0, split).replace('.', '/') + key.substring(split),
                        ignored -> new LinkedHashSet<>()).add(component.id());
                continue;
            }
            var binding = spell.formulas().stream().filter(value -> value.key().equals(key)).findFirst().orElse(null);
            if (binding != null) previews.computeIfAbsent(binding.declaringClass().replace('.', '/') + '#' + key,
                    ignored -> new LinkedHashSet<>()).add(component.id());
        }
        var candidates = flow.instructions().stream().filter(instruction -> instruction.instruction() instanceof MethodInsnNode call
                && call.owner.equals("net/minecraft/network/chat/Component") && call.name.equals("translatable")
                && call.desc.equals("(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;"))
                .map(instruction -> (MethodInsnNode) instruction.instruction()).toList();
        long damageRows = candidates.stream().filter(call -> damageLabel(flow, call)).count();
        if (spell.damageComponents().size() == 1 && damageRows == 1) for (var binding : spell.formulas()) {
            if (binding.editable()) continue;
            var metadata = new com.google.gson.JsonObject();
            metadata.addProperty("declaringClass", binding.declaringClass());
            metadata.addProperty("name", binding.name());
            metadata.addProperty("descriptor", binding.descriptor());
            if (FormulaDisplayControls.onlyDescription(repository, owner, metadata))
                previews.computeIfAbsent(binding.declaringClass().replace('.', '/') + '#' + binding.key(), ignored -> new LinkedHashSet<>())
                        .add(spell.damageComponents().get(0).id());
        }
        Map<MethodInsnNode, Set<String>> result = new IdentityHashMap<>();
        Map<FormulaDataFlow.Node, List<FormulaDataFlow.Instruction>> arrayWrites = null;
        for (var call : candidates) {
            var arguments = flow.arguments(call);
            var label = flow.origin(arguments.get(0));
            if (label == null || !(label.instruction() instanceof LdcInsnNode constant)
                    || !(constant.cst instanceof String key) || !DAMAGE_LABELS.contains(key)) continue;
            var array = flow.origin(arguments.get(1));
            if (array == null || !(array.instruction() instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.ANEWARRAY) continue;
            var dimensions = flow.dependencies(array);
            var length = dimensions.size() == 1 ? flow.origin(dimensions.get(0)) : null;
            if (length == null || length.instruction() == null || length.instruction().getOpcode() != Opcodes.ICONST_1) continue;
            if (arrayWrites == null) {
                arrayWrites = new HashMap<>();
                for (var write : flow.instructions()) {
                    if (write.instruction().getOpcode() != Opcodes.AASTORE || write.operands().size() != 3) continue;
                    var target = flow.origin(write.operands().get(0));
                    if (target != null) arrayWrites.computeIfAbsent(target, ignored -> new ArrayList<>()).add(write);
                }
            }
            var writes = arrayWrites.getOrDefault(array, List.of());
            if (writes.size() != 1 || !flow.dominates(writes.get(0).instruction(), call)) continue;
            var proof = provenance(repository, flow, writes.get(0).operands().get(2), previews, new HashSet<>(), 0);
            if (proof.complete() && !proof.components().isEmpty()) result.put(call, Set.copyOf(proof.components()));
        }
        return result;
    }

    private static boolean damageLabel(FormulaDataFlow.Analysis flow, MethodInsnNode call) {
        var arguments = flow.arguments(call);
        if (arguments.isEmpty()) return false;
        var label = flow.origin(arguments.get(0));
        return label != null && label.instruction() instanceof LdcInsnNode literal && literal.cst instanceof String key && DAMAGE_LABELS.contains(key);
    }

    private static Provenance provenance(FormulaClassRepository repository, FormulaDataFlow.Analysis flow,
                                          FormulaDataFlow.Value value, Map<String, Set<String>> previews,
                                          Set<FormulaDataFlow.Node> path, int depth) {
        if (depth > 128 || value.sources().size() != 1) return new Provenance(Set.of(), false);
        var node = value.sources().iterator().next();
        if (!path.add(node)) return new Provenance(Set.of(), false);
        try {
            boolean numeric = node.type() != null && node.type().getSort() >= Type.BOOLEAN && node.type().getSort() <= Type.DOUBLE;
            if (numeric && (node.kind() == FormulaDataFlow.NodeKind.PARAMETER || node.instruction() instanceof FieldInsnNode))
                return new Provenance(Set.of(), false);
            if (node.instruction() instanceof MethodInsnNode source) {
                var resolved = repository.resolveMethod(source.owner, source.name, source.desc);
                var known = previews.getOrDefault((resolved == null ? source.owner : resolved.owner().name) + '#'
                        + source.name + source.desc, Set.of());
                if (!known.isEmpty()) {
                    if (source.getOpcode() != Opcodes.INVOKESTATIC && !flow.parameter(flow.receiver(source), 0))
                        return new Provenance(Set.of(), false);
                    return new Provenance(known, true);
                }
            }
            Set<String> components = new LinkedHashSet<>();
            for (var input : flow.dependencies(node)) {
                var proof = provenance(repository, flow, input, previews, path, depth + 1);
                if (!proof.complete()) return proof;
                components.addAll(proof.components());
            }
            if (numeric && node.instruction() instanceof MethodInsnNode && components.isEmpty())
                return new Provenance(Set.of(), false);
            return new Provenance(components, true);
        } finally {
            path.remove(node);
        }
    }

    public static Map<Object, Set<String>> begin(Object spell, Object caster) {
        var override = FormulaNativeValues.override(spell, caster);
        if (override == null || FormulaNativeValues.isEvaluatingOriginal()) return null;
        for (var component : override.spell().attributeComponents())
            if (override.attributes().containsKey(component.key())
                    && FormulaRuntimeDispatcher.isActive(FormulaRuntimeDispatcher.key(override.spell().className(), component.binding()), caster))
                return new IdentityHashMap<>();
        for (var component : override.spell().damageComponents()) for (String key : component.terminalBindings())
            if (override.formulas().containsKey(key)
                    && FormulaRuntimeDispatcher.isActive(FormulaRuntimeDispatcher.key(override.spell().className(), key), caster))
                return new IdentityHashMap<>();
        for (var binding : override.spell().formulas())
            if (binding.consumptionFingerprint().startsWith("EFFECT_ATTRIBUTE:") && override.formulas().containsKey(binding.key())
                    && FormulaRuntimeDispatcher.isActive(FormulaRuntimeDispatcher.key(override.spell().className(), binding.key()), caster))
                return new IdentityHashMap<>();
        return null;
    }

    public static Object mark(Object value, Map<Object, Set<String>> rows, String component) {
        if (rows != null) rows.computeIfAbsent(value, ignored -> new LinkedHashSet<>()).add(component);
        return value;
    }

    public static List<MutableComponent> finish(List<MutableComponent> original, Map<Object, Set<String>> rows,
                                               Object spell, int level, Object caster) {
        if (rows == null) return original;
        var override = FormulaNativeValues.override(spell, caster);
        if (override == null) return original;
        Map<String, MutableComponent> replacements = new LinkedHashMap<>();
        Map<String, SpellFormulaCatalog.DamageComponent> components = new LinkedHashMap<>();
        for (var component : override.spell().damageComponents()) {
            var preview = preview(override, component, spell, level, caster);
            if (preview != null) {
                replacements.put(component.id(), preview);
                components.put(component.id(), component);
            }
        }
        if (replacements.isEmpty()) return finishAttributes(original, override, spell, level, caster);
        List<MutableComponent> result = new ArrayList<>();
        Set<String> displayed = new HashSet<>();
        for (var row : original) {
            Set<String> associated = rows.get(row);
            if (associated != null && associated.size() > 1) {
                if (!replacements.keySet().containsAll(associated)) result.add(row);
                continue;
            }
            String id = associated == null || associated.isEmpty() ? null : associated.iterator().next();
            var replacement = replacements.get(id);
            if (replacement == null) result.add(row);
            else if (displayed.add(id)) {
                var selected = replacement.copy().setStyle(row.getStyle());
                if (override.spell().damageComponents().size() > 1 && row.getContents() instanceof TranslatableContents content
                        && Set.of("ui.irons_spellbooks.impact_damage", "ui.irons_spellbooks.aoe_damage").contains(content.getKey())
                        && selected.getContents() instanceof TranslatableContents translated
                        && translated.getKey().equals("ui.irons_spellbooks.damage"))
                    selected = Component.translatable(content.getKey(), translated.getArgs()).setStyle(row.getStyle());
                result.add(selected);
            }
        }
        for (var entry : replacements.entrySet()) if (displayed.add(entry.getKey())) {
            int number = override.spell().damageComponents().indexOf(components.get(entry.getKey())) + 1;
            result.add(override.spell().damageComponents().size() == 1 ? entry.getValue()
                    : Component.translatable("ui.spell_compiler.damage_component", number, entry.getValue()));
        }
        return finishAttributes(result, override, spell, level, caster);
    }

    private static List<MutableComponent> finishAttributes(List<MutableComponent> original, SpellFormulaFiles.SpellOverride override,
                                                           Object spell, int level, Object caster) {
        List<AttributePreview> previews = new ArrayList<>();
        for (var component : override.spell().attributeComponents()) {
            var config = override.attributes().get(component.key());
            if (config == null) continue;
            var ids = PREVIEWS.get(spell.getClass()).attributeIds(override, component);
            var source = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getOptional(ids.source()).orElse(null);
            var target = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getOptional(ids.target()).orElse(null);
            if (source == null || target == null) continue;
            var plan = PREVIEWS.get(spell.getClass()).get(override, component.binding());
            if (!FormulaRuntimeDispatcher.isActive(plan.dispatch(), caster)) continue;
            MutableComponent rendered = Component.translatable("ui.spell_compiler.attribute_on_cast",
                    Component.translatable(target.getDescriptionId()));
            if (!plan.contextual() && level > 0 && caster instanceof net.minecraft.world.entity.LivingEntity) {
                Number value = FormulaAttributeRuntime.evaluate(component, config, spell, level, caster, 0);
                if (value != null) rendered = attributeComponent(target, config.operation(), value);
            }
            previews.add(new AttributePreview(source.getDescriptionId(), rendered));
        }
        for (var definition : FormulaEffectAttributeControls.definitions(override.spell().className())) {
            if (override.spell().attributeComponents().stream().anyMatch(component -> component.binding().equals(definition.numeric().key())
                    && override.attributes().containsKey(component.key()))) continue;
            if (!override.formulas().containsKey(definition.numeric().key())
                    || !FormulaRuntimeDispatcher.isActive(definition.numeric().dispatchKey(), caster)) continue;
            var attribute = FormulaEffectAttributeControls.attribute(definition);
            if (attribute == null) continue;
            String description = attribute.getDescriptionId();
            var preview = attributePreview(override, definition, Component.translatable(description), spell, level, caster);
            if (preview != null) previews.add(new AttributePreview(description, preview));
        }
        return attributeRows(original, previews);
    }

    private static MutableComponent attributeComponent(net.minecraft.world.entity.ai.attributes.Attribute attribute,
                                                        FormulaAttributes.Operation operation, Number value) {
        String amount = FormulaNumberFormatting.magnitude(value);
        return Component.translatable("attribute.modifier." + (value.doubleValue() < 0 ? "take." : "plus.")
                + operation.nativeOperation().toValue(), amount, Component.translatable(attribute.getDescriptionId()));
    }

    static List<MutableComponent> attributeRows(List<MutableComponent> original, List<AttributePreview> previews) {
        if (previews.isEmpty()) return original;
        List<MutableComponent> result = new ArrayList<>();
        Set<String> displayed = new HashSet<>();
        for (var row : original) {
            AttributePreview selected = null;
            if (row.getContents() instanceof TranslatableContents contents
                    && ATTRIBUTE_MODIFIER_LABELS.contains(contents.getKey())
                    && contents.getArgs().length == 2 && contents.getArgs()[1] instanceof Component description
                    && description.getSiblings().isEmpty() && description.getContents() instanceof TranslatableContents label
                    && label.getArgs().length == 0)
                for (var preview : previews) if (preview.description().equals(label.getKey())) {
                    selected = preview;
                    break;
                }
            if (selected == null) result.add(row);
            else if (displayed.add(selected.description())) {
                var replacement = selected.component().copy().setStyle(row.getStyle());
                row.getSiblings().forEach(sibling -> replacement.append(sibling.copy()));
                result.add(replacement);
            }
        }
        for (var preview : previews) if (displayed.add(preview.description())) result.add(preview.component());
        return result;
    }

    static MutableComponent attributePreview(SpellFormulaFiles.SpellOverride override, FormulaEffectAttributeControls.Definition definition,
                                              Component description, Object spell, int level, Object caster) {
        String key = definition.numeric().key();
        String expression = override.formulas().get(key);
        if (expression == null || !FormulaRuntimeDispatcher.isActive(definition.numeric().dispatchKey(), caster)) return null;
        var deferred = Component.translatable("ui.spell_compiler.attribute_on_cast", description);
        if (level <= 0 || !(caster instanceof net.minecraft.world.entity.LivingEntity)) return deferred;
        var binding = override.spell().formulas().stream().filter(value -> value.key().equals(key)).findFirst().orElse(null);
        if (binding == null) return null;
        if (requiresContext(spell.getClass(), override, key)) return deferred;
        Number value = FormulaNumericHooks.evaluate(definition.numeric(), 0, spell, level, caster);
        if (value == null) return null;
        String operation = switch (definition.operation()) {
            case "ADDITION" -> "0";
            case "MULTIPLY_BASE" -> "1";
            case "MULTIPLY_TOTAL" -> "2";
            default -> throw new IllegalArgumentException("不支持的属性操作：" + definition.operation());
        };
        String amount = FormulaNumberFormatting.magnitude(value);
        return Component.translatable("attribute.modifier." + (value.doubleValue() < 0 ? "take." : "plus.") + operation, amount, description);
    }

    private static MutableComponent preview(SpellFormulaFiles.SpellOverride override, SpellFormulaCatalog.DamageComponent component,
                                            Object spell, int level, Object caster) {
        if (component.terminalBindings().size() != 1) return null;
        String key = component.terminalBindings().get(0);
        String expression = override.formulas().get(key);
        String dispatch = FormulaRuntimeDispatcher.key(override.spell().className(), key);
        if (expression == null || !FormulaRuntimeDispatcher.isActive(dispatch, caster)) return null;
        var site = FormulaAutomaticSites.find(dispatch);
        boolean adjustment = site != null && site.kind().name().equals("EVENT_DAMAGE");
        String deferred = adjustment ? "ui.spell_compiler.damage_adjustment_on_hit" : "ui.spell_compiler.damage_on_hit";
        if (level <= 0 || !(caster instanceof net.minecraft.world.entity.LivingEntity)) return Component.translatable(deferred);
        if (requiresContext(spell.getClass(), override, key)) return Component.translatable(deferred);
        var definition = FormulaNumericHooks.definition(dispatch);
        if (definition == null) return Component.translatable(deferred);
        Object[] inputs = site == null || site.inputs().isEmpty() ? null : new Object[site.inputs().size()];
        Number value = FormulaNumericHooks.evaluate(definition, 0, spell, level, caster, inputs);
        return value == null ? null : Component.translatable(adjustment ? "ui.spell_compiler.damage_adjustment" : "ui.irons_spellbooks.damage",
                FormulaNumberFormatting.format(value));
    }
}
