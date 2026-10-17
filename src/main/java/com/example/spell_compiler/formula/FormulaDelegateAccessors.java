package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FormulaDelegateAccessors {
    private record FieldKey(Class<?> owner, String name, String descriptor, boolean isStatic) {}
    private record MethodKey(Class<?> caller, Class<?> owner, String name, String descriptor, int opcode) {}

    private static final Map<FieldKey, Integer> FIELD_IDS = new LinkedHashMap<>();
    private static final Map<MethodKey, Integer> METHOD_IDS = new LinkedHashMap<>();
    private static volatile MethodHandle[] fields = new MethodHandle[0];
    private static volatile FieldKey[] fieldKeys = new FieldKey[0];
    private static volatile MethodHandle[] methods = new MethodHandle[0];

    private FormulaDelegateAccessors() {}

    static synchronized int registerField(Class<?> owner, String name, String descriptor, boolean isStatic) {
        FieldKey key = new FieldKey(owner, name, descriptor, isStatic);
        Integer existing = FIELD_IDS.get(key);
        if (existing != null) return existing;
        Class<?> type = MethodType.fromMethodDescriptorString("()" + descriptor, owner.getClassLoader()).returnType();
        MethodHandle field;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            field = isStatic ? lookup.findStaticGetter(owner, name, type) : lookup.findGetter(owner, name, type);
            field = isStatic ? MethodHandles.dropArguments(field, 0, Object.class)
                    : field.asType(MethodType.methodType(type, Object.class));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("无法访问公式字段：" + owner.getName() + '#' + name + descriptor, failure);
        }
        int index = fields.length;
        MethodHandle[] updated = Arrays.copyOf(fields, index + 1);
        updated[index] = field;
        FieldKey[] updatedKeys = Arrays.copyOf(fieldKeys, index + 1);
        updatedKeys[index] = key;
        fieldKeys = updatedKeys;
        fields = updated;
        FIELD_IDS.put(key, index);
        return index;
    }

    static synchronized int registerMethod(Class<?> caller, Class<?> owner, String name, String descriptor, int opcode) {
        MethodKey key = new MethodKey(caller, owner, name, descriptor, opcode);
        Integer existing = METHOD_IDS.get(key);
        if (existing != null) return existing;
        MethodType type = MethodType.fromMethodDescriptorString(descriptor, caller.getClassLoader());
        MethodHandle handle;
        try {
            if (opcode == Opcodes.INVOKESPECIAL) {
                handle = MethodHandles.privateLookupIn(caller, MethodHandles.lookup()).findSpecial(owner, name, type, caller);
            } else {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
                handle = opcode == Opcodes.INVOKESTATIC ? lookup.findStatic(owner, name, type)
                        : lookup.findVirtual(owner, name, type);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("无法访问公式辅助方法：" + owner.getName() + '#' + name + descriptor, failure);
        }
        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        handle = handle.asSpreader(Object[].class, type.parameterCount());
        if (isStatic) {
            handle = handle.asType(MethodType.methodType(Object.class, Object[].class));
            handle = MethodHandles.dropArguments(handle, 0, Object.class);
        } else handle = handle.asType(MethodType.methodType(Object.class, Object.class, Object[].class));
        int index = methods.length;
        MethodHandle[] updated = Arrays.copyOf(methods, index + 1);
        updated[index] = handle;
        methods = updated;
        METHOD_IDS.put(key, index);
        return index;
    }

    public static int readInt(Object owner, int index) throws Throwable {
        Number original = FormulaNativeValues.originalField(owner, fieldKeys[index].name());
        if (original != null) return original.intValue();
        return (int) fields[index].invokeExact(owner);
    }

    public static long readLong(Object owner, int index) throws Throwable {
        return (long) fields[index].invokeExact(owner);
    }

    public static float readFloat(Object owner, int index) throws Throwable {
        return (float) fields[index].invokeExact(owner);
    }

    public static double readDouble(Object owner, int index) throws Throwable {
        return (double) fields[index].invokeExact(owner);
    }

    public static Object invoke(Object owner, Object[] arguments, int index) throws Throwable {
        return (Object) methods[index].invokeExact(owner, arguments);
    }
}
