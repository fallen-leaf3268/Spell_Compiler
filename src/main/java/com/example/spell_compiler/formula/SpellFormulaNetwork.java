package com.example.spell_compiler.formula;

import com.example.spell_compiler.SpellCompiler;
import com.google.gson.JsonElement;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

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
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class SpellFormulaNetwork {
    private static final String PROTOCOL = "10";
    static final int MAX_FILE_BYTES = 256 * 1024;
    static final int MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024;
    static final int MAX_PART_BYTES = 256 * 1024;
    static final int PART_DATA_BYTES = 240 * 1024;
    private static final int MAX_KEY_BYTES = 256;
    private static final int MAX_RESOURCES = 512;
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Reassembler PENDING = new Reassembler();
    private static final FormulaNativeConfigNetwork.Staging ORIGINAL_PENDING = new FormulaNativeConfigNetwork.Staging();
    private static SimpleChannel channel;

    private SpellFormulaNetwork() {}

    public static synchronized void initialize() {
        if (channel != null) return;
        channel = NetworkRegistry.newSimpleChannel(ResourceLocation.fromNamespaceAndPath(
                SpellCompiler.MODID, "spell_formula_config"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
        channel.registerMessage(0, SnapshotPacket.class, SnapshotPacket::encode, SnapshotPacket::decode,
                SnapshotPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(1, FormulaNativeConfigNetwork.Packet.class, FormulaNativeConfigNetwork.Packet::encode,
                FormulaNativeConfigNetwork.Packet::decode, FormulaNativeConfigNetwork.Packet::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, SpellFormulaNetwork::synchronize);
    }

    private static void synchronize(OnDatapackSyncEvent event) {
        try {
            long generation = GENERATION.incrementAndGet();
            var snapshot = SpellFormulaState.serverSnapshot();
            var packets = packets(SpellFormulaFiles.resourcesForSnapshot(snapshot), generation);
            var original = new FormulaNativeConfigNetwork.Packet(generation, FormulaNativeValues.exportOriginalConfig(snapshot),
                    SpellFormulaState.catalogFingerprint());
            SpellRegistry.onConfigReload();
            if (event.getPlayer() != null) channel.send(PacketDistributor.PLAYER.with(event::getPlayer), original);
            else channel.send(PacketDistributor.ALL.noArg(), original);
            for (var packet : packets) {
                if (event.getPlayer() != null) channel.send(PacketDistributor.PLAYER.with(event::getPlayer), packet);
                else channel.send(PacketDistributor.ALL.noArg(), packet);
            }
        } catch (RuntimeException | LinkageError failure) {
            SpellCompiler.LOGGER.error("[SpellFormula] 无法同步服务器法术公式", failure);
            throw failure;
        }
    }

    public static void validateSnapshot(SpellFormulaFiles.Snapshot snapshot) {
        validateResources(SpellFormulaFiles.resourcesForSnapshot(snapshot));
    }

    static void validateResources(Map<String, JsonElement> resources) {
        encodeResources(resources);
    }

    public static List<SnapshotPacket> packets(Map<String, JsonElement> resources, long generation) {
        byte[] data = encodeResources(resources);
        int count = (data.length + PART_DATA_BYTES - 1) / PART_DATA_BYTES;
        List<SnapshotPacket> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int start = index * PART_DATA_BYTES;
            result.add(new SnapshotPacket(generation, index, count, data.length,
                    Arrays.copyOfRange(data, start, Math.min(start + PART_DATA_BYTES, data.length))));
        }
        return List.copyOf(result);
    }

    public record SnapshotPacket(long generation, int index, int count, int totalLength, byte[] data) {
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

        public void handle(Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                if (!context.getNetworkManager().isConnected()) return;
                try {
                    var resources = PENDING.accept(this);
                    if (resources == null) return;
                    var snapshot = SpellFormulaFiles.parseResources(resources, SpellFormulaState.catalog());
                    if (!snapshot.diagnostics().isEmpty()) {
                        snapshot.diagnostics().forEach(SpellFormulaRuntime::logDiagnostic);
                        throw new IllegalArgumentException("Invalid server formula snapshot");
                    }
                    var originals = ORIGINAL_PENDING.complete(generation);
                    var expected = snapshot.byClass().values().stream().map(value -> value.spell().id())
                            .collect(java.util.stream.Collectors.toSet());
                    if (!originals.keySet().equals(expected))
                        throw new IllegalArgumentException("原生法术配置与公式法术列表不匹配");
                    FormulaNativeValues.applyOriginalConfig(originals);
                    var effective = SpellFormulaRuntime.applyClient(snapshot);
                    if (!effective.fingerprint().equals(snapshot.fingerprint()))
                        throw new IllegalStateException("Server formula snapshot could not be compiled");
                } catch (RuntimeException | LinkageError failure) {
                    rejectSync(context, failure);
                }
            });
            context.setPacketHandled(true);
        }
    }

    static void stageOriginalConfig(FormulaNativeConfigNetwork.Packet packet) {
        ORIGINAL_PENDING.accept(packet, SpellFormulaState.catalogFingerprint());
    }

    static void rejectSync(NetworkEvent.Context context, Throwable failure) {
        PENDING.reset();
        ORIGINAL_PENDING.reset();
        FormulaNativeValues.resetConfig(true);
        SpellCompiler.LOGGER.error("[SpellFormula] 无法应用服务器法术公式", failure);
        context.getNetworkManager().disconnect(Component.translatable("disconnect.spell_compiler.formula_sync_failed"));
    }

    static final class Reassembler {
        private long generation;
        private long completed;
        private int count;
        private int total;
        private int next;
        private ByteArrayOutputStream pending;

        Map<String, JsonElement> accept(SnapshotPacket packet) {
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
            Map<String, JsonElement> resources = decodeResources(data);
            completed = generation;
            return resources;
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
        if (resources.size() > MAX_RESOURCES) throw new IllegalArgumentException("Too many spell formula resources");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(resources.size());
            for (var entry : new TreeMap<>(resources).entrySet()) {
                byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] json = entry.getValue().toString().getBytes(StandardCharsets.UTF_8);
                if (key.length < 1 || key.length > MAX_KEY_BYTES || json.length > MAX_FILE_BYTES)
                    throw new IllegalArgumentException("Spell formula resource exceeds its UTF-8 byte limit");
                int length = FriendlyByteBuf.getVarIntSize(key.length) + key.length
                        + FriendlyByteBuf.getVarIntSize(json.length) + json.length;
                if (buffer.writerIndex() + length > MAX_SNAPSHOT_BYTES)
                    throw new IllegalArgumentException("Spell formula snapshot is too large");
                buffer.writeByteArray(key);
                buffer.writeByteArray(json);
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static Map<String, JsonElement> decodeResources(byte[] data) {
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
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing spell formula snapshot bytes");
            return Map.copyOf(resources);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid spell formula snapshot JSON", failure);
        } finally {
            buffer.release();
        }
    }

    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid UTF-8 spell formula resource", failure);
        }
    }

    @Mod.EventBusSubscriber(modid = SpellCompiler.MODID, value = Dist.CLIENT)
    public static final class ClientEvents {
        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            PENDING.reset();
            ORIGINAL_PENDING.reset();
            SpellFormulaRuntime.resetClient();
        }
    }
}
