package com.example.spell_compiler.formula;

import com.google.gson.JsonElement;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.util.thread.EffectiveSide;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaCastSafety {
    private static final String CAST_TIME = "getCastTime(I)I";
    private static final String EFFECTIVE_CAST_TIME = "getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I";
    private static final String EVALUATE = "evaluate(III)I";
    private static final Map<String, Program> PROGRAMS = new ConcurrentHashMap<>();

    private FormulaCastSafety() {}

    static void validate(SpellFormulaCatalog.Spell spell, Map<String, JsonElement> parameters, int maximum) {
        var binding = castMethod(spell);
        if (binding == null || !spell.parameters().has("castTime")) return;
        int castTime = parameters.getOrDefault("castTime", spell.parameters().get("castTime")).getAsInt();
        var result = program(binding).bounds(new Range(1, maximum), Range.of(castTime), new Range(0, 1));
        if (result.minimum() < 0) throw new ArithmeticException("正常等级的法术吟唱时间不能为负数");
    }

    public static int guardCastTime(int original, Object spell, int level) {
        return guard(original, spell, level, null, CAST_TIME);
    }

    public static int guardEffectiveCastTime(int original, Object spell, int level, Object caster) {
        return guard(original, spell, level, caster, EFFECTIVE_CAST_TIME);
    }

    private static int guard(int original, Object spell, int level, Object caster, String method) {
        if (!(spell instanceof AbstractSpell source)) return original;
        if (FormulaNativeValues.isEvaluatingOriginal()) return originalValue(source, method, level, caster);
        boolean client = caster instanceof Entity entity ? entity.level().isClientSide : EffectiveSide.get().isClient();
        var override = (client ? SpellFormulaState.clientSnapshot() : SpellFormulaState.serverSnapshot())
                .byClass().get(spell.getClass().getName());
        if (override == null || !FormulaParameterLimits.related(override.parameters(),
                override.advanced().containsKey("max_level"), "castTime")) return original;
        var binding = castMethod(override.spell());
        if (binding == null) return original;
        int baseline = override.spell().parameters().get("castTime").getAsInt();
        int castTime = override.parameters().containsKey("castTime") ? override.parameters().get("castTime").getAsInt()
                : SpellFormulaRuntime.originalParameter(source, "castTime", baseline);
        try {
            program(binding).evaluate(level, castTime, source.getCastType() == CastType.INSTANT ? 1 : 0);
            return original;
        } catch (ArithmeticException failure) {
            FormulaNativeValues.report(spell, method, caster, failure);
            return originalValue(source, method, level, caster);
        }
    }

    private static int originalValue(AbstractSpell spell, String method, int level, Object caster) {
        try {
            Object[] arguments = method.equals(CAST_TIME) ? new Object[]{level} : new Object[]{level, caster};
            return FormulaNativeValues.evaluateOriginal(spell, method, arguments).intValue();
        } catch (VirtualMachineError | ThreadDeath failure) {
            throw failure;
        } catch (Throwable failure) {
            FormulaNativeValues.report(spell, method + ":original", caster, failure);
            return spell.getCastType() == CastType.INSTANT ? 0 : Integer.MAX_VALUE;
        }
    }

    private static SpellFormulaCatalog.Method castMethod(SpellFormulaCatalog.Spell spell) {
        return spell.formulas().stream().filter(value -> value.key().equals(CAST_TIME)).findFirst().orElse(null);
    }

    private static Program program(SpellFormulaCatalog.Method method) {
        if (!method.variables().keySet().stream().allMatch(Map.of("level", "arg0", "castTime", "arg1", "is_instant", "arg2")::containsKey)
                || !method.variables().values().stream().allMatch("I"::equals))
            throw new IllegalArgumentException("不支持的原生吟唱参数：" + method.key());
        return PROGRAMS.computeIfAbsent(method.strictExpression(), Program::compile);
    }

    private record Range(int minimum, int maximum) {
        static Range of(int value) { return new Range(value, value); }

        Range negate() {
            return new Range(Math.negateExact(maximum), Math.negateExact(minimum));
        }

        Range combine(Range other, String operation) {
            long minimumValue = Long.MAX_VALUE;
            long maximumValue = Long.MIN_VALUE;
            if (operation.equals("divideExact") && other.minimum <= 0 && other.maximum >= 0)
                throw new ArithmeticException("原生吟唱参数区间包含除零");
            for (long left : new long[]{minimum, maximum}) for (long right : new long[]{other.minimum, other.maximum}) {
                long value = switch (operation) {
                    case "addExact" -> left + right;
                    case "subtractExact" -> left - right;
                    case "multiplyExact" -> left * right;
                    case "divideExact" -> FormulaFunctions.divideExact((int) left, (int) right);
                    default -> throw new IllegalArgumentException("不支持的原生吟唱区间运算：" + operation);
                };
                Math.toIntExact(value);
                minimumValue = Math.min(minimumValue, value);
                maximumValue = Math.max(maximumValue, value);
            }
            return new Range(Math.toIntExact(minimumValue), Math.toIntExact(maximumValue));
        }
    }

    private record Program(MethodHandle handle, MethodNode method) {
        static Program compile(String expression) {
            String converted = expression.replaceAll("\\blevel\\b", "arg0")
                    .replaceAll("\\bcastTime\\b", "arg1").replaceAll("\\bis_instant\\b", "arg2");
            try (var stream = Inputs.class.getResourceAsStream('/' + Inputs.class.getName().replace('.', '/') + ".class")) {
                if (stream == null) throw new IOException("找不到吟唱参数计算入口");
                byte[] original = stream.readAllBytes();
                ClassNode source = new ClassNode();
                new ClassReader(original).accept(source, 0);
                byte[] transformed = FormulaCompiler.transformRuntime(original, Map.of(EVALUATE, '(' + converted + ") + int(0)"),
                        FormulaClassRepository.of(source));
                ClassNode compiled = new ClassNode();
                new ClassReader(transformed).accept(compiled, 0);
                MethodNode method = compiled.methods.stream().filter(value -> (value.name + value.desc).equals(EVALUATE))
                        .findFirst().orElseThrow();
                var lookup = MethodHandles.lookup().defineHiddenClass(transformed, false);
                MethodHandle handle = lookup.findStatic(lookup.lookupClass(), "evaluate",
                        MethodType.methodType(int.class, int.class, int.class, int.class));
                return new Program(handle, method);
            } catch (IOException | ReflectiveOperationException failure) {
                throw new IllegalArgumentException("无法创建吟唱参数检查器", failure);
            }
        }

        int evaluate(int level, int castTime, int instant) {
            try {
                return (int) handle.invokeExact(level, castTime, instant);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("吟唱参数检查失败", failure);
            }
        }

        Range bounds(Range level, Range castTime, Range instant) {
            Range[] arguments = {level, castTime, instant};
            var stack = new ArrayDeque<Range>();
            for (AbstractInsnNode instruction : method.instructions) {
                int opcode = instruction.getOpcode();
                if (opcode < 0) continue;
                if (instruction instanceof VarInsnNode variable && opcode == Opcodes.ILOAD) {
                    stack.push(arguments[variable.var]);
                } else if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Integer integer) {
                    stack.push(Range.of(integer));
                } else if (instruction instanceof IntInsnNode constant && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) {
                    stack.push(Range.of(constant.operand));
                } else if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
                    stack.push(Range.of(opcode - Opcodes.ICONST_0));
                } else if (instruction instanceof MethodInsnNode call && (call.owner.equals("java/lang/Math")
                        || call.owner.equals("com/example/spell_compiler/formula/FormulaFunctions"))) {
                    if (call.name.equals("negateExact") && call.desc.equals("(I)I")) stack.push(stack.pop().negate());
                    else if (call.desc.equals("(II)I")) {
                        Range right = stack.pop();
                        stack.push(stack.pop().combine(right, call.name));
                    } else throw new IllegalArgumentException("不支持的原生吟唱区间调用：" + call.name + call.desc);
                } else if (opcode == Opcodes.IRETURN) return stack.pop();
                else throw new IllegalArgumentException("不支持的原生吟唱区间指令：" + opcode);
            }
            throw new IllegalArgumentException("原生吟唱公式没有返回值");
        }
    }

    private static final class Inputs {
        static int evaluate(int first, int second, int third) {
            return first + second + third;
        }
    }
}
