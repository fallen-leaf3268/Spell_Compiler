package com.example.spell_compiler.audit.formula;

import com.example.spell_compiler.formula.FormulaCatalogGenerator;
import com.example.spell_compiler.formula.FormulaClassRepository;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaCastTypeContractTest {
    @Test
    void registeredNativeSpellsHaveFixedCastingTypes() throws Exception {
        var repository = FormulaClassRepository.fromJar(Path.of("libs/ironsspells-3.16.1.jar"));
        var classes = FormulaCatalogGenerator.registeredSpellClasses(repository);
        assertEquals(109, classes.size());
        for (String owner : classes) {
            var resolved = repository.resolveMethod(owner, "getCastType",
                    "()Lio/redspace/ironsspellbooks/api/spells/CastType;");
            assertNotNull(resolved, owner);
            var instructions = StreamSupport.stream(resolved.method().instructions.spliterator(), false)
                    .filter(instruction -> instruction.getOpcode() >= 0).toList();
            assertEquals(2, instructions.size(), owner);
            var field = assertInstanceOf(FieldInsnNode.class, instructions.get(0), owner);
            assertEquals(Opcodes.GETSTATIC, field.getOpcode(), owner);
            assertEquals("io/redspace/ironsspellbooks/api/spells/CastType", field.owner, owner);
            assertTrue(Set.of("INSTANT", "LONG", "CONTINUOUS", "NONE").contains(field.name), owner);
            assertEquals(Opcodes.ARETURN, instructions.get(1).getOpcode(), owner);
        }
    }
}
