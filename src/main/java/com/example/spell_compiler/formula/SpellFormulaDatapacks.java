package com.example.spell_compiler.formula;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class SpellFormulaDatapacks {
    private SpellFormulaDatapacks() {}

    public static Path directory() {
        return FMLPaths.GAMEDIR.get().resolve("spell_compiler").toAbsolutePath().normalize();
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("spell_compiler")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("export_spells").executes(context -> export(context.getSource()))));
    }

    private static int export(CommandSourceStack source) {
        Path directory = directory();
        var catalog = SpellFormulaState.catalog();
        var diagnostics = SpellFormulaFiles.exportPack(directory, catalog);
        diagnostics.forEach(SpellFormulaRuntime::logDiagnostic);
        if (!diagnostics.isEmpty()) {
            source.sendFailure(Component.translatable("commands.spell_compiler.export_spells.failure",
                    diagnostics.size(), directory.toString()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.spell_compiler.export_spells.success",
                directory.toString()), false);
        return catalog.spells().size();
    }
}
