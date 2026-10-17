package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.AutomaticFormulaExtractor;
import com.example.spell_compiler.formula.FormulaCatalogGenerator;
import com.example.spell_compiler.formula.FormulaClassRepository;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaCastContextTest {
    private static final String SPELL = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String CAST_TYPE = "io/redspace/ironsspellbooks/api/spells/CastType";
    private static final String EFFECTIVE_DESCRIPTOR = "(ILnet/minecraft/world/entity/LivingEntity;)I";

    @Test
    void allRegisteredContextsExposeRawReductionAndRetainSpecialEffectiveOverrides() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs/ironsspells-3.16.1.jar"));
        var classes = FormulaCatalogGenerator.registeredSpellClasses(repository);
        assertEquals(109, classes.size());
        int inherited = 0;
        int continuous = 0;
        int special = 0;
        var specialOwners = new HashSet<String>();
        var virtualTimeOwners = new HashSet<String>();
        var directTimeOwners = new HashSet<String>();
        for (String receiver : classes) {
            var resolved = repository.resolveMethod(receiver, "getEffectiveCastTime", EFFECTIVE_DESCRIPTOR);
            assertNotNull(resolved, receiver);
            var analysis = AutomaticFormulaExtractor.analyze(resolved.owner(), resolved.method(), repository, receiver);
            var runtime = AutomaticFormulaExtractor.analyzeRuntime(resolved.owner(), resolved.method(), repository, receiver);
            assertTrue(analysis.supported(), receiver + ": " + analysis.rejection());
            assertTrue(runtime.supported(), receiver + ": " + runtime.rejection());
            assertEquals(analysis.formula().expression(), runtime.formula().expression(), receiver);
            var formula = analysis.formula();
            if (!resolved.owner().name.equals(SPELL)) {
                var original = AutomaticFormulaExtractor.analyze(resolved.owner(), resolved.method(), repository);
                assertTrue(original.supported(), receiver + ": " + original.rejection());
                assertEquals(original.formula().expression(), formula.expression(), receiver);
                assertEquals(original.formula().variables().keySet(), formula.variables().keySet(), receiver);
                boolean callsNativeCastTime = StreamSupport.stream(resolved.method().instructions.spliterator(), false)
                        .anyMatch(instruction -> instruction instanceof MethodInsnNode call && call.name.equals("getCastTime")
                                && call.desc.equals("(I)I"));
                assertTrue(formula.variables().containsKey("castTime"), receiver);
                if (callsNativeCastTime) {
                    assertEquals("castTime", formula.readableExpression(), receiver);
                    virtualTimeOwners.add(resolved.owner().name);
                } else {
                    assertTrue(formula.variables().containsKey("castTime"), receiver);
                    assertEquals("castTime", formula.readableExpression(), receiver);
                    directTimeOwners.add(resolved.owner().name);
                }
                special++;
                specialOwners.add(resolved.owner().name);
                continue;
            }
            inherited++;
            assertEquals(Type.DOUBLE_TYPE, formula.variables().get("cast_time_reduction").type(), receiver);
            assertEquals(Type.INT_TYPE, formula.variables().get("castTime").type(), receiver);
            assertFalse(formula.variables().containsKey("cast_time_multiplier"), receiver);
            assertFalse(formula.variables().containsKey("is_continuous"), receiver);
            assertFalse(formula.variables().containsKey("is_instant"), receiver);
            assertTrue(formula.expression().startsWith("round("), receiver);
            assertTrue(formula.expression().contains("float(castTime)"), receiver);
            var castType = repository.resolveMethod(receiver, "getCastType", "()L" + CAST_TYPE + ';');
            var instructions = StreamSupport.stream(castType.method().instructions.spliterator(), false)
                    .filter(instruction -> instruction.getOpcode() >= 0).toList();
            String kind = assertInstanceOf(FieldInsnNode.class, instructions.get(0)).name;
            if (kind.equals("CONTINUOUS")) {
                assertFalse(formula.expression().contains("min("), receiver);
                assertFalse(formula.expression().contains("max("), receiver);
                continuous++;
            } else {
                assertTrue(formula.expression().contains("min("), receiver);
                assertTrue(formula.expression().contains("max("), receiver);
                assertTrue(formula.expression().contains("double(1.5)"), receiver);
                assertTrue(formula.expression().contains("double(-0.25)"), receiver);
            }
        }
        assertEquals(102, inherited);
        assertEquals(7, special);
        assertEquals(7, specialOwners.size());
        assertEquals(Set.of("io/redspace/ironsspellbooks/spells/evocation/ThrowSpell",
                "io/redspace/ironsspellbooks/spells/fire/FlamingStrikeSpell",
                "io/redspace/ironsspellbooks/spells/fire/RaiseHellSpell",
                "io/redspace/ironsspellbooks/spells/holy/DivineSmiteSpell",
                "io/redspace/ironsspellbooks/spells/nature/StompSpell"), virtualTimeOwners);
        assertEquals(Set.of("io/redspace/ironsspellbooks/spells/eldritch/PocketDimensionSpell",
                "io/redspace/ironsspellbooks/spells/ender/RecallSpell"), directTimeOwners);
        assertTrue(continuous > 0);
    }

    @Test
    void contextReadsReductionOnceWithNullFallbackAndKeepsVirtualBaseTimeDispatch() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs/ironsspells-3.16.1.jar"));
        var resolved = repository.resolveMethod(SPELL, "getEffectiveCastTime", EFFECTIVE_DESCRIPTOR);
        var analysis = AutomaticFormulaExtractor.analyze(resolved.owner(), resolved.method(), repository,
                "io.redspace.ironsspellbooks.spells.fire.FireballSpell");
        assertTrue(analysis.supported(), analysis.rejection());
        var prelude = analysis.formula().inputPrelude();
        assertEquals(1, prelude.stream().filter(instruction -> instruction instanceof MethodInsnNode call
                && call.desc.equals("(Lnet/minecraft/world/entity/ai/attributes/Attribute;)D")
                && Set.of("getAttributeValue", "m_21133_").contains(call.name)).count());
        assertEquals(1, prelude.stream().filter(instruction -> instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.name.equals("getCastTime")
                && call.desc.equals("(I)I")).count());
        assertTrue(prelude.stream().anyMatch(instruction -> instruction instanceof JumpInsnNode jump
                && jump.getOpcode() == Opcodes.IFNULL));
        assertTrue(prelude.stream().anyMatch(instruction -> instruction.getOpcode() == Opcodes.DCONST_1));
    }

    @Test
    void noContextRetainsLegacyMultiplierExtraction() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs/ironsspells-3.16.1.jar"));
        var resolved = repository.resolveMethod(SPELL, "getEffectiveCastTime", EFFECTIVE_DESCRIPTOR);
        var original = AutomaticFormulaExtractor.analyze(resolved.owner(), resolved.method(), repository);
        var explicitNull = AutomaticFormulaExtractor.analyze(resolved.owner(), resolved.method(), repository, null);
        assertTrue(original.supported(), original.rejection());
        assertEquals(original.formula().expression(), explicitNull.formula().expression());
        assertEquals(original.formula().variables().keySet(), explicitNull.formula().variables().keySet());
        assertTrue(original.formula().variables().containsKey("cast_time_multiplier"));
        assertFalse(original.formula().variables().containsKey("cast_time_reduction"));
    }

    @Test
    void dynamicCastTypesCannotBeSpecializedAsFixedNativeTypes() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs/ironsspells-3.16.1.jar"));
        var dynamic = new ClassNode();
        dynamic.name = "test/spells/DynamicCastSpell";
        dynamic.superName = SPELL;
        var castType = new MethodNode(Opcodes.ACC_PUBLIC, "getCastType", "()L" + CAST_TYPE + ';', null, null);
        castType.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        castType.instructions.add(new InsnNode(Opcodes.ARETURN));
        dynamic.methods.add(castType);
        repository.add(dynamic);
        var resolved = repository.resolveMethod(SPELL, "getEffectiveCastTime", EFFECTIVE_DESCRIPTOR);
        var analysis = AutomaticFormulaExtractor.analyze(resolved.owner(), resolved.method(), repository, dynamic.name);
        assertFalse(analysis.supported());
        assertTrue(analysis.rejection().contains("not a fixed enum"));
    }
}
