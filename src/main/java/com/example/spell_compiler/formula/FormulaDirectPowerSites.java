package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Set;

public final class FormulaDirectPowerSites {
    public record Coverage(int damageMethods, int equipmentConsumers) {}

    private static final Set<String> DAMAGE_CLASSES = Set.of(
            "io/redspace/ironsspellbooks/spells/eldritch/SculkTentaclesSpell",
            "io/redspace/ironsspellbooks/spells/fire/MagmaBombSpell");
    private static final String RAISE_DEAD = "io/redspace/ironsspellbooks/spells/blood/RaiseDeadSpell";
    private static final String DAMAGE = "getDamage(ILnet/minecraft/world/entity/LivingEntity;)F";
    private static final String EQUIPMENT = "getEquipment(FLnet/minecraft/util/RandomSource;)[Lnet/minecraft/world/item/ItemStack;";

    private FormulaDirectPowerSites() {}

    public static Coverage apply(ClassNode target, SpellFormulaCatalog.Spell definition) {
        if (definition == null || !definition.className().equals(target.name.replace('/', '.')))
            return new Coverage(0, 0);
        int damage = 0;
        int equipment = 0;
        if (DAMAGE_CLASSES.contains(target.name)) {
            if (definition.formulas().stream().noneMatch(method -> method.key().equals(DAMAGE)))
                throw new IllegalArgumentException("直接强度伤害缺少公式绑定：" + target.name);
            protectDamage(target, requiredMethod(target, DAMAGE));
            damage++;
        }
        if (target.name.equals(RAISE_DEAD)) {
            protectEquipment(target, requiredMethod(target, EQUIPMENT));
            equipment++;
        }
        return new Coverage(damage, equipment);
    }

    private static void protectDamage(ClassNode target, MethodNode method) {
        var fields = new ArrayList<FieldInsnNode>();
        for (var instruction : method.instructions)
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("baseSpellPower") && field.desc.equals("I")) fields.add(field);
        if (fields.size() != 1) throw changed(target, method);
        var field = fields.get(0);
        var conversion = next(field);
        if (!(previous(field) instanceof VarInsnNode receiver) || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0
                || conversion == null || conversion.getOpcode() != Opcodes.I2F) throw changed(target, method);
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new LdcInsnNode("baseSpellPower"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/example/spell_compiler/formula/FormulaWidePower", "readParameter",
                "(ILjava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)J", false));
        method.instructions.insertBefore(conversion, code);
        method.instructions.set(conversion, new InsnNode(Opcodes.L2F));
        method.maxStack += 3;
    }

    private static void protectEquipment(ClassNode target, MethodNode method) {
        var fields = new ArrayList<FieldInsnNode>();
        for (var instruction : method.instructions)
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("spellPowerPerLevel") && field.desc.equals("I")) fields.add(field);
        if (fields.size() != 1) throw changed(target, method);
        var field = fields.get(0);
        var multiply = next(field);
        var fifteen = next(multiply);
        var add = next(fifteen);
        var store = next(add);
        var receiver = previous(field);
        var maximum = previous(receiver);
        if (multiply == null || multiply.getOpcode() != Opcodes.IMUL || !integer(fifteen, 15)
                || add == null || add.getOpcode() != Opcodes.IADD
                || !(store instanceof VarInsnNode upper) || store.getOpcode() != Opcodes.ISTORE
                || !(receiver instanceof VarInsnNode self) || receiver.getOpcode() != Opcodes.ALOAD || self.var != 0
                || !(maximum instanceof MethodInsnNode getter) || !getter.name.equals("getMaxLevel") || !getter.desc.equals("()I"))
            throw changed(target, method);
        int lowerSlot = -1;
        for (var instruction : method.instructions) {
            if (!integer(instruction, 12) || !(next(instruction) instanceof VarInsnNode lower)
                    || lower.getOpcode() != Opcodes.ISTORE) continue;
            if (lowerSlot >= 0) throw changed(target, method);
            lowerSlot = lower.var;
        }
        if (lowerSlot < 0 || lowerSlot == upper.var) throw changed(target, method);
        var conversions = new ArrayList<AbstractInsnNode>();
        for (var instruction : method.instructions) {
            if (instruction.getOpcode() != Opcodes.I2F) continue;
            var subtract = previous(instruction);
            var lower = previous(subtract);
            var upperLoad = previous(lower);
            if (subtract != null && subtract.getOpcode() == Opcodes.ISUB
                    && lower instanceof VarInsnNode low && low.getOpcode() == Opcodes.ILOAD && low.var == lowerSlot
                    && upperLoad instanceof VarInsnNode high && high.getOpcode() == Opcodes.ILOAD && high.var == upper.var
                    && next(instruction) != null && next(instruction).getOpcode() == Opcodes.FDIV) conversions.add(instruction);
        }
        if (conversions.size() != 1) throw changed(target, method);
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/example/spell_compiler/formula/FormulaDirectPowerSafety", "equipmentDenominator",
                "(FLjava/lang/Object;)F", false));
        method.instructions.insert(conversions.get(0), code);
        method.maxStack += 1;
    }

    private static MethodNode requiredMethod(ClassNode target, String key) {
        var method = target.methods.stream().filter(value -> (value.name + value.desc).equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("直接强度消费者不存在：" + target.name + '#' + key));
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
            throw changed(target, method);
        return method;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do { instruction = instruction.getNext(); }
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do { instruction = instruction.getPrevious(); }
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static boolean integer(AbstractInsnNode instruction, int value) {
        return instruction instanceof IntInsnNode integerConstant && integerConstant.operand == value
                || instruction instanceof LdcInsnNode loadedConstant && loadedConstant.cst instanceof Integer integer && integer == value
                || instruction != null && instruction.getOpcode() >= Opcodes.ICONST_M1 && instruction.getOpcode() <= Opcodes.ICONST_5
                && instruction.getOpcode() - Opcodes.ICONST_0 == value;
    }

    private static IllegalArgumentException changed(ClassNode target, MethodNode method) {
        return new IllegalArgumentException("原生直接强度消费者结构已变化：" + target.name + '#' + method.name + method.desc);
    }
}
