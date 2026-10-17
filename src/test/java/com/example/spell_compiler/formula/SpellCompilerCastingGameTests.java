package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.redspace.ironsspellbooks.api.events.SpellOnCastEvent;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.magic.MagicHelper;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.util.Utils;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.capabilities.magic.RecastResult;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import io.redspace.ironsspellbooks.entity.spells.AbstractMagicProjectile;
import io.redspace.ironsspellbooks.entity.spells.HealingAoe;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder("spell_compiler_formula")
@PrefixGameTestTemplate(false)
public final class SpellCompilerCastingGameTests {
    private SpellCompilerCastingGameTests() {}

    @GameTest(batch = "compiler_casting_long", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void ordinaryLanceCompletesOneNativeChantWithModifiedPowerAndCost(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper, "lightning_lance")) {
            fixture.configure(Map.of("spell_power", '(' + fixture.original("spell_power") + ") + 5"),
                    Map.of("baseSpellPower", 22, "castTime", 12, "baseManaCost", 53));
            helper.assertTrue(fixture.spell.getCastType() == CastType.LONG, "Lance native cast type changed");
            fixture.initiate();
            helper.assertTrue(fixture.magic.isCasting() && fixture.magic.getCastDuration() == 12
                            && fixture.projectiles().isEmpty(), "Ordinary cast did not start its native chant");
            fixture.nativeTicks(6);
            helper.assertTrue(fixture.magic.isCasting() && fixture.projectiles().isEmpty() && fixture.magic.getMana() == 1000,
                    "Ordinary cast released or charged mana before its native chant completed");
            fixture.completeNativeChant();
            close(helper, fixture.singleProjectile("LightningLanceProjectile").getDamage(), 27,
                    "Ordinary native projectile lost the modified spell power");
            fixture.assertCostAndCooldown(53);
            helper.assertTrue(fixture.casts == 1, "Ordinary native chant completed more than once");
        }
        helper.succeed();
    }

    @GameTest(batch = "compiler_casting_projection", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 200)
    public static void editableCastTimePreservesTypeRoundingFallbackAndSpecialNativeMethods(GameTestHelper helper) {
        for (String id : List.of("fireball", "electrocute")) {
            try (var fixture = new Fixture(helper, id)) {
                fixture.player.getAttribute(AttributeRegistry.CAST_TIME_REDUCTION.get()).setBaseValue(1.2);
                fixture.configure(Map.of(), Map.of("castTime", 12));
                int nativeExpected = fixture.spell.getEffectiveCastTime(1, fixture.player);
                helper.assertTrue(nativeExpected == (id.equals("electrocute") ? 14 : 10),
                        "Native cast fixture differs before formula editing: " + id + " actual=" + nativeExpected);
                fixture.configure(Map.of("cast_time", "37.5"), Map.of("castTime", 12));
                helper.assertTrue(fixture.spell.getCastTime(1) == 12 && fixture.spell.getEffectiveCastTime(1, fixture.player) == 38,
                        "Unified cast time lost its final native rounding: " + id);
                fixture.configure(Map.of("cast_time", "1 / 0.0"), Map.of("castTime", 12));
                int actualFallback = fixture.spell.getEffectiveCastTime(1, fixture.player);
                helper.assertTrue(actualFallback == nativeExpected,
                        "Invalid unified cast time did not restore the native attribute rule: " + id
                                + " actual=" + actualFallback + " expected=" + nativeExpected);
                fixture.configure(Map.of("cast_time", "castTime + 5"), Map.of("castTime", 12));
                helper.assertTrue(fixture.spell.getEffectiveCastTime(1, fixture.player) == 17,
                        "Reload did not recover the disabled unified cast time: " + id);
            }
        }
        try (var fixture = new Fixture(helper, "magic_missile")) {
            var definition = SpellFormulaState.catalog().spells().stream()
                    .filter(value -> value.id().equals(fixture.spell.getSpellId())).findFirst().orElseThrow();
            var json = SpellFormulaFiles.preview(definition);
            helper.assertTrue(!json.getAsJsonObject("formulas").has("cast_time")
                            && !json.getAsJsonObject("parameters").has("castTime"), "Instant spell exported ineffective cast controls");
            json.getAsJsonObject("formulas").addProperty("cast_time", "77");
            var rejected = SpellFormulaFiles.parseResources(Map.of(definition.id(), json), SpellFormulaState.catalog());
            helper.assertTrue(rejected.byClass().isEmpty() && !rejected.diagnostics().isEmpty(),
                    "An instant spell accepted an inaccessible cast formula");
            helper.assertTrue(fixture.spell.getCastTime(1) == 0 && fixture.spell.getEffectiveCastTime(1, fixture.player) == 0,
                    "Rejected instant cast control changed the native timing");
        }
        for (String id : List.of("throw", "flaming_strike", "raise_hell", "divine_smite", "stomp")) {
            try (var fixture = new Fixture(helper, id)) {
                fixture.configure(Map.of("cast_time", "castTime * 0.5 + 3"), Map.of("castTime", 25));
                helper.assertTrue(fixture.spell.getCastTime(1) == 25
                                && fixture.spell.getEffectiveCastTime(1, fixture.player) == 15,
                        "Special native cast method lost its field binding or integer conversion: " + id);
            }
        }
        helper.succeed();
    }

    @GameTest(batch = "formula_casting_instant", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void instantMissileUsesModifiedDamageManaAndCooldown(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper, "magic_missile")) {
            float nativeDamage = number(fixture.spell, "getDamage", fixture.player);
            fixture.configure(Map.of("damage", fixture.original("damage") + " + 3"), Map.of("baseManaCost", 17));
            helper.assertTrue(fixture.spell.getCastType() == CastType.INSTANT, "Magic missile is not the native instant fixture");
            fixture.initiate();
            SpellCompiler.LOGGER.info("FORMULA_CASTING_INSTANT_STATE phase=initiated isCasting={} casts={} projectiles={} duration_ticks={} remaining={}",
                    fixture.magic.isCasting(), fixture.casts, fixture.projectiles().size(), fixture.magic.getCastDuration(),
                    fixture.magic.getCastDurationRemaining());
            helper.assertTrue(fixture.magic.isCasting() && fixture.magic.getCastDuration() == 0 && fixture.casts == 0
                            && fixture.projectiles().isEmpty() && fixture.magic.getMana() == 1000,
                    "Native instant initiation did not await its zero-duration_ticks manager tick");
            fixture.nativeTicks(1);
            SpellCompiler.LOGGER.info("FORMULA_CASTING_INSTANT_STATE phase=completed isCasting={} casts={} projectiles={} duration_ticks={} remaining={}",
                    fixture.magic.isCasting(), fixture.casts, fixture.projectiles().size(), fixture.magic.getCastDuration(),
                    fixture.magic.getCastDurationRemaining());
            helper.assertTrue(!fixture.magic.isCasting() && fixture.casts == 1,
                    "Instant spell did not complete natively once: isCasting=" + fixture.magic.isCasting()
                            + ", casts=" + fixture.casts + ", projectiles=" + fixture.projectiles().size()
                            + ", duration_ticks=" + fixture.magic.getCastDuration());
            var missile = fixture.singleProjectile("MagicMissileProjectile");
            close(helper, missile.getDamage(), nativeDamage + 3, "Instant projectile did not capture the changed damage formula");
            fixture.assertCostAndCooldown(17);
            pass("instant_full_attempt", fixture.spell);
        }
        helper.succeed();
    }

    @GameTest(batch = "formula_casting_channel", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void continuousElectrocuteUsesModifiedDamageDuringNativeChannel(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper, "electrocute")) {
            float original = number(fixture.spell, "getDamage", fixture.player);
            fixture.configure(Map.of("damage", fixture.original("damage") + " + 2"), Map.of("castTime", 40));
            var target = fixture.target(6);
            var amounts = new ArrayList<Float>();
            Consumer<LivingHurtEvent> damage = event -> {
                if (event.getEntity() == target && event.getSource() instanceof SpellDamageSource source
                        && source.spell() == fixture.spell) amounts.add(event.getAmount());
            };
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingHurtEvent.class, damage);
            try {
                helper.assertTrue(fixture.spell.getCastType() == CastType.CONTINUOUS, "Electrocute is not the native continuous fixture");
                float before = target.getHealth();
                fixture.initiate();
                helper.assertTrue(fixture.magic.isCasting(), "Continuous cast did not remain active");
                for (int tick = 0; tick < 40 && amounts.isEmpty(); tick++) {
                    fixture.nativeTicks(1);
                    for (var entity : List.copyOf(fixture.spawned)) {
                        if (entity instanceof Projectile && !entity.isRemoved()) {
                            entity.tickCount++;
                            entity.tick();
                        }
                    }
                }
                helper.assertTrue(!amounts.isEmpty() && target.getHealth() < before, "Native continuous electrocute never damaged its real target");
                close(helper, amounts.get(0), original + 2, "Continuous native hit ignored the changed damage formula");
                helper.assertTrue(fixture.magic.getMana() < 1000, "Continuous native cast did not charge mana");
                Utils.serverSideCancelCast(fixture.player);
                helper.assertTrue(!fixture.magic.isCasting(), "Native continuous cancellation left the cast active");
                pass("continuous_full_attempt_native_channel_and_hit", fixture.spell);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(damage);
            }
        }
        helper.succeed();
    }

    @GameTest(batch = "formula_casting_buff", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void hasteCompleteNativeChantAppliesModifiedEffect(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper, "haste")) {
            fixture.configure(Map.of("effect_level", "3", "duration_ticks", "clamp((" + fixture.original("duration_ticks") + "), 80, 80)"),
                    Map.of("castTime", 6));
            fixture.initiate();
            helper.assertTrue(fixture.magic.isCasting() && fixture.player.getActiveEffects().isEmpty(),
                    "Haste effect appeared before its native chant completed");
            fixture.completeNativeChant();
            helper.assertTrue(fixture.player.getActiveEffects().stream().anyMatch(effect -> effect.getAmplifier() == 3
                            && effect.getDuration() == 80), "Native haste did not apply the modified amplifier and duration_ticks");
            fixture.assertCostAndCooldown(fixture.spell.getManaCost(1));
            pass("buff_complete_native_chant_and_real_effect", fixture.spell);
        }
        helper.succeed();
    }

    @GameTest(batch = "formula_casting_healing_field", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void completedHealingCastBuildsModifiedPersistentHealingEntity(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper, "healing_circle")) {
            float original = number(fixture.spell, "getHealing", fixture.player);
            fixture.configure(Map.of("healing", fixture.original("healing") + " + 2", "radius", "7", "duration_ticks", "120",
                    "healing_interval_ticks", "3.9"), Map.of());
            fixture.player.setHealth(5);
            fixture.magic.setAdditionalCastData(new TargetEntityCastData(fixture.player));
            fixture.spell.onCast(helper.getLevel(), 1, fixture.player, CastSource.COMMAND, fixture.magic);
            var fields = fixture.spawned.stream().filter(HealingAoe.class::isInstance).map(HealingAoe.class::cast).toList();
            helper.assertTrue(fields.size() == 1, "Completed native healing cast did not construct one persistent field");
            var field = fields.get(0);
            close(helper, field.getRadius(), 7, "Persistent healing field lost the changed radius");
            helper.assertTrue(field.getDuration() == 120, "Persistent healing field lost the changed lifetime");
            helper.assertTrue(field.getReapplicationDelay() == 3, "Healing interval did not use native integer conversion");
            close(helper, field.getDamage(), original + 2, "Persistent healing entity lost the changed healing value");
            float before = fixture.player.getHealth();
            field.applyEffect(fixture.player);
            close(helper, fixture.player.getHealth() - before, original + 2, "Native persistent field did not heal by the changed formula");
            var saved = new CompoundTag();
            field.saveWithoutId(saved);
            close(helper, saved.getFloat("Radius"), 7, "Persistent field NBT lost its configured radius");
            helper.assertTrue(saved.getInt("Duration") == 120, "Persistent field NBT lost its configured duration_ticks");
            helper.assertTrue(saved.getInt("ReapplicationDelay") == 3, "Persistent field NBT lost its configured healing interval");
            field.tickCount = field.getDelay() + 120;
            field.tick();
            helper.assertTrue(!field.isRemoved(), "Configured persistent field expired before its boundary");
            field.tickCount = field.getDelay() + 121;
            field.tick();
            helper.assertTrue(field.isRemoved(), "Configured persistent field survived beyond its boundary");
            pass("completed_oncast_healing_persistent_entity_effect_and_nbt", fixture.spell);
        }
        helper.succeed();
    }

    @GameTest(batch = "formula_casting_summon", templateNamespace = "spell_compiler_formula", template = "empty", timeoutTicks = 100)
    public static void completedSummoningCastBuildsModifiedSummonAttributes(GameTestHelper helper) throws Exception {
        try (var fixture = new Fixture(helper, "summon_polar_bear")) {
            float health = number(fixture.spell, "getBearHealth", fixture.player);
            float damage = number(fixture.spell, "getBearDamage", fixture.player);
            fixture.configure(Map.of("bear_health", fixture.original("bear_health") + " + 11",
                    "bear_damage", fixture.original("bear_damage") + " + 3", "summon_duration_ticks", "60.9"), Map.of());
            fixture.spell.onCast(helper.getLevel(), 1, fixture.player, CastSource.COMMAND, fixture.magic);
            var summons = fixture.spawned.stream().filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast)
                    .filter(entity -> entity.getClass().getSimpleName().equals("SummonedPolarBear")).toList();
            helper.assertTrue(summons.size() == 1, "Completed native summoning did not construct one actual polar bear");
            var summon = summons.get(0);
            close(helper, summon.getMaxHealth(), health + 11, "Summon maximum health ignored the changed formula");
            close(helper, summon.getHealth(), health + 11, "Summon current health ignored its changed maximum");
            close(helper, (float) summon.getAttributeValue(Attributes.ATTACK_DAMAGE), damage + 3,
                    "Summon attack attribute ignored the changed damage formula");
            var recast = fixture.magic.getPlayerRecasts().getRecastInstance(fixture.spell.getSpellId());
            helper.assertTrue(recast.getTicksToLive() == 60 && recast.getTicksRemaining() == 60,
                    "Summon recast lifetime did not use the same integer duration_ticks as the summon");
            pass("completed_oncast_real_summon_health_and_attack_attributes", fixture.spell);
        }
        helper.succeed();
    }

    private static float number(AbstractSpell spell, String name, ServerPlayer player) throws ReflectiveOperationException {
        var formula = SpellFormulaState.catalog().spells().stream().filter(value -> value.id().equals(spell.getSpellId()))
                .findFirst().orElseThrow().formulas().stream().filter(value -> value.name().equals(name))
                .findFirst().orElseThrow();
        var signature = MethodType.fromMethodDescriptorString(formula.descriptor(), spell.getClass().getClassLoader());
        var lookup = MethodHandles.privateLookupIn(spell.getClass(), MethodHandles.lookup());
        var handle = lookup.findVirtual(spell.getClass(), name, signature);
        try {
            return ((Number) handle.invokeWithArguments(spell, 1, player)).floatValue();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Native formula probe invocation failed", failure);
        }
    }

    private static ItemStack container(ItemStack stack, AbstractSpell spell, int slots) {
        var container = new CompoundTag();
        container.putInt("maxSpells", slots);
        container.putBoolean("mustEquip", true);
        container.putBoolean("spellWheel", true);
        var data = new ListTag();
        var entry = new CompoundTag();
        entry.putInt("index", 0);
        entry.putString("id", spell.getSpellId());
        entry.putInt("level", 1);
        data.add(entry);
        container.put("data", data);
        stack.getOrCreateTag().put(ISpellContainer.NBT, container);
        return stack;
    }

    private static void close(GameTestHelper helper, float actual, float expected, String message) {
        helper.assertTrue(Float.isFinite(actual) && Math.abs(actual - expected) < 0.002f,
                message + ": actual=" + actual + ", expected=" + expected);
    }

    private static void pass(String name, AbstractSpell spell) {
        SpellCompiler.LOGGER.info("FORMULA_CASTING_PROBE_CASE_PASS case={} spell={}", name, spell.getSpellId());
    }

    static final class Fixture implements AutoCloseable {
        private final GameTestHelper helper;
        final AbstractSpell spell;
        final ServerPlayer player;
        final MagicData magic;
        private final ItemStack book;
        private final SpellFormulaFiles.Snapshot saved = SpellFormulaState.serverSnapshot();
        final List<Entity> spawned = new ArrayList<>();
        private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        private final Consumer<EntityJoinLevelEvent> joined;
        private final Consumer<SpellOnCastEvent> cast;
        private int casts;

        Fixture(GameTestHelper helper, String id) {
            this.helper = helper;
            SpellFormulaRuntime.applyServer(SpellFormulaFiles.snapshot(Map.of(), List.of()));
            synchronize();
            spell = SpellRegistry.getSpell("irons_spellbooks:" + id);
            player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "[FormulaCast]"));
            player.connection = new ServerGamePacketListenerImpl(player.server, new Connection(PacketFlow.SERVERBOUND) {
                @Override public void send(Packet<?> packet) {}
            }, player);
            player.setGameMode(GameType.SURVIVAL);
            player.setNoGravity(true);
            player.setPos(helper.absoluteVec(new Vec3(1, 128, 1)));
            player.setYRot(0);
            player.setXRot(0);
            player.getAttribute(AttributeRegistry.MAX_MANA.get()).setBaseValue(1000);
            player.getAttribute(AttributeRegistry.MANA_REGEN.get()).setBaseValue(0);
            player.getAttribute(AttributeRegistry.SPELL_POWER.get()).setBaseValue(1);
            var crit = ForgeRegistries.ATTRIBUTES.getValue(ResourceLocation.parse("attributeslib:crit_chance"));
            if (crit != null && player.getAttribute(crit) != null) player.getAttribute(crit).setBaseValue(0);
            magic = MagicData.getPlayerMagicData(player);
            magic.setMana(1000);
            magic.getSyncedData().learnSpell(spell);
            book = container(new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("irons_spellbooks:netherite_spell_book"))),
                    spell, 12);
            player.setItemInHand(InteractionHand.MAIN_HAND, book);
            joined = event -> {
                if (event.getLevel() == helper.getLevel() && event.getEntity() != player) spawned.add(event.getEntity());
            };
            cast = event -> {
                if (event.getEntity() == player && event.getSpellId().equals(spell.getSpellId())) casts++;
            };
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, joined);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, SpellOnCastEvent.class, cast);
            helper.getLevel().addNewPlayer(player);
            var floor = BlockPos.containing(player.position()).below();
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 10; z++) for (int y = 0; y <= 4; y++) {
                var position = floor.offset(x, y, z);
                blocks.put(position, helper.getLevel().getBlockState(position));
                helper.getLevel().setBlockAndUpdate(position, y == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        }

        String original(String alias) {
            return SpellFormulaState.catalog().spells().stream().filter(value -> value.id().equals(spell.getSpellId()))
                    .findFirst().orElseThrow().formulas().stream().filter(value -> value.alias().equals(alias))
                    .findFirst().orElseThrow().readableExpression();
        }

        void configure(Map<String, String> formulas, Map<String, Integer> parameters) {
            var json = new JsonObject();
            var formulaJson = new JsonObject();
            formulas.forEach(formulaJson::addProperty);
            json.add("formulas", formulaJson);
            var parameterJson = new JsonObject();
            parameters.forEach(parameterJson::addProperty);
            json.add("parameters", parameterJson);
            var parsed = SpellFormulaFiles.parseResources(Map.of(spell.getSpellId(), json), SpellFormulaState.catalog());
            helper.assertTrue(parsed.diagnostics().isEmpty(), "Casting fixture did not parse: " + parsed.diagnostics());
            var merged = new LinkedHashMap<>(saved.byClass());
            merged.putAll(parsed.byClass());
            var applied = SpellFormulaRuntime.applyServer(SpellFormulaFiles.snapshot(merged, List.of()));
            synchronize();
            helper.assertTrue(applied.diagnostics().isEmpty(), "Casting fixture did not compile: " + applied.diagnostics());
        }

        private void synchronize() {
            MinecraftForge.EVENT_BUS.post(new OnDatapackSyncEvent(helper.getLevel().getServer().getPlayerList(), null));
        }

        private void initiate() {
            magic.getPlayerCooldowns().removeCooldown(spell.getSpellId());
            helper.assertTrue(spell.attemptInitiateCast(book, 1, player.level(), player, CastSource.SPELLBOOK, true, "mainhand"),
                    "Native spell initiation rejected " + spell.getSpellId());
        }

        private void nativeTicks(int count) {
            for (int tick = 0; tick < count; tick++) {
                player.tickCount++;
                MinecraftForge.EVENT_BUS.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, player));
                ((MagicManager) MagicHelper.MAGIC_MANAGER).tick(player.level());
            }
        }

        private void completeNativeChant() {
            helper.assertTrue(magic.isCasting(), "There is no active native chant to complete");
            nativeTicks(magic.getCastDurationRemaining() + 1);
            helper.assertTrue(!magic.isCasting(), "Native chant did not complete");
        }

        private List<AbstractMagicProjectile> projectiles() {
            return spawned.stream().filter(AbstractMagicProjectile.class::isInstance).map(AbstractMagicProjectile.class::cast)
                    .filter(entity -> entity.getOwner() == player).toList();
        }

        private AbstractMagicProjectile singleProjectile(String name) {
            var projectiles = projectiles().stream().filter(entity -> entity.getClass().getSimpleName().equals(name)).toList();
            helper.assertTrue(projectiles.size() == 1, "Expected one actual " + name + " but got " + projectiles.size());
            return projectiles.get(0);
        }

        private LivingEntity target(double distance) {
            var target = EntityType.IRON_GOLEM.create(helper.getLevel());
            helper.assertTrue(target != null, "Could not create continuous-cast target");
            target.setNoAi(true);
            target.setNoGravity(true);
            target.setPos(player.getEyePosition().add(player.getLookAngle().scale(distance)).add(0, -target.getBbHeight() / 2, 0));
            helper.assertTrue(helper.getLevel().addFreshEntity(target), "Could not spawn continuous-cast target");
            return target;
        }

        private void assertCostAndCooldown(int cost) {
            SpellCompilerCastingGameTests.close(helper, 1000 - magic.getMana(), cost, "Native complete cast used the wrong mana cost");
            helper.assertTrue(magic.getPlayerCooldowns().isOnCooldown(spell), "Native complete cast did not create its cooldown");
        }

        @Override
        public void close() {
            try {
                Utils.serverSideCancelCast(player);
                if (magic.getPlayerRecasts().hasRecastForSpell(spell))
                    magic.getPlayerRecasts().removeRecast(magic.getPlayerRecasts().getRecastInstance(spell.getSpellId()),
                            RecastResult.COUNTERSPELL);
                magic.setAdditionalCastData(null);
                MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));
            } finally {
                MinecraftForge.EVENT_BUS.unregister(joined);
                MinecraftForge.EVENT_BUS.unregister(cast);
                spawned.forEach(Entity::discard);
                player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                blocks.forEach((position, state) -> helper.getLevel().setBlockAndUpdate(position, state));
                SpellFormulaRuntime.applyServer(saved);
                synchronize();
            }
        }
    }
}
