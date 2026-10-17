package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = SpellCompiler.MODID)
public final class NativeOriginalRemoteServerCases {
    private static final String ID = "irons_spellbooks:gust";
    private static SpellFormulaFiles.Snapshot saved;
    private static byte[] previous;
    private static String activeNonce;

    private NativeOriginalRemoteServerCases() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        if (!Boolean.getBoolean("spell_compiler.formulaRemoteProbe")) return;
        var root = Commands.literal("formula_native_original_probe").requires(source -> source.hasPermission(4));
        for (String action : List.of("install", "restore"))
            root.then(Commands.literal(action).then(Commands.argument("nonce", StringArgumentType.word()).executes(context -> {
                if (!(context.getSource().getEntity() instanceof ServerPlayer)) throw new IllegalArgumentException("Missing actual remote player");
                String nonce = StringArgumentType.getString(context, "nonce");
                try {
                    if (action.equals("install")) install(context.getSource().getServer(), nonce);
                    else restore(context.getSource().getServer(), nonce);
                    return 1;
                } catch (Exception failure) {
                    SpellCompiler.LOGGER.error("FORMULA_REMOTE_NATIVE_ORIGINAL_SERVER_FAIL", failure);
                    try {
                        publish("failed", nonce, Map.of("error", failure.toString()));
                    } catch (Exception diagnosticFailure) {
                        failure.addSuppressed(diagnosticFailure);
                    }
                    throw new IllegalStateException(failure);
                }
            })));
        event.getDispatcher().register(root);
    }

    private static void install(MinecraftServer server, String nonce) throws Exception {
        if (saved != null) throw new IllegalStateException("A native original configuration test is active");
        saved = SpellFormulaState.serverSnapshot();
        activeNonce = nonce;
        previous = Files.isRegularFile(file()) ? Files.readAllBytes(file()) : null;
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "{\"irons_spellbooks:mana_cost_multiplier\":2.0,\"irons_spellbooks:power_multiplier\":2.0,\"irons_spellbooks:cooldown_in_seconds\":27.0}",
                StandardCharsets.UTF_8);
        SpellConfigManager.getInstance().handleServerConfigUpdate();
        synchronize(server);
        AbstractSpell spell = spell();
        int expectedMana = spell.getManaCost(2);
        float expectedPower = spell.getSpellPower(5, null);
        if (expectedMana != 70 || expectedPower != 28) throw new IllegalStateException("Independent native test baseline is incorrect");
        var json = new JsonObject();
        var advanced = new JsonObject();
        advanced.addProperty("max_level", 1);
        json.add("advanced", advanced);
        var parameters = new JsonObject();
        parameters.addProperty("baseManaCost", Integer.MAX_VALUE / 4);
        parameters.addProperty("manaCostPerLevel", Integer.MAX_VALUE / 4);
        parameters.addProperty("baseSpellPower", Long.MAX_VALUE / 4);
        parameters.addProperty("spellPowerPerLevel", Long.MAX_VALUE / 4);
        parameters.addProperty("mana_cost_multiplier", 3.0);
        parameters.addProperty("power_multiplier", 3.0);
        parameters.addProperty("cooldown_in_seconds", 3.0);
        json.add("parameters", parameters);
        var parsed = SpellFormulaFiles.parseResources(Map.of(ID, json), SpellFormulaState.catalog());
        if (!parsed.diagnostics().isEmpty()) throw new IllegalStateException("Independent test JSON did not parse");
        var merged = new LinkedHashMap<>(saved.byClass());
        merged.putAll(parsed.byClass());
        var applied = SpellFormulaRuntime.applyServer(SpellFormulaFiles.snapshot(merged, List.of()));
        synchronize(server);
        if (!applied.diagnostics().isEmpty() || spell.getManaCost(2) != expectedMana || spell.getSpellPower(5, null) != expectedPower)
            throw new IllegalStateException("Independent server fallback failed");
        publish("installed", nonce, Map.of("mana", Integer.toString(expectedMana), "power", Float.toString(expectedPower),
                "original_cooldown", "27.0", "fingerprint", applied.fingerprint()));
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_NATIVE_ORIGINAL_SERVER_CASE_PASS case=server_file_original_fallback");
    }

    private static void restore(MinecraftServer server, String nonce) throws Exception {
        if (saved == null || !nonce.equals(activeNonce)) throw new IllegalStateException("No matching native original configuration test");
        if (previous == null) Files.writeString(file(), "{}", StandardCharsets.UTF_8);
        else Files.write(file(), previous);
        var restored = SpellFormulaRuntime.applyServer(saved);
        synchronize(server);
        publish("restored", nonce, Map.of("mana", Integer.toString(spell().getManaCost(2)),
                "power", Float.toString(spell().getSpellPower(5, null)),
                "native_mana", SpellConfigManager.getSpellConfigValue(spell(), SpellConfigParameter.MANA_MULTIPLIER).toString(),
                "native_power", SpellConfigManager.getSpellConfigValue(spell(), SpellConfigParameter.POWER_MULTIPLIER).toString(),
                "native_cooldown", SpellConfigManager.getSpellConfigValue(spell(), SpellConfigParameter.COOLDOWN_IN_SECONDS).toString(),
                "fingerprint", restored.fingerprint()));
        saved = null;
        previous = null;
        activeNonce = null;
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_NATIVE_ORIGINAL_SERVER_PASS");
    }

    private static AbstractSpell spell() { return SpellRegistry.getSpell(ID); }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("irons_spellbooks_spell_config/irons_spellbooks/gust.json");
    }

    private static void synchronize(MinecraftServer server) {
        MinecraftForge.EVENT_BUS.post(new OnDatapackSyncEvent(server.getPlayerList(), null));
    }

    private static void publish(String stage, String nonce, Map<String, String> values) throws Exception {
        Path control = Path.of(System.getProperty("spell_compiler.formulaRemoteControl"));
        String name = System.getProperty("spell_compiler.formulaRemoteName", "A");
        var data = new LinkedHashMap<>(values);
        data.put("stage", stage);
        data.put("nonce", nonce);
        data.put("pid", Long.toString(ProcessHandle.current().pid()));
        StringBuilder text = new StringBuilder();
        data.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Path target = control.resolve("native-original-" + name + ".properties");
        Path temporary = control.resolve("native-original-" + name + ".properties.tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
