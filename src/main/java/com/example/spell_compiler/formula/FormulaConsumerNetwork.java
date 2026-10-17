package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class FormulaConsumerNetwork {
    private static final String PROTOCOL = "1";
    private static final int MAX_PACKET_BYTES = 1024;
    private static final int MAX_VALUES = 3;
    private static SimpleChannel channel;

    private FormulaConsumerNetwork() {}

    public static synchronized void initialize() {
        if (channel != null) return;
        channel = NetworkRegistry.newSimpleChannel(ResourceLocation.fromNamespaceAndPath(
                SpellCompiler.MODID, "spell_formula_effect"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
        channel.registerMessage(0, EffectPacket.class, EffectPacket::encode, EffectPacket::decode,
                EffectPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(FormulaConsumerNetwork::tracking);
        MinecraftForge.EVENT_BUS.addListener(FormulaConsumerNetwork::login);
        MinecraftForge.EVENT_BUS.addListener(FormulaConsumerNetwork::changedDimension);
    }

    public static void synchronize(LivingEntity entity, MobEffectInstance effect) {
        var id = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect());
        if (id == null || FormulaConsumerSites.forEffect(id.toString()).isEmpty()) return;
        if (entity.level().isClientSide) {
            Client.applyPending(entity, effect);
        } else if (channel != null) {
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
            var id = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect());
            if (id != null && !FormulaConsumerSites.forEffect(id.toString()).isEmpty())
                channel.send(PacketDistributor.PLAYER.with(() -> recipient), packet(entity, effect, id));
        }
    }

    private static EffectPacket packet(LivingEntity entity, MobEffectInstance effect, ResourceLocation id) {
        return new EffectPacket(entity.getId(), entity.getUUID(), entity.level().dimension().location(), id,
                ((FormulaConsumerCarrier) effect).apoth$getFormulaValues());
    }

    public record EffectPacket(int entityId, UUID entityUuid, ResourceLocation dimension, ResourceLocation effect,
                               Map<String, Float> values) {
        public EffectPacket {
            Objects.requireNonNull(entityUuid);
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(effect);
            Objects.requireNonNull(values);
            var allowed = FormulaConsumerSites.forEffect(effect.toString()).stream()
                    .map(FormulaConsumerSites.Definition::alias).toList();
            if (entityId < 0 || allowed.isEmpty() || values.size() > MAX_VALUES
                    || dimension.toString().length() > 128 || effect.toString().length() > 64)
                throw new IllegalArgumentException("Invalid formula effect packet header");
            for (var entry : values.entrySet()) {
                if (!allowed.contains(entry.getKey()) || entry.getValue() == null || !Float.isFinite(entry.getValue()))
                    throw new IllegalArgumentException("Invalid formula effect value: " + entry.getKey());
            }
            values = Map.copyOf(values);
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
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing formula effect packet bytes");
            return new EffectPacket(entity, uuid, dimension, effect, values);
        }

        public void handle(Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> Client.receive(this, context));
            context.setPacketHandled(true);
        }
    }

    @Mod.EventBusSubscriber(modid = SpellCompiler.MODID, value = Dist.CLIENT)
    public static final class Client {
        private record Key(ResourceLocation dimension, int entity, UUID uuid, ResourceLocation effect) {}
        private record Pending(net.minecraft.network.Connection connection, Map<String, Float> values) {}
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
            PENDING.put(key, new Pending(context.getNetworkManager(), packet.values()));
            var level = minecraft.level;
            if (level != null && level.dimension().location().equals(packet.dimension())
                    && level.getEntity(packet.entityId()) instanceof LivingEntity entity
                    && entity.getUUID().equals(packet.entityUuid())) {
                var nativeEffect = ForgeRegistries.MOB_EFFECTS.getValue(packet.effect());
                var instance = nativeEffect == null ? null : entity.getEffect(nativeEffect);
                if (instance != null) {
                    FormulaConsumerHooks.applyClientValues(entity, instance, packet.values());
                }
            }
        }

        private static void applyPending(LivingEntity entity, MobEffectInstance effect) {
            var id = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect());
            if (id == null) return;
            var key = new Key(entity.level().dimension().location(), entity.getId(), entity.getUUID(), id);
            var pending = PENDING.remove(key);
            var listener = net.minecraft.client.Minecraft.getInstance().getConnection();
            if (pending != null && listener != null && pending.connection().isConnected()
                    && listener.getConnection() == pending.connection())
                FormulaConsumerHooks.applyClientValues(entity, effect, pending.values());
        }

        private static void removePending(LivingEntity entity, MobEffectInstance effect) {
            var id = ForgeRegistries.MOB_EFFECTS.getKey(effect.getEffect());
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
