package com.example.spell_compiler.formula;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaUsageGraphTest {
    private static final String BASE = "fixture/BaseSpell";
    private static final String SPELL = "fixture/Spell";
    private static final String POWER = "getSpellPower(I)F";

    @Test
    void deadNumericMethodsAndTheirInnerHelpersDoNotKeepPowerOrItsFieldsAlive() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        method(spell, Opcodes.ACC_PUBLIC, "onCast", "()V").instructions.add(new InsnNode(Opcodes.RETURN));
        callPower(method(spell, Opcodes.ACC_PUBLIC, "getUnusedDamage", "(I)F"), SPELL);
        ClassNode inner = owner(SPELL + "$Dead", "java/lang/Object");
        callPower(method(inner, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "unused", "(L" + SPELL + ";)F"), SPELL);
        var usage = usage(spell, base, inner);
        assertFalse(usage.uncertain());
        assertFalse(usage.usesMethod(POWER));
        assertFalse(usage.usesField("baseSpellPower"));
        assertFalse(usage.usesField("spellPowerPerLevel"));
    }

    @Test
    void inheritedLifecycleReachesIndirectNumericHelpersAndTheirPowerFields() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        MethodNode cast = method(base, Opcodes.ACC_PROTECTED, "onCast", "()V");
        invoke(cast, Opcodes.INVOKEVIRTUAL, BASE, "getDamage", "(I)F");
        cast.instructions.add(new InsnNode(Opcodes.RETURN));
        callPower(method(base, Opcodes.ACC_PROTECTED, "getDamage", "(I)F"), BASE);
        var usage = usage(spell, base);
        assertFalse(usage.uncertain());
        assertTrue(usage.usesMethod("getDamage(I)F"));
        assertTrue(usage.usesMethod(POWER));
        assertTrue(usage.usesField("baseSpellPower"));
        assertTrue(usage.usesField("spellPowerPerLevel"));
    }

    @Test
    void lambdaImplementationHandlesKeepTheirPrivatePowerConsumerAlive() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        MethodNode cast = method(spell, Opcodes.ACC_PUBLIC, "onCast", "()V");
        cast.instructions.add(new InvokeDynamicInsnNode("run", "(L" + SPELL + ";)Ljava/lang/Runnable;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false),
                Type.getMethodType("()V"), new Handle(Opcodes.H_INVOKESTATIC, SPELL, "lambda$onCast$0",
                "(L" + SPELL + ";)V", false), Type.getMethodType("()V")));
        cast.instructions.add(new InsnNode(Opcodes.RETURN));
        MethodNode lambda = method(spell, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "lambda$onCast$0", "(L" + SPELL + ";)V");
        invoke(lambda, Opcodes.INVOKEVIRTUAL, SPELL, "getSpellPower", "(I)F");
        lambda.instructions.add(new InsnNode(Opcodes.RETURN));
        var usage = usage(spell, base);
        assertFalse(usage.uncertain());
        assertTrue(usage.usesMethod(POWER));
        assertTrue(usage.usesField("baseSpellPower"));
    }

    @Test
    void frameworkNumericCallbacksDispatchToTheConcreteSpellOverride() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        method(base, Opcodes.ACC_PUBLIC, "getDamage", "(I)F").instructions.add(new InsnNode(Opcodes.FRETURN));
        callPower(method(spell, Opcodes.ACC_PUBLIC, "getDamage", "(I)F"), SPELL);
        ClassNode framework = owner("fixture/Framework", "java/lang/Object");
        invoke(method(framework, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dispatch", "(L" + BASE + ";I)F"),
                Opcodes.INVOKEVIRTUAL, BASE, "getDamage", "(I)F");
        assertFalse(usage(spell, base).usesMethod(POWER));
        var usage = usage(spell, base, framework);
        assertFalse(usage.uncertain());
        assertTrue(usage.usesMethod("getDamage(I)F"));
        assertTrue(usage.usesMethod(POWER));
    }

    @Test
    void overloadedNumericMethodsHaveSeparateReachability() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        MethodNode cast = method(spell, Opcodes.ACC_PUBLIC, "onCast", "()V");
        invoke(cast, Opcodes.INVOKEVIRTUAL, SPELL, "getDamage", "(I)F");
        cast.instructions.add(new InsnNode(Opcodes.RETURN));
        method(spell, Opcodes.ACC_PUBLIC, "getDamage", "(I)F").instructions.add(new InsnNode(Opcodes.FRETURN));
        callPower(method(spell, Opcodes.ACC_PUBLIC, "getDamage", "(D)F"), SPELL);
        var usage = usage(spell, base);
        assertFalse(usage.uncertain());
        assertTrue(usage.usesMethod("getDamage(I)F"));
        assertFalse(usage.usesMethod("getDamage(D)F"));
        assertFalse(usage.usesMethod(POWER));
    }

    @Test
    void anotherSpellUsingTheSameInheritedPowerDoesNotPolluteThisSpell() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        ClassNode other = owner("fixture/OtherSpell", BASE);
        MethodNode ownCast = method(spell, Opcodes.ACC_PUBLIC, "onCast", "()V");
        invoke(ownCast, Opcodes.INVOKEVIRTUAL, other.name, "getSpellPower", "(I)F");
        ownCast.instructions.add(new InsnNode(Opcodes.RETURN));
        MethodNode otherCast = method(other, Opcodes.ACC_PUBLIC, "onCast", "()V");
        invoke(otherCast, Opcodes.INVOKEVIRTUAL, other.name, "getSpellPower", "(I)F");
        otherCast.instructions.add(new InsnNode(Opcodes.RETURN));
        invoke(method(other, Opcodes.ACC_PUBLIC, "getUnusedDamage", "(I)F"),
                Opcodes.INVOKESPECIAL, BASE, "getSpellPower", "(I)F");
        var usage = usage(spell, base, other);
        assertFalse(usage.uncertain());
        assertFalse(usage.usesMethod(POWER));
        assertFalse(usage.usesField("baseSpellPower"));
    }

    @Test
    void explicitCrossSpellCallsToThisConcreteSpellRemainRealConsumers() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        ClassNode other = owner("fixture/OtherSpell", BASE);
        invoke(method(other, Opcodes.ACC_PUBLIC, "onCast", "()V"),
                Opcodes.INVOKEVIRTUAL, SPELL, "getSpellPower", "(I)F");
        var usage = usage(spell, base, other);
        assertFalse(usage.uncertain());
        assertTrue(usage.usesMethod(POWER));
        assertTrue(usage.usesField("baseSpellPower"));
    }

    @Test
    void anotherSpellMayConsumeThisSpellThroughASharedBaseParameter() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        ClassNode other = owner("fixture/OtherSpell", BASE);
        MethodNode read = method(other, Opcodes.ACC_PUBLIC, "readBasePower", "(L" + BASE + ";)I");
        read.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        read.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, BASE, "baseSpellPower", "I"));
        read.instructions.add(new InsnNode(Opcodes.IRETURN));
        var fieldUsage = usage(spell, base, other);
        assertFalse(fieldUsage.uncertain());
        assertTrue(fieldUsage.usesField("baseSpellPower"));
        assertFalse(fieldUsage.usesField("spellPowerPerLevel"));
        assertFalse(fieldUsage.usesMethod(POWER));
        MethodNode callback = method(other, Opcodes.ACC_PUBLIC, "powerFor", "(L" + BASE + ";)F");
        callback.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        callback.instructions.add(new InsnNode(Opcodes.ICONST_1));
        invoke(callback, Opcodes.INVOKEVIRTUAL, BASE, "getSpellPower", "(I)F");
        callback.instructions.add(new InsnNode(Opcodes.FRETURN));
        var usage = usage(spell, base, other);
        assertFalse(usage.uncertain());
        assertTrue(usage.usesMethod(POWER));
        assertTrue(usage.usesField("baseSpellPower"));
        assertTrue(usage.usesField("spellPowerPerLevel"));
    }

    @Test
    void externalConsumersKeepPackagePrivateNonnumericCallbacksAndTheirPowerCalls() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        invoke(method(spell, 0, "callback", "()V"), Opcodes.INVOKEVIRTUAL, SPELL, "getSpellPower", "(I)F");
        ClassNode framework = owner("fixture/Framework", "java/lang/Object");
        invoke(method(framework, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dispatch", "(L" + SPELL + ";)V"),
                Opcodes.INVOKEVIRTUAL, SPELL, "callback", "()V");
        assertFalse(usage(spell, base).usesMethod(POWER));
        var usage = usage(spell, base, framework);
        assertFalse(usage.uncertain());
        assertTrue(usage.usesMethod("callback()V"));
        assertTrue(usage.usesMethod(POWER));
    }

    @Test
    void missingNativeOwnersAndAncestorsRetainUsageButNormalExternalEndpointsDoNot() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        MethodNode cast = method(spell, Opcodes.ACC_PUBLIC, "onCast", "()V");
        invoke(cast, Opcodes.INVOKESTATIC, "java/lang/Math", "sqrt", "(D)D");
        invoke(cast, Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "tick", "()V");
        assertFalse(usage(spell, base).uncertain());
        invoke(cast, Opcodes.INVOKESTATIC, "fixture/MissingHelper", "consume", "()V");
        assertTrue(usage(spell, base).usesMethod(POWER));
        cast.instructions.clear();
        base.superName = "fixture/MissingParent";
        var unknown = usage(spell, base);
        assertTrue(unknown.uncertain());
        assertTrue(unknown.usesField("spellPowerPerLevel"));
    }

    @Test
    void directBaseReadsKeepOnlyTheirOwnParameterAndUnknownLocalCallsKeepEverything() {
        ClassNode base = base();
        ClassNode spell = owner(SPELL, BASE);
        MethodNode cast = method(spell, Opcodes.ACC_PUBLIC, "onCast", "()V");
        cast.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cast.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, BASE, "baseSpellPower", "I"));
        cast.instructions.add(new InsnNode(Opcodes.POP));
        cast.instructions.add(new InsnNode(Opcodes.RETURN));
        var direct = usage(spell, base);
        assertFalse(direct.uncertain());
        assertTrue(direct.usesField("baseSpellPower"));
        assertFalse(direct.usesField("spellPowerPerLevel"));
        assertFalse(direct.usesMethod(POWER));
        invoke(cast, Opcodes.INVOKEVIRTUAL, SPELL, "missingLocalMethod", "()V");
        var unknown = usage(spell, base);
        assertTrue(unknown.uncertain());
        assertTrue(unknown.usesMethod(POWER));
        assertTrue(unknown.usesField("spellPowerPerLevel"));
    }

    @Test
    void reflectionAndCustomBootstrapsKeepUnknownUsageWhileStringConcatenationDoesNot() {
        for (String reflected : new String[]{"getDeclaredMethod", "getField", "getDeclaredField", "getFields", "getDeclaredFields"}) {
            ClassNode base = base();
            ClassNode spell = owner(SPELL, BASE);
            invoke(method(spell, Opcodes.ACC_PUBLIC, "onCast", "()V"), Opcodes.INVOKEVIRTUAL,
                    "java/lang/Class", reflected, "()Ljava/lang/Object;");
            assertTrue(usage(spell, base).uncertain(), reflected);
        }
        for (String invocation : new String[]{"invoke", "invokeExact", "invokeWithArguments"}) {
            ClassNode base = base();
            ClassNode spell = owner(SPELL, BASE);
            invoke(method(spell, Opcodes.ACC_PUBLIC, "onCast", "()V"), Opcodes.INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandle", invocation, "()Ljava/lang/Object;");
            assertTrue(usage(spell, base).uncertain(), invocation);
        }
        for (boolean standard : new boolean[]{true, false}) {
            ClassNode base = base();
            ClassNode spell = owner(SPELL, BASE);
            MethodNode cast = method(spell, Opcodes.ACC_PUBLIC, "onCast", "()V");
            cast.instructions.add(new InvokeDynamicInsnNode("make", "()Ljava/lang/String;",
                    new Handle(Opcodes.H_INVOKESTATIC, standard ? "java/lang/invoke/StringConcatFactory" : "fixture/Bootstrap",
                            standard ? "makeConcatWithConstants" : "create", "()V", false), "value"));
            var usage = usage(spell, base);
            assertEquals(!standard, usage.uncertain());
            assertEquals(!standard, usage.usesMethod(POWER));
        }
    }

    private static FormulaUsage.Usage usage(ClassNode spell, ClassNode... others) {
        ClassNode[] sources = new ClassNode[others.length + 1];
        sources[0] = spell;
        System.arraycopy(others, 0, sources, 1, others.length);
        return new FormulaUsage(FormulaClassRepository.of(spell, others), Arrays.asList(sources)).forSpell(spell.name);
    }

    private static ClassNode base() {
        ClassNode base = owner(BASE, "java/lang/Object");
        base.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "baseSpellPower", "I", null, null));
        base.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "spellPowerPerLevel", "I", null, null));
        MethodNode power = method(base, Opcodes.ACC_PUBLIC, "getSpellPower", "(I)F");
        for (String field : new String[]{"baseSpellPower", "spellPowerPerLevel"}) {
            power.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            power.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, BASE, field, "I"));
            power.instructions.add(new InsnNode(Opcodes.I2F));
        }
        power.instructions.add(new InsnNode(Opcodes.FADD));
        power.instructions.add(new InsnNode(Opcodes.FRETURN));
        return base;
    }

    private static ClassNode owner(String name, String parent) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        owner.name = name;
        owner.superName = parent;
        owner.access = Opcodes.ACC_PUBLIC;
        return owner;
    }

    private static MethodNode method(ClassNode owner, int access, String name, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ASM9, access, name, descriptor, null, null);
        owner.methods.add(method);
        return method;
    }

    private static void invoke(MethodNode method, int opcode, String owner, String name, String descriptor) {
        method.instructions.add(new MethodInsnNode(opcode, owner, name, descriptor, false));
    }

    private static void callPower(MethodNode method, String owner) {
        invoke(method, Opcodes.INVOKEVIRTUAL, owner, "getSpellPower", "(I)F");
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
    }
}
