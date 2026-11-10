package com.example.spell_compiler.formula;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class FormulaAutomaticInjector {
    private static final String HOOKS = "com/example/spell_compiler/formula/FormulaNumericHooks";

    private record Point(MethodNode method, AbstractInsnNode producer, FormulaAutomaticSites.Site site) {}
    private record Arguments(MethodNode method, MethodInsnNode call, Map<Integer, FormulaAutomaticSites.Site> sites) {}

    private FormulaAutomaticInjector() {}

    static Set<String> apply(String owner, ClassNode target, List<FormulaAutomaticSites.Site> sites) {
        String selectedOwner = owner.replace('/', '.');
        List<FormulaAutomaticSites.Site> selected = sites.stream().filter(site -> site.owner().equals(selectedOwner)).toList();
        boolean explosionOwner = FormulaExplosionBindings.targetClasses().contains(selectedOwner);
        if (selected.isEmpty() && !FormulaEntityFlow.hasCaptures(selectedOwner) && !FormulaCastContext.hasRoots(selectedOwner) && !explosionOwner) return Set.of();
        if (!selectedOwner.equals(target.name.replace('/', '.')))
            throw new IllegalArgumentException("自动消费入口与目标类不符：" + selectedOwner);
        List<MethodNode> copies = new ArrayList<>();
        for (MethodNode method : target.methods) {
            MethodNode copy = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc, method.signature,
                    method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
            method.accept(copy);
            copies.add(copy);
        }
        FormulaClassRepository repository = FormulaClassRepository.of(target);
        var movement = FormulaMovementPublication.prepare(repository, target.name, copies, selected);
        Runnable explosions = FormulaExplosionBindings.prepare(selectedOwner, copies);
        Runnable captures = FormulaEntityFlow.prepareCaptures(selectedOwner, copies);
        Runnable scopes = FormulaCastContext.prepareRoots(selectedOwner, copies);
        var inputCaptures = FormulaInputCapture.prepare(repository, target.name, copies, selected);
        Map<AbstractInsnNode, AbstractInsnNode> modifiedResults = new IdentityHashMap<>();
        Map<String, Point> points = new LinkedHashMap<>();
        Map<String, Arguments> arguments = new LinkedHashMap<>();
        Set<String> successful = new LinkedHashSet<>();
        Map<MethodNode, String> fingerprints = new IdentityHashMap<>();
        Map<MethodNode, List<AbstractInsnNode>> executableInstructions = new IdentityHashMap<>();
        for (FormulaAutomaticSites.Site site : selected) {
            var methods = copies.stream().filter(method -> method.name.equals(site.methodName())
                    && method.desc.equals(site.methodDescriptor())).toList();
            if (methods.size() != 1) throw mismatch(site, "目标方法数量不符");
            MethodNode method = methods.get(0);
            if (!fingerprints.computeIfAbsent(method, FormulaAutomaticInjector::fingerprint).equals(site.methodFingerprint()))
                throw mismatch(site, "原始方法结构已经变化");
            validateContext(repository, target.name, method, site);
            List<AbstractInsnNode> instructions = executableInstructions.computeIfAbsent(method, FormulaAutomaticInjector::executable);
            if (site.producerIndex() < 0 || site.producerIndex() >= instructions.size())
                throw mismatch(site, "数值生产点不存在");
            AbstractInsnNode producer = instructions.get(site.producerIndex());
            if (site.argumentIndex() >= 0) {
                if (!(producer instanceof MethodInsnNode call)) throw mismatch(site, "消费锚点不是方法调用");
                Type[] types = Type.getArgumentTypes(call.desc);
                if (site.argumentIndex() >= types.length
                        || !types[site.argumentIndex()].getDescriptor().equals(site.definition().valueType().descriptor()))
                    throw mismatch(site, "消费参数位置或类型已经变化");
                String key = method.name + method.desc + '#' + site.producerIndex();
                Arguments selectedArguments = arguments.computeIfAbsent(key, ignored -> new Arguments(method, call, new LinkedHashMap<>()));
                var previous = selectedArguments.sites().putIfAbsent(site.argumentIndex(), site);
                if (previous != null && !compatible(previous, site)) throw mismatch(site, "共享消费参数的来源上下文存在冲突");
                successful.add(site.definition().dispatchKey());
                continue;
            }
            Type type = resultType(producer);
            if (type == null || !type.getDescriptor().equals(site.definition().valueType().descriptor()))
                throw mismatch(site, "数值生产点类型已经变化");
            String key = method.name + method.desc + '#' + site.producerIndex();
            Point previous = points.putIfAbsent(key, new Point(method, producer, site));
            if (previous != null && !compatible(previous.site(), site))
                throw mismatch(site, "共享生产点的类型或来源上下文存在冲突");
            successful.add(site.definition().dispatchKey());
        }
        for (Point point : points.values()) {
            var site = point.site();
            var type = site.definition().valueType();
            InsnList inserted = new InsnList();
            hook(inserted, site, inputCaptures);
            AbstractInsnNode last = inserted.getLast();
            point.method().instructions.insert(point.producer(), inserted);
            modifiedResults.put(point.producer(), last);
            point.method().maxStack += 16;
        }
        for (Arguments selectedArguments : arguments.values()) {
            MethodNode method = selectedArguments.method();
            if (selectedArguments.sites().size() == 1 && selectedArguments.sites().containsKey(0)) {
                var site = selectedArguments.sites().get(0);
                var store = FormulaHealingEventFlow.sharedStore(repository, target.name, method, selectedArguments.call(), site);
                if (store != null) {
                    InsnList shared = new InsnList();
                    hook(shared, site, inputCaptures);
                    method.instructions.insertBefore(store, shared);
                    method.maxStack += 16;
                    continue;
                }
            }
            Type[] types = Type.getArgumentTypes(selectedArguments.call().desc);
            int[] slots = new int[types.length];
            int next = method.maxLocals;
            for (int index = 0; index < types.length; index++) {
                slots[index] = next;
                next += types[index].getSize();
            }
            InsnList inserted = new InsnList();
            for (int index = types.length - 1; index >= 0; index--)
                inserted.add(new VarInsnNode(types[index].getOpcode(Opcodes.ISTORE), slots[index]));
            for (var entry : selectedArguments.sites().entrySet()) {
                int index = entry.getKey();
                inserted.add(new VarInsnNode(types[index].getOpcode(Opcodes.ILOAD), slots[index]));
                hook(inserted, entry.getValue(), inputCaptures);
                inserted.add(new VarInsnNode(types[index].getOpcode(Opcodes.ISTORE), slots[index]));
            }
            for (int index = 0; index < types.length; index++)
                inserted.add(new VarInsnNode(types[index].getOpcode(Opcodes.ILOAD), slots[index]));
            method.instructions.insertBefore(selectedArguments.call(), inserted);
            method.maxLocals = next;
            method.maxStack += 16;
        }
        inputCaptures.apply(modifiedResults);
        captures.run();
        explosions.run();
        boolean synchronizedPublication = movement.apply();
        scopes.run();
        if (!inputCaptures.empty() || synchronizedPublication || explosionOwner) {
            ClassNode framed = new ClassNode(Opcodes.ASM9);
            target.accept(framed);
            framed.methods = copies;
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                @Override protected String getCommonSuperClass(String first, String second) {
                    return repository.commonSuperClass(first, second);
                }
            };
            framed.accept(writer);
            ClassNode decoded = new ClassNode(Opcodes.ASM9);
            new ClassReader(writer.toByteArray()).accept(decoded, ClassReader.EXPAND_FRAMES);
            copies = decoded.methods;
        }
        target.methods = copies;
        return Set.copyOf(successful);
    }

    private static boolean compatible(FormulaAutomaticSites.Site first, FormulaAutomaticSites.Site second) {
        return first.kind() == second.kind() && first.definition().valueType() == second.definition().valueType()
                && first.methodFingerprint().equals(second.methodFingerprint()) && first.casterSlot() == second.casterSlot()
                && first.levelSlot() == second.levelSlot() && first.spellSlot() == second.spellSlot()
                && first.argumentIndex() == second.argumentIndex() && first.contextMode() == second.contextMode()
                && first.contextSlot() == second.contextSlot()
                && FormulaAutomaticSites.captureLayout(first).equals(FormulaAutomaticSites.captureLayout(second));
    }

    private static void hook(InsnList instructions, FormulaAutomaticSites.Site site, FormulaInputCapture.Plan captures) {
        var type = site.definition().valueType();
        LabelNode unchanged = null;
        String captured = "";
        if (!site.inputs().isEmpty()) {
            unchanged = new LabelNode();
            instructions.add(new LdcInsnNode(site.definition().dispatchKey()));
            reference(instructions, site.spellSlot());
            reference(instructions, site.casterSlot());
            reference(instructions, site.contextSlot());
            instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/example/spell_compiler/formula/FormulaCapturedValues", "active",
                    "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", false));
            instructions.add(new JumpInsnNode(Opcodes.IFEQ, unchanged));
            captures.emit(instructions, site);
            captured = "[Ljava/lang/Object;";
        }
        instructions.add(new LdcInsnNode(site.definition().dispatchKey()));
        if (site.contextMode() == FormulaAutomaticSites.ContextMode.SCOPED) {
            instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/example/spell_compiler/formula/FormulaCastContext", type.hookMethod(),
                    '(' + type.descriptor() + captured + "Ljava/lang/String;)" + type.descriptor(), false));
            if (unchanged != null) instructions.add(unchanged);
            return;
        }
        if (site.contextMode() == FormulaAutomaticSites.ContextMode.ENTITY) {
            reference(instructions, site.contextSlot());
            instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/example/spell_compiler/formula/FormulaEntityContext", type.hookMethod(),
                    '(' + type.descriptor() + captured + "Ljava/lang/String;Ljava/lang/Object;)" + type.descriptor(), false));
            if (unchanged != null) instructions.add(unchanged);
            return;
        }
        reference(instructions, site.spellSlot());
        instructions.add(site.levelSlot() < 0 ? new InsnNode(Opcodes.ICONST_M1) : new VarInsnNode(Opcodes.ILOAD, site.levelSlot()));
        reference(instructions, site.casterSlot());
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, type.hookMethod(),
                '(' + type.descriptor() + captured + "Ljava/lang/String;Ljava/lang/Object;ILjava/lang/Object;)" + type.descriptor(), false));
        if (unchanged != null) instructions.add(unchanged);
    }

    private static void validateContext(FormulaClassRepository repository, String owner, MethodNode method, FormulaAutomaticSites.Site site) {
        Map<Integer, Type> parameters = new LinkedHashMap<>();
        int slot = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) parameters.put(slot++, Type.getType(Object.class));
        for (Type type : Type.getArgumentTypes(method.desc)) {
            parameters.put(slot, type);
            slot += type.getSize();
        }
        boolean localContext = site.contextMode() == FormulaAutomaticSites.ContextMode.ENTITY && !parameters.containsKey(site.contextSlot());
        if (localContext && !FormulaUnifiedAnalysis.verifiedEventContext(repository, owner, method, site))
            throw mismatch(site, "局部实体上下文未证明来自实际药效消费");
        if (!contextIntact(method, site.casterSlot(), site.levelSlot(), site.spellSlot(), localContext ? -1 : site.contextSlot()))
            throw mismatch(site, "原参数槽被改写，无法保持施法上下文");
        if (site.levelSlot() >= 0 && !Type.INT_TYPE.equals(parameters.get(site.levelSlot())))
            throw mismatch(site, "等级不是原方法的整数参数");
        for (int reference : new int[]{site.spellSlot(), site.casterSlot()}) {
            if (reference < 0) continue;
            Type type = parameters.get(reference);
            if (type == null || type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY)
                throw mismatch(site, "法术或施法者不是原方法的对象参数");
        }
        if (site.contextMode() == FormulaAutomaticSites.ContextMode.ENTITY && !localContext) {
            Type context = parameters.get(site.contextSlot());
            if (context == null || context.getSort() != Type.OBJECT && context.getSort() != Type.ARRAY)
                throw mismatch(site, "实体上下文不是原方法的对象参数");
            if (method.name.equals("<init>") && site.contextSlot() == 0)
                throw mismatch(site, "构造中的实体上下文尚未初始化");
        }
        if (method.name.equals("<init>") && (site.spellSlot() == 0 || site.casterSlot() == 0))
            throw mismatch(site, "构造中的接收对象尚未初始化");
    }

    static boolean contextIntact(MethodNode method, int... slots) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof IincInsnNode increment) {
                for (int slot : slots) if (slot >= 0 && increment.var == slot) return false;
            } else if (instruction instanceof VarInsnNode variable
                    && variable.getOpcode() >= Opcodes.ISTORE && variable.getOpcode() <= Opcodes.ASTORE) {
                int width = variable.getOpcode() == Opcodes.LSTORE || variable.getOpcode() == Opcodes.DSTORE ? 2 : 1;
                for (int slot : slots) if (slot >= variable.var && slot < variable.var + width) return false;
            }
        }
        return true;
    }

    private static void reference(InsnList instructions, int slot) {
        instructions.add(slot < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, slot));
    }

    private static IllegalArgumentException mismatch(FormulaAutomaticSites.Site site, String reason) {
        return new IllegalArgumentException("自动消费数值锚点校验失败：" + site.owner() + '#' + site.methodName()
                + site.methodDescriptor() + ": " + reason);
    }

    static List<AbstractInsnNode> executable(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    static Type resultType(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (instruction instanceof LdcInsnNode literal) {
            if (literal.cst instanceof Integer) return Type.INT_TYPE;
            if (literal.cst instanceof Float) return Type.FLOAT_TYPE;
            if (literal.cst instanceof Double) return Type.DOUBLE_TYPE;
            if (literal.cst instanceof Long) return Type.LONG_TYPE;
        }
        if (instruction instanceof IntInsnNode && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return Type.INT_TYPE;
        if (instruction instanceof FieldInsnNode field && (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC))
            return Type.getType(field.desc);
        if (instruction instanceof MethodInsnNode call) return Type.getReturnType(call.desc);
        if (instruction instanceof InvokeDynamicInsnNode call) return Type.getReturnType(call.desc);
        return switch (opcode) {
            case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
                    Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.ILOAD, Opcodes.IADD, Opcodes.ISUB, Opcodes.IMUL,
                    Opcodes.IDIV, Opcodes.IREM, Opcodes.INEG, Opcodes.IAND, Opcodes.IOR, Opcodes.IXOR,
                    Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR, Opcodes.L2I, Opcodes.F2I, Opcodes.D2I,
                    Opcodes.I2B, Opcodes.I2C, Opcodes.I2S -> Type.INT_TYPE;
            case Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2, Opcodes.FLOAD, Opcodes.FADD,
                    Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM, Opcodes.FNEG,
                    Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> Type.FLOAT_TYPE;
            case Opcodes.DCONST_0, Opcodes.DCONST_1, Opcodes.DLOAD, Opcodes.DADD, Opcodes.DSUB, Opcodes.DMUL,
                    Opcodes.DDIV, Opcodes.DREM, Opcodes.DNEG, Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> Type.DOUBLE_TYPE;
            case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.LLOAD, Opcodes.LADD, Opcodes.LSUB, Opcodes.LMUL,
                    Opcodes.LDIV, Opcodes.LREM, Opcodes.LNEG, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR,
                    Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR, Opcodes.I2L, Opcodes.F2L, Opcodes.D2L -> Type.LONG_TYPE;
            default -> null;
        };
    }

    static String fingerprint(MethodNode method) {
        StringBuilder text = new StringBuilder(method.name).append(method.desc).append(':')
                .append(method.access & Opcodes.ACC_STATIC);
        Map<LabelNode, Integer> positions = new IdentityHashMap<>();
        int position = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LabelNode label) positions.put(label, position);
            if (instruction.getOpcode() >= 0) position++;
        }
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() < 0) continue;
            text.append('|').append(instruction.getOpcode()).append(':');
            if (instruction instanceof VarInsnNode local) text.append(local.var);
            else if (instruction instanceof IntInsnNode literal) text.append(literal.operand);
            else if (instruction instanceof LdcInsnNode literal) constant(text, literal.cst);
            else if (instruction instanceof TypeInsnNode type) text.append(type.desc);
            else if (instruction instanceof FieldInsnNode field) text.append(field.owner).append('#').append(field.name).append(field.desc);
            else if (instruction instanceof MethodInsnNode call)
                text.append(call.owner).append('#').append(call.name).append(call.desc).append(':').append(call.itf);
            else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                text.append(dynamic.name).append(dynamic.desc);
                constant(text, dynamic.bsm);
                for (Object argument : dynamic.bsmArgs) constant(text, argument);
            } else if (instruction instanceof JumpInsnNode jump) text.append(positions.get(jump.label));
            else if (instruction instanceof IincInsnNode increment) text.append(increment.var).append(':').append(increment.incr);
            else if (instruction instanceof TableSwitchInsnNode table) {
                text.append(table.min).append(':').append(table.max).append(':').append(positions.get(table.dflt));
                for (LabelNode label : table.labels) text.append(':').append(positions.get(label));
            } else if (instruction instanceof LookupSwitchInsnNode table) {
                text.append(table.keys).append(':').append(positions.get(table.dflt));
                for (LabelNode label : table.labels) text.append(':').append(positions.get(label));
            } else if (instruction instanceof MultiANewArrayInsnNode array) text.append(array.desc).append(':').append(array.dims);
        }
        for (TryCatchBlockNode handler : method.tryCatchBlocks)
            text.append("!catch:").append(positions.get(handler.start)).append(':').append(positions.get(handler.end))
                    .append(':').append(positions.get(handler.handler)).append(':').append(handler.type);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("无法计算自动消费入口指纹", failure);
        }
    }

    private static void constant(StringBuilder text, Object value) {
        if (value instanceof Float number) text.append("F").append(Integer.toHexString(Float.floatToRawIntBits(number)));
        else if (value instanceof Double number) text.append("D").append(Long.toHexString(Double.doubleToRawLongBits(number)));
        else if (value instanceof Handle handle)
            text.append("H").append(handle.getTag()).append(handle.getOwner()).append('#').append(handle.getName())
                    .append(handle.getDesc()).append(handle.isInterface());
        else if (value instanceof ConstantDynamic dynamic) {
            text.append("C").append(dynamic.getName()).append(dynamic.getDescriptor());
            constant(text, dynamic.getBootstrapMethod());
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) constant(text, dynamic.getBootstrapMethodArgument(index));
        } else {
            String rendered = String.valueOf(value);
            text.append(value == null ? "null" : value.getClass().getName()).append(':').append(rendered.length()).append(':').append(rendered);
        }
    }
}
