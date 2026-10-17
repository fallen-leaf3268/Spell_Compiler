package com.example.spell_compiler.formula;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@GameTestHolder("spell_compiler_formula")
@PrefixGameTestTemplate(false)
public final class SpellCompilerReloadListenerGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failedSelectedResourcesStillRejectDuplicateSpellPaths(GameTestHelper helper) throws Exception {
        Path parent = helper.getLevel().getServer().getWorldPath(LevelResource.DATAPACK_DIR)
                .resolve("formula-failed-resource-probe");
        Path lower = parent.resolve("lower");
        write(lower, "fire", "{\"parameters\":{\"baseManaCost\":70}}");
        for (String folder : List.of("a", "z")) {
            for (String invalid : List.of("{invalid", " ".repeat(262_145),
                    "{\"parameters\":{\"baseManaCost\":80,\"baseManaCost\":90}}")) {
                Path upper = parent.resolve(folder + '-' + invalid.length());
                write(upper, folder, invalid);
                try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(
                        new PathPackResources("valid_lower", lower, false),
                        new PathPackResources("invalid_upper", upper, false)))) {
                    var snapshot = SpellFormulaReloadListener.load(resources);
                    helper.assertTrue(snapshot.byClass().isEmpty(), "Invalid duplicate resource was silently discarded");
                    helper.assertTrue(snapshot.diagnostics().size() == 2
                                    && snapshot.diagnostics().stream().filter(value -> value.message()
                                    .contains("同一法术存在多个数据路径")).count() == 1,
                            "Failed duplicate resource did not retain exactly its reading and conflict diagnostics");
                    helper.assertTrue(snapshot.diagnostics().stream().allMatch(value -> value.cause() != null),
                            "Failed duplicate resource lost its original diagnostic cause");
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void invalidHighPrioritySamePathDoesNotRestoreTheLowerResource(GameTestHelper helper) throws Exception {
        Path parent = helper.getLevel().getServer().getWorldPath(LevelResource.DATAPACK_DIR)
                .resolve("formula-invalid-priority-probe");
        Path lower = parent.resolve("lower");
        Path upper = parent.resolve("upper");
        write(lower, "fire", "{\"parameters\":{\"baseManaCost\":70}}");
        write(upper, "fire", "{invalid");
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(
                new PathPackResources("valid_lower", lower, false),
                new PathPackResources("invalid_upper", upper, false)))) {
            var snapshot = SpellFormulaReloadListener.load(resources);
            helper.assertTrue(snapshot.byClass().isEmpty(), "Invalid high priority file restored a lower priority rule");
            helper.assertTrue(snapshot.diagnostics().size() == 1
                            && snapshot.diagnostics().get(0).message().contains("invalid_upper")
                            && snapshot.diagnostics().get(0).cause() != null,
                    "Invalid high priority file did not retain its single original reading diagnostic");
        }
        helper.succeed();
    }

    private static void write(Path root, String folder, String contents) throws Exception {
        Path file = root.resolve("data/irons_spellbooks/spells/" + folder + "/fireball.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
