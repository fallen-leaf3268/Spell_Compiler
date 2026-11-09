package com.example.spell_compiler.formula;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.HashSet;
import java.util.Set;

final class FormulaAttributeBridgeProof {
    private static final String MODIFIER = "(Lnet/minecraft/world/entity/ai/attributes/Attribute;Ljava/lang/String;DLnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)Lnet/minecraft/world/effect/MobEffect;";
    private static final String COMPAT = "io/redspace/ironsspellbooks/api/backwards_compat/IBackwardsAttributeCompatMobEffect";
    private static final String COMPAT_MODIFIER = "(Lnet/minecraft/world/entity/ai/attributes/Attribute;Lnet/minecraft/resources/ResourceLocation;DLnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)L" + COMPAT + ";";
    private static final String COMPAT_SUPPLIER = "(Ljava/util/function/Supplier;Lnet/minecraft/resources/ResourceLocation;DLnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;)L" + COMPAT + ";";

    private FormulaAttributeBridgeProof() {}

    static boolean verified(FormulaClassRepository repository, String spellClassName, String alias) {
        var definition = FormulaConsumerSites.attributeDefinitions(spellClassName).stream()
                .filter(candidate -> candidate.alias().equals(alias)).findFirst().orElse(null);
        if (definition == null) return false;
        var graph = FormulaExecutionGraph.analyze(repository, spellClassName);
        return graph.effects().stream().anyMatch(effect -> effect.ownershipComplete()
                && effect.spellClasses().equals(Set.of(graph.spellClass())) && template(repository, effect, definition));
    }

    static boolean hasNativeModifiers(FormulaClassRepository repository, FormulaExecutionGraph.EffectLink effect) {
        var initialization = repository.resolveMethod(effect.registryOwner(), "<clinit>", "()V");
        if (initialization == null) return true;
        var flow = FormulaDataFlow.analyze(repository, initialization.owner().name, initialization.method());
        if (!flow.successful()) return true;
        for (var instruction : flow.instructions()) {
            if (!(instruction.instruction() instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !field.owner.equals(effect.registryOwner()) || !field.name.equals(effect.registryField())) continue;
            var assigned = flow.registryOrigin(instruction.field().value());
            if (assigned == null || !(assigned.instruction() instanceof MethodInsnNode register)) return true;
            var arguments = flow.arguments(register);
            if (arguments.size() != 2) return true;
            var supplier = flow.registryOrigin(arguments.get(1));
            if (supplier == null || !(supplier.instruction() instanceof InvokeDynamicInsnNode dynamic)) return true;
            var handles = java.util.Arrays.stream(dynamic.bsmArgs).filter(Handle.class::isInstance).map(Handle.class::cast).toList();
            if (handles.size() != 1) return true;
            var factory = handles.get(0);
            var resolved = repository.resolveMethod(factory.getOwner(), factory.getName(), factory.getDesc());
            if (resolved == null) return true;
            Set<String> classes = new HashSet<>();
            for (var operation : resolved.method().instructions) {
                if (operation instanceof MethodInsnNode call && attributeCall(repository, call)) return true;
                if (operation instanceof org.objectweb.asm.tree.TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                        && repository.isSubtype(allocation.desc, "net/minecraft/world/effect/MobEffect")) classes.add(allocation.desc);
            }
            if (classes.isEmpty()) return true;
            Set<String> visited = new HashSet<>();
            for (String created : classes) {
                String current = created;
                while (!current.equals("net/minecraft/world/effect/MobEffect") && visited.add(current)) {
                    var owner = repository.classNode(current);
                    if (owner == null) return true;
                    for (var method : owner.methods) for (var operation : method.instructions)
                        if (operation instanceof MethodInsnNode call && attributeCall(repository, call)) return true;
                    if (owner.superName == null) return true;
                    current = owner.superName;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean attributeCall(FormulaClassRepository repository, MethodInsnNode call) {
        return FormulaMinecraftNames.method(call.name).equals("addAttributeModifier")
                && (call.desc.equals(MODIFIER) && repository.isSubtype(call.owner, "net/minecraft/world/effect/MobEffect")
                || (call.desc.equals(COMPAT_MODIFIER) || call.desc.equals(COMPAT_SUPPLIER))
                && (call.owner.equals(COMPAT) || repository.isSubtype(call.owner, COMPAT))
                && FormulaEffectAttributeControls.nativeCompat(repository));
    }

    private static boolean template(FormulaClassRepository repository, FormulaExecutionGraph.EffectLink effect,
                                    FormulaConsumerSites.Definition definition) {
        var initialization = repository.resolveMethod(effect.registryOwner(), "<clinit>", "()V");
        if (initialization == null) return false;
        var flow = FormulaDataFlow.analyze(repository, initialization.owner().name, initialization.method());
        if (!flow.successful()) return false;
        for (var instruction : flow.instructions()) {
            if (!(instruction.instruction() instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !field.owner.equals(effect.registryOwner()) || !field.name.equals(effect.registryField())) continue;
            var assigned = flow.registryOrigin(instruction.field().value());
            if (assigned == null || !(assigned.instruction() instanceof MethodInsnNode register) || !register.name.equals("register")) return false;
            var arguments = flow.arguments(register);
            if (arguments.size() != 2) return false;
            var id = flow.registryOrigin(arguments.get(0));
            var supplier = flow.registryOrigin(arguments.get(1));
            if (id == null || !(id.instruction() instanceof LdcInsnNode text)
                    || !definition.effectId().equals("irons_spellbooks:" + text.cst)
                    || supplier == null || !(supplier.instruction() instanceof InvokeDynamicInsnNode dynamic)) return false;
            var handles = java.util.Arrays.stream(dynamic.bsmArgs).filter(Handle.class::isInstance).map(Handle.class::cast).toList();
            if (handles.size() != 1) return false;
            Handle factory = handles.get(0);
            var resolved = repository.resolveMethod(factory.getOwner(), factory.getName(), factory.getDesc());
            if (resolved == null) return false;
            var body = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
            if (!body.successful()) return false;
            var returns = body.instructions().stream().filter(operation -> operation.instruction().getOpcode() == Opcodes.ARETURN).toList();
            if (returns.size() != 1 || returns.get(0).operands().size() != 1) return false;
            var current = body.registryOrigin(returns.get(0).operands().get(0));
            if (current != null && current.instruction() instanceof MethodInsnNode cast
                    && cast.owner.equals(COMPAT) && cast.name.equals("cast")
                    && cast.desc.equals("()Lnet/minecraft/world/effect/MobEffect;")) {
                if (!FormulaEffectAttributeControls.nativeCompat(repository)) return false;
                current = body.registryOrigin(body.receiver(cast));
            }
            int matches = 0;
            Set<FormulaDataFlow.Node> visited = new HashSet<>();
            while (current != null && visited.add(current) && current.instruction() instanceof MethodInsnNode modifier
                    && attributeCall(repository, modifier)) {
                var values = body.arguments(modifier);
                var attribute = body.registryOrigin(values.get(0));
                var operation = body.registryOrigin(values.get(3));
                if (attribute != null && attribute.instruction() instanceof FieldInsnNode attributeField
                        && attributeField.getOpcode() == Opcodes.GETSTATIC && matches(definition.alias(), attributeField)) {
                    if (operation == null || !(operation.instruction() instanceof FieldInsnNode operationField)
                            || operationField.getOpcode() != Opcodes.GETSTATIC
                            || !operationField.owner.equals("net/minecraft/world/entity/ai/attributes/AttributeModifier$Operation")
                            || !Set.of("MULTIPLY_BASE", "MULTIPLY_TOTAL").contains(operationField.name)) return false;
                    matches++;
                }
                current = body.registryOrigin(body.receiver(modifier));
            }
            return matches == 1 && current != null
                    && current.instruction() instanceof org.objectweb.asm.tree.TypeInsnNode allocation
                    && allocation.getOpcode() == Opcodes.NEW && repository.isSubtype(allocation.desc, "net/minecraft/world/effect/MobEffect")
                    && (effect.effectClass().isEmpty() || allocation.desc.equals(effect.effectClass().replace('.', '/')))
                    && nativeTemplateMethods(repository, allocation.desc);
        }
        return false;
    }

    private static boolean nativeTemplateMethods(FormulaClassRepository repository, String effect) {
        var addition = repository.resolveMinecraftMethod(effect, "addAttributeModifier", MODIFIER);
        var creation = repository.resolveMinecraftMethod(effect, "getAttributeModifierValue", "(ILnet/minecraft/world/entity/ai/attributes/AttributeModifier;)D");
        return addition != null && creation != null
                && addition.owner().name.equals("net/minecraft/world/effect/MobEffect")
                && creation.owner().name.equals("net/minecraft/world/effect/MobEffect");
    }

    private static boolean matches(String alias, FieldInsnNode attribute) {
        return switch (alias) {
            case "percent_attack_damage" -> attribute.owner.equals("net/minecraft/world/entity/ai/attributes/Attributes") && Set.of("ATTACK_DAMAGE", "f_22281_").contains(attribute.name);
            case "percent_speed" -> attribute.owner.equals("net/minecraft/world/entity/ai/attributes/Attributes") && Set.of("MOVEMENT_SPEED", "f_22279_").contains(attribute.name);
            case "percent_spell_power" -> attribute.owner.equals("io/redspace/ironsspellbooks/api/registry/AttributeRegistry") && attribute.name.equals("SPELL_POWER");
            default -> false;
        };
    }
}
