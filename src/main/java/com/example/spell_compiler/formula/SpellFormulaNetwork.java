package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import com.google.gson.JsonElement;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SpellFormulaNetwork {
    private static final String PROTOCOL = "18";
    static final int MAX_FILE_BYTES = 256 * 1024;
    static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;
    static final int MAX_PART_BYTES = 256 * 1024;
    static final int PART_DATA_BYTES = 240 * 1024;
    private static final int MAX_KEY_BYTES = 256;
    private static final int MAX_RESOURCES = 512;
    private static final int MAX_DISABLED = 16_384;
    private static final int MAX_BINDING_BYTES = 4096;
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Reassembler PENDING = new Reassembler();
    private static final FormulaNativeConfigNetwork.Staging ORIGINAL_PENDING = new FormulaNativeConfigNetwork.Staging();
    private static final Deliveries DELIVERIES = new Deliveries();
    private static Object clientConnection;
    private static long clientGeneration;
    private static boolean initialized;

    private SpellFormulaNetwork() {}

    public static synchronized void initialize(IEventBus modBus) {
        if (initialized) return;
        modBus.addListener(SpellFormulaNetwork::registerPayloads);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, SpellFormulaNetwork::synchronize);
        NeoForge.EVENT_BUS.addListener(SpellFormulaNetwork::playerLeft);
        NeoForge.EVENT_BUS.addListener(SpellFormulaNetwork::serverStopped);
        FormulaRuntimeDispatcher.onServerFailure(SpellFormulaNetwork::serverFailed);
        initialized = true;
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToClient(SnapshotPacket.TYPE, SnapshotPacket.STREAM_CODEC, SnapshotPacket::handle);
        registrar.playToClient(FormulaNativeConfigNetwork.Packet.TYPE, FormulaNativeConfigNetwork.Packet.STREAM_CODEC,
                FormulaNativeConfigNetwork.Packet::handle);
        registrar.playToClient(DisabledPacket.TYPE, DisabledPacket.STREAM_CODEC, DisabledPacket::handle);
    }

    private static void synchronize(OnDatapackSyncEvent event) {
        try {
            long generation = GENERATION.incrementAndGet();
            var snapshot = SpellFormulaState.serverSnapshot();
            Object bindings = FormulaRuntimeDispatcher.serverBindingsIdentity();
            var packets = packets(SpellFormulaFiles.resourcesForSnapshot(snapshot), generation,
                    FormulaRuntimeDispatcher.disabledServerKeys());
            var original = new FormulaNativeConfigNetwork.Packet(generation, FormulaNativeValues.exportOriginalConfig(snapshot),
                    SpellFormulaState.catalogFingerprint());
            SpellRegistry.onConfigReload();
            var players = event.getPlayer() != null ? List.of(event.getPlayer()) : List.copyOf(event.getPlayerList().getPlayers());
            for (var player : players) {
                PacketDistributor.sendToPlayer(player, original);
                for (var packet : packets) PacketDistributor.sendToPlayer(player, packet);
                DELIVERIES.install(player.connection, generation, bindings);
            }
        } catch (RuntimeException | LinkageError failure) {
            SpellCompiler.LOGGER.error("[SpellFormula] 无法同步服务器法术公式", failure);
            throw failure;
        }
    }

    public static void validateSnapshot(SpellFormulaFiles.Snapshot snapshot) {
        validateResources(SpellFormulaFiles.resourcesForSnapshot(snapshot));
    }

    static void validateSnapshot(SpellFormulaFiles.Snapshot snapshot, Set<String> bindings) {
        encodeResources(SpellFormulaFiles.resourcesForSnapshot(snapshot), bindings);
    }

    static void validateResources(Map<String, JsonElement> resources) {
        encodeResources(resources);
    }

    public static List<SnapshotPacket> packets(Map<String, JsonElement> resources, long generation) {
        return packets(resources, generation, Set.of());
    }

    static List<SnapshotPacket> packets(Map<String, JsonElement> resources, long generation, Set<String> disabled) {
        byte[] data = encodeResources(resources, disabled);
        int count = (data.length + PART_DATA_BYTES - 1) / PART_DATA_BYTES;
        List<SnapshotPacket> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int start = index * PART_DATA_BYTES;
            result.add(new SnapshotPacket(generation, index, count, data.length,
                    Arrays.copyOfRange(data, start, Math.min(start + PART_DATA_BYTES, data.length))));
        }
        return List.copyOf(result);
    }

    public record SnapshotPacket(long generation, int index, int count, int totalLength, byte[] data) implements CustomPacketPayload {
        public static final Type<SnapshotPacket> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(SpellCompiler.MODID, "spell_formula_config"));
        public static final StreamCodec<FriendlyByteBuf, SnapshotPacket> STREAM_CODEC =
                StreamCodec.ofMember(SnapshotPacket::encode, SnapshotPacket::decode);

        public SnapshotPacket {
            if (generation <= 0 || totalLength < 1 || totalLength > MAX_SNAPSHOT_BYTES
                    || count != (totalLength + PART_DATA_BYTES - 1) / PART_DATA_BYTES
                    || index < 0 || index >= count)
                throw new IllegalArgumentException("Invalid spell formula packet header");
            int expectedLength = Math.min(PART_DATA_BYTES, totalLength - index * PART_DATA_BYTES);
            if (data == null || data.length != expectedLength)
                throw new IllegalArgumentException("Invalid spell formula packet length");
            data = data.clone();
        }

        public SnapshotPacket(Map<String, JsonElement> resources) {
            this(1, singlePart(resources));
        }

        private SnapshotPacket(long generation, byte[] data) {
            this(generation, 0, 1, data.length, data);
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public Type<SnapshotPacket> type() {
            return TYPE;
        }

        public Map<String, JsonElement> resources() {
            if (count != 1) throw new IllegalStateException("Spell formula snapshot requires reassembly");
            return decodeResources(data);
        }

        public void encode(FriendlyByteBuf buffer) {
            int start = buffer.writerIndex();
            buffer.writeLong(generation);
            buffer.writeVarInt(index);
            buffer.writeVarInt(count);
            buffer.writeVarInt(totalLength);
            buffer.writeByteArray(data);
            if (buffer.writerIndex() - start > MAX_PART_BYTES)
                throw new IllegalArgumentException("Spell formula packet is too large");
        }

        public static SnapshotPacket decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() > MAX_PART_BYTES)
                throw new IllegalArgumentException("Spell formula packet is too large");
            long generation = buffer.readLong();
            int index = buffer.readVarInt();
            int count = buffer.readVarInt();
            int total = buffer.readVarInt();
            byte[] data = buffer.readByteArray(PART_DATA_BYTES);
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing spell formula packet bytes");
            return new SnapshotPacket(generation, index, count, total, data);
        }

        public void handle(IPayloadContext context) {
            if (!acceptsConnection(context)) return;
            try {
                var received = PENDING.acceptSnapshot(this);
                if (received == null) return;
                var snapshot = SpellFormulaFiles.parseResources(received.resources(), SpellFormulaState.catalog());
                if (!snapshot.diagnostics().isEmpty()) {
                    snapshot.diagnostics().forEach(SpellFormulaRuntime::logDiagnostic);
                    throw new IllegalArgumentException("Invalid server formula snapshot");
                }
                var originals = ORIGINAL_PENDING.complete(generation);
                var expected = snapshot.byClass().values().stream().map(value -> value.spell().id())
                        .collect(java.util.stream.Collectors.toSet());
                if (!originals.keySet().equals(expected))
                    throw new IllegalArgumentException("原生法术配置与公式法术列表不匹配");
                var effective = installSnapshot(snapshot, originals, received.disabled());
                if (!effective.fingerprint().equals(snapshot.fingerprint()))
                    throw new IllegalStateException("Server formula snapshot could not be compiled");
                clientGeneration = generation;
            } catch (RuntimeException | LinkageError failure) {
                rejectSync(context, failure);
            }
        }
    }

    static void stageOriginalConfig(FormulaNativeConfigNetwork.Packet packet) {
        ORIGINAL_PENDING.accept(packet, SpellFormulaState.catalogFingerprint());
    }

    static SpellFormulaFiles.Snapshot installSnapshot(SpellFormulaFiles.Snapshot snapshot,
            Map<String, FormulaNativeConfigNetwork.Values> originals, Set<String> disabled) {
        var previous = FormulaNativeValues.configCheckpoint();
        try {
            FormulaNativeValues.applyOriginalConfig(originals);
            return SpellFormulaRuntime.applyClient(snapshot, disabled);
        } catch (RuntimeException | LinkageError failure) {
            FormulaNativeValues.restoreClientConfig(previous);
            throw failure;
        }
    }

    static boolean acceptsConnection(IPayloadContext context) {
        if (!ClientEvents.isCurrentConnection(context)) return false;
        if (clientConnection == null) clientConnection = context.connection();
        return clientConnection == context.connection();
    }

    private static void serverFailed(String key, FormulaRuntimeDispatcher.Invoker invoker) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            if (ServerLifecycleHooks.getCurrentServer() != server || !FormulaRuntimeDispatcher.isCurrentServerInvoker(key, invoker)) return;
            for (var player : server.getPlayerList().getPlayers()) {
                long generation = DELIVERIES.generation(player.connection);
                if (generation != 0) PacketDistributor.sendToPlayer(player, new DisabledPacket(generation, key));
            }
        });
    }

    private static void playerLeft(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) DELIVERIES.remove(player.connection);
    }

    private static void serverStopped(ServerStoppedEvent event) { DELIVERIES.clear(); }

    static final class Deliveries {
        private record Delivery(long generation, Object bindings) {}
        private final Map<Object, Delivery> generations = new IdentityHashMap<>();
        void install(Object connection, long generation) {
            install(connection, generation, FormulaRuntimeDispatcher.serverBindingsIdentity());
        }
        void install(Object connection, long generation, Object bindings) {
            generations.put(connection, new Delivery(generation, bindings));
        }
        long generation(Object connection) {
            var delivery = generations.get(connection);
            return delivery != null && delivery.bindings() == FormulaRuntimeDispatcher.serverBindingsIdentity() ? delivery.generation() : 0;
        }
        void remove(Object connection) { generations.remove(connection); }
        void clear() { generations.clear(); }
    }

    public record DisabledPacket(long generation, String key) implements CustomPacketPayload {
        public static final Type<DisabledPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SpellCompiler.MODID, "spell_formula_disabled"));
        public static final StreamCodec<FriendlyByteBuf, DisabledPacket> STREAM_CODEC =
                StreamCodec.ofMember(DisabledPacket::encode, DisabledPacket::decode);

        public DisabledPacket {
            if (generation <= 0) throw new IllegalArgumentException("Invalid disabled formula generation");
            requireBinding(key);
        }

        @Override public Type<DisabledPacket> type() { return TYPE; }

        public void encode(FriendlyByteBuf buffer) {
            buffer.writeLong(generation);
            buffer.writeByteArray(key.getBytes(StandardCharsets.UTF_8));
        }

        public static DisabledPacket decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() > MAX_BINDING_BYTES + 13) throw new IllegalArgumentException("Disabled formula packet is too large");
            var packet = new DisabledPacket(buffer.readLong(), utf8(buffer.readByteArray(MAX_BINDING_BYTES)));
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing disabled formula bytes");
            return packet;
        }

        public void handle(IPayloadContext context) {
            if (!acceptsConnection(context)) return;
            try {
                apply(clientGeneration);
            } catch (RuntimeException | LinkageError failure) {
                rejectSync(context, failure);
            }
        }

        void apply(long installedGeneration) {
            if (generation < installedGeneration) return;
            if (generation != installedGeneration) throw new IllegalArgumentException("Disabled formula generation has not been installed");
            FormulaRuntimeDispatcher.disableClient(key);
        }
    }

    record ReceivedSnapshot(Map<String, JsonElement> resources, Set<String> disabled) {
        ReceivedSnapshot {
            resources = Map.copyOf(resources);
            disabled = Set.copyOf(disabled);
        }
    }

    static void rejectSync(IPayloadContext context, Throwable failure) {
        PENDING.reset();
        ORIGINAL_PENDING.reset();
        clientGeneration = 0;
        SpellCompiler.LOGGER.error("[SpellFormula] 无法应用服务器法术公式", failure);
        context.disconnect(Component.translatable("disconnect.spell_compiler.formula_sync_failed"));
    }

    static final class Reassembler {
        private long generation;
        private long completed;
        private int count;
        private int total;
        private int next;
        private ByteArrayOutputStream pending;

        Map<String, JsonElement> accept(SnapshotPacket packet) {
            var received = acceptSnapshot(packet);
            return received == null ? null : received.resources();
        }

        ReceivedSnapshot acceptSnapshot(SnapshotPacket packet) {
            if (packet.generation < generation) return null;
            if (packet.generation == completed)
                throw new IllegalArgumentException("Duplicate completed spell formula snapshot");
            if (packet.generation > generation) {
                if (packet.index != 0) throw new IllegalArgumentException("Spell formula snapshot did not start with part zero");
                generation = packet.generation;
                count = packet.count;
                total = packet.totalLength;
                next = 0;
                pending = new ByteArrayOutputStream(total);
            }
            if (pending == null || packet.count != count || packet.totalLength != total || packet.index != next)
                throw new IllegalArgumentException("Inconsistent, duplicate or out-of-order spell formula part");
            pending.writeBytes(packet.data);
            next++;
            if (next != count) return null;
            if (pending.size() != total) throw new IllegalArgumentException("Incomplete spell formula snapshot");
            byte[] data = pending.toByteArray();
            pending = null;
            var received = decodeSnapshot(data);
            completed = generation;
            return received;
        }

        void reset() {
            generation = 0;
            completed = 0;
            count = 0;
            total = 0;
            next = 0;
            pending = null;
        }
    }

    private static byte[] singlePart(Map<String, JsonElement> resources) {
        byte[] data = encodeResources(resources);
        if (data.length > PART_DATA_BYTES) throw new IllegalArgumentException("Use packets() for multipart spell formula snapshots");
        return data;
    }

    private static byte[] encodeResources(Map<String, JsonElement> resources) {
        return encodeResources(resources, Set.of());
    }

    private static byte[] encodeResources(Map<String, JsonElement> resources, Set<String> disabled) {
        if (resources.size() > MAX_RESOURCES) throw new IllegalArgumentException("Too many spell formula resources");
        if (disabled.size() > MAX_DISABLED) throw new IllegalArgumentException("Too many disabled formulas");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(resources.size());
            for (var entry : new TreeMap<>(resources).entrySet()) {
                byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] json = entry.getValue().toString().getBytes(StandardCharsets.UTF_8);
                if (key.length < 1 || key.length > MAX_KEY_BYTES || json.length > MAX_FILE_BYTES)
                    throw new IllegalArgumentException("Spell formula resource exceeds its UTF-8 byte limit");
                int length = VarInt.getByteSize(key.length) + key.length
                        + VarInt.getByteSize(json.length) + json.length;
                if (buffer.writerIndex() + length > MAX_SNAPSHOT_BYTES)
                    throw new IllegalArgumentException("Spell formula snapshot is too large");
                buffer.writeByteArray(key);
                buffer.writeByteArray(json);
            }
            if (!disabled.isEmpty()) {
                buffer.writeVarInt(disabled.size());
                for (String key : new TreeSet<>(disabled)) {
                    requireBinding(key);
                    byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
                    if (buffer.writerIndex() + VarInt.getByteSize(bytes.length) + bytes.length > MAX_SNAPSHOT_BYTES)
                        throw new IllegalArgumentException("Spell formula snapshot is too large");
                    buffer.writeByteArray(bytes);
                }
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static Map<String, JsonElement> decodeResources(byte[] data) {
        return decodeSnapshot(data).resources();
    }

    private static ReceivedSnapshot decodeSnapshot(byte[] data) {
        if (data.length > MAX_SNAPSHOT_BYTES) throw new IllegalArgumentException("Spell formula snapshot is too large");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
        try {
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_RESOURCES) throw new IllegalArgumentException("Invalid spell formula resource count");
            Map<String, JsonElement> resources = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                String key = utf8(buffer.readByteArray(MAX_KEY_BYTES));
                if (key.isEmpty()) throw new IllegalArgumentException("Empty spell formula resource key");
                String json = utf8(buffer.readByteArray(MAX_FILE_BYTES));
                if (resources.putIfAbsent(key, SpellFormulaFiles.parseJson(json)) != null)
                    throw new IllegalArgumentException("Duplicate spell formula resource: " + key);
            }
            Set<String> disabled = new HashSet<>();
            if (buffer.isReadable()) {
                int disabledCount = buffer.readVarInt();
                if (disabledCount < 1 || disabledCount > MAX_DISABLED) throw new IllegalArgumentException("Invalid disabled formula count");
                for (int index = 0; index < disabledCount; index++) {
                    String key = utf8(buffer.readByteArray(MAX_BINDING_BYTES));
                    requireBinding(key);
                    if (!disabled.add(key)) throw new IllegalArgumentException("Duplicate disabled formula");
                }
            }
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing spell formula snapshot bytes");
            return new ReceivedSnapshot(resources, disabled);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid spell formula snapshot JSON", failure);
        } finally {
            buffer.release();
        }
    }

    private static void requireBinding(String key) {
        if (key == null || key.isEmpty() || key.getBytes(StandardCharsets.UTF_8).length > MAX_BINDING_BYTES)
            throw new IllegalArgumentException("Invalid disabled formula key");
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid UTF-8 spell formula resource", failure);
        }
    }

    @EventBusSubscriber(modid = SpellCompiler.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static final class ClientEvents {
        private static boolean isCurrentConnection(IPayloadContext context) {
            var listener = net.minecraft.client.Minecraft.getInstance().getConnection();
            return context.connection().isConnected() && listener != null && listener.getConnection() == context.connection();
        }

        @SubscribeEvent
        public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
            PENDING.reset();
            ORIGINAL_PENDING.reset();
            clientConnection = event.getConnection();
            clientGeneration = 0;
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            if (clientConnection != null && event.getConnection() != null && clientConnection != event.getConnection()) return;
            PENDING.reset();
            ORIGINAL_PENDING.reset();
            clientGeneration = 0;
            clientConnection = null;
            SpellFormulaRuntime.resetClient();
        }
    }
}
