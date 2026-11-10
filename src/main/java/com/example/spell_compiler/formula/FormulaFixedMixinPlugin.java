package com.example.spell_compiler.formula;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class FormulaFixedMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger(FormulaFixedMixinPlugin.class);
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String LIVING = "Lnet/minecraft/world/entity/LivingEntity;";
    private static final String EFFECT = "Lnet/minecraft/world/effect/MobEffectInstance;";
    private static final String ADD = '(' + EFFECT + ENTITY + ")V";
    private static final String UPDATE = '(' + EFFECT + 'Z' + ENTITY + ")V";
    private static final Map<String, String> DIAGNOSTICS = new ConcurrentHashMap<>();
    private final Function<String, ClassNode> classes;
    private final Set<String> declinedMarkers = ConcurrentHashMap.newKeySet();

    public FormulaFixedMixinPlugin() { this(FormulaFixedMixinPlugin::read); }
    FormulaFixedMixinPlugin(Function<String, ClassNode> classes) { this.classes = classes; }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String problem;
        try { problem = mismatch(targetClassName, mixinClassName, classes.apply(targetClassName)); }
        catch (RuntimeException | LinkageError failure) { problem = "读取目标接口失败：" + failure; }
        if (problem == null) return true;
        return unavailable(targetClassName, mixinClassName, problem);
    }

    @Override public void preApply(String targetClassName, ClassNode target, String mixinClassName, IMixinInfo info) {
        String problem = mismatch(targetClassName, mixinClassName, target);
        if (mixinClassName.endsWith("FormulaPoisonMixin")) {
            if (problem == null && FormulaEffectPublicationControls.applyPoisonTarget(target)) return;
            declinedMarkers.add(mixinClassName);
            FormulaEffectPublicationControls.observePoisonTarget(null);
            unavailable(targetClassName, mixinClassName, problem == null ? "中毒伤害消费无法安全绑定" : problem);
            return;
        }
        if (problem != null && mixinClassName.endsWith("FormulaLifestealMixin$Consumer")) {
            declinedMarkers.add(mixinClassName);
            FormulaLifestealControls.observeConsumerTarget(null);
            unavailable(targetClassName, mixinClassName, problem);
            return;
        }
        if (problem != null) {
            String reason = "选定挂钩后目标又发生变化，无法安全撤回注入：" + problem;
            DIAGNOSTICS.put(mixinClassName, targetClassName + "：" + reason);
            throw new IllegalStateException("[SpellFormula] " + mixinClassName + " -> " + targetClassName + "：" + reason);
        }
    }

    @Override public void postApply(String targetClassName, ClassNode target, String mixinClassName, IMixinInfo info) {
        if (declinedMarkers.contains(mixinClassName)) return;
        if (mixinClassName.endsWith("FormulaPoisonMixin")) FormulaEffectPublicationControls.observePoisonTarget(target);
        if (mixinClassName.endsWith("FormulaExplosionMixin")) FormulaExplosionBindings.observeExplosionTarget(target);
        if (mixinClassName.endsWith("FormulaLifestealMixin")) FormulaLifestealControls.observeSourceTarget(target);
        if (mixinClassName.endsWith("FormulaLifestealMixin$Consumer")) FormulaLifestealControls.observeConsumerTarget(target);
    }

    public static Map<String, String> diagnostics() { return Map.copyOf(DIAGNOSTICS); }

    private static boolean unavailable(String target, String mixin, String problem) {
        String message = target + "：" + problem;
        if (!optional(mixin) && !optionalSource(mixin)) {
            DIAGNOSTICS.put(mixin, message);
            throw new IllegalStateException("[SpellFormula] 核心公式/效果载体接口不兼容，无法安全启用：" + mixin + " -> " + message);
        }
        if (!message.equals(DIAGNOSTICS.put(mixin, message))) {
            if (optionalSource(mixin))
                LOGGER.warn("[SpellFormula] 来源公式挂钩 {} 不可用，保留该消费者原生行为：{}", mixin, message);
            else LOGGER.warn("[SpellFormula] 可选显示挂钩 {} 不可用，仅关闭该显示增强：{}", mixin, message);
        }
        return false;
    }

    private static boolean optionalSource(String mixin) {
        return mixin.endsWith("FormulaPoisonMixin") || mixin.endsWith("FormulaExplosionMixin")
                || mixin.endsWith("FormulaLifestealMixin") || mixin.endsWith("FormulaLifestealMixin$Consumer");
    }

    private static boolean optional(String mixin) {
        return mixin.endsWith("FormulaSacrificeMixin") || mixin.endsWith("FormulaNumberFormattingMixin")
                || mixin.endsWith("AttributesLibClientMixin") || mixin.endsWith("FormulaConsumerMixin$Tooltips")
                || mixin.endsWith("FormulaTooltipTimeMixin");
    }

    private static ClassNode read(String name) {
        return read(name, MixinService.getService().getBytecodeProvider());
    }

    static ClassNode read(String name, IClassBytecodeProvider provider) {
        try { return provider.getClassNode(name, true); }
        catch (ClassNotFoundException missing) { return null; }
        catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    static String mismatch(String target, String mixin, ClassNode node) {
        if (node == null) return "目标类不存在";
        if (!node.name.replace('/', '.').equals(target)) return "目标类名不匹配：" + node.name;
        try {
            if (mixin.endsWith("FormulaBaseSpellMixin")) {
                method(node, "getManaCost", "(I)I", false);
                method(node, "getSpellCooldown", "()I", false);
                method(node, "getCastTime", "(I)I", false);
                method(node, "getSpellPower", "(I" + ENTITY + ")F", false);
                method(node, "getEntityPowerMultiplier", '(' + LIVING + ")F", false);
                method(node, "getEffectiveCastTime", "(I" + LIVING + ")I", false);
            } else if (mixin.endsWith("FormulaSacrificeMixin")) {
                var method = method(node, "getUniqueInfo", "(I" + LIVING + ")Ljava/util/List;", false);
                calls(node, method, "net/minecraft/network/chat/Component", "translatable",
                        "(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;", 1, -1);
                require(method.instructions.iterator().hasNext() && java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                        .anyMatch(instruction -> instruction instanceof LdcInsnNode constant && constant.cst.equals("ui.irons_spellbooks.radius")), "半径提示入口不存在");
            } else if (mixin.endsWith("FormulaNumberFormattingMixin")) {
                method(node, "stringTruncation", "(DI)Ljava/lang/String;", true);
            } else if (mixin.endsWith("FormulaTooltipTimeMixin")) {
                for (String name : List.of("formatActiveSpellTooltip", "formatScrollTooltip")) {
                    var matching = node.methods.stream().filter(value -> value.name.equals(name)).toList();
                    require(matching.size() == 1, "法术说明方法缺失或重载不符：" + name);
                    calls(node, matching.get(0), "io/redspace/ironsspellbooks/api/util/Utils", "timeFromTicks",
                            "(FI)Ljava/lang/String;", 1, -1);
                }
            } else if (mixin.endsWith("FormulaLifestealMixin")) {
                require(FormulaLifestealControls.validateSourceTarget(node), "原生吸血属性读取结构不匹配");
            } else if (mixin.endsWith("FormulaLifestealMixin$Consumer")) {
                require(FormulaLifestealControls.validateConsumerTarget(node), "原生吸血实际治疗消费结构不匹配");
            } else if (mixin.endsWith("FormulaExplosionMixin")) {
                String problem = FormulaExplosionBindings.validateExplosionTarget(node);
                require(problem == null, problem);
            } else if (mixin.endsWith("FormulaPoisonMixin")) {
                require(FormulaEffectPublicationControls.validatePoisonTarget(node), "原生中毒伤害消费结构不匹配");
            } else if (mixin.endsWith("FormulaConsumerMixin$EffectData")) {
                field(node, "hiddenEffect", EFFECT);
                field(node, "duration", "I");
                method(node, "setDetailsFrom", '(' + EFFECT + ")V", false);
                var update = method(node, "update", '(' + EFFECT + ")Z", false);
                long writes = java.util.stream.StreamSupport.stream(update.instructions.spliterator(), false)
                        .filter(instruction -> instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                                && field.owner.equals(node.name) && FormulaMinecraftNames.field(field.name).equals("duration") && field.desc.equals("I")).count();
                require(writes == 2, "update 的 duration 写入数量应为 2，实际 " + writes);
                method(node, "save", "(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;", false);
                method(node, "loadSpecifiedEffect", "(Lnet/minecraft/world/effect/MobEffect;Lnet/minecraft/nbt/CompoundTag;)" + EFFECT, true);
            } else if (mixin.endsWith("FormulaConsumerMixin$Attributes")) {
                for (var entry : Map.of("onEffectAdded", ADD, "onEffectUpdated", UPDATE).entrySet())
                    calls(node, method(node, entry.getKey(), entry.getValue(), false), "net/minecraft/world/effect/MobEffect",
                            "addAttributeModifiers", "(" + LIVING + "Lnet/minecraft/world/entity/ai/attributes/AttributeMap;I)V", 1, -1);
            } else if (mixin.endsWith("FormulaConsumerMixin$Synchronization")) {
                method(node, "onEffectAdded", ADD, false);
                method(node, "onEffectUpdated", UPDATE, false);
                method(node, "onEffectRemoved", '(' + EFFECT + ")V", false);
            } else if (mixin.endsWith("FormulaConsumerMixin$Tooltips")) {
                var method = method(node, "addPotionTooltip", "(Ljava/util/List;Ljava/util/List;F)V", true);
                attributeTooltip(node, method);
            } else if (mixin.endsWith("AttributesLibClientMixin")) {
                var method = method(node, "effectGuiTooltips", "(Ldev/shadowsoffire/attributeslib/api/client/GatherEffectScreenTooltipsEvent;)V", false);
                attributeTooltip(node, method);
            } else return "未定义固定挂钩合同";
            return null;
        } catch (IllegalArgumentException failure) { return failure.getMessage(); }
    }

    private static void attributeTooltip(ClassNode node, MethodNode method) {
        for (var call : calls(node, method, "net/minecraft/world/effect/MobEffect", "getAttributeModifierValue",
                "(ILnet/minecraft/world/entity/ai/attributes/AttributeModifier;)D", 1, 1))
            locals(node, method, call, EFFECT, "Ljava/util/Map$Entry;");
        for (var call : calls(node, method, "java/util/Map$Entry", "getKey", "()Ljava/lang/Object;", 1, 1))
            locals(node, method, call, EFFECT);
        var operations = calls(node, method, "net/minecraft/world/entity/ai/attributes/AttributeModifier", "getOperation",
                "()Lnet/minecraft/world/entity/ai/attributes/AttributeModifier$Operation;", 1, -1);
        locals(node, method, operations.get(0), EFFECT, "Ljava/util/Map$Entry;");
    }

    private static MethodNode method(ClassNode node, String name, String descriptor, boolean isStatic) {
        var methods = node.methods.stream().filter(method -> FormulaMinecraftNames.method(method.name).equals(name) && method.desc.equals(descriptor)).toList();
        require(methods.size() == 1, "方法缺失或重载不符：" + name + descriptor);
        var method = methods.get(0);
        require(((method.access & Opcodes.ACC_STATIC) != 0) == isStatic
                        && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0 && method.instructions.size() > 0,
                "方法不是预期的可执行" + (isStatic ? "静态" : "实例") + "方法：" + name + descriptor);
        return method;
    }

    private static void field(ClassNode node, String name, String descriptor) {
        require(node.fields.stream().anyMatch(field -> FormulaMinecraftNames.field(field.name).equals(name) && field.desc.equals(descriptor)
                && (field.access & Opcodes.ACC_STATIC) == 0), "字段缺失或类型不符：" + name + ':' + descriptor);
    }

    private static List<MethodInsnNode> calls(ClassNode node, MethodNode method, String owner, String name,
                                             String descriptor, int minimum, int maximum) {
        var matching = java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                        && FormulaMinecraftNames.method(call.name).equals(name) && call.desc.equals(descriptor)).map(MethodInsnNode.class::cast).toList();
        require(matching.size() >= minimum && (maximum < 0 || matching.size() <= maximum),
                method.name + method.desc + " 中 " + owner + '#' + name + descriptor + " 注入点数量不符：" + matching.size());
        try {
            var frames = new Analyzer<>(new BasicInterpreter()).analyze(node.name, method);
            require(matching.stream().allMatch(call -> frames[method.instructions.indexOf(call)] != null), "注入点不可达：" + method.name + descriptor);
        } catch (AnalyzerException failure) { throw new IllegalArgumentException("无法分析注入点：" + method.name + ": " + failure.getMessage(), failure); }
        return matching;
    }

    private static void locals(ClassNode node, MethodNode method, AbstractInsnNode at, String... descriptors) {
        try {
            var frames = new Analyzer<>(new BasicInterpreter(Opcodes.ASM9) {
                @Override public BasicValue newValue(Type type) {
                    if (type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) return new BasicValue(type);
                    return super.newValue(type);
                }
                @Override public BasicValue merge(BasicValue first, BasicValue second) {
                    return first.equals(second) ? first : super.merge(first, second);
                }
            }).analyze(node.name, method);
            var frame = frames[method.instructions.indexOf(at)];
            require(frame != null, "局部捕获点不可达：" + method.name);
            for (String descriptor : descriptors) {
                int count = 0;
                for (int slot = 0; slot < frame.getLocals(); slot++)
                    if (Type.getType(descriptor).equals(frame.getLocal(slot).getType())) count++;
                require(count == 1, "局部捕获 " + descriptor + " 应唯一，实际 " + count + "：" + method.name);
            }
        } catch (AnalyzerException failure) { throw new IllegalArgumentException("无法验证局部捕获：" + method.name + ": " + failure.getMessage(), failure); }
    }

    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
