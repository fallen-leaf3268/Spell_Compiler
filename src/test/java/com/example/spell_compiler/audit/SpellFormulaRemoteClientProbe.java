package com.example.spell_compiler.audit;

import com.example.spell_compiler.SpellCompiler;
import com.example.spell_compiler.formula.FormulaRuntimeDispatcher;
import com.example.spell_compiler.formula.SpellFormulaState;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.spells.fire.FireballSpell;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = SpellCompiler.MODID, value = Dist.CLIENT)
public final class SpellFormulaRemoteClientProbe {
    private static final String OWNER = "io.redspace.ironsspellbooks.spells.fire.FireballSpell";
    private static final String METHOD = "getDamage(ILnet/minecraft/world/entity/LivingEntity;)F";
    private static int phase;
    private static int passes;
    private static long started;
    private static long generationA;
    private static int loginsA;
    private static boolean stopped;
    private static String screenState;

    private SpellFormulaRemoteClientProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("spell_compiler.formulaRemoteClientProbe") || stopped
                || event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (started == 0) started = System.nanoTime();
        try {
            check(System.nanoTime() - started < 600_000_000_000L, "Remote client timeout in phase " + phase);
            String currentScreen = phase + ":" + (minecraft.screen == null ? "null" : minecraft.screen.getClass().getName())
                    + ":" + (minecraft.getOverlay() == null ? "null" : minecraft.getOverlay().getClass().getName());
            if (!currentScreen.equals(screenState)) {
                screenState = currentScreen;
                SpellCompiler.LOGGER.info("FORMULA_REMOTE_CLIENT_SCREEN {}", currentScreen);
            }
            if (minecraft.screen instanceof DisconnectedScreen disconnected && phase != 7 && phase != 9 && phase != 10
                    && phase != 12 && phase != 13)
                throw new AssertionError("Unexpected remote disconnect in phase " + phase + ": "
                        + disconnected.getNarrationMessage().getString());
            if (phase == 0) {
                if (minecraft.getOverlay() != null || !(minecraft.screen instanceof TitleScreen)) return;
                var a = status("A");
                var b = status("B");
                if (a == null || b == null || !a.getProperty("stage").equals("initial")
                        || !b.getProperty("stage").equals("initial")) return;
                long pidA = Long.parseLong(a.getProperty("pid"));
                long pidB = Long.parseLong(b.getProperty("pid"));
                check(pidA != pidB && pidA != ProcessHandle.current().pid() && pidB != ProcessHandle.current().pid(),
                        "Remote endpoints did not run in three separate JVMs");
                connect(minecraft, a);
                phase = 1;
                return;
            }
            if (phase == 7 || phase == 10 || phase == 13) {
                if (minecraft.player != null || minecraft.level != null || minecraft.getConnection() != null) return;
                check(SpellFormulaState.clientSnapshot().byClass().isEmpty(), "Remote logout retained overrides");
                check(FormulaRuntimeDispatcher.resolve(FormulaRuntimeDispatcher.key(OWNER, METHOD), null) == null,
                        "Remote logout retained a client delegate");
                for (var spell : SpellFormulaState.catalog().spells())
                    for (var method : spell.formulas())
                        check(FormulaRuntimeDispatcher.resolve(FormulaRuntimeDispatcher.key(spell.className(), method.key()), null) == null,
                                "Remote logout retained a catalog delegate " + spell.id() + '#' + method.alias());
                check(fireball().getManaCost(1) == 60, "Remote logout retained changed base mana");
                check(fireball().getSpellCooldown() == 500, "Remote logout retained a cooldown formula or parameter");
                close(fireball().getDamage(1, null), 10, "Remote logout retained changed damage");
                close(lance().getSpellPower(1, null), 14, "Remote logout retained inherited power");
                if (phase == 7) {
                    pass("remote_logout_clears_all_catalog_delegates_and_base_parameters");
                    connect(minecraft, status("A"));
                    phase = 8;
                } else if (phase == 10) {
                    pass("second_remote_logout_restores_native_state");
                    connect(minecraft, status("B"));
                    phase = 11;
                } else {
                    pass("cross_server_logout_restores_native_state");
                    check(passes == 12, "Unexpected remote coverage count " + passes);
                    SpellCompiler.LOGGER.info("FORMULA_REMOTE_CLIENT_PASS cases={} firstServerGeneration={}", passes, generationA);
                    stopped = true;
                    minecraft.stop();
                }
                return;
            }
            if (phase == 9 || phase == 12) {
                var state = status(phase == 9 ? "A" : "B");
                if (state == null || !state.getProperty("stage").equals("done")) return;
                disconnect(minecraft);
                phase = phase == 9 ? 10 : 13;
                return;
            }
            if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) return;
            minecraft.setScreen(null);
            if (phase == 1) {
                var a = ready(minecraft, "A", "initial");
                if (a == null || integer(a, "logins") < 1) return;
                check(integer(a, "spells") == 1 && integer(a, "mana") == 63, "Initial server A fixture differs");
                close(fireball().getDamage(1, null), 13.75f, "Initial server A damage differs");
                check(SpellConfigManager.getSpellConfigValue(fireball(), SpellConfigParameter.MAX_LEVEL) == 6,
                        "Remote native max-level config did not synchronize");
                close(SpellConfigManager.getSpellConfigValue(fireball(), SpellConfigParameter.POWER_MULTIPLIER).floatValue(),
                        1.25f, "Remote native power multiplier did not synchronize");
                nativeSchoolAttributePreviews(minecraft);
                pass("independent_tcp_login_native_config_and_previews");
                command(minecraft, "large");
                phase = 2;
            } else if (phase == 2) {
                var a = ready(minecraft, "A", "large");
                if (a == null) return;
                large(minecraft, a);
                if (!FormulaConsumerRemoteClientCases.advance(minecraft)) return;
                nativeSchoolAttributePreviews(minecraft);
                pass("six_fragment_snapshot_installs_all_catalog_client_formulas_and_inherited_power");
                command(minecraft, "bad");
                phase = 3;
            } else if (phase == 3) {
                if (!NativeOriginalRemoteClientCases.advance(minecraft)) return;
                if (!FormulaWidePowerRemoteClientCases.advance(minecraft)) return;
                var a = ready(minecraft, "A", "bad");
                if (a == null) return;
                for (int attempt = 0; attempt < 3; attempt++)
                    close(fireball().getDamage(1, null), 10, "Remote failing formula did not fall back natively");
                pass("remote_runtime_failure_falls_back_without_disconnect");
                command(minecraft, "recover");
                phase = 4;
            } else if (phase == 4) {
                if (ready(minecraft, "A", "recover") == null) return;
                close(fireball().getDamage(1, null), 12, "Remote reload did not restore a disabled formula");
                pass("remote_reload_recovers_after_runtime_failure");
                command(minecraft, "invalid");
                phase = 14;
            } else if (phase == 14) {
                var a = ready(minecraft, "A", "invalid");
                if (a == null) return;
                check(integer(a, "spells") == catalogSpellCount() - 1
                                && integer(a, "formulas") == catalogFormulaCount() - fireballFormulaCount()
                                && integer(a, "parts") == 6,
                        "Invalid remote JSON did not isolate its one rejected spell");
                check(!SpellFormulaState.clientSnapshot().byClass().containsKey(OWNER),
                        "Invalid remote JSON retained a partial Fireball override");
                check(FormulaRuntimeDispatcher.resolve(FormulaRuntimeDispatcher.key(OWNER, METHOD), minecraft.player) == null,
                        "Invalid remote JSON retained its Fireball delegate");
                pass("invalid_remote_json_rejects_whole_spell_and_keeps_others_connected");
                command(minecraft, "remove");
                phase = 5;
            } else if (phase == 5) {
                if (ready(minecraft, "A", "remove") == null) return;
                check(SpellFormulaState.clientSnapshot().byClass().isEmpty(), "Removed remote datapack rules survived");
                check(FormulaRuntimeDispatcher.resolve(FormulaRuntimeDispatcher.key(OWNER, METHOD), minecraft.player) == null,
                        "Removed remote rule retained its delegate");
                close(fireball().getDamage(1, null), 10, "Removed remote formula did not restore original method");
                pass("remote_datapack_removal_restores_original_method_and_fields");
                command(minecraft, "large");
                phase = 6;
            } else if (phase == 6) {
                var a = ready(minecraft, "A", "large");
                if (a == null) return;
                large(minecraft, a);
                generationA = Long.parseLong(a.getProperty("generation"));
                loginsA = integer(a, "logins");
                pass("six_fragment_snapshot_reinstalls_after_removal");
                disconnect(minecraft);
                phase = 7;
            } else if (phase == 8) {
                var a = ready(minecraft, "A", "large");
                if (a == null || integer(a, "logins") <= loginsA) return;
                large(minecraft, a);
                generationA = Long.parseLong(a.getProperty("generation"));
                pass("same_tcp_server_reconnect_resynchronizes_large_snapshot");
                command(minecraft, "done");
                phase = 9;
            } else if (phase == 11) {
                var b = ready(minecraft, "B", "initial");
                if (b == null || integer(b, "logins") < 1) return;
                long generationB = Long.parseLong(b.getProperty("generation"));
                if (generationB == 0) return;
                check(generationB > 0 && generationB < generationA,
                        "New server did not exercise a lower snapshot generation: A=" + generationA + ", B=" + generationB);
                check(integer(b, "spells") == 1 && integer(b, "mana") == 69, "Initial server B fixture differs");
                close(fireball().getDamage(1, null), 18.75f, "New server retained the previous server's formula");
                nativeSchoolAttributePreviews(minecraft);
                pass("new_server_lower_generation_replaces_previous_session_rules");
                command(minecraft, "done");
                phase = 12;
            }
        } catch (Throwable error) {
            stopped = true;
            SpellCompiler.LOGGER.error("FORMULA_REMOTE_CLIENT_FAIL phase=" + phase, error);
            try {
                Files.writeString(control().resolve("client-failed.txt"), error.toString(), StandardCharsets.UTF_8);
            } catch (java.io.IOException diagnosticFailure) {
                SpellCompiler.LOGGER.error("FORMULA_REMOTE_CLIENT_DIAGNOSTIC_FAIL", diagnosticFailure);
            }
            minecraft.stop();
        }
    }

    private static Properties ready(Minecraft minecraft, String name, String stage) throws Exception {
        var state = status(name);
        if (state == null || !stage.equals(state.getProperty("stage"))) return null;
        var snapshot = SpellFormulaState.clientSnapshot();
        if (!snapshot.fingerprint().equals(state.getProperty("fingerprint"))) return null;
        check(minecraft.getSingleplayerServer() == null, "Remote check ran against a shared integrated server");
        check(!minecraft.getConnection().getConnection().isMemoryConnection(), "Remote check used a memory channel");
        check(SpellFormulaState.serverSnapshot().byClass().isEmpty(), "Client JVM unexpectedly owns server formula state");
        check(snapshot.byClass().size() == integer(state, "spells"), "Client spell count differs from server publication");
        int formulas = snapshot.byClass().values().stream().mapToInt(value -> value.formulas().size()).sum();
        check(formulas == integer(state, "formulas"), "Client formula count differs from server publication");
        close(fireball().getDamage(1, null), Float.parseFloat(state.getProperty("damage")), "Remote client damage differs");
        check(fireball().getManaCost(1) == integer(state, "mana"), "Remote client base parameter differs");
        check(fireball().getSpellCooldown() == integer(state, "cooldown_ticks"), "Remote client inherited cooldown differs");
        close(lance().getSpellPower(1, null), Float.parseFloat(state.getProperty("lance_power")),
                "Remote client inherited power differs");
        return state;
    }

    private static void large(Minecraft minecraft, Properties state) {
        check(integer(state, "parts") == 6 && integer(state, "formulas") == catalogFormulaCount()
                        && integer(state, "spells") == catalogSpellCount(),
                "Large remote snapshot did not cover six actual fragments and all formulas");
        check(integer(state, "total_formulas") == catalogFormulaCount()
                        && integer(state, "total_spells") == catalogSpellCount()
                        && integer(state, "fireball_formulas") == fireballFormulaCount()
                        && integer(state, "total_native_formulas") == catalogNativeFormulaCount()
                        && integer(state, "total_site_helpers") == catalogFormulaCount() - catalogNativeFormulaCount(),
                "Server and client catalogs differ");
        int installed = 0;
        for (var spell : SpellFormulaState.clientSnapshot().byClass().values()) {
            for (String method : spell.formulas().keySet()) {
                check(FormulaRuntimeDispatcher.resolve(FormulaRuntimeDispatcher.key(spell.spell().className(), method),
                        minecraft.player) != null, "Large remote snapshot omitted a client delegate");
                installed++;
            }
        }
        check(installed == catalogFormulaCount(), "Large remote delegate count differs " + installed);
        close(fireball().getDamage(1, null), 11, "Large remote damage edit did not apply");
        check(fireball().getManaCost(1) == 81, "Large remote mana formula did not apply after its base parameter");
        check(fireball().getSpellCooldown() == 520, "Large remote inherited cooldown formula did not apply");
        close(lance().getSpellPower(1, null), 19, "Large remote inherited power edit did not apply");
        String expectedPreview = Component.translatable("ui.irons_spellbooks.damage", Utils.stringTruncation(19, 1)).getString();
        check(lance().getUniqueInfo(1, null).stream().anyMatch(line -> line.getString().equals(expectedPreview)),
                "Large remote inherited power preview did not update");
    }

    private static void connect(Minecraft minecraft, Properties state) {
        check(state != null, "Remote endpoint status is missing");
        String address = "127.0.0.1:" + state.getProperty("port");
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_CLIENT_CONNECT {}", address);
        ConnectScreen.startConnecting(new TitleScreen(), minecraft, ServerAddress.parseString(address),
                new ServerData("Formula remote audit", address, false), false);
    }

    private static void disconnect(Minecraft minecraft) {
        if (minecraft.level != null) minecraft.level.disconnect();
        minecraft.clearLevel();
        minecraft.setScreen(new TitleScreen());
    }

    private static void command(Minecraft minecraft, String action) {
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_CLIENT_COMMAND {}", action);
        minecraft.getConnection().sendCommand("formula_remote_probe " + action);
    }

    private static Path control() {
        return Path.of(System.getProperty("spell_compiler.formulaRemoteControl"));
    }

    private static Properties status(String name) throws Exception {
        Path file = control().resolve("remote-" + name + ".properties");
        if (!Files.isRegularFile(file)) return null;
        var result = new Properties();
        result.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
        return result;
    }

    private static int integer(Properties state, String key) {
        return Integer.parseInt(state.getProperty(key));
    }

    private static FireballSpell fireball() {
        return (FireballSpell) SpellRegistry.getSpell("irons_spellbooks:fireball");
    }

    private static AbstractSpell lance() {
        return SpellRegistry.getSpell("irons_spellbooks:lightning_lance");
    }

    private static int catalogSpellCount() {
        return (int) SpellFormulaState.catalog().spells().stream().filter(spell -> !spell.formulas().isEmpty()).count();
    }

    private static int catalogFormulaCount() {
        return SpellFormulaState.catalog().spells().stream().mapToInt(spell -> spell.editableFormulas().size()).sum();
    }

    private static int catalogNativeFormulaCount() {
        return (int) SpellFormulaState.catalog().spells().stream().flatMap(spell -> spell.editableFormulas().stream())
                .filter(method -> !method.declaringClass().equals("com.example.spell_compiler.formula.FormulaSiteValues")).count();
    }

    private static int fireballFormulaCount() {
        return SpellFormulaState.catalog().spells().stream()
                .filter(spell -> spell.id().equals("irons_spellbooks:fireball"))
                .findFirst().orElseThrow().editableFormulas().size();
    }

    private static void nativeSchoolAttributePreviews(Minecraft minecraft) {
        var attribute = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.parse("irons_spellbooks:fire_spell_power"));
        check(attribute != null, "Native fire school attribute is absent");
        var instance = minecraft.player.getAttribute(attribute);
        check(instance != null, "Native client fire school attribute is absent");
        double beforeValue = instance.getValue();
        var beforeModifiers = java.util.Set.copyOf(instance.getModifiers());
        float beforePower = fireball().getSpellPower(1, minecraft.player);
        float beforeDamage = fireball().getDamage(1, minecraft.player);
        var modifier = new AttributeModifier(UUID.randomUUID(), "compiler_client_school_probe", 0.35,
                AttributeModifier.Operation.MULTIPLY_BASE);
        try {
            instance.addTransientModifier(modifier);
            float power = fireball().getSpellPower(1, minecraft.player);
            float damage = fireball().getDamage(1, minecraft.player);
            check(instance.getValue() > beforeValue && power > beforePower && damage > beforeDamage,
                    "Client formulas ignored a native school attribute modifier");
            close(power, (float) (beforePower * instance.getValue() / beforeValue),
                    "Client native school multiplier was applied more than once");
            String expected = Component.translatable("ui.irons_spellbooks.damage", Utils.stringTruncation(damage, 2)).getString();
            check(fireball().getUniqueInfo(1, minecraft.player).stream().map(Component::getString).anyMatch(expected::equals),
                    "Client native preview differs from its formula result after an attribute change");
        } finally {
            instance.removeModifier(modifier.getId());
        }
        check(Math.abs(instance.getValue() - beforeValue) < 0.000001
                        && beforeModifiers.equals(java.util.Set.copyOf(instance.getModifiers())),
                "Client preview changed the native attribute map");
    }

    private static void close(float actual, float expected, String message) {
        check(Float.isFinite(actual) && Math.abs(actual - expected) < 0.002f,
                message + ": actual=" + actual + ", expected=" + expected);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void pass(String name) {
        passes++;
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_CLIENT_CASE_PASS {}", name);
    }
}
