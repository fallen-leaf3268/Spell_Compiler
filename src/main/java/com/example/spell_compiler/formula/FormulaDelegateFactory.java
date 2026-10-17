package com.example.spell_compiler.formula;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class FormulaDelegateFactory {
    private static final String ACCESSORS = "com/example/spell_compiler/formula/FormulaDelegateAccessors";
    private static final AtomicLong SERIAL = new AtomicLong();
    private record CacheKey(Map<String, String> overrides, boolean named, boolean widePower, String receiverClass) {}
    private record CompiledInvoker(FormulaRuntimeDispatcher.Invoker delegate, boolean nativePower)
            implements FormulaRuntimeDispatcher.Invoker {
        @Override
        public Number invoke(Object receiver, Object[] arguments) throws Throwable {
            return delegate.invoke(receiver, arguments);
        }
    }
    private static final class Cache {
        private final LinkedHashMap<CacheKey, Map<String, FormulaRuntimeDispatcher.Invoker>> entries =
                new LinkedHashMap<>(3, 0.75f, true);
    }
    private static final ClassValue<Cache> CACHE = new ClassValue<>() {
        @Override
        protected Cache computeValue(Class<?> type) {
            return new Cache();
        }
    };

    private FormulaDelegateFactory() {}

    public static Map<String, FormulaRuntimeDispatcher.Invoker> compile(Class<?> owner, Map<String, String> overrides) {
        return compile(owner, overrides, false);
    }

    public static Map<String, FormulaRuntimeDispatcher.Invoker> compileSpell(Class<?> owner,
            SpellFormulaCatalog.Spell definition, Map<String, String> overrides) {
        return compileSpell(owner, definition, overrides, false);
    }

    public static Map<String, FormulaRuntimeDispatcher.Invoker> compileSpell(Class<?> owner,
            SpellFormulaCatalog.Spell definition, Map<String, String> overrides, boolean widePower) {
        return compileSpell(owner, definition, overrides, widePower, true);
    }

    static Map<String, FormulaRuntimeDispatcher.Invoker> compileNativeSpell(Class<?> owner,
            SpellFormulaCatalog.Spell definition, Map<String, String> overrides, boolean widePower) {
        return compileSpell(owner, definition, overrides, widePower, false);
    }

    private static Map<String, FormulaRuntimeDispatcher.Invoker> compileSpell(Class<?> owner,
            SpellFormulaCatalog.Spell definition, Map<String, String> overrides, boolean widePower, boolean configured) {
        if (!owner.getName().equals(definition.className()))
            throw new IllegalArgumentException("公式目录与法术类不符：" + definition.id());
        Map<String, SpellFormulaCatalog.Method> methods = new LinkedHashMap<>();
        definition.formulas().forEach(method -> methods.put(method.key(), method));
        Map<String, String> nativeMethods = new LinkedHashMap<>();
        Map<String, String> siteMethods = new LinkedHashMap<>();
        for (var entry : overrides.entrySet()) {
            var binding = methods.get(entry.getKey());
            if (binding == null) throw new IllegalArgumentException("未知公式绑定：" + entry.getKey());
            if (binding.declaringClass().equals("com.example.spell_compiler.formula.FormulaSiteValues"))
                siteMethods.put(entry.getKey(), entry.getValue());
            else nativeMethods.put(entry.getKey(), entry.getValue());
        }
        Map<String, FormulaRuntimeDispatcher.Invoker> result = new LinkedHashMap<>(
                compile(owner, nativeMethods, false, widePower, definition.className()));
        if (!siteMethods.isEmpty()) {
            try {
                Class<?> values = Class.forName("com.example.spell_compiler.formula.FormulaSiteValues", false,
                        owner.getClassLoader());
                result.putAll(compile(values, siteMethods, false, widePower));
            } catch (ClassNotFoundException failure) {
                throw new IllegalArgumentException("找不到原生数值入口", failure);
            }
        }
        Map<String, FormulaRuntimeDispatcher.Invoker> checked = new LinkedHashMap<>();
        result.forEach((key, invoker) -> {
            var binding = methods.get(key);
            if (configured) FormulaMethodCompatibility.requireCompatible(binding.declaringClass(), key);
            boolean finalDamage = configured && damageValue(binding.alias())
                    && invoker instanceof CompiledInvoker compiled && !compiled.nativePower();
            checked.put(key, (receiver, arguments) -> {
                Number value = invoker.invoke(receiver, arguments);
                if (binding.declaringClass().equals("com.example.spell_compiler.formula.FormulaSiteValues"))
                    FormulaSiteHooks.validateValue(key, value, arguments);
                else if (requiresNonnegativeResult(binding) && value.doubleValue() < 0)
                    throw new ArithmeticException("法术消耗和时间不能为负数：" + binding.alias());
                if (finalDamage) value = finishDamage(value, receiver, arguments, owner, definition, binding);
                return value;
            });
        });
        return Map.copyOf(checked);
    }

    private static boolean damageValue(String alias) {
        return alias.equals("damage") || alias.endsWith("_damage") && !alias.startsWith("percent_");
    }

    private static Number finishDamage(Number value, Object receiver, Object[] arguments, Class<?> owner,
                                       SpellFormulaCatalog.Spell definition, SpellFormulaCatalog.Method binding) {
        Object spell = receiver;
        Object caster = null;
        for (Object argument : arguments)
            if (argument instanceof net.minecraft.world.entity.Entity) {
                caster = argument;
                break;
            }
        if (binding.declaringClass().equals("com.example.spell_compiler.formula.FormulaSiteValues")) {
            spell = arguments[arguments.length - 2];
            caster = arguments[arguments.length - 1];
        } else if (spell == null && io.redspace.ironsspellbooks.api.spells.AbstractSpell.class.isAssignableFrom(owner)) {
            spell = io.redspace.ironsspellbooks.api.registry.SpellRegistry.REGISTRY.get()
                    .getValue(net.minecraft.resources.ResourceLocation.parse(definition.id()));
        }
        float multiplier = FormulaFunctions.requireFinite(FormulaIntegration.damageMultiplier(spell, caster));
        if (multiplier == 1) return value;
        if (value instanceof Float) return FormulaFunctions.requireFinite(value.floatValue() * multiplier);
        if (value instanceof Double) return FormulaFunctions.requireFinite(value.doubleValue() * multiplier);
        var product = BigDecimal.valueOf(value.longValue()).multiply(new BigDecimal((double) multiplier)).toBigInteger();
        if (value instanceof Integer) return product.intValueExact();
        if (value instanceof Long) return product.longValueExact();
        throw new IllegalArgumentException("不支持的伤害数值类型：" + value.getClass().getName());
    }

    private static boolean requiresNonnegativeResult(SpellFormulaCatalog.Method binding) {
        String alias = binding.alias();
        return java.util.Set.of("getManaCost", "getSpellCooldown", "getCastTime", "getEffectiveCastTime")
                .contains(binding.name()) || FormulaNames.duration(alias);
    }

    static Map<String, FormulaRuntimeDispatcher.Invoker> compile(Class<?> owner, Map<String, String> overrides,
                                                                boolean forceNamed) {
        return compile(owner, overrides, forceNamed, false);
    }

    static Map<String, FormulaRuntimeDispatcher.Invoker> compile(Class<?> owner, Map<String, String> overrides,
                                                                boolean forceNamed, boolean widePower) {
        return compile(owner, overrides, forceNamed, widePower, null);
    }

    private static Map<String, FormulaRuntimeDispatcher.Invoker> compile(Class<?> owner, Map<String, String> overrides,
                                                                        boolean forceNamed, boolean widePower,
                                                                        String receiverClass) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(overrides);
        if (overrides.isEmpty()) return Map.of();
        Map<String, String> stable = Map.copyOf(overrides);
        Cache cache = CACHE.get(owner);
        CacheKey key = new CacheKey(stable, forceNamed, widePower, receiverClass);
        synchronized (cache) {
            var existing = cache.entries.get(key);
            if (existing != null) return existing;
            var result = compileUncached(owner, stable, forceNamed, widePower, receiverClass);
            cache.entries.put(key, result);
            if (cache.entries.size() > 2) cache.entries.remove(cache.entries.keySet().iterator().next());
            return result;
        }
    }

    private static Map<String, FormulaRuntimeDispatcher.Invoker> compileUncached(Class<?> owner,
                                                                               Map<String, String> overrides,
                                                                               boolean forceNamed, boolean widePower,
                                                                               String receiverClass) {
        Map<Class<?>, Map<String, String>> groups = declaringGroups(owner, overrides);
        if (groups.size() != 1 || !groups.containsKey(owner)) {
            Map<String, FormulaRuntimeDispatcher.Invoker> combined = new LinkedHashMap<>();
            groups.forEach((declaring, changes) -> combined.putAll(
                    compile(declaring, changes, forceNamed, widePower, receiverClass)));
            return Map.copyOf(combined);
        }
        byte[] original = original(owner);
        ClassNode source = new ClassNode();
        new ClassReader(original).accept(source, 0);
        FormulaClassRepository repository = FormulaClassRepository.of(source, owner.getClassLoader());
        Map<String, String> changed = new LinkedHashMap<>();
        Map<String, Boolean> powerStages = new LinkedHashMap<>();
        for (MethodNode method : source.methods) {
            String key = method.name + method.desc;
            if (!overrides.containsKey(key)) continue;
            var analysis = AutomaticFormulaExtractor.analyzeRuntime(source, method, repository, receiverClass);
            if (!analysis.supported()) throw new IllegalArgumentException(key + ": " + analysis.rejection());
            Map<String, Type> types = new LinkedHashMap<>();
            analysis.formula().variables().forEach((name, value) -> types.put(name, value.type()));
            String resolved = ReadableFormula.resolve(analysis.formula().expression(), overrides.get(key), types).expression();
            powerStages.put(key, analysis.formula().nativePowers().keySet().stream()
                    .anyMatch(marker -> Pattern.compile("\\b" + Pattern.quote(marker) + "\\s*\\(").matcher(resolved).find()));
            if (widePower && FormulaWidePower.applies(analysis.formula().variables())
                    || !compact(resolved).equals(compact(analysis.formula().expression()))) changed.put(key, overrides.get(key));
        }
        if (changed.isEmpty()) return Map.of();
        byte[] transformed = FormulaCompiler.transformRuntime(original, changed, repository, widePower, receiverClass);
        ClassNode compiled = new ClassNode();
        new ClassReader(transformed).accept(compiled, 0);
        String className = source.name + "$$ApothFormulaDelegate$" + SERIAL.incrementAndGet();
        ClassNode generated = new ClassNode(Opcodes.ASM9);
        generated.version = Opcodes.V17;
        generated.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
        generated.name = className;
        generated.superName = "java/lang/Object";
        Map<String, MethodNode> delegates = new LinkedHashMap<>();
        Map<String, Boolean> statics = new LinkedHashMap<>();
        Map<String, Boolean> synchronizedMethods = new LinkedHashMap<>();
        for (MethodNode method : compiled.methods) {
            String key = method.name + method.desc;
            if (!changed.containsKey(key)) continue;
            boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
            MethodNode delegate = copy(method);
            delegate.name = "evaluate$" + delegates.size();
            delegate.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
            delegate.signature = null;
            if (!isStatic) {
                Type[] arguments = Type.getArgumentTypes(method.desc);
                Type[] adjusted = new Type[arguments.length + 1];
                adjusted[0] = Type.getObjectType(source.name);
                System.arraycopy(arguments, 0, adjusted, 1, arguments.length);
                delegate.desc = Type.getMethodDescriptor(Type.getReturnType(method.desc), adjusted);
            }
            bridge(delegate, owner, repository);
            generated.methods.add(delegate);
            delegates.put(key, delegate);
            statics.put(key, isStatic);
            synchronizedMethods.put(key, (method.access & Opcodes.ACC_SYNCHRONIZED) != 0);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String left, String right) {
                return repository.commonSuperClass(left, right);
            }
        };
        generated.accept(writer);
        try {
            MethodHandles.Lookup targetLookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            MethodHandles.Lookup definedLookup;
            Class<?> defined;
            if (!forceNamed && targetLookup.hasFullPrivilegeAccess()) {
                definedLookup = targetLookup.defineHiddenClass(writer.toByteArray(), false, MethodHandles.Lookup.ClassOption.NESTMATE);
                defined = definedLookup.lookupClass();
            } else {
                defined = targetLookup.defineClass(writer.toByteArray());
                definedLookup = targetLookup;
            }
            Map<String, FormulaRuntimeDispatcher.Invoker> invokers = new LinkedHashMap<>();
            for (var entry : delegates.entrySet()) {
                String key = entry.getKey();
                MethodNode method = entry.getValue();
                MethodType signature = MethodType.fromMethodDescriptorString(method.desc, owner.getClassLoader());
                MethodHandle handle = definedLookup.findStatic(defined, method.name, signature);
                int argumentCount = Type.getArgumentTypes(key.substring(key.indexOf('('))).length;
                handle = handle.asSpreader(Object[].class, argumentCount);
                if (statics.get(key)) {
                    handle = handle.asType(MethodType.methodType(Number.class, Object[].class));
                    handle = MethodHandles.dropArguments(handle, 0, Object.class);
                } else handle = handle.asType(MethodType.methodType(Number.class, Object.class, Object[].class));
                MethodHandle invocation = handle;
                boolean locked = synchronizedMethods.get(key);
                boolean isStatic = statics.get(key);
                FormulaRuntimeDispatcher.Invoker invoker = (receiver, arguments) -> {
                    if (!locked) return (Number) invocation.invokeExact(receiver, arguments);
                    synchronized (isStatic ? owner : Objects.requireNonNull(receiver)) {
                        return (Number) invocation.invokeExact(receiver, arguments);
                    }
                };
                invokers.put(key, new CompiledInvoker(invoker, powerStages.get(key)));
            }
            return Map.copyOf(invokers);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("无法创建法术公式委托：" + owner.getName(), failure);
        }
    }

    private static Map<Class<?>, Map<String, String>> declaringGroups(Class<?> owner, Map<String, String> overrides) {
        Map<String, String> unresolved = new LinkedHashMap<>(overrides);
        Map<Class<?>, Map<String, String>> groups = new LinkedHashMap<>();
        for (Class<?> cursor = owner; cursor != null && !unresolved.isEmpty(); cursor = cursor.getSuperclass()) {
            ClassNode source = new ClassNode();
            new ClassReader(original(cursor)).accept(source, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            Map<String, String> selected = new LinkedHashMap<>();
            for (MethodNode method : source.methods) {
                String key = method.name + method.desc;
                if (!unresolved.containsKey(key)) continue;
                if (cursor != owner && (method.access & Opcodes.ACC_PRIVATE) != 0)
                    throw new IllegalArgumentException("公式目标不是可继承方法：" + cursor.getName() + '#' + key);
                selected.put(key, unresolved.remove(key));
            }
            if (!selected.isEmpty()) groups.put(cursor, Map.copyOf(selected));
        }
        if (!unresolved.isEmpty()) throw new IllegalArgumentException("存在未找到的公式目标方法：" + owner.getName() + '#' + unresolved.keySet());
        return groups;
    }

    private static byte[] original(Class<?> owner) {
        byte[] original;
        String resource = '/' + owner.getName().replace('.', '/') + ".class";
        try (var stream = owner.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("找不到原始法术类资源：" + resource);
            original = stream.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalArgumentException("无法读取法术类：" + owner.getName(), failure);
        }
        ClassNode source = new ClassNode();
        new ClassReader(original).accept(source, 0);
        if (!source.name.equals(owner.getName().replace('.', '/')))
            throw new IllegalArgumentException("原始法术类资源名称不匹配：" + owner.getName());
        return original;
    }

    private static void bridge(MethodNode method, Class<?> owner, FormulaClassRepository repository) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field
                    && (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)
                    && numeric(Type.getType(field.desc))) {
                var declaration = repository.resolveField(field.owner, field.name, field.desc);
                if (declaration == null) throw new IllegalArgumentException("找不到数值字段声明：" + field.owner + '#' + field.name);
                int index = FormulaDelegateAccessors.registerField(resolve(declaration.owner().name, owner), field.name, field.desc,
                        field.getOpcode() == Opcodes.GETSTATIC);
                String getter = switch (field.desc) {
                    case "I" -> "readInt";
                    case "J" -> "readLong";
                    case "F" -> "readFloat";
                    case "D" -> "readDouble";
                    default -> throw new IllegalArgumentException("不支持的数值字段：" + field.desc);
                };
                InsnList replacement = new InsnList();
                if (field.getOpcode() == Opcodes.GETSTATIC) replacement.add(new InsnNode(Opcodes.ACONST_NULL));
                replacement.add(new LdcInsnNode(index));
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ACCESSORS, getter,
                        "(Ljava/lang/Object;I)" + field.desc, false));
                method.instructions.insertBefore(instruction, replacement);
                method.instructions.remove(instruction);
            } else if (instruction instanceof MethodInsnNode call) {
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("io/redspace/ironsspellbooks/api/config/SpellConfigManager")
                        && call.name.equals("getSpellConfigValue")) {
                    call.owner = "com/example/spell_compiler/formula/FormulaNativeValues";
                    call.name = "readConfig";
                }
                if (call.owner.equals("java/lang/invoke/MethodHandle")) continue;
                if (call.name.equals("<init>")) throw new IllegalArgumentException("数值委托不支持构造调用：" + call.owner);
                var declaration = repository.resolveMethod(call.owner, call.name, call.desc);
                ClassNode symbolicOwner = repository.classNode(call.owner);
                if (declaration == null || symbolicOwner == null)
                    throw new IllegalArgumentException("找不到公式辅助方法声明：" + call.owner + '#' + call.name + call.desc);
                boolean isPublic = (declaration.method().access & Opcodes.ACC_PUBLIC) != 0
                        && (declaration.owner().access & Opcodes.ACC_PUBLIC) != 0
                        && (symbolicOwner.access & Opcodes.ACC_PUBLIC) != 0;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL || !isPublic)
                    bridgeCall(method, call, owner, resolve(call.owner, owner));
            }
        }
    }

    private static void bridgeCall(MethodNode method, MethodInsnNode call, Class<?> owner, Class<?> target) {
        int index = FormulaDelegateAccessors.registerMethod(owner, target, call.name, call.desc, call.getOpcode());
        Type[] types = Type.getArgumentTypes(call.desc);
        int[] slots = new int[types.length];
        InsnList replacement = new InsnList();
        for (int argument = types.length - 1; argument >= 0; argument--) {
            slots[argument] = method.maxLocals;
            method.maxLocals += types[argument].getSize();
            replacement.add(new VarInsnNode(types[argument].getOpcode(Opcodes.ISTORE), slots[argument]));
        }
        if (call.getOpcode() == Opcodes.INVOKESTATIC) replacement.add(new InsnNode(Opcodes.ACONST_NULL));
        replacement.add(new LdcInsnNode(types.length));
        replacement.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int argument = 0; argument < types.length; argument++) {
            replacement.add(new InsnNode(Opcodes.DUP));
            replacement.add(new LdcInsnNode(argument));
            replacement.add(new VarInsnNode(types[argument].getOpcode(Opcodes.ILOAD), slots[argument]));
            box(replacement, types[argument]);
            replacement.add(new InsnNode(Opcodes.AASTORE));
        }
        replacement.add(new LdcInsnNode(index));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ACCESSORS, "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;I)Ljava/lang/Object;", false));
        Type result = Type.getReturnType(call.desc);
        unbox(replacement, result);
        method.instructions.insertBefore(call, replacement);
        method.instructions.remove(call);
    }

    private static void box(InsnList instructions, Type type) {
        String wrapper = wrapper(type);
        if (wrapper == null) return;
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf",
                '(' + type.getDescriptor() + ")L" + wrapper + ';', false));
    }

    private static void unbox(InsnList instructions, Type type) {
        if (type.getSort() == Type.VOID) {
            instructions.add(new InsnNode(Opcodes.POP));
            return;
        }
        String wrapper = wrapper(type);
        if (wrapper == null) {
            instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
            return;
        }
        instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper));
        String method = switch (type.getSort()) {
            case Type.BOOLEAN -> "booleanValue";
            case Type.BYTE -> "byteValue";
            case Type.SHORT -> "shortValue";
            case Type.CHAR -> "charValue";
            case Type.INT -> "intValue";
            case Type.LONG -> "longValue";
            case Type.FLOAT -> "floatValue";
            case Type.DOUBLE -> "doubleValue";
            default -> throw new IllegalArgumentException("不支持的数值类型：" + type);
        };
        instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper, method, "()" + type.getDescriptor(), false));
    }

    private static String wrapper(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.SHORT -> "java/lang/Short";
            case Type.CHAR -> "java/lang/Character";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
    }

    private static Class<?> resolve(String internalName, Class<?> owner) {
        try { return Class.forName(internalName.replace('/', '.'), false, owner.getClassLoader()); }
        catch (ClassNotFoundException failure) { throw new IllegalArgumentException("找不到公式成员声明类：" + internalName, failure); }
    }

    private static boolean numeric(Type type) {
        return type.equals(Type.INT_TYPE) || type.equals(Type.LONG_TYPE)
                || type.equals(Type.FLOAT_TYPE) || type.equals(Type.DOUBLE_TYPE);
    }

    private static MethodNode copy(MethodNode method) {
        MethodNode result = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc,
                method.signature, method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
        method.accept(result);
        return result;
    }

    private static String compact(String expression) {
        return expression.replaceAll("\\s+", "");
    }
}
