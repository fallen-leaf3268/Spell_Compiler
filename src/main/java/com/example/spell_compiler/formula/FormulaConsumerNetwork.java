package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class FormulaConsumerNetwork {
    private static final String PROTOCOL = "3";
    private static final int MAX_PACKET_BYTES = 32768;
    private static final int MAX_VALUES = 32;
    private static boolean initialized;
    private static SimpleChannel channel;

    private FormulaConsumerNetwork() {}

    public static synchronized void initialize(IEventBus modBus) {
        if (initialized) return;
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation(SpellCompiler.MODID, "spell_formula_effect"),
                () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
        channel.registerMessage(0, EffectPacket.class, EffectPacket::encode, EffectPacket::decode,
                EffectPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(FormulaConsumerNetwork::tracking);
        MinecraftForge.EVENT_BUS.addListener(FormulaConsumerNetwork::login);
        MinecraftForge.EVENT_BUS.addListener(FormulaConsumerNetwork::changedDimension);
        initialized = true;
    }


    public static void synchronize(LivingEntity entity, MobEffectInstance effect) {
        var id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect());
        if (id == null || FormulaConsumerSites.aliasesForEffect(id.toString()).isEmpty()) return;
        if (entity.level().isClientSide) {
            Client.applyPending(entity, effect);
        } else if (initialized) {
            channel.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), packet(entity, effect, id));
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
            var id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect());
            if (id != null && !FormulaConsumerSites.aliasesForEffect(id.toString()).isEmpty())
                channel.send(PacketDistributor.PLAYER.with(() -> recipient), packet(entity, effect, id));
        }
    }

    private static EffectPacket packet(LivingEntity entity, MobEffectInstance effect, ResourceLocation id) {
        return new EffectPacket(entity.getId(), entity.getUUID(), entity.level().dimension().location(), id,
                ((FormulaConsumerCarrier) effect).apoth$getFormulaValues(), ((FormulaConsumerCarrier) effect).apoth$getAttributeValues());
    }

    public record EffectPacket(int entityId, UUID entityUuid, ResourceLocation dimension, ResourceLocation effect,
                               Map<String, Float> values, Map<String, FormulaAttributes.Value> attributes) {
        public EffectPacket(int entityId, UUID entityUuid, ResourceLocation dimension, ResourceLocation effect, Map<String, Float> values) {
            this(entityId, entityUuid, dimension, effect, values, Map.of());
        }

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
            var dimension = new ResourceLocation(buffer.readUtf(128));
            var effect = new ResourceLocation(buffer.readUtf(64));
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

        public void handle(Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                Client.receive(this, context);
            });
            context.setPacketHandled(true);
        }
    }

    @Mod.EventBusSubscriber(modid = SpellCompiler.MODID, value = Dist.CLIENT)
    public static final class Client {
        private record Key(ResourceLocation dimension, int entity, UUID uuid, ResourceLocation effect) {}
        private record Pending(net.minecraft.network.Connection connection, Map<String, Float> values, Map<String, FormulaAttributes.Value> attributes) {}
        private static final Map<Key, Pending> PENDING = new LinkedHashMap<>();
        private static final int MAX_PENDING = 1024;

        private Client() {}

        private static void receive(EffectPacket packet, NetworkEvent.Context context) {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            var listener = minecraft.getConnection();
            if (!context.getNetworkManager().isConnected() || listener == null
                    || listener.getConnection() != context.getNetworkManager()) return;
            var key = new Key(packet.dimension(), packet.entityId(), packet.entityUuid(), packet.effect());
            if (PENDING.size() >= MAX_PENDING && !PENDING.containsKey(key))
                PENDING.remove(PENDING.keySet().iterator().next());
            PENDING.put(key, new Pending(context.getNetworkManager(), packet.values(), packet.attributes()));
            var level = minecraft.level;
            if (level != null && level.dimension().location().equals(packet.dimension())
                    && level.getEntity(packet.entityId()) instanceof LivingEntity entity
                    && entity.getUUID().equals(packet.entityUuid())) {
                var nativeEffect = BuiltInRegistries.MOB_EFFECT.getOptional(packet.effect());
                var instance = nativeEffect.map(entity::getEffect).orElse(null);
                if (instance != null) {
                    FormulaConsumerHooks.applyClientValues(entity, instance, packet.values(), packet.attributes());
                }
            }
        }

        private static void applyPending(LivingEntity entity, MobEffectInstance effect) {
            var id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect());
            if (id == null) return;
            var key = new Key(entity.level().dimension().location(), entity.getId(), entity.getUUID(), id);
            var pending = PENDING.remove(key);
            var listener = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (pending != null && listener != null && pending.connection().isConnected()
                    && listener.getConnection() == pending.connection())
                FormulaConsumerHooks.applyClientValues(entity, effect, pending.values(), pending.attributes());
        }

        private static void removePending(LivingEntity entity, MobEffectInstance effect) {
            var id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect());
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
