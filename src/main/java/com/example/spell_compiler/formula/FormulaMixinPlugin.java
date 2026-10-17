package com.example.spell_compiler.formula;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger(FormulaMixinPlugin.class);
    private static final String DISPATCHER = "com/example/spell_compiler/formula/FormulaRuntimeDispatcher";
    private static final String INVOKER = DISPATCHER + "$Invoker";
    private static final Map<String, Set<String>> HOOKED_METHODS = new ConcurrentHashMap<>();
    private Map<String, Set<String>> targets = Map.of();
    private Map<String, SpellFormulaCatalog.Spell> definitions = Map.of();
    private FormulaAddonDiscovery.Discovery addons;
    private final Map<String, Set<String>> compatibilityMethods = new ConcurrentHashMap<>();
    private String mixinPackage;
    private String dynamicMixin;

    public FormulaMixinPlugin() {}

    FormulaMixinPlugin(SpellFormulaCatalog catalog) {
        initialize(catalog);
    }

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage;
        try {
            initialize(SpellFormulaCatalog.load(FormulaMixinPlugin.class.getClassLoader()));
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取原生法术公式目录", failure);
        }
        try {
            addons = FormulaAddonDiscovery.discover();
        } catch (IOException | RuntimeException | LinkageError failure) {
            LOGGER.warn("[SpellFormula] 无法建立附属法术的早期挂钩，本次启动保留原生法术入口", failure);
        }
    }

    private void initialize(SpellFormulaCatalog catalog) {
        targets = bindings(catalog);
        Map<String, SpellFormulaCatalog.Spell> values = new LinkedHashMap<>();
        catalog.spells().forEach(spell -> values.put(spell.className(), spell));
        definitions = Map.copyOf(values);
    }

    private static Map<String, Set<String>> bindings(SpellFormulaCatalog catalog) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (var spell : catalog.spells()) {
            Set<String> declared = Set.copyOf(spell.formulas().stream()
                    .filter(method -> method.declaringClass().equals(spell.className()))
                    .map(SpellFormulaCatalog.Method::key).toList());
            result.put(spell.className(), declared);
        }
        FormulaSites.targetClasses().forEach(target -> result.putIfAbsent(target, Set.of()));
        FormulaConsumerSites.targetClasses().forEach(target -> result.putIfAbsent(target, Set.of()));
        return Map.copyOf(result);
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (isDynamic(mixinClassName)) return addons != null && addons.spellClasses().contains(targetClassName.replace('.', '/'));
        return targets.containsKey(targetClassName);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        FormulaDynamicMixin.restore();
    }

    @Override
    public List<String> getMixins() {
        if (addons == null || addons.spellClasses().isEmpty()) return null;
        if (dynamicMixin == null) {
            try {
                Set<String> names = addons.spellClasses().stream().map(name -> name.replace('/', '.'))
                        .collect(java.util.stream.Collectors.toSet());
                dynamicMixin = FormulaDynamicMixin.install(mixinPackage, names);
            } catch (RuntimeException | LinkageError failure) {
                FormulaDynamicMixin.restore();
                LOGGER.warn("[SpellFormula] 无法提供附属法术的动态Mixin资源，本次启动保留原生法术入口", failure);
                return null;
            }
        }
        return List.of(dynamicMixin);
    }

    public static Map<String, Set<String>> hookedMethods() {
        return Map.copyOf(HOOKED_METHODS);
    }

    private boolean isDynamic(String mixinClassName) {
        return dynamicMixin != null && mixinClassName.equals(mixinPackage + '.' + dynamicMixin);
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        FormulaDynamicMixin.restore();
        if (isDynamic(mixinClassName)) {
            applyAddon(targetClassName, targetClass);
            return;
        }
        Set<String> selected = targets.get(targetClassName);
        if (selected == null) return;
        var definition = definitions.get(targetClassName);
        var missing = new java.util.HashSet<>(selected);
        targetClass.methods.forEach(method -> missing.remove(method.name + method.desc));
        if (!missing.isEmpty()) throw new IllegalStateException("公式目标方法不存在：" + targetClassName + '#' + missing);
        FormulaDurationSites.apply(targetClass, definition);
        FormulaDirectPowerSites.apply(targetClass, definition);
        FormulaSites.apply(targetClassName, targetClass);
        FormulaConsumerSites.apply(targetClassName, targetClass);
        List<MethodNode> updated = new ArrayList<>(targetClass.methods);
        var unmatched = new java.util.HashSet<>(selected);
        for (int index = 0; index < updated.size(); index++) {
            MethodNode method = updated.get(index);
            String key = method.name + method.desc;
            if (!selected.contains(key)) continue;
            if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0)
                throw new IllegalStateException("公式目标没有可执行方法体：" + targetClassName + '#' + key);
            Type result = Type.getReturnType(method.desc);
            if (result.getSort() != Type.INT && result.getSort() != Type.LONG
                    && result.getSort() != Type.FLOAT && result.getSort() != Type.DOUBLE)
                throw new IllegalStateException("公式目标不是数值方法：" + targetClassName + '#' + key);
            MethodNode replacement = copy(method);
            var binding = definition.formulas().stream().filter(value -> value.key().equals(key)).findFirst().orElseThrow();
            FormulaMethodCompatibility.recordOriginal(targetClass.name, replacement);
            prependGuard(targetClass.name, replacement, FormulaDurationSites.isDuration(binding));
            protectCastReturns(replacement);
            if ((method.access & Opcodes.ACC_STATIC) == 0
                    && (result.getSort() == Type.FLOAT || result.getSort() == Type.DOUBLE))
                protectFiniteReturns(targetClass.name, replacement, result);
            updated.set(index, replacement);
            unmatched.remove(key);
        }
        if (!unmatched.isEmpty()) throw new IllegalStateException("公式目标方法不存在：" + targetClassName + '#' + unmatched);
        targetClass.methods = updated;
        compatibilityMethods.put(targetClass.name, Set.copyOf(selected));
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        Set<String> selected = compatibilityMethods.get(targetClass.name);
        if (selected == null) return;
        FormulaMethodCompatibility.observeTransformed(targetClass, selected);
    }

    private void applyAddon(String targetClassName, ClassNode targetClass) {
        if (addons == null || !addons.spellClasses().contains(targetClass.name)) return;
        Set<String> selected = new java.util.LinkedHashSet<>();
        List<MethodNode> updated = new ArrayList<>(targetClass.methods);
        for (int index = 0; index < updated.size(); index++) {
            MethodNode method = updated.get(index);
            Type result = Type.getReturnType(method.desc);
            if (result.getSort() != Type.INT && result.getSort() != Type.LONG
                    && result.getSort() != Type.FLOAT && result.getSort() != Type.DOUBLE) continue;
            if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC)) != 0
                    || method.instructions.size() == 0) continue;
            String key = method.name + method.desc;
            try {
                MethodNode replacement = copy(method);
                FormulaMethodCompatibility.recordOriginal(targetClass.name, replacement);
                prependGuard(targetClass.name, replacement, false, true);
                updated.set(index, replacement);
                selected.add(key);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("[SpellFormula] {}#{} 无法建立附属公式挂钩，保留原方法", targetClassName, key, failure);
            }
        }
        targetClass.methods = updated;
        compatibilityMethods.put(targetClass.name, Set.copyOf(selected));
        HOOKED_METHODS.put(targetClassName, Set.copyOf(selected));
    }

    private static MethodNode copy(MethodNode original) {
        MethodNode result = new MethodNode(Opcodes.ASM9, original.access, original.name, original.desc,
                original.signature, original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        original.accept(result);
        return result;
    }

    private static void prependGuard(String owner, MethodNode method, boolean duration) {
        prependGuard(owner, method, duration, false);
    }

    private static void prependGuard(String owner, MethodNode method, boolean duration, boolean dynamicReceiver) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        int actor = -1;
        int[] slots = new int[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            slots[index] = slot;
            if (actor < 0 && arguments[index].getSort() == Type.OBJECT
                    && arguments[index].getInternalName().startsWith("net/minecraft/world/entity/")) actor = slot;
            slot += arguments[index].getSize();
        }
        int invokerSlot = Math.max(method.maxLocals, slot);
        InsnList prefix = new InsnList();
        if (dynamicReceiver) {
            prefix.add(new LdcInsnNode(owner.replace('/', '.')));
            prefix.add(new LdcInsnNode(method.name + method.desc));
            prefix.add(isStatic ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, 0));
            prefix.add(actor < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, actor));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCHER, "resolveDeclared",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)L" + INVOKER + ';', false));
        } else {
            prefix.add(new LdcInsnNode(owner.replace('/', '.') + '#' + method.name + method.desc));
            prefix.add(actor < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, actor));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCHER, "resolve",
                    "(Ljava/lang/String;Ljava/lang/Object;)L" + INVOKER + ';', false));
        }
        prefix.add(new VarInsnNode(Opcodes.ASTORE, invokerSlot));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, invokerSlot));
        LabelNode original = new LabelNode();
        prefix.add(new JumpInsnNode(Opcodes.IFNULL, original));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, invokerSlot));
        prefix.add(isStatic ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, 0));
        prefix.add(new LdcInsnNode(arguments.length));
        prefix.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int index = 0; index < arguments.length; index++) {
            Type argument = arguments[index];
            prefix.add(new InsnNode(Opcodes.DUP));
            prefix.add(new LdcInsnNode(index));
            prefix.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slots[index]));
            box(prefix, argument);
            prefix.add(new InsnNode(Opcodes.AASTORE));
        }
        prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCHER, "invoke",
                "(L" + INVOKER + ";Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Number;", false));
        int resultSlot = invokerSlot + 1;
        prefix.add(new VarInsnNode(Opcodes.ASTORE, resultSlot));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        prefix.add(new JumpInsnNode(Opcodes.IFNULL, original));
        prefix.add(new VarInsnNode(Opcodes.ALOAD, resultSlot));
        Type result = Type.getReturnType(method.desc);
        prefix.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", result.getClassName() + "Value",
                "()" + result.getDescriptor(), false));
        if (duration) {
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 0));
            prefix.add(new LdcInsnNode(method.name + method.desc));
            prefix.add(new VarInsnNode(Opcodes.ILOAD, 1));
            prefix.add(new VarInsnNode(Opcodes.ALOAD, 2));
            prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/example/spell_compiler/formula/FormulaDurationSafety",
                    result.getSort() == Type.INT ? "validateOverrideInt" : "validateOverrideFloat",
                    '(' + result.getDescriptor() + "Ljava/lang/Object;Ljava/lang/String;ILjava/lang/Object;)"
                            + result.getDescriptor(), false));
        }
        prefix.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        prefix.add(original);
        boolean expanded = false;
        for (var instruction : method.instructions)
            if (instruction instanceof FrameNode frame && frame.type == Opcodes.F_NEW) expanded = true;
        List<Object> locals = initialLocals(owner, method);
        prefix.add(expanded ? new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, null)
                : new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(prefix);
        method.maxLocals = resultSlot + 1;
        method.maxStack = Math.max(method.maxStack, 8);
    }

    private static void protectCastReturns(MethodNode method) {
        boolean cast = (method.name + method.desc).equals("getCastTime(I)I");
        boolean effective = (method.name + method.desc).equals("getEffectiveCastTime(ILnet/minecraft/world/entity/LivingEntity;)I");
        if (!cast && !effective) return;
        for (var instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.IRETURN) continue;
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            if (effective) code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/example/spell_compiler/formula/FormulaCastSafety",
                    effective ? "guardEffectiveCastTime" : "guardCastTime",
                    effective ? "(ILjava/lang/Object;ILjava/lang/Object;)I" : "(ILjava/lang/Object;I)I", false));
            method.instructions.insertBefore(instruction, code);
        }
        method.maxStack += effective ? 3 : 2;
    }

    private static void protectFiniteReturns(String owner, MethodNode method, Type result) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        boolean expanded = false;
        for (var instruction : method.instructions)
            if (instruction instanceof FrameNode frame && frame.type == Opcodes.F_NEW) expanded = true;
        for (var instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != result.getOpcode(Opcodes.IRETURN)) continue;
            InsnList code = new InsnList();
            code.add(new InsnNode(result.getSize() == 1 ? Opcodes.DUP : Opcodes.DUP2));
            String wrapper = result.getSort() == Type.FLOAT ? "java/lang/Float" : "java/lang/Double";
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "isFinite", '(' + result.getDescriptor() + ")Z", false));
            LabelNode finite = new LabelNode();
            code.add(new JumpInsnNode(Opcodes.IFNE, finite));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new LdcInsnNode(method.name + method.desc));
            code.add(new LdcInsnNode(arguments.length));
            code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            int slot = 1;
            for (int index = 0; index < arguments.length; index++) {
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode(index));
                code.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ILOAD), slot));
                box(code, arguments[index]);
                code.add(new InsnNode(Opcodes.AASTORE));
                slot += arguments[index].getSize();
            }
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/example/spell_compiler/formula/FormulaRuntimeHooks",
                    result.getSort() == Type.FLOAT ? "finiteFloat" : "finiteDouble",
                    '(' + result.getDescriptor() + "Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
                            + result.getDescriptor(), false));
            code.add(finite);
            List<Object> locals = initialLocals(owner, method);
            code.add(new FrameNode(expanded ? Opcodes.F_NEW : Opcodes.F_FULL, locals.size(), locals.toArray(),
                    1, new Object[]{result.getSort() == Type.FLOAT ? Opcodes.FLOAT : Opcodes.DOUBLE}));
            method.instructions.insertBefore(instruction, code);
        }
        method.maxStack = Math.max(method.maxStack, 10);
    }

    private static List<Object> initialLocals(String owner, MethodNode method) {
        List<Object> locals = new ArrayList<>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) locals.add(owner);
        for (Type type : Type.getArgumentTypes(method.desc)) {
            locals.add(switch (type.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
                case Type.LONG -> Opcodes.LONG;
                case Type.FLOAT -> Opcodes.FLOAT;
                case Type.DOUBLE -> Opcodes.DOUBLE;
                case Type.ARRAY -> type.getDescriptor();
                default -> type.getInternalName();
            });
        }
        return locals;
    }

    private static void box(InsnList instructions, Type type) {
        String wrapper = switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
        if (wrapper != null) instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf",
                '(' + type.getDescriptor() + ")L" + wrapper + ';', false));
    }
}
