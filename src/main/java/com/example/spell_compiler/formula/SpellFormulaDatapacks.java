package com.example.spell_compiler.formula;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class SpellFormulaDatapacks {
    private SpellFormulaDatapacks() {}

    public static Path directory() {
        return FMLPaths.GAMEDIR.get().resolve("spell_compiler").toAbsolutePath().normalize();
    }

    public static Path diagnosticDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("logs").resolve("spell_compiler").toAbsolutePath().normalize();
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("spell_compiler")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("export_spells").executes(context -> export(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context.getSource(), null))
                        .then(Commands.argument("spell_id", ResourceLocationArgument.id()).executes(context ->
                                status(context.getSource(), ResourceLocationArgument.getId(context, "spell_id").toString()))))
                .then(Commands.literal("export_report").executes(context -> report(context.getSource()))));
    }

    private static int status(CommandSourceStack source, String id) {
        var catalog = SpellFormulaState.catalog();
        var snapshot = SpellFormulaState.serverSnapshot();
        var disabled = FormulaRuntimeDispatcher.disabledServerKeys();
        if (id == null) {
            source.sendSuccess(() -> Component.translatable("commands.spell_compiler.status.summary",
                    catalog.spells().size(), snapshot.byClass().size(), snapshot.diagnostics().size(), disabled.size()), false);
            return snapshot.byClass().size();
        }
        var spell = catalog.spells().stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
        if (spell == null) {
            source.sendFailure(Component.translatable("commands.spell_compiler.status.unknown", id));
            return 0;
        }
        long stopped = spell.formulas().stream().filter(method -> disabled.contains(
                FormulaRuntimeDispatcher.key(spell.className(), method.key()))).count();
        var analysis = catalog.analysis(id);
        int diagnostics = analysis.has("diagnostics") ? analysis.getAsJsonArray("diagnostics").size() : 0;
        source.sendSuccess(() -> Component.translatable("commands.spell_compiler.status.spell", id,
                snapshot.byClass().containsKey(spell.className()), spell.editableFormulas().size(), stopped, diagnostics), false);
        return 1;
    }

    private static int report(CommandSourceStack source) {
        try {
            Path file = FormulaReports.write(diagnosticDirectory(), "analysis", FormulaReports.report(SpellFormulaState.catalog(),
                    SpellFormulaState.serverSnapshot(), FormulaRuntimeDispatcher.disabledServerKeys()));
            source.sendSuccess(() -> Component.translatable("commands.spell_compiler.report.success", file.toString()), false);
            return 1;
        } catch (java.io.IOException failure) {
            source.sendFailure(Component.translatable("commands.spell_compiler.report.failure", failure.getMessage()));
            return 0;
        }
    }

    private static int export(CommandSourceStack source) {
        Path directory = directory();
        var catalog = SpellFormulaState.catalog();
        var diagnostics = SpellFormulaFiles.exportPack(directory, catalog, diagnosticDirectory());
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
