package com.example.spell_compiler.formula;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

public final class FormulaNativeConfigNetwork {
    static final int MAX_ENTRIES = 512;
    static final int MAX_ID_BYTES = 256;
    static final int MAX_PACKET_BYTES = 160 * 1024;
    private static final int MAX_FINGERPRINT_BYTES = 64;

    private FormulaNativeConfigNetwork() {}

    public record Values(double manaMultiplier, double powerMultiplier, double cooldownSeconds) {
        public Values {
            if (!Double.isFinite(manaMultiplier) || !Double.isFinite(powerMultiplier) || !Double.isFinite(cooldownSeconds))
                throw new IllegalArgumentException("原生法术配置必须是有限数值");
        }
    }

    public record Packet(long generation, Map<String, Values> values, String catalogFingerprint) {
        public Packet {
            if (generation <= 0 || values == null || values.size() > MAX_ENTRIES)
                throw new IllegalArgumentException("无效原生法术配置批次");
            catalogFingerprint = requireFingerprint(catalogFingerprint);
            values = Map.copyOf(values);
            int size = 8 + FriendlyByteBuf.getVarIntSize(MAX_FINGERPRINT_BYTES) + MAX_FINGERPRINT_BYTES
                    + FriendlyByteBuf.getVarIntSize(values.size());
            for (var entry : values.entrySet()) {
                SpellFormulaCatalog.requireId(entry.getKey());
                int length = entry.getKey().getBytes(StandardCharsets.UTF_8).length;
                if (length > MAX_ID_BYTES) throw new IllegalArgumentException("原生法术配置 ID 过长");
                size += FriendlyByteBuf.getVarIntSize(length) + length + 24;
            }
            if (size > MAX_PACKET_BYTES) throw new IllegalArgumentException("原生法术配置数据过大");
        }

        public Packet(long generation, Map<String, Values> values) {
            this(generation, values, SpellFormulaState.catalogFingerprint());
        }

        public void encode(FriendlyByteBuf buffer) {
            buffer.writeLong(generation);
            buffer.writeUtf(catalogFingerprint, MAX_FINGERPRINT_BYTES);
            buffer.writeVarInt(values.size());
            new TreeMap<>(values).forEach((id, value) -> {
                buffer.writeUtf(id, MAX_ID_BYTES);
                buffer.writeDouble(value.manaMultiplier());
                buffer.writeDouble(value.powerMultiplier());
                buffer.writeDouble(value.cooldownSeconds());
            });
        }

        public static Packet decode(FriendlyByteBuf buffer) {
            if (buffer.readableBytes() > MAX_PACKET_BYTES) throw new IllegalArgumentException("原生法术配置数据过大");
            long generation = buffer.readLong();
            String catalogFingerprint = requireFingerprint(buffer.readUtf(MAX_FINGERPRINT_BYTES));
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("原生法术配置数量无效");
            Map<String, Values> values = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                String id = buffer.readUtf(MAX_ID_BYTES);
                var value = new Values(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
                if (values.putIfAbsent(id, value) != null) throw new IllegalArgumentException("重复原生法术配置 ID");
            }
            if (buffer.isReadable()) throw new IllegalArgumentException("原生法术配置包含多余数据");
            return new Packet(generation, values, catalogFingerprint);
        }

        public void handle(Supplier<NetworkEvent.Context> supplier) {
            var context = supplier.get();
            context.enqueueWork(() -> {
                if (!context.getNetworkManager().isConnected()) return;
                try {
                    SpellFormulaNetwork.stageOriginalConfig(this);
                } catch (RuntimeException | LinkageError failure) {
                    SpellFormulaNetwork.rejectSync(context, failure);
                }
            });
            context.setPacketHandled(true);
        }
    }

    private static String requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("无效法术目录指纹");
        return value.toLowerCase(Locale.ROOT);
    }

    static final class Staging {
        private long generation;
        private Packet pending;

        void accept(Packet packet, String expectedFingerprint) {
            if (!packet.catalogFingerprint().equals(requireFingerprint(expectedFingerprint)))
                throw new IllegalArgumentException("客户端与服务器法术目录不一致");
            accept(packet);
        }

        void accept(Packet packet) {
            if (packet.generation() < generation) return;
            if (packet.generation() == generation) throw new IllegalArgumentException("重复原生法术配置批次");
            generation = packet.generation();
            pending = packet;
        }

        Map<String, Values> complete(long snapshotGeneration) {
            if (pending == null || pending.generation() != snapshotGeneration)
                throw new IllegalArgumentException("原生法术配置与公式批次不匹配");
            var values = pending.values();
            pending = null;
            return values;
        }

        void reset() {
            generation = 0;
            pending = null;
        }
    }
}
