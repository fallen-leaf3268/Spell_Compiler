package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class FormulaConsumerNetwork {
    private static final String PROTOCOL = "3";
    private static final int MAX_PACKET_BYTES = 32768;
    private static final int MAX_VALUES = 32;
    private static boolean initialized;

    private FormulaConsumerNetwork() {}

    public static synchronized void initialize(IEventBus modBus) {
        if (initialized) return;
        modBus.addListener(FormulaConsumerNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(FormulaConsumerNetwork::tracking);
        NeoForge.EVENT_BUS.addListener(FormulaConsumerNetwork::login);
        NeoForge.EVENT_BUS.addListener(FormulaConsumerNetwork::changedDimension);
        initialized = true;
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL).playToClient(EffectPacket.TYPE, EffectPacket.STREAM_CODEC, EffectPacket::handle);
    }

    public static void synchronize(LivingEntity entity, MobEffectInstance effect) {
        var id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
        if (id == null || FormulaConsumerSites.aliasesForEffect(id.toString()).isEmpty()) return;
        if (entity.level().isClientSide) {
            Client.applyPending(entity, effect);
        } else if (initialized) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, packet(entity, effect, id));
        }
    }

    public static void removed(LivingEntity entity, MobEffectInstance effect) {
        if (entity.level().isClientSide) Client.removePending(entity, effect);
    }

    private static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getTarget() instanceof LivingEntity target)
            sendAll(player, target);
    }

    private static void login(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) sendAll(event.getPlayer(), event.getPlayer());
    }

    private static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendAll(player, player);
    }

    private static void sendAll(ServerPlayer recipient, LivingEntity entity) {
        for (var effect : entity.getActiveEffects()) {
            var id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
            if (id != null && !FormulaConsumerSites.aliasesForEffect(id.toString()).isEmpty())
                PacketDistributor.sendToPlayer(recipient, packet(entity, effect, id));
        }
    }

    private static EffectPacket packet(LivingEntity entity, MobEffectInstance effect, ResourceLocation id) {
        return new EffectPacket(entity.getId(), entity.getUUID(), entity.level().dimension().location(), id,
                ((FormulaConsumerCarrier) effect).apoth$getFormulaValues(), ((FormulaConsumerCarrier) effect).apoth$getAttributeValues());
    }

    public record EffectPacket(int entityId, UUID entityUuid, ResourceLocation dimension, ResourceLocation effect,
                               Map<String, Float> values, Map<String, FormulaAttributes.Value> attributes) implements CustomPacketPayload {
        public EffectPacket(int entityId, UUID entityUuid, ResourceLocation dimension, ResourceLocation effect, Map<String, Float> values) {
            this(entityId, entityUuid, dimension, effect, values, Map.of());
        }
        public static final Type<EffectPacket> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(SpellCompiler.MODID, "spell_formula_effect"));
        public static final StreamCodec<FriendlyByteBuf, EffectPacket> STREAM_CODEC =
                StreamCodec.ofMember(EffectPacket::encode, EffectPacket::decode);

        public EffectPacket {
            Objects.requireNonNull(entityUuid);
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(effect);
            Objects.requireNonNull(values);
            var allowed = FormulaConsumerSites.aliasesForEffect(effect.toString());
            if (entityId < 0 || allowed.isEmpty() || values.size() > MAX_VALUES
                    || dimension.toString().length() > 128 || effect.toString().length() > 64)
                throw new IllegalArgumentException("Invalid formula effect packet header");
            for (var entry : values.entrySet()) {
                if (!allowed.contains(entry.getKey()) || entry.getValue() == null || !Float.isFinite(entry.getValue()))
                    throw new IllegalArgumentException("Invalid formula effect value: " + entry.getKey());
            }
            values = Map.copyOf(values);
            attributes = FormulaAttributeRuntime.valid(effect.toString(), attributes);
        }

        @Override
        public Type<EffectPacket> type() {
            return TYPE;
        }

        public void encode(FriendlyByteBuf buffer) {
            int start = buffer.writerIndex();
            buffer.writeVarInt(entityId);
            buffer.writeUUID(entityUuid);
            buffer.writeUtf(dimension.toString(), 128);
            buffer.writeUtf(effect.toString(), 64);
            buffer.writeVarInt(values.size());
            values.forEach((alias, value) -> {
                buffer.writeUtf(alias, 64);
                buffer.writeFloat(value);
            });
            buffer.writeNbt(FormulaAttributeRuntime.write(attributes));
            if (buffer.writerIndex() - start > MAX_PACKET_BYTES)
                throw new IllegalArgumentException("Formula effect packet is too large");
        }

        public static EffectPacket decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() > MAX_PACKET_BYTES)
                throw new IllegalArgumentException("Formula effect packet is too large");
            int entity = buffer.readVarInt();
            UUID uuid = buffer.readUUID();
            var dimension = ResourceLocation.parse(buffer.readUtf(128));
            var effect = ResourceLocation.parse(buffer.readUtf(64));
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_VALUES) throw new IllegalArgumentException("Invalid formula effect value count");
            Map<String, Float> values = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                String alias = buffer.readUtf(64);
                if (values.putIfAbsent(alias, buffer.readFloat()) != null)
                    throw new IllegalArgumentException("Duplicate formula effect value: " + alias);
            }
            var tag = buffer.readNbt();
            var attributes = tag == null ? Map.<String, FormulaAttributes.Value>of() : FormulaAttributeRuntime.read(tag);
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing formula effect packet bytes");
            return new EffectPacket(entity, uuid, dimension, effect, values, attributes);
        }

        public void handle(IPayloadContext context) {
            Client.receive(this, context);
        }
    }

    @EventBusSubscriber(modid = SpellCompiler.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static final class Client {
        private record Key(ResourceLocation dimension, int entity, UUID uuid, ResourceLocation effect) {}
        private record Pending(net.minecraft.network.Connection connection, Map<String, Float> values, Map<String, FormulaAttributes.Value> attributes) {}
        private static final Map<Key, Pending> PENDING = new LinkedHashMap<>();
        private static final int MAX_PENDING = 1024;

        private Client() {}

        private static void receive(EffectPacket packet, IPayloadContext context) {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            var listener = minecraft.getConnection();
            if (!context.connection().isConnected() || listener == null
                    || listener.getConnection() != context.connection()) return;
            var key = new Key(packet.dimension(), packet.entityId(), packet.entityUuid(), packet.effect());
            if (PENDING.size() >= MAX_PENDING && !PENDING.containsKey(key))
                PENDING.remove(PENDING.keySet().iterator().next());
            PENDING.put(key, new Pending(context.connection(), packet.values(), packet.attributes()));
            var level = minecraft.level;
            if (level != null && level.dimension().location().equals(packet.dimension())
                    && level.getEntity(packet.entityId()) instanceof LivingEntity entity
                    && entity.getUUID().equals(packet.entityUuid())) {
                var nativeEffect = BuiltInRegistries.MOB_EFFECT.getHolder(packet.effect());
                var instance = nativeEffect.map(entity::getEffect).orElse(null);
                if (instance != null) {
                    FormulaConsumerHooks.applyClientValues(entity, instance, packet.values(), packet.attributes());
                }
            }
        }

        private static void applyPending(LivingEntity entity, MobEffectInstance effect) {
            var id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
            if (id == null) return;
            var key = new Key(entity.level().dimension().location(), entity.getId(), entity.getUUID(), id);
            var pending = PENDING.remove(key);
            var listener = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (pending != null && listener != null && pending.connection().isConnected()
                    && listener.getConnection() == pending.connection())
                FormulaConsumerHooks.applyClientValues(entity, effect, pending.values(), pending.attributes());
        }

        private static void removePending(LivingEntity entity, MobEffectInstance effect) {
            var id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
            if (id != null) PENDING.remove(new Key(entity.level().dimension().location(), entity.getId(), entity.getUUID(), id));
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            PENDING.clear();
        }

        @SubscribeEvent
        public static void unload(LevelEvent.Unload event) {
            if (event.getLevel().isClientSide() && event.getLevel() instanceof net.minecraft.world.level.Level level)
                PENDING.keySet().removeIf(key -> key.dimension().equals(level.dimension().location()));
        }
    }
}
