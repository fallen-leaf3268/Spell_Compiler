package com.example.spell_compiler.formula;

import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpellFormulaReloadListenerTest {
    @Test
    void failedSelectedResourcesStillRejectDuplicateSpellPaths() throws Exception {
        SpellFormulaState.initialize(getClass().getClassLoader());
        for (String folder : List.of("a", "z")) {
            for (String invalid : List.of("{invalid", " ".repeat(262_145),
                    "{\"parameters\":{\"baseManaCost\":80,\"baseManaCost\":90}}")) {
                Path root = directory();
                Path lower = root.resolve("lower");
                Path upper = root.resolve("upper");
                write(lower, "fire", "{\"parameters\":{\"baseManaCost\":70}}");
                write(upper, folder, invalid);
                try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(
                        new PathPackResources("valid_lower", lower, false),
                        new PathPackResources("invalid_upper", upper, false)))) {
                    var snapshot = SpellFormulaReloadListener.load(resources);
                    String scenario = folder + '/' + invalid.length();
                    assertTrue(snapshot.byClass().isEmpty(), scenario);
                    assertEquals(2, snapshot.diagnostics().size(), scenario);
                    assertEquals(1, snapshot.diagnostics().stream().filter(value -> value.message()
                            .contains("同一法术存在多个数据路径")).count(), scenario);
                    assertTrue(snapshot.diagnostics().stream().allMatch(value -> value.cause() != null), scenario);
                    assertTrue(snapshot.diagnostics().stream().anyMatch(value -> value.message()
                            .contains("invalid_upper")), scenario);
                }
            }
        }
    }

    @Test
    void invalidHighPrioritySamePathDoesNotRestoreTheLowerResource() throws Exception {
        SpellFormulaState.initialize(getClass().getClassLoader());
        Path root = directory();
        Path lower = root.resolve("lower");
        Path upper = root.resolve("upper");
        write(lower, "fire", "{\"parameters\":{\"baseManaCost\":70}}");
        write(upper, "fire", "{invalid");
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(
                new PathPackResources("valid_lower", lower, false),
                new PathPackResources("invalid_upper", upper, false)))) {
            var snapshot = SpellFormulaReloadListener.load(resources);
            assertTrue(snapshot.byClass().isEmpty());
            assertEquals(1, snapshot.diagnostics().size());
            assertTrue(snapshot.diagnostics().get(0).message().contains("invalid_upper"));
            assertNotNull(snapshot.diagnostics().get(0).cause());
        }
    }

    private static Path directory() throws Exception {
        Path parent = Path.of("build/formula-runtime-test").toAbsolutePath();
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "reload-");
    }

    private static void write(Path root, String folder, String contents) throws Exception {
        Path file = root.resolve("data/irons_spellbooks/spells/" + folder + "/fireball.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
