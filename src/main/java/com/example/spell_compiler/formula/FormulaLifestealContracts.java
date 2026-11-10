package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.*;

final class FormulaLifestealContracts {
    static final String SOURCE = "io/redspace/ironsspellbooks/damage/SpellDamageSource";
    static final String EVENTS = "io/redspace/ironsspellbooks/damage/DamageSources";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String HOOK = "com/example/spell_compiler/formula/FormulaLifestealControls";
    record Contract(String field, String fingerprint) {}

    private FormulaLifestealContracts() {}

    static Contract source(ClassNode target) {
        if (target == null || !target.name.equals(SOURCE)) return null;
        MethodNode getter = method(target, "getLifestealPercent", "()F");
        MethodNode setter = method(target, "setLifestealPercent", "(F)L" + SOURCE + ';');
        MethodNode gate = method(target, "hasPostHitEffects", "()Z");
        if (getter == null || setter == null || gate == null) return null;
        var code = FormulaAutomaticInjector.executable(getter);
        if (code.size() != 3 || !(code.get(0) instanceof VarInsnNode receiver) || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0
                || !(code.get(1) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                || !field.owner.equals(SOURCE) || !field.desc.equals("F") || code.get(2).getOpcode() != Opcodes.FRETURN) return null;
        var repository = FormulaClassRepository.of(target);
        var flow = FormulaDataFlow.analyze(repository, target.name, setter);
        if (!flow.successful()) return null;
        int writes = 0;
        for (var instruction : flow.instructions()) {
            var write = instruction.field();
            if (write == null || !write.write() || !write.owner().equals(SOURCE) || !write.name().equals(field.name)) continue;
            if (!flow.parameter(write.receiver(), 0) || !flow.parameter(write.value(), 1)) return null;
            writes++;
        }
        boolean returned = flow.instructions().stream().filter(i -> i.instruction().getOpcode() == Opcodes.ARETURN)
                .allMatch(i -> i.operands().size() == 1 && flow.parameter(i.operands().get(0), 0));
        var gateCode = FormulaAutomaticInjector.executable(gate);
        boolean positiveGate = false;
        for (int i = 0; i + 3 < gateCode.size(); i++) {
            if (gateCode.get(i) instanceof MethodInsnNode call && call.owner.equals(SOURCE) && call.name.equals("getLifestealPercent")
                    && call.desc.equals("()F") && gateCode.get(i + 1).getOpcode() == Opcodes.FCONST_0
                    && gateCode.get(i + 2).getOpcode() == Opcodes.FCMPL && gateCode.get(i + 3) instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFGT) positiveGate = true;
        }
        return writes == 1 && returned && positiveGate ? new Contract(field.name,
                FormulaAutomaticInjector.fingerprint(getter) + ':' + FormulaAutomaticInjector.fingerprint(setter) + ':'
                        + FormulaAutomaticInjector.fingerprint(gate)) : null;
    }

    static MethodNode consumer(ClassNode target) {
        if (target == null || !target.name.equals(EVENTS)) return null;
        var repository = FormulaClassRepository.of(target);
        var methods = target.methods.stream().filter(method -> method.name.equals("postHitEffects")
                && (method.access & Opcodes.ACC_STATIC) != 0).toList();
        if (methods.size() != 1) return null;
        MethodNode method = methods.get(0);
        var flow = FormulaDataFlow.analyze(repository, target.name, method);
        if (!flow.successful()) return null;
        int consumers = 0;
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode heal) || !heal.owner.equals(LIVING)
                    || !Set.of("heal", "m_5634_").contains(heal.name) || !heal.desc.equals("(F)V")) continue;
            var product = flow.origin(flow.arguments(heal).get(0));
            if (product == null || product.instruction() == null || product.instruction().getOpcode() != Opcodes.FMUL) return null;
            var operands = flow.instruction(product.instruction()).operands();
            if (operands.size() != 2) return null;
            MethodInsnNode ratio = call(flow.origin(operands.get(0)));
            MethodInsnNode damage = call(flow.origin(operands.get(1)));
            if (!ratio(ratio)) { MethodInsnNode swap = ratio; ratio = damage; damage = swap; }
            if (!ratio(ratio) || damage == null || !Set.of("getNewDamage", "getAmount").contains(damage.name)
                    || !damage.desc.equals("()F") || !flow.parameter(flow.receiver(damage), 0)
                    || !eventSource(flow, flow.receiver(ratio)) || !guardedRatio(method, flow, heal)
                    || !healer(flow, flow.receiver(heal))) return null;
            consumers++;
        }
        return consumers == 1 ? method : null;
    }

    private static boolean guardedRatio(MethodNode method, FormulaDataFlow.Analysis flow, MethodInsnNode heal) {
        var code = FormulaAutomaticInjector.executable(method);
        int end = method.instructions.indexOf(heal);
        for (int i = 0; i + 3 < code.size(); i++) {
            if (!(code.get(i) instanceof MethodInsnNode value) || !ratio(value) || !eventSource(flow, flow.receiver(value))
                    || code.get(i + 1).getOpcode() != Opcodes.FCONST_0 || code.get(i + 2).getOpcode() != Opcodes.FCMPL
                    || !(code.get(i + 3) instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFLE) continue;
            if (method.instructions.indexOf(jump.label) > end && flow.resultDominates(value, heal)) return true;
        }
        return false;
    }

    private static boolean healer(FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value) {
        var entity = call(flow.origin(value));
        return entity != null && Set.of("getEntity", "m_7639_").contains(entity.name)
                && eventSource(flow, flow.receiver(entity));
    }

    private static boolean eventSource(FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value) {
        var source = call(flow.origin(value));
        return source != null && source.name.equals("getSource")
                && source.desc.equals("()Lnet/minecraft/world/damagesource/DamageSource;") && flow.parameter(flow.receiver(source), 0);
    }

    private static boolean ratio(MethodInsnNode call) {
        return call != null && call.owner.equals(SOURCE) && call.name.equals("getLifestealPercent") && call.desc.equals("()F");
    }

    private static MethodInsnNode call(FormulaDataFlow.Node node) {
        return node != null && node.instruction() instanceof MethodInsnNode call ? call : null;
    }

    static boolean getterRetained(ClassNode target) {
        if (target == null || !target.name.equals(SOURCE)) return false;
        target = FormulaRetentionBytecode.analysisCopy(target);
        if (target == null) return false;
        MethodNode getter = method(target, "getLifestealPercent", "()F");
        if (getter == null) return false;
        MethodNode setter = method(target, "setLifestealPercent", "(F)L" + SOURCE + ';');
        if (setter == null) return false;
        var fields = FormulaAutomaticInjector.executable(setter).stream().filter(instruction -> instruction instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.PUTFIELD && field.owner.equals(SOURCE) && field.desc.equals("F")).toList();
        if (fields.size() != 1) return false;
        String sourceField = ((FieldInsnNode) fields.get(0)).name;
        var repository = FormulaClassRepository.of(target);
        var flow = FormulaDataFlow.analyze(repository, target.name, getter);
        if (!flow.successful()) return false;
        int connected = 0;
        for (var instruction : getter.instructions) {
            if (!(instruction instanceof MethodInsnNode wrapper) || !wrapper.owner.equals(SOURCE) || !wrapper.desc.equals("(F)F")
                    || !flow.parameter(flow.receiver(wrapper), 0)) continue;
            var original = flow.origin(flow.arguments(wrapper).get(0));
            if (original == null || !(original.instruction() instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(SOURCE) || !field.name.equals(sourceField) || !field.desc.equals("F")
                    || !flow.parameter(flow.instruction(field).field().receiver(), 0)) continue;
            MethodNode handler = method(target, wrapper.name, wrapper.desc);
            if (handler == null || !handler(repository, target, handler) || !returned(flow, wrapper)) continue;
            connected++;
        }
        return connected == 1;
    }

    private static boolean handler(FormulaClassRepository repository, ClassNode owner, MethodNode method) {
        var flow = FormulaDataFlow.analyze(repository, owner.name, method);
        if (!flow.successful()) return false;
        var hooks = FormulaAutomaticInjector.executable(method).stream().filter(i -> i instanceof MethodInsnNode call
                && call.owner.equals(HOOK) && call.name.equals("lifestealRatio") && call.desc.equals("(FLjava/lang/Object;)F")).toList();
        if (hooks.size() != 1) return false;
        var hook = (MethodInsnNode) hooks.get(0);
        return flow.parameter(flow.arguments(hook).get(0), 1) && flow.parameter(flow.arguments(hook).get(1), 0) && returned(flow, hook);
    }

    private static boolean returned(FormulaDataFlow.Analysis flow, AbstractInsnNode value) {
        int count = 0;
        for (var instruction : flow.instructions()) if (instruction.instruction().getOpcode() == Opcodes.FRETURN) {
            if (instruction.operands().size() != 1 || flow.origin(instruction.operands().get(0)) == null
                    || flow.origin(instruction.operands().get(0)).instruction() != value) return false;
            count++;
        }
        return count > 0;
    }

    static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor)).findFirst().orElse(null);
    }
}
