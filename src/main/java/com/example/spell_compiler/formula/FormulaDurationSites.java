package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class FormulaDurationSites {
    public record Coverage(int integerMethods, int floatingMethods, int directConsumers, int previewConsumers) {}
    private record Power(MethodInsnNode instruction, int levelSlot, int casterSlot) {}
    private record Plan(AbstractInsnNode root, Power power, float scale, boolean integralFirst) {}
    private record Site(Plan plan, boolean preview, String durationMethod) {}
    private record AoeSite(MethodInsnNode consumer, String method, int levelSlot, int casterSlot) {}

    private static final String SAFETY = "com/example/spell_compiler/formula/FormulaDurationSafety";
    private static final String POWER_DESCRIPTOR = "(ILnet/minecraft/world/entity/Entity;)F";
    private static final String DURATION_ARGUMENTS = "(ILnet/minecraft/world/entity/LivingEntity;)";

    private FormulaDurationSites() {}

    public static boolean isDuration(SpellFormulaCatalog.Method method) {
        return FormulaNames.duration(method.alias());
    }

    public static Coverage apply(ClassNode target, SpellFormulaCatalog.Spell definition) {
        if (definition == null || !definition.className().equals(target.name.replace('/', '.')))
            return new Coverage(0, 0, 0, 0);
        Map<String, SpellFormulaCatalog.Method> durations = definition.formulas().stream()
                .filter(FormulaDurationSites::isDuration)
                .filter(value -> value.declaringClass().equals(definition.className()))
                .collect(Collectors.toMap(SpellFormulaCatalog.Method::key, Function.identity()));
        int integers = 0;
        int floating = 0;
        int direct = 0;
        int previews = 0;
        for (MethodNode method : target.methods) {
            if (durations.containsKey(method.name + method.desc)) {
                Type result = Type.getReturnType(method.desc);
                if (!method.desc.equals(DURATION_ARGUMENTS + result.getDescriptor())
                        || (method.access & Opcodes.ACC_STATIC) != 0
                        || result.getSort() != Type.INT && result.getSort() != Type.FLOAT)
                    throw new IllegalArgumentException("原生持续时间方法签名已变化：" + target.name + '#' + method.name + method.desc);
                protectReturns(method, result);
                if (result.getSort() == Type.INT) integers++;
                else floating++;
            }
            if (!method.name.equals("onCast") && !method.name.startsWith("lambda$onCast$")
                    && !method.name.equals("getUniqueInfo")) continue;
            String durationMethod = durations.size() == 1 ? durations.keySet().iterator().next() : null;
            if (durationMethod != null && !durationMethod.endsWith(DURATION_ARGUMENTS + 'I')) durationMethod = null;
            List<Site> sites = findSites(target.name, method, durationMethod);
            for (Site site : sites) {
                inject(method, site);
                if (site.preview()) previews++;
                else direct++;
            }
            protectAoeConsumers(target, method, durations);
        }
        return new Coverage(integers, floating, direct, previews);
    }

    private static void protectReturns(MethodNode method, Type result) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != result.getOpcode(Opcodes.IRETURN)) continue;
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new LdcInsnNode(method.name + method.desc));
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            String kind = result.getSort() == Type.INT ? "Int" : "Float";
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SAFETY, "override" + kind,
                    '(' + result.getDescriptor() + "Ljava/lang/Object;Ljava/lang/String;ILjava/lang/Object;)"
                            + result.getDescriptor(), false));
            method.instructions.insertBefore(instruction, code);
        }
        method.maxStack += 4;
    }

    private static List<Site> findSites(String owner, MethodNode method, String durationMethod) {
        boolean casts = method.name.equals("onCast") || method.name.startsWith("lambda$onCast$");
        List<MethodInsnNode> consumers = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (casts && call.owner.equals("net/minecraft/world/effect/MobEffectInstance") && call.name.equals("<init>")
                    && call.desc.startsWith("(Lnet/minecraft/world/effect/MobEffect;I")) consumers.add(call);
            else if (!casts && call.owner.equals("io/redspace/ironsspellbooks/api/util/Utils")
                    && call.name.equals("timeFromTicks") && call.desc.equals("(FI)Ljava/lang/String;")) consumers.add(call);
        }
        if (consumers.isEmpty()) return List.of();
        try {
            Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
            List<Site> sites = new ArrayList<>();
            for (MethodInsnNode consumer : consumers) {
                Frame<SourceValue> frame = frames[method.instructions.indexOf(consumer)];
                if (frame == null) continue;
                int argument = frame.getStackSize() - Type.getArgumentTypes(consumer.desc).length + (casts ? 1 : 0);
                Plan plan = plan(method, frames, source(frame.getStack(argument)), !casts);
                if (plan != null) sites.add(new Site(plan, !casts, casts ? null : durationMethod));
            }
            return List.copyOf(sites);
        } catch (AnalyzerException failure) {
            throw new IllegalArgumentException("无法核对原生持续时间消费者：" + owner + '#' + method.name + method.desc, failure);
        }
    }

    private static void protectAoeConsumers(ClassNode owner, MethodNode method,
                                             Map<String, SpellFormulaCatalog.Method> durations) {
        if (!method.name.equals("onCast") || durations.isEmpty()) return;
        var repository = FormulaClassRepository.of(owner);
        List<MethodInsnNode> consumers = new ArrayList<>();
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals("setDuration") && call.desc.equals("(I)V")
                    && repository.isSubtype(call.owner, "io/redspace/ironsspellbooks/entity/spells/AoeEntity")) consumers.add(call);
        if (consumers.isEmpty()) return;
        try {
            Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
            List<AoeSite> selected = new ArrayList<>();
            for (var consumer : consumers) {
                var produced = operand(method, frames, consumer, 0);
                if (!(produced instanceof MethodInsnNode getter) || getter.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !getter.owner.equals(owner.name) || !durations.containsKey(getter.name + getter.desc)
                        || !getter.desc.equals(DURATION_ARGUMENTS + 'I')) continue;
                var receiver = operand(method, frames, getter, 2);
                var level = operand(method, frames, getter, 1);
                var caster = operand(method, frames, getter, 0);
                if (!(receiver instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                        || !(level instanceof VarInsnNode integer) || integer.getOpcode() != Opcodes.ILOAD
                        || !(caster instanceof VarInsnNode actor) || actor.getOpcode() != Opcodes.ALOAD) continue;
                selected.add(new AoeSite(consumer, getter.name + getter.desc, integer.var, actor.var));
            }
            for (var site : selected) {
                int durationSlot = method.maxLocals++;
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ISTORE, durationSlot));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new VarInsnNode(Opcodes.ILOAD, durationSlot));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new LdcInsnNode(site.method()));
                code.add(new VarInsnNode(Opcodes.ILOAD, site.levelSlot()));
                code.add(new VarInsnNode(Opcodes.ALOAD, site.casterSlot()));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SAFETY, "guardAoeDuration",
                        "(Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/String;ILjava/lang/Object;)I", false));
                method.instructions.insertBefore(site.consumer(), code);
                method.maxStack += 5;
            }
        } catch (AnalyzerException failure) {
            throw new IllegalArgumentException("无法核对原生范围实体持续时间：" + owner.name + '#' + method.name + method.desc, failure);
        }
    }

    private static Plan plan(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode root, boolean preview) {
        if (root == null) return null;
        if (preview) {
            Power input = power(method, frames, root);
            if (input != null) return new Plan(root, input, 1, false);
            return floatProduct(method, frames, root, root);
        }
        if (root.getOpcode() == Opcodes.F2I) {
            AbstractInsnNode input = operand(method, frames, root, 0);
            Power power = power(method, frames, input);
            if (power != null) return new Plan(root, power, 1, false);
            return floatProduct(method, frames, input, root);
        }
        if (root.getOpcode() == Opcodes.IMUL) {
            AbstractInsnNode left = operand(method, frames, root, 1);
            Number right = constant(operand(method, frames, root, 0));
            if (left == null || left.getOpcode() != Opcodes.F2I || !(right instanceof Integer)) return null;
            Power power = power(method, frames, operand(method, frames, left, 0));
            if (power != null) return new Plan(root, power, right.floatValue(), true);
        }
        return null;
    }

    private static Plan floatProduct(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode product,
                                     AbstractInsnNode root) {
        if (product == null || product.getOpcode() != Opcodes.FMUL) return null;
        Power power = power(method, frames, operand(method, frames, product, 1));
        Number scale = constant(operand(method, frames, product, 0));
        if (power == null || !(scale instanceof Float)) return null;
        return new Plan(root, power, scale.floatValue(), false);
    }

    private static Power power(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !call.name.equals("getSpellPower") || !call.desc.equals(POWER_DESCRIPTOR)) return null;
        AbstractInsnNode receiver = operand(method, frames, call, 2);
        AbstractInsnNode level = operand(method, frames, call, 1);
        AbstractInsnNode caster = operand(method, frames, call, 0);
        if (!(receiver instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0
                || !(level instanceof VarInsnNode integer) || integer.getOpcode() != Opcodes.ILOAD
                || !(caster instanceof VarInsnNode actor) || actor.getOpcode() != Opcodes.ALOAD) return null;
        return new Power(call, integer.var, actor.var);
    }

    private static AbstractInsnNode operand(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode instruction,
                                             int offset) {
        Frame<SourceValue> frame = frames[method.instructions.indexOf(instruction)];
        return frame == null || frame.getStackSize() <= offset ? null
                : source(frame.getStack(frame.getStackSize() - 1 - offset));
    }

    private static AbstractInsnNode source(SourceValue value) {
        return value == null || value.insns.size() != 1 ? null : value.insns.iterator().next();
    }

    private static Number constant(AbstractInsnNode instruction) {
        if (instruction instanceof LdcInsnNode value && value.cst instanceof Number number) return number;
        if (instruction instanceof IntInsnNode value
                && (value.getOpcode() == Opcodes.BIPUSH || value.getOpcode() == Opcodes.SIPUSH)) return value.operand;
        if (instruction != null && instruction.getOpcode() >= Opcodes.ICONST_M1 && instruction.getOpcode() <= Opcodes.ICONST_5)
            return instruction.getOpcode() - Opcodes.ICONST_0;
        if (instruction != null && instruction.getOpcode() >= Opcodes.FCONST_0 && instruction.getOpcode() <= Opcodes.FCONST_2)
            return (float) (instruction.getOpcode() - Opcodes.FCONST_0);
        return null;
    }

    private static void inject(MethodNode method, Site site) {
        Plan plan = site.plan();
        int powerSlot = method.maxLocals++;
        InsnList capture = new InsnList();
        capture.add(new InsnNode(Opcodes.DUP));
        capture.add(new VarInsnNode(Opcodes.FSTORE, powerSlot));
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.FLOAD, powerSlot));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, plan.power().levelSlot()));
        code.add(new VarInsnNode(Opcodes.ALOAD, plan.power().casterSlot()));
        code.add(new LdcInsnNode(plan.scale()));
        code.add(new InsnNode(plan.integralFirst() ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        if (site.preview()) code.add(site.durationMethod() == null ? new InsnNode(Opcodes.ACONST_NULL)
                : new LdcInsnNode(site.durationMethod()));
        String type = site.preview() ? "F" : "I";
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SAFETY,
                site.preview() ? "overridePowerPreview" : "overridePowerDuration",
                '(' + type + "FLjava/lang/Object;ILjava/lang/Object;FZ"
                        + (site.preview() ? "Ljava/lang/String;" : "") + ')' + type, false));
        method.instructions.insert(plan.root(), code);
        method.instructions.insert(plan.power().instruction(), capture);
        method.maxStack += site.preview() ? 7 : 6;
    }
}
