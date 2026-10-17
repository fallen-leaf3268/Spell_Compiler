package com.example.spell_compiler.audit;

import com.example.spell_compiler.SpellCompiler;
import com.example.spell_compiler.formula.FormulaNativeValues;
import com.example.spell_compiler.formula.SpellFormulaState;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.client.Minecraft;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class NativeOriginalRemoteClientCases {
    private static int phase;
    private static String nonce;
    private static String originalFingerprint;
    private static long started;

    private NativeOriginalRemoteClientCases() {}

    public static boolean advance(Minecraft minecraft) throws Exception {
        if (phase == 3) return true;
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) return false;
        if (phase == 0) {
            var parent = read("remote-A.properties");
            if (parent == null || !"bad".equals(parent.getProperty("stage"))
                    || !SpellFormulaState.clientSnapshot().fingerprint().equals(parent.getProperty("fingerprint"))) return false;
            check(minecraft.getSingleplayerServer() == null && !minecraft.getConnection().getConnection().isMemoryConnection(),
                    "Original native configuration check did not use independent TCP");
            originalFingerprint = SpellFormulaState.clientSnapshot().fingerprint();
            started = System.nanoTime();
            nonce = Long.toUnsignedString(started);
            command(minecraft, "install");
            phase = 1;
            return false;
        }
        check(System.nanoTime() - started < 120_000_000_000L, "Original native configuration remote test timed out");
        var status = read("native-original-A.properties");
        if (status == null || !nonce.equals(status.getProperty("nonce"))) return false;
        check(!"failed".equals(status.getProperty("stage")), "Native original server failed: " + status.getProperty("error"));
        check(Long.parseLong(status.getProperty("pid")) != ProcessHandle.current().pid(), "Native original check shared a JVM");
        if (!SpellFormulaState.clientSnapshot().fingerprint().equals(status.getProperty("fingerprint"))) return false;
        var spell = SpellRegistry.getSpell("irons_spellbooks:gust");
        if (phase == 1 && "installed".equals(status.getProperty("stage"))) {
            check(SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.MANA_MULTIPLIER) == 3.0
                            && SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.POWER_MULTIPLIER) == 3.0,
                    "Modified native multipliers did not arrive over TCP");
            int mana = Integer.parseInt(status.getProperty("mana"));
            float power = Float.parseFloat(status.getProperty("power"));
            for (int attempt = 0; attempt < 3; attempt++) {
                check(spell.getManaCost(2) == mana, "Client fallback discarded server original mana multiplier");
                check(spell.getSpellPower(5, null) == power, "Client fallback discarded server original power multiplier");
            }
            check(FormulaNativeValues.originalConfig(spell, SpellConfigParameter.COOLDOWN_IN_SECONDS, null) == 27.0,
                    "Client fallback discarded original server cooldown");
            pass("tcp_original_mana_multiplier_survives_modified_native_packet");
            pass("tcp_original_power_multiplier_survives_modified_native_packet");
            pass("tcp_original_cooldown_survives_modified_native_packet");
            command(minecraft, "restore");
            phase = 2;
            return false;
        }
        if (phase == 2 && "restored".equals(status.getProperty("stage"))) {
            check(SpellFormulaState.clientSnapshot().fingerprint().equals(originalFingerprint), "Remote original test changed the parent snapshot");
            check(spell.getManaCost(2) == Integer.parseInt(status.getProperty("mana"))
                            && spell.getSpellPower(5, null) == Float.parseFloat(status.getProperty("power")),
                    "Remote original test retained its temporary base fields");
            check(FormulaNativeValues.originalConfig(spell, SpellConfigParameter.MANA_MULTIPLIER, null)
                            == Double.parseDouble(status.getProperty("native_mana"))
                            && FormulaNativeValues.originalConfig(spell, SpellConfigParameter.POWER_MULTIPLIER, null)
                            == Double.parseDouble(status.getProperty("native_power"))
                            && FormulaNativeValues.originalConfig(spell, SpellConfigParameter.COOLDOWN_IN_SECONDS, null)
                            == Double.parseDouble(status.getProperty("native_cooldown")),
                    "A new snapshot retained stale original native numeric values");
            pass("tcp_reload_replaces_original_config_and_restores_parent_snapshot");
            SpellCompiler.LOGGER.info("FORMULA_REMOTE_NATIVE_ORIGINAL_CLIENT_PASS cases=4");
            phase = 3;
            return true;
        }
        return false;
    }

    private static Properties read(String name) throws Exception {
        Path file = Path.of(System.getProperty("spell_compiler.formulaRemoteControl")).resolve(name);
        if (!Files.isRegularFile(file)) return null;
        var properties = new Properties();
        properties.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        return properties;
    }

    private static void command(Minecraft minecraft, String action) {
        minecraft.getConnection().sendCommand("formula_native_original_probe " + action + ' ' + nonce);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void pass(String name) {
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_NATIVE_ORIGINAL_CASE_PASS case={}", name);
    }
}
