package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.HashMap;
import java.util.Map;

final class FormulaAttributeRegistration {
    private FormulaAttributeRegistration() {}

    static String id(FormulaClassRepository repository, String owner, String field) {
        String result = field(repository, owner, field, 0);
        if (result == null || result.startsWith("@")) return null;
        if (!result.contains(":")) result = "minecraft:" + result;
        try { SpellFormulaCatalog.requireId(result); return result; }
        catch (IllegalArgumentException failure) { return null; }
    }

    private static String field(FormulaClassRepository repository, String owner, String name, int depth) {
        if (depth > 12) return null;
        if ((owner.equals("net/minecraft/core/registries/BuiltInRegistries") || owner.equals("net/minecraft/core/registries/Registries"))
                && FormulaMinecraftNames.field(name).equals("ATTRIBUTE") || isForgeRegistryOwner(owner)
                && name.equals("ATTRIBUTES")) return "@attribute_registry";
        var initialization = repository.resolveMethod(owner, "<clinit>", "()V");
        if (initialization == null) return null;
        var flow = FormulaDataFlow.analyze(repository, initialization.owner().name, initialization.method());
        if (!flow.successful()) return null;
        String found = null;
        int matches = 0;
        for (var instruction : flow.instructions()) {
            if (!(instruction.instruction() instanceof FieldInsnNode assignment) || assignment.getOpcode() != Opcodes.PUTSTATIC
                    || !assignment.owner.equals(owner) || !assignment.name.equals(name) || instruction.field() == null) continue;
            matches++;
            found = value(repository, flow, instruction.field().value(), Map.of(), depth + 1);
        }
        return matches == 1 ? found : null;
    }

    private static boolean isForgeRegistryOwner(String owner) {
        return owner.equals("net/minecraftforge/registries/ForgeRegistries")
                || owner.equals("net/minecraftforge/registries/ForgeRegistries$Keys");
    }

    private static String value(FormulaClassRepository repository, FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value,
                                Map<Integer, String> parameters, int depth) {
        if (depth > 12) return null;
        var node = flow.origin(value);
        if (node == null) return null;
        if (node.kind() == FormulaDataFlow.NodeKind.PARAMETER) return parameters.get(node.parameterSlot());
        if (node.instruction() instanceof LdcInsnNode literal && literal.cst instanceof String text) return text;
        if (node.instruction() instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                && allocation.desc.equals("net/minecraft/resources/ResourceLocation")) {
            String result = null;
            int matches = 0;
            for (var operation : flow.instructions()) {
                if (!(operation.instruction() instanceof MethodInsnNode constructor) || !constructor.name.equals("<init>")
                        || !constructor.owner.equals(allocation.desc) || !node.equals(flow.origin(flow.receiver(constructor)))) continue;
                var inputs = flow.arguments(constructor);
                if (inputs.size() < 1 || inputs.size() > 2) return null;
                String first = value(repository, flow, inputs.get(0), parameters, depth + 1);
                String second = inputs.size() == 2 ? value(repository, flow, inputs.get(1), parameters, depth + 1) : null;
                if (first == null || inputs.size() == 2 && second == null) return null;
                result = inputs.size() == 2 ? first + ':' + second : first.contains(":") ? first : "minecraft:" + first;
                matches++;
            }
            return matches == 1 ? result : null;
        }
        if (node.instruction() instanceof FieldInsnNode source && source.getOpcode() == Opcodes.GETSTATIC)
            return field(repository, source.owner, source.name, depth + 1);
        if (node.instruction() instanceof InvokeDynamicInsnNode concat && concat.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory")
                && concat.bsm.getName().equals("makeConcatWithConstants") && concat.bsmArgs.length == 1 && concat.bsmArgs[0] instanceof String recipe) {
            var arguments = flow.dependencies(node);
            StringBuilder result = new StringBuilder();
            int index = 0;
            for (char character : recipe.toCharArray()) {
                if (character == 2) return null;
                if (character != 1) result.append(character);
                else {
                    if (index >= arguments.size()) return null;
                    String argument = value(repository, flow, arguments.get(index++), parameters, depth + 1);
                    if (argument == null) return null;
                    result.append(argument);
                }
            }
            return index == arguments.size() ? result.toString() : null;
        }
        if (!(node.instruction() instanceof MethodInsnNode call)) return null;
        var arguments = flow.arguments(call);
        if (call.owner.equals("net/minecraftforge/registries/DeferredRegister")) {
            if (call.name.equals("create") && arguments.size() == 2) {
                String registry = value(repository, flow, arguments.get(0), parameters, depth + 1);
                String namespace = value(repository, flow, arguments.get(1), parameters, depth + 1);
                return "@attribute_registry".equals(registry) && namespace != null ? "@namespace:" + namespace : null;
            }
            if (call.name.equals("register") && arguments.size() == 2) {
                String receiver = value(repository, flow, flow.receiver(call), parameters, depth + 1);
                String path = value(repository, flow, arguments.get(0), parameters, depth + 1);
                return receiver != null && receiver.startsWith("@namespace:") && path != null ? receiver.substring(11) + ':' + path : null;
            }
        }
        if (call.owner.equals("net/minecraft/resources/ResourceLocation")) {
            if (FormulaMinecraftNames.method(call.name).equals("withDefaultNamespace") && arguments.size() == 1) {
                String path = value(repository, flow, arguments.get(0), parameters, depth + 1);
                return path == null ? null : "minecraft:" + path;
            }
            if (FormulaMinecraftNames.method(call.name).equals("fromNamespaceAndPath") && arguments.size() == 2) {
                String namespace = value(repository, flow, arguments.get(0), parameters, depth + 1);
                String path = value(repository, flow, arguments.get(1), parameters, depth + 1);
                return namespace == null || path == null ? null : namespace + ':' + path;
            }
        }
        if (call.owner.equals("net/minecraft/core/Registry") && (FormulaMinecraftNames.method(call.name).equals("registerForHolder")
                || FormulaMinecraftNames.method(call.name).equals("register"))
                && arguments.size() == 3) {
            String registry = value(repository, flow, arguments.get(0), parameters, depth + 1);
            return "@attribute_registry".equals(registry) ? value(repository, flow, arguments.get(1), parameters, depth + 1) : null;
        }
        if (call.getOpcode() != Opcodes.INVOKESTATIC) return null;
        var target = repository.resolveMethod(call.owner, call.name, call.desc);
        if (target == null) return null;
        var nested = FormulaDataFlow.analyze(repository, target.owner().name, target.method());
        if (!nested.successful()) return null;
        Map<Integer, String> bound = new HashMap<>();
        var types = Type.getArgumentTypes(call.desc);
        for (int i = 0, slot = 0; i < arguments.size(); slot += types[i++].getSize()) {
            String argument = value(repository, flow, arguments.get(i), parameters, depth + 1);
            if (argument != null) bound.put(slot, argument);
        }
        var returns = nested.instructions().stream().filter(instruction -> instruction.instruction().getOpcode() == Opcodes.ARETURN).toList();
        return returns.size() == 1 && returns.get(0).operands().size() == 1
                ? value(repository, nested, returns.get(0).operands().get(0), bound, depth + 1) : null;
    }
}
