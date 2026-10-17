package com.example.spell_compiler.formula;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FormulaDirectPowerSitesTest {
    @Test
    void onlyTheThreeReviewedNativeConsumersAreBridgedAndTheirBytecodeRemainsValid() throws Exception {
        var repository = repository();
        var catalog = SpellFormulaCatalog.load(getClass().getClassLoader());
        int damageMethods = 0;
        int equipmentConsumers = 0;
        for (var definition : catalog.spells()) {
            var owner = repository.classNode(definition.className().replace('.', '/'));
            var originalRandom = calls(owner, "net/minecraft/util/RandomSource");
            var originalMultipliers = calls(owner, "getEntityPowerMultiplier");
            var coverage = FormulaDirectPowerSites.apply(owner, definition);
            damageMethods += coverage.damageMethods();
            equipmentConsumers += coverage.equipmentConsumers();
            assertEquals(originalRandom, calls(owner, "net/minecraft/util/RandomSource"), definition.id());
            assertEquals(originalMultipliers, calls(owner, "getEntityPowerMultiplier"), definition.id());
            if (coverage.damageMethods() == 1) {
                var method = method(owner, "getDamage");
                assertEquals(1, methodCalls(method, "readParameter"));
                assertEquals(1, opcodes(method, Opcodes.L2F));
                assertEquals(0, opcodes(method, Opcodes.I2F));
                assertEquals(1, methodCalls(method, "getEntityPowerMultiplier"));
                assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(owner.name, method));
            }
            if (coverage.equipmentConsumers() == 1) {
                var method = method(owner, "getEquipment");
                assertEquals(1, methodCalls(method, "equipmentDenominator"));
                assertEquals(1, opcodes(method, Opcodes.IMUL));
                assertDoesNotThrow(() -> new Analyzer<>(new BasicVerifier()).analyze(owner.name, method));
            }
        }
        assertEquals(2, damageMethods);
        assertEquals(1, equipmentConsumers);
    }

    @Test
    void changedNativeFieldOrConversionShapesFailBeforeSilentPartialInjection() throws Exception {
        for (String id : List.of("irons_spellbooks:sculk_tentacles", "irons_spellbooks:raise_dead")) {
            var repository = repository();
            var definition = SpellFormulaCatalog.load(getClass().getClassLoader()).spells().stream()
                    .filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            var owner = repository.classNode(definition.className().replace('.', '/'));
            var method = method(owner, id.endsWith("raise_dead") ? "getEquipment" : "getDamage");
            if (id.endsWith("raise_dead")) {
                for (var instruction : method.instructions)
                    if (instruction instanceof FieldInsnNode field && field.name.equals("spellPowerPerLevel")) {
                        field.name = "unexpectedPowerField";
                        break;
                    }
            } else {
                for (var instruction : method.instructions.toArray())
                    if (instruction.getOpcode() == Opcodes.I2F) {
                        method.instructions.set(instruction, new InsnNode(Opcodes.I2D));
                        break;
                    }
            }
            assertThrows(IllegalArgumentException.class, () -> FormulaDirectPowerSites.apply(owner, definition), id);
        }
    }

    @Test
    void equipmentDenominatorKeepsLongPrecisionAndChecksEveryIntegerIntermediate() {
        assertEquals(21, FormulaDirectPowerSafety.calculateEquipmentDenominator(6, 3));
        assertEquals(6, FormulaDirectPowerSafety.calculateEquipmentDenominator(1, 3));
        assertEquals(9, FormulaDirectPowerSafety.calculateEquipmentDenominator(6, 1));
        assertEquals((float) 18_000_000_009L, FormulaDirectPowerSafety.calculateEquipmentDenominator(6, 3_000_000_001L));
        assertEquals((float) (Long.MAX_VALUE - 12), FormulaDirectPowerSafety.calculateEquipmentDenominator(1, Long.MAX_VALUE - 15));
        assertThrows(ArithmeticException.class, () -> FormulaDirectPowerSafety.calculateEquipmentDenominator(1, Long.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> FormulaDirectPowerSafety.calculateEquipmentDenominator(2, Long.MAX_VALUE / 2));
        assertThrows(ArithmeticException.class, () -> FormulaDirectPowerSafety.calculateEquipmentDenominator(3, Long.MAX_VALUE / 2));
    }

    private static FormulaClassRepository repository() throws Exception {
        return FormulaClassRepository.fromJar(Path.of("libs/ironsspells-3.16.1.jar"));
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(value -> value.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> calls(ClassNode owner, String identity) {
        var result = new ArrayList<String>();
        for (var method : owner.methods)
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call && (call.owner.equals(identity) || call.name.equals(identity)))
                    result.add(method.name + method.desc + ':' + call.getOpcode() + ':' + call.owner + '#' + call.name + call.desc);
        return List.copyOf(result);
    }

    private static int methodCalls(MethodNode method, String name) {
        int count = 0;
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) count++;
        return count;
    }

    private static int opcodes(MethodNode method, int opcode) {
        int count = 0;
        for (var instruction : method.instructions) if (instruction.getOpcode() == opcode) count++;
        return count;
    }
}
