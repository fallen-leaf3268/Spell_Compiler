package com.example.spell_compiler.formula;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class FormulaDisplayControls {
    private FormulaDisplayControls() {}

    static void apply(FormulaClassRepository repository, String spellClassName, JsonArray formulas,
                      List<SpellFormulaCatalog.DamageComponent> components, JsonArray diagnostics,
                      Predicate<JsonObject> verifiedBridge) {
        for (var value : formulas) {
            JsonObject formula = value.getAsJsonObject();
            if (formula.has("editable") && !formula.get("editable").getAsBoolean()
                    || verifiedBridge.test(formula) || !onlyDescription(repository, spellClassName, formula)) continue;
            String reason = "该私有数值方法只用于原生法术说明，未连接实际数值消费";
            if (!components.isEmpty()) reason += "；当前可用最终伤害组件："
                    + String.join(", ", components.stream().map(SpellFormulaCatalog.DamageComponent::alias).toList());
            formula.addProperty("editable", false);
            formula.addProperty("readOnlyReason", reason);
            diagnostics.add("DISPLAY_ONLY_GETTER: " + formula.get("declaringClass").getAsString() + '#'
                    + formula.get("name").getAsString() + formula.get("descriptor").getAsString() + ": " + reason);
        }
    }

    static boolean onlyDescription(FormulaClassRepository repository, String spellClassName, JsonObject formula) {
        String ownerName = spellClassName.replace('.', '/');
        var owner = repository.classNode(ownerName);
        if (owner == null) return false;
        String name = formula.get("name").getAsString();
        String descriptor = formula.get("descriptor").getAsString();
        var declaration = repository.resolveMethod(formula.get("declaringClass").getAsString().replace('.', '/'), name, descriptor);
        if (declaration == null || !declaration.owner().name.equals(ownerName)
                || (declaration.method().access & Opcodes.ACC_PRIVATE) == 0) return false;
        Set<String> nest = new LinkedHashSet<>();
        nest.add(owner.name);
        if (owner.nestMembers != null) nest.addAll(owner.nestMembers);
        if (owner.nestHostClass != null) {
            var host = repository.classNode(owner.nestHostClass);
            if (host == null) return false;
            nest.add(host.name);
            if (host.nestMembers != null) nest.addAll(host.nestMembers);
        }
        boolean found = false;
        for (String member : nest) {
            var caller = repository.classNode(member);
            if (caller == null) return false;
            for (var method : caller.methods) for (var instruction : method.instructions) {
                if (instruction instanceof InvokeDynamicInsnNode dynamic)
                    for (Object argument : dynamic.bsmArgs) if (argument instanceof Handle handle
                            && handle.getOwner().equals(ownerName) && handle.getName().equals(name) && handle.getDesc().equals(descriptor)) return false;
                if (!(instruction instanceof MethodInsnNode call) || !call.name.equals(name) || !call.desc.equals(descriptor)) continue;
                var target = repository.resolveMethod(call.owner, call.name, call.desc);
                if (target == null || !target.owner().name.equals(ownerName)) continue;
                if (!caller.name.equals(owner.name) || !method.name.equals("getUniqueInfo")
                        || !method.desc.equals("(ILnet/minecraft/world/entity/LivingEntity;)Ljava/util/List;")) return false;
                found = true;
            }
        }
        return found;
    }
}
