package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.entity.spells.AbstractMagicProjectile;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Mod.EventBusSubscriber(modid = SpellCompiler.MODID)
public final class FormulaWidePowerRemoteServerCases {
    private static final String ID = "irons_spellbooks:lightning_lance";
    private static final int LEVEL = 5;
    private static SpellFormulaFiles.Snapshot saved;
    private static String activeNonce;
    private static float originalPower;
    private static int phase;

    private FormulaWidePowerRemoteServerCases() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        if (!Boolean.getBoolean("spell_compiler.formulaRemoteProbe")) return;
        var root = Commands.literal("formula_wide_power_probe").requires(source -> source.hasPermission(4));
        for (String action : List.of("install", "update", "restore"))
            root.then(Commands.literal(action).then(Commands.argument("nonce", StringArgumentType.word()).executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                String nonce = StringArgumentType.getString(context, "nonce");
                try {
                    switch (action) {
                        case "install" -> install(player, nonce);
                        case "update" -> update(player, nonce);
                        case "restore" -> restore(player.getServer(), nonce);
                        default -> throw new IllegalArgumentException("Unknown wide power test action");
                    }
                    return 1;
                } catch (Exception failure) {
                    SpellCompiler.LOGGER.error("FORMULA_REMOTE_WIDE_POWER_SERVER_FAIL", failure);
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

    private static void install(ServerPlayer player, String nonce) throws Exception {
        check(saved == null && phase == 0, "A wide power remote test is already active");
        saved = SpellFormulaState.serverSnapshot();
        activeNonce = nonce;
        originalPower = spell().getSpellPower(1, null);
        check(originalPower == 19, "The parent lance override is incorrect");
        apply(player, nonce, "installed", 3_000_000_001L, 1_000_000_003L);
        phase = 1;
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_WIDE_POWER_SERVER_CASE_PASS case=long_fields_actual_projectile");
    }

    private static void update(ServerPlayer player, String nonce) throws Exception {
        check(saved != null && phase == 1 && nonce.equals(activeNonce), "No matching installed wide power test");
        apply(player, nonce, "updated", 9_007_199_254_740_993L, 0);
        phase = 2;
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_WIDE_POWER_SERVER_CASE_PASS case=above_double_exact_integer_actual_projectile");
    }

    private static void apply(ServerPlayer player, String nonce, String stage, long base, long step) throws Exception {
        var json = new JsonObject();
        var parameters = new JsonObject();
        parameters.addProperty("baseSpellPower", base);
        parameters.addProperty("spellPowerPerLevel", step);
        parameters.addProperty("power_multiplier", 1.0);
        json.add("parameters", parameters);
        var parsed = SpellFormulaFiles.parseResources(Map.of(ID, json), SpellFormulaState.catalog());
        check(parsed.diagnostics().isEmpty() && parsed.byClass().size() == 1,
                "The wide power remote JSON did not parse: " + parsed.diagnostics());
        var merged = new LinkedHashMap<>(saved.byClass());
        merged.putAll(parsed.byClass());
        var applied = SpellFormulaRuntime.applyServer(SpellFormulaFiles.snapshot(merged, List.of()));
        check(applied.diagnostics().isEmpty(), "The wide power remote JSON did not apply: " + applied.diagnostics());
        synchronize(player.getServer());
        long total = Math.addExact(base, Math.multiplyExact(step, LEVEL - 1L));
        float raw = spell().getSpellPower(LEVEL, null);
        check(Float.isFinite(raw) && raw == (float) total, "The server raw power lost its long parameters");
        float damage = projectileDamage(player, total);
        publish(stage, nonce, Map.of("base", Long.toString(base), "step", Long.toString(step),
                "level", Integer.toString(LEVEL), "raw", Float.toString(raw), "damage", Float.toString(damage),
                "fingerprint", applied.fingerprint()));
    }

    private static float projectileDamage(ServerPlayer player, long total) {
        var spawned = new ArrayList<AbstractMagicProjectile>();
        Consumer<EntityJoinLevelEvent> joined = event -> {
            if (event.getLevel() == player.serverLevel() && event.getEntity() instanceof AbstractMagicProjectile projectile
                    && projectile.getOwner() == player) spawned.add(projectile);
        };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, joined);
        try {
            float expected = (float) ((double) total * (float) player.getAttributeValue(AttributeRegistry.SPELL_POWER.get())
                    * spell().getSchoolType().getPowerFor(player));
            check(Float.isFinite(expected) && spell().getSpellPower(LEVEL, player) == expected,
                    "The server entity power differs from the independent long and attribute calculation");
            spell().onCast(player.serverLevel(), LEVEL, player, CastSource.COMMAND, MagicData.getPlayerMagicData(player));
            check(spawned.size() == 1 && spawned.get(0).getClass().getSimpleName().equals("LightningLanceProjectile"),
                    "The native lance cast did not create exactly one owned projectile");
            float damage = spawned.get(0).getDamage();
            check(Float.isFinite(expected) && Float.isFinite(damage) && damage == expected,
                    "The actual native projectile did not capture the server wide power");
            return damage;
        } finally {
            MinecraftForge.EVENT_BUS.unregister(joined);
            spawned.forEach(AbstractMagicProjectile::discard);
        }
    }

    private static void restore(MinecraftServer server, String nonce) throws Exception {
        check(saved != null && phase == 2 && nonce.equals(activeNonce), "No matching updated wide power test");
        var restored = SpellFormulaRuntime.applyServer(saved);
        synchronize(server);
        check(restored.diagnostics().isEmpty() && restored.fingerprint().equals(saved.fingerprint()),
                "The wide power remote test did not restore its parent snapshot");
        float raw = spell().getSpellPower(1, null);
        check(raw == originalPower, "Restoring the parent snapshot retained the long parameters");
        publish("restored", nonce, Map.of("raw", Float.toString(raw), "fingerprint", restored.fingerprint()));
        saved = null;
        activeNonce = null;
        phase = 0;
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_WIDE_POWER_SERVER_CASE_PASS case=restore_parent_snapshot_and_native_power");
        SpellCompiler.LOGGER.info("FORMULA_REMOTE_WIDE_POWER_SERVER_PASS cases=3");
    }

    private static AbstractSpell spell() {
        return SpellRegistry.getSpell(ID);
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
        Path target = control.resolve("wide-power-" + name + ".properties");
        Path temporary = control.resolve("wide-power-" + name + ".properties.tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
