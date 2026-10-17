package com.example.spell_compiler.formula;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaAddonUsageTest {
    private static final String BASE = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String LEAF = "addon/usage/RegisteredSpell";
    private static final String POWER_NAME = "getSpellPower";
    private static final String POWER_DESC = "(ILnet/minecraft/world/entity/Entity;)F";
    private static final Set<String> COVERED = Set.of(BASE + '#' + POWER_NAME + POWER_DESC);
    private static final Set<String> POWER_FIELDS = Set.of("baseSpellPower", "spellPowerPerLevel");

    @Test
    void inheritedCoveredEntryStopsTraversalWithoutChangingTheExistingUsageResult() {
        var fixture = fixture();
        var usage = fixture.usage();
        var original = usage.forSpell(LEAF);
        assertEquals(POWER_FIELDS, original.fields());
        assertFalse(original.uncertain());
        assertTrue(usage.uncoveredFields(LEAF, COVERED).isEmpty());
        assertSame(original, usage.forSpell(LEAF));
        assertEquals(POWER_FIELDS, usage.forSpell(LEAF).fields());
    }

    @Test
    void anOverrideWithTheSameMethodKeyDoesNotAcquireItsParentsCoverage() {
        var fixture = fixture();
        var override = new MethodNode(Opcodes.ACC_PUBLIC, POWER_NAME, POWER_DESC, null, null);
        override.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        override.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, BASE, "baseSpellPower", "I"));
        override.instructions.add(new InsnNode(Opcodes.I2F));
        override.instructions.add(new InsnNode(Opcodes.FRETURN));
        fixture.leaf().methods.add(override);
        assertEquals(Set.of("baseSpellPower"), fixture.usage().uncoveredFields(LEAF, COVERED));
    }

    @Test
    void aConstantManaOverrideLeavesItsInheritedBaseAndGrowthFieldsUnused() {
        var fixture = fixture();
        fixture.base().fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "baseManaCost", "I", null, null));
        fixture.base().fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "manaCostPerLevel", "I", null, null));
        var inherited = new MethodNode(Opcodes.ACC_PUBLIC, "getManaCost", "(I)I", null, null);
        inherited.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inherited.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, BASE, "baseManaCost", "I"));
        inherited.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inherited.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, BASE, "manaCostPerLevel", "I"));
        inherited.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        inherited.instructions.add(new InsnNode(Opcodes.IMUL));
        inherited.instructions.add(new InsnNode(Opcodes.IADD));
        inherited.instructions.add(new InsnNode(Opcodes.IRETURN));
        fixture.base().methods.add(inherited);
        var override = new MethodNode(Opcodes.ACC_PUBLIC, "getManaCost", "(I)I", null, null);
        override.instructions.add(new InsnNode(Opcodes.ICONST_0));
        override.instructions.add(new InsnNode(Opcodes.IRETURN));
        fixture.leaf().methods.add(override);
        var consumed = new InsnList();
        consumed.add(new VarInsnNode(Opcodes.ALOAD, 0));
        consumed.add(new InsnNode(Opcodes.ICONST_1));
        consumed.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEAF, "getManaCost", "(I)I", false));
        consumed.add(new InsnNode(Opcodes.POP));
        fixture.cast().instructions.insertBefore(fixture.cast().instructions.getLast(), consumed);
        var usage = fixture.usage();
        var result = usage.forSpell(LEAF);
        assertFalse(result.uncertain());
        assertTrue(result.usesMethod("getManaCost(I)I"));
        assertFalse(result.usesField("baseManaCost"));
        assertFalse(result.usesField("manaCostPerLevel"));
        assertTrue(usage.uncoveredFields(LEAF, COVERED).isEmpty());
    }

    @Test
    void aSharedHelperStillExposesItsFieldsThroughAnUncoveredSecondPath() {
        var fixture = fixture();
        var direct = new InsnList();
        direct.add(new VarInsnNode(Opcodes.ALOAD, 0));
        direct.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BASE, "powerFields", "()I", false));
        direct.add(new InsnNode(Opcodes.POP));
        fixture.cast().instructions.insertBefore(fixture.cast().instructions.getLast(), direct);
        assertEquals(POWER_FIELDS, fixture.usage().uncoveredFields(LEAF, COVERED));
    }

    @Test
    void aSuperCallIsCoveredButAdditionalReadsInItsOverrideRemainUncovered() {
        var fixture = fixture();
        var override = new MethodNode(Opcodes.ACC_PUBLIC, POWER_NAME, POWER_DESC, null, null);
        override.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        override.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        override.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        override.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BASE, POWER_NAME, POWER_DESC, false));
        override.instructions.add(new InsnNode(Opcodes.FRETURN));
        fixture.leaf().methods.add(override);
        assertTrue(fixture.usage().uncoveredFields(LEAF, COVERED).isEmpty());
        var extra = new InsnList();
        extra.add(new VarInsnNode(Opcodes.ALOAD, 0));
        extra.add(new FieldInsnNode(Opcodes.GETFIELD, BASE, "spellPowerPerLevel", "I"));
        extra.add(new InsnNode(Opcodes.I2F));
        extra.add(new InsnNode(Opcodes.FADD));
        override.instructions.insertBefore(override.instructions.getLast(), extra);
        assertEquals(Set.of("spellPowerPerLevel"), fixture.usage().uncoveredFields(LEAF, COVERED));
    }

    @Test
    void reflectionAndUnresolvedLocalHelpersConservativelyExposeBothPowerFields() {
        for (boolean reflection : new boolean[]{true, false}) {
            var fixture = fixture();
            var unknown = new InsnList();
            if (reflection) {
                unknown.add(new LdcInsnNode("addon.usage.UnknownConsumer"));
                unknown.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                        "(Ljava/lang/String;)Ljava/lang/Class;", false));
            } else {
                unknown.add(new VarInsnNode(Opcodes.ALOAD, 0));
                unknown.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BASE, "unknownNumericHelper", "()I", false));
            }
            unknown.add(new InsnNode(Opcodes.POP));
            fixture.cast().instructions.insertBefore(fixture.cast().instructions.getLast(), unknown);
            var usage = fixture.usage();
            assertTrue(usage.forSpell(LEAF).uncertain(), "reflection=" + reflection);
            assertEquals(POWER_FIELDS, usage.uncoveredFields(LEAF, COVERED), "reflection=" + reflection);
        }
    }

    private static Fixture fixture() {
        var base = type(BASE, "java/lang/Object", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT);
        base.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "baseSpellPower", "I", null, null));
        base.fields.add(new FieldNode(Opcodes.ACC_PROTECTED, "spellPowerPerLevel", "I", null, null));
        var helper = new MethodNode(Opcodes.ACC_PROTECTED, "powerFields", "()I", null, null);
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, BASE, "baseSpellPower", "I"));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, BASE, "spellPowerPerLevel", "I"));
        helper.instructions.add(new InsnNode(Opcodes.IADD));
        helper.instructions.add(new InsnNode(Opcodes.IRETURN));
        base.methods.add(helper);
        var power = new MethodNode(Opcodes.ACC_PUBLIC, POWER_NAME, POWER_DESC, null, null);
        power.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        power.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BASE, "powerFields", "()I", false));
        power.instructions.add(new InsnNode(Opcodes.I2F));
        power.instructions.add(new InsnNode(Opcodes.FRETURN));
        base.methods.add(power);
        var leaf = type(LEAF, BASE, Opcodes.ACC_PUBLIC);
        var cast = new MethodNode(Opcodes.ACC_PUBLIC, "onCast", "()V", null, null);
        cast.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cast.instructions.add(new InsnNode(Opcodes.ICONST_1));
        cast.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        cast.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEAF, POWER_NAME, POWER_DESC, false));
        cast.instructions.add(new InsnNode(Opcodes.POP));
        cast.instructions.add(new InsnNode(Opcodes.RETURN));
        leaf.methods.add(cast);
        return new Fixture(base, leaf, cast);
    }

    private static ClassNode type(String name, String parent, int access) {
        var type = new ClassNode();
        type.version = Opcodes.V17;
        type.access = access;
        type.name = name;
        type.superName = parent;
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        type.methods.add(constructor);
        return type;
    }

    private record Fixture(ClassNode base, ClassNode leaf, MethodNode cast) {
        FormulaUsage usage() {
            var repository = FormulaClassRepository.of(base, leaf);
            return new FormulaUsage(repository, repository.sourceClasses());
        }
    }
}
