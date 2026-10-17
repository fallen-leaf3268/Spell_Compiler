package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.spells.fire.FireballSpell;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Mod.EventBusSubscriber(modid = SpellCompiler.MODID)
@PrefixGameTestTemplate(false)
public final class SpellFormulaRemoteGameTests {
    private static final String OWNER = "io.redspace.ironsspellbooks.spells.fire.FireballSpell";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static GameTestHelper helper;
    private static MinecraftServer server;
    private static Path control;
    private static Path fixture;
    private static String name;
    private static int port;
    private static long started;
    private static int logins;
    private static String stage;
    private static String pending;
    private static String pendingFingerprint;
    private static long pendingGeneration;
    private static long pendingStarted;
    private static boolean done;
    private static boolean finished;
    private static Field generationField;
    private static long publishedGeneration = -1;

    private SpellFormulaRemoteGameTests() {}

    private static boolean enabled() {
        return Boolean.getBoolean("spell_compiler.formulaRemoteProbe");
    }

    @Mod.EventBusSubscriber(modid = SpellCompiler.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void register(RegisterGameTestsEvent event) {
            if (enabled()) event.register(SpellFormulaRemoteGameTests.class);
        }
    }

    @GameTest(templateNamespace = "spell_compiler_formula_remote", template = "empty", timeoutTicks = 16000)
    public static void remoteTcpSession(GameTestHelper test) {
        if (!enabled()) {
            test.succeed();
            return;
        }
        helper = test;
        server = test.getLevel().getServer();
        started = System.nanoTime();
        try {
            name = System.getProperty("spell_compiler.formulaRemoteName", "A");
            check(name.equals("A") || name.equals("B"), "Invalid remote server name");
            port = Integer.parseInt(System.getProperty("spell_compiler.formulaRemotePort"));
            check(port > 0 && port <= 65535, "Invalid remote TCP port");
            control = Path.of(System.getProperty("spell_compiler.formulaRemoteControl")).toAbsolutePath().normalize();
            Files.createDirectories(control);
            fixture = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("spell_compiler_fixture");
            check(Files.isRegularFile(fixture.resolve("pack.mcmeta")), "Missing standard world fixture metadata");
            generationField = SpellFormulaNetwork.class.getDeclaredField("GENERATION");
            generationField.setAccessible(true);
            stage = "initial";
            MinecraftForge.EVENT_BUS.post(new OnDatapackSyncEvent(server.getPlayerList(), null));
            validateStage(stage);
            server.setUsesAuthentication(false);
            server.getPlayerList().setViewDistance(2);
            server.getPlayerList().setSimulationDistance(2);
            server.getConnection().startTcpServerListener(InetAddress.getByName("127.0.0.1"), port);
            publish();
            SpellCompiler.LOGGER.info("FORMULA_REMOTE_SERVER_READY name={} port={} pid={}", name, port,
                    ProcessHandle.current().pid());
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        if (!enabled()) return;
        var command = Commands.literal("formula_remote_probe").requires(source -> source.hasPermission(4));
        for (String action : new String[]{"large", "bad", "recover", "invalid", "remove", "done"})
            command.then(Commands.literal(action).executes(context -> execute(context.getSource(), action)));
        event.getDispatcher().register(command);
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || helper == null || finished
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
                || player.getServer() != server) return;
        try {
            var players = server.getPlayerList();
            players.getOps().add(new ServerOpListEntry(player.getGameProfile(), 4, true));
            players.sendPlayerPermissionLevel(player);
            player.setNoGravity(true);
            logins++;
            publish();
            SpellCompiler.LOGGER.info("FORMULA_REMOTE_SERVER_LOGIN name={} count={} generation={} player={}",
                    name, logins, generation(), player.getGameProfile().getName());
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static int execute(CommandSourceStack source, String action) {
        if (helper == null || finished || source.getServer() != server) return 0;
        try {
            check(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer,
                    "Remote probe action did not come from an actual player command");
            check(pending == null && !done, "Another remote probe action is pending");
            if (action.equals("done")) {
                done = true;
                return 1;
            }
            check(name.equals("A"), "Only server A accepts fixture mutations");
            pendingGeneration = generation();
            pendingStarted = System.nanoTime();
            if (action.equals("large")) writeLarge();
            else if (action.equals("remove")) writeEmpty();
            else if (action.equals("invalid")) writeInvalid();
            else writeDamage(action);
            var requested = readFixture();
            validateDiagnostics(requested, action);
            SpellFormulaNetwork.validateSnapshot(requested);
            pendingFingerprint = requested.fingerprint();
            check(!pendingFingerprint.equals(SpellFormulaState.serverSnapshot().fingerprint()),
                    "Remote action would not change its published fingerprint");
            pending = action;
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4), "reload");
            SpellCompiler.LOGGER.info("FORMULA_REMOTE_SERVER_RELOAD_REQUESTED name={} stage={} oldGeneration={}",
                    name, pending, pendingGeneration);
            return 1;
        } catch (Throwable failure) {
            fail(failure);
            return 0;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (!enabled() || event.phase != TickEvent.Phase.END || helper == null || finished) return;
        LockSupport.parkNanos(50_000_000L);
        try {
            check(System.nanoTime() - started < 600_000_000_000L, "Remote TCP session exceeded 600 seconds");
            check(!Files.exists(control.resolve("client-failed.txt")), "Remote client reported failure");
            if (pending != null) {
                check(System.nanoTime() - pendingStarted < 120_000_000_000L, "Remote datapack reload timed out");
                var snapshot = SpellFormulaState.serverSnapshot();
                if (!snapshot.fingerprint().equals(pendingFingerprint) || generation() <= pendingGeneration) return;
                validateStage(pending);
                stage = pending;
                pending = null;
                publish();
                SpellCompiler.LOGGER.info("FORMULA_REMOTE_SERVER_STAGE_PASS name={} stage={} generation={}",
                        name, stage, generation());
            }
            if (done) {
                check(logins >= (name.equals("A") ? 2 : 1), "Remote session did not exercise its required login count");
                validateStage(stage);
                stage = "done";
                publish();
                finished = true;
                SpellCompiler.LOGGER.info("FORMULA_REMOTE_SERVER_PASS name={} logins={} generation={}",
                        name, logins, generation());
                helper.succeed();
            } else if (generation() != publishedGeneration) {
                publish();
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void writeLarge() throws IOException {
        Map<String, JsonElement> resources = new LinkedHashMap<>();
        for (var spell : SpellFormulaState.catalog().spells()) {
            if (spell.editableFormulas().isEmpty()) continue;
            var json = new JsonObject();
            var formulas = new JsonObject();
            for (var method : spell.editableFormulas()) {
                String addition = spell.className().equals(OWNER) && method.alias().equals("damage") ? "1"
                        : spell.className().equals(OWNER) && method.alias().equals("mana_cost") ? "4"
                        : spell.id().equals("irons_spellbooks:lightning_lance") && method.alias().equals("spell_power") ? "5" : "0";
                formulas.addProperty(method.alias(), padded('(' + method.readableExpression() + ")+" + addition));
            }
            json.add("formulas", formulas);
            if (spell.className().equals(OWNER)) {
                var parameters = new JsonObject();
                parameters.addProperty("baseManaCost", 77);
                parameters.addProperty("cooldown_in_seconds", 26);
                json.add("parameters", parameters);
            }
            resources.put(resourceKey(spell), json);
        }
        var snapshot = SpellFormulaFiles.parseResources(resources, SpellFormulaState.catalog());
        check(snapshot.diagnostics().isEmpty(), "Large remote fixture has invalid formulas");
        check(snapshot.byClass().size() == catalogSpellCount() && formulaCount(snapshot) == catalogFormulaCount(),
                "Large remote fixture coverage differs");
        SpellFormulaNetwork.validateSnapshot(snapshot);
        check(parts(snapshot) == 6, "Large remote fixture does not produce exactly six parts");
        for (var spell : SpellFormulaState.catalog().spells()) {
            var json = resources.get(resourceKey(spell));
            if (json != null) write(spell, json);
        }
    }

    private static void writeDamage(String action) throws IOException {
        check(action.equals("bad") || action.equals("recover"), "Unknown remote action");
        var definition = SpellFormulaState.catalog().spells().stream()
                .filter(spell -> spell.className().equals(OWNER)).findFirst().orElseThrow();
        Path file = file(definition);
        var json = SpellFormulaFiles.parseJson(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        String original = definition.formulas().stream().filter(method -> method.alias().equals("damage"))
                .findFirst().orElseThrow().readableExpression();
        String expression = '(' + original + ')' + (action.equals("bad") ? "+1/(level-level)" : "+2");
        json.getAsJsonObject("formulas").addProperty("damage", padded(expression));
        write(definition, json);
    }

    private static void writeEmpty() throws IOException {
        for (var spell : SpellFormulaState.catalog().spells())
            if (!spell.editableFormulas().isEmpty()) write(spell, new JsonObject());
    }

    private static void writeInvalid() throws IOException {
        var definition = SpellFormulaState.catalog().spells().stream()
                .filter(spell -> spell.className().equals(OWNER)).findFirst().orElseThrow();
        Files.writeString(file(definition), "{invalid", StandardCharsets.UTF_8);
    }

    private static SpellFormulaFiles.Snapshot readFixture() {
        try (var resources = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(new PathPackResources("remote_fixture", fixture, false)))) {
            return SpellFormulaReloadListener.load(resources);
        }
    }

    private static void validateStage(String expectedStage) {
        var snapshot = SpellFormulaState.serverSnapshot();
        validateDiagnostics(snapshot, expectedStage);
        check(SpellFormulaState.clientSnapshot().byClass().isEmpty(), "Separate server JVM contains a client snapshot");
        int expectedSpells = expectedStage.equals("initial") ? 1 : expectedStage.equals("remove") ? 0
                : expectedStage.equals("invalid") ? catalogSpellCount() - 1 : catalogSpellCount();
        int expectedFormulas = expectedStage.equals("initial") ? 2 : expectedStage.equals("remove") ? 0
                : expectedStage.equals("invalid") ? catalogFormulaCount() - fireballFormulaCount() : catalogFormulaCount();
        check(snapshot.byClass().size() == expectedSpells && formulaCount(snapshot) == expectedFormulas,
                "Remote published spell/formula coverage differs for " + expectedStage);
        check(parts(snapshot) == (expectedSpells > 1 ? 6 : 1), "Actual remote snapshot part count differs");
        float damage = expectedStage.equals("initial") ? name.equals("A") ? 13.75f : 18.75f
                : expectedStage.equals("large") ? 11f : expectedStage.equals("recover") ? 12f : 10f;
        int mana = expectedStage.equals("initial") ? name.equals("A") ? 63 : 69
                : expectedStage.equals("remove") || expectedStage.equals("invalid") ? 60 : 81;
        int cooldown = expectedStage.equals("initial") ? 600
                : expectedStage.equals("remove") || expectedStage.equals("invalid") ? 500 : 520;
        var spell = fireball();
        for (int attempt = 0; attempt < 3; attempt++) {
            float actualDamage = spell.getDamage(1, null);
            if (!(Math.abs(actualDamage - damage) < 0.0001f)) {
                var override = snapshot.byClass().get(OWNER);
                check(false, "Actual remote damage differs for " + expectedStage + " actual=" + actualDamage + " expected=" + damage
                        + " power=" + spell.getSpellPower(1, null)
                        + " config_POWER_MULTIPLIER=" + SpellConfigManager.getSpellConfigValue(spell, SpellConfigParameter.POWER_MULTIPLIER)
                        + " snapshot_parameters=" + (override == null ? "none" : override.parameters()));
            }
        }
        check(spell.getManaCost(1) == mana, "Actual remote mana differs for " + expectedStage);
        check(spell.getSpellCooldown() == cooldown, "Actual remote cooldown differs for " + expectedStage);
        float lance = SpellRegistry.getSpell("irons_spellbooks:lightning_lance").getSpellPower(1, null);
        float expectedLance = expectedStage.equals("initial") || expectedStage.equals("remove") ? 14f : 19f;
        check(Float.floatToRawIntBits(lance) == Float.floatToRawIntBits(expectedLance),
                "Actual inherited lance power differs for " + expectedStage);
    }

    private static void validateDiagnostics(SpellFormulaFiles.Snapshot snapshot, String expectedStage) {
        if (expectedStage.equals("invalid")) {
            check(snapshot.diagnostics().size() == 1 && snapshot.diagnostics().get(0).cause() != null,
                    "Invalid JSON did not retain exactly its original reading diagnostic");
            check(snapshot.diagnostics().get(0).message().contains("法术文件未应用"),
                    "Invalid JSON diagnostic did not come from resource reading");
        } else {
            check(snapshot.diagnostics().isEmpty(), "Actual remote fixture retained unexpected diagnostics");
        }
    }

    private static void publish() throws IOException, IllegalAccessException {
        var snapshot = SpellFormulaState.serverSnapshot();
        var data = new LinkedHashMap<String, String>();
        data.put("stage", stage);
        data.put("fingerprint", snapshot.fingerprint());
        data.put("spells", Integer.toString(snapshot.byClass().size()));
        data.put("formulas", Integer.toString(formulaCount(snapshot)));
        data.put("total_spells", Integer.toString(catalogSpellCount()));
        data.put("total_formulas", Integer.toString(catalogFormulaCount()));
        data.put("total_native_formulas", Integer.toString(catalogNativeFormulaCount()));
        data.put("total_site_helpers", Integer.toString(catalogFormulaCount() - catalogNativeFormulaCount()));
        data.put("fireball_formulas", Integer.toString(fireballFormulaCount()));
        data.put("padding", Integer.toString(padding()));
        data.put("parts", Integer.toString(parts(snapshot)));
        data.put("damage", Float.toString(fireball().getDamage(1, null)));
        data.put("mana", Integer.toString(fireball().getManaCost(1)));
        data.put("cooldown_ticks", Integer.toString(fireball().getSpellCooldown()));
        data.put("lance_power", Float.toString(SpellRegistry.getSpell("irons_spellbooks:lightning_lance").getSpellPower(1, null)));
        data.put("port", Integer.toString(port));
        data.put("generation", Long.toString(generation()));
        data.put("pid", Long.toString(ProcessHandle.current().pid()));
        data.put("logins", Integer.toString(logins));
        StringBuilder text = new StringBuilder();
        data.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        Path destination = control.resolve("remote-" + name + ".properties");
        Path temporary = control.resolve("remote-" + name + ".properties.tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        publishedGeneration = generation();
    }

    private static long generation() throws IllegalAccessException {
        return ((AtomicLong) generationField.get(null)).get();
    }

    private static int parts(SpellFormulaFiles.Snapshot snapshot) {
        return SpellFormulaNetwork.packets(SpellFormulaFiles.resourcesForSnapshot(snapshot), 1).size();
    }

    private static int formulaCount(SpellFormulaFiles.Snapshot snapshot) {
        return snapshot.byClass().values().stream().mapToInt(override -> override.formulas().size()).sum();
    }

    private static int catalogSpellCount() {
        return (int) SpellFormulaState.catalog().spells().stream().filter(spell -> !spell.editableFormulas().isEmpty()).count();
    }

    private static int catalogFormulaCount() {
        return SpellFormulaState.catalog().spells().stream().mapToInt(spell -> spell.editableFormulas().size()).sum();
    }

    private static int catalogNativeFormulaCount() {
        return (int) SpellFormulaState.catalog().spells().stream().flatMap(spell -> spell.editableFormulas().stream())
                .filter(method -> !method.declaringClass().equals("com.example.spell_compiler.formula.FormulaSiteValues")).count();
    }

    private static int fireballFormulaCount() {
        return SpellFormulaState.catalog().spells().stream().filter(spell -> spell.className().equals(OWNER))
                .findFirst().orElseThrow().editableFormulas().size();
    }

    private static int padding() {
        return Math.min(8192, 1_300_000 / catalogFormulaCount());
    }

    private static FireballSpell fireball() {
        return (FireballSpell) SpellRegistry.getSpell("irons_spellbooks:fireball");
    }

    private static String padded(String expression) {
        int length = padding();
        check(expression.length() <= length, "Remote formula exceeds its character budget");
        return expression + " ".repeat(length - expression.length());
    }

    private static String resourceKey(SpellFormulaCatalog.Spell spell) {
        return spell.id().substring(0, spell.id().indexOf(':') + 1)
                + spell.school().substring(spell.school().indexOf(':') + 1) + '/'
                + spell.id().substring(spell.id().indexOf(':') + 1);
    }

    private static Path file(SpellFormulaCatalog.Spell spell) {
        String namespace = spell.id().substring(0, spell.id().indexOf(':'));
        String school = spell.school().substring(spell.school().indexOf(':') + 1);
        String id = spell.id().substring(spell.id().indexOf(':') + 1);
        return fixture.resolve("data").resolve(namespace).resolve("spells").resolve(school).resolve(id + ".json");
    }

    private static void write(SpellFormulaCatalog.Spell spell, JsonElement json) throws IOException {
        Path path = file(spell);
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
    }

    private static void fail(Throwable failure) {
        if (finished) return;
        finished = true;
        SpellCompiler.LOGGER.error("FORMULA_REMOTE_SERVER_FAIL name=" + name + " stage=" + stage, failure);
        helper.runAfterDelay(1, () -> helper.fail("Remote TCP probe failed: " + failure));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
