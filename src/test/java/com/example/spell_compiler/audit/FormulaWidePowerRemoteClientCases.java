package com.example.spell_compiler.audit;

import com.example.spell_compiler.SpellCompiler;
import com.example.spell_compiler.formula.SpellFormulaState;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import net.minecraft.client.Minecraft;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class FormulaWidePowerRemoteClientCases {
    private static int phase;
    private static String nonce;
    private static String originalFingerprint;
    private static float originalPower;
    private static long serverPid;
    private static long started;

    private FormulaWidePowerRemoteClientCases() {}

    public static boolean advance(Minecraft minecraft) throws Exception {
        if (phase == 4) return true;
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) return false;
        if (phase == 0) {
            var parent = read("remote-A.properties");
            if (parent == null || !"bad".equals(parent.getProperty("stage"))
                    || !SpellFormulaState.clientSnapshot().fingerprint().equals(parent.getProperty("fingerprint"))) return false;
            check(minecraft.getSingleplayerServer() == null && !minecraft.getConnection().getConnection().isMemoryConnection(),
                    "Wide power check did not use an independent TCP connection");
            serverPid = Long.parseLong(parent.getProperty("pid"));
            check(serverPid != ProcessHandle.current().pid(), "Wide power check shared the server JVM");
            originalFingerprint = SpellFormulaState.clientSnapshot().fingerprint();
            originalPower = SpellRegistry.getSpell("irons_spellbooks:lightning_lance").getSpellPower(1, null);
            check(originalPower == 19, "The client parent lance override is incorrect");
            started = System.nanoTime();
            nonce = Long.toUnsignedString(started);
            command(minecraft, "install");
            phase = 1;
            return false;
        }
        check(System.nanoTime() - started < 120_000_000_000L, "Wide power remote test timed out");
        var status = read("wide-power-A.properties");
        if (status == null || !nonce.equals(status.getProperty("nonce"))) return false;
        check(!"failed".equals(status.getProperty("stage")), "Wide power server failed: " + status.getProperty("error"));
        check(Long.parseLong(status.getProperty("pid")) == serverPid, "Wide power status came from an unexpected JVM");
        if (!SpellFormulaState.clientSnapshot().fingerprint().equals(status.getProperty("fingerprint"))) return false;
        if (phase == 1 && "installed".equals(status.getProperty("stage"))) {
            validate(minecraft, status, 3_000_000_001L, 1_000_000_003L);
            pass("tcp_long_base_and_growth_match_server_actual_projectile");
            command(minecraft, "update");
            phase = 2;
            return false;
        }
        if (phase == 2 && "updated".equals(status.getProperty("stage"))) {
            validate(minecraft, status, 9_007_199_254_740_993L, 0);
            pass("tcp_integer_above_double_exact_range_preserves_all_digits");
            command(minecraft, "restore");
            phase = 3;
            return false;
        }
        if (phase == 3 && "restored".equals(status.getProperty("stage"))) {
            check(SpellFormulaState.clientSnapshot().fingerprint().equals(originalFingerprint),
                    "Wide power restoration changed the parent fingerprint");
            float raw = SpellRegistry.getSpell("irons_spellbooks:lightning_lance").getSpellPower(1, null);
            check(raw == originalPower && raw == Float.parseFloat(status.getProperty("raw")),
                    "The client retained its temporary long power parameters");
            pass("tcp_reload_restores_parent_snapshot_and_native_power");
            SpellCompiler.LOGGER.info("FORMULA_REMOTE_WIDE_POWER_CLIENT_PASS cases=3");
            phase = 4;
            return true;
        }
        return false;
    }

    private static void validate(Minecraft minecraft, Properties status, long base, long step) {
        check(Long.parseLong(status.getProperty("base")) == base && Long.parseLong(status.getProperty("step")) == step,
                "The independent server changed the prepared long parameters");
        var spell = SpellRegistry.getSpell("irons_spellbooks:lightning_lance");
        var override = SpellFormulaState.clientSnapshot().byClass().get(spell.getClass().getName());
        check(override != null && override.parameters().get("baseSpellPower").getAsLong() == base
                        && override.parameters().get("spellPowerPerLevel").getAsLong() == step,
                "The TCP snapshot truncated the long parameters");
        int level = Integer.parseInt(status.getProperty("level"));
        long total = Math.addExact(base, Math.multiplyExact(step, level - 1L));
        float expected = (float) total;
        float raw = spell.getSpellPower(level, null);
        check(Float.isFinite(raw) && raw == expected && raw == Float.parseFloat(status.getProperty("raw")),
                "The client getter differs from the server wide power");
        float actualDamage = Float.parseFloat(status.getProperty("damage"));
        check(Float.isFinite(actualDamage) && actualDamage > Integer.MAX_VALUE,
                "The actual server projectile did not contain finite damage beyond the integer limit");
        float clientPower = spell.getSpellPower(level, minecraft.player);
        check(Float.isFinite(clientPower) && clientPower == actualDamage,
                "The client entity power differs from the actual server projectile damage");
    }

    private static Properties read(String name) throws Exception {
        Path file = Path.of(System.getProperty("spell_compiler.formulaRemoteControl")).resolve(name);
        if (!Files.isRegularFile(file)) return null;
        var properties = new Properties();
        properties.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        return properties;
    }

    private static void command(Minecraft minecraft, String action) {
        minecraft.getConnection().sendCommand("formula_wide_power_probe " + action + ' ' + nonce);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void pass(String name) {
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_WIDE_POWER_CLIENT_CASE_PASS case={}", name);
    }
}
