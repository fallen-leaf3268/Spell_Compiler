package com.example.spell_compiler.formula;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormulaNativeConfigNetworkTest {
    @Test
    void originalNumericConfigurationRoundTripsAsAnImmutableIndependentPacket() {
        var values = new FormulaNativeConfigNetwork.Values(2, 3, 27);
        var source = new LinkedHashMap<>(Map.of("irons_spellbooks:fireball", values));
        var packet = new FormulaNativeConfigNetwork.Packet(7, source);
        source.clear();
        var buffer = buffer();
        try {
            packet.encode(buffer);
            assertEquals(packet, FormulaNativeConfigNetwork.Packet.decode(buffer));
            assertFalse(buffer.isReadable());
            assertThrows(UnsupportedOperationException.class, packet.values()::clear);
        } finally {
            buffer.release();
        }
    }

    @Test
    void scalarValidationRejectsEveryNonFiniteConfigurationField() {
        for (double value : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new FormulaNativeConfigNetwork.Values(value, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new FormulaNativeConfigNetwork.Values(1, value, 1));
            assertThrows(IllegalArgumentException.class, () -> new FormulaNativeConfigNetwork.Values(1, 1, value));
        }
    }

    @Test
    void originalNativeFiniteValuesKeepTheirExistingSignAndDoublePrecision() {
        var values = new FormulaNativeConfigNetwork.Values(-1, Double.MAX_VALUE, -0.0);
        var packet = new FormulaNativeConfigNetwork.Packet(1, Map.of("test:spell", values));
        var buffer = buffer();
        try {
            packet.encode(buffer);
            var decoded = FormulaNativeConfigNetwork.Packet.decode(buffer).values().get("test:spell");
            assertEquals(values, decoded);
            assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(decoded.cooldownSeconds()));
        } finally {
            buffer.release();
        }
    }

    @Test
    void packetConstructionEnforcesGenerationEntryAndIdentifierLimits() {
        var values = new FormulaNativeConfigNetwork.Values(1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new FormulaNativeConfigNetwork.Packet(0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new FormulaNativeConfigNetwork.Packet(-1, Map.of()));
        Map<String, FormulaNativeConfigNetwork.Values> entries = new LinkedHashMap<>();
        for (int index = 0; index < FormulaNativeConfigNetwork.MAX_ENTRIES; index++) entries.put("test:s" + index, values);
        assertDoesNotThrow(() -> new FormulaNativeConfigNetwork.Packet(1, entries));
        entries.put("test:extra", values);
        assertThrows(IllegalArgumentException.class, () -> new FormulaNativeConfigNetwork.Packet(1, entries));
        assertThrows(IllegalArgumentException.class, () -> new FormulaNativeConfigNetwork.Packet(1, Map.of("invalid", values)));
        assertThrows(IllegalArgumentException.class, () -> new FormulaNativeConfigNetwork.Packet(1,
                Map.of("test:" + "x".repeat(252), values)));
    }

    @Test
    void decoderRejectsDuplicateIdsInvalidCountsTrailingBytesAndOversizedPackets() {
        var buffer = buffer();
        try {
            buffer.writeLong(1);
            buffer.writeUtf(SpellFormulaState.catalogFingerprint(), 64);
            buffer.writeVarInt(2);
            for (int index = 0; index < 2; index++) {
                buffer.writeUtf("test:spell");
                buffer.writeDouble(1).writeDouble(1).writeDouble(1);
            }
            assertThrows(IllegalArgumentException.class, () -> FormulaNativeConfigNetwork.Packet.decode(buffer));
            buffer.clear();
            buffer.writeLong(1);
            buffer.writeUtf(SpellFormulaState.catalogFingerprint(), 64);
            buffer.writeVarInt(513);
            assertThrows(IllegalArgumentException.class, () -> FormulaNativeConfigNetwork.Packet.decode(buffer));
            buffer.clear();
            new FormulaNativeConfigNetwork.Packet(1, Map.of()).encode(buffer);
            buffer.writeByte(1);
            assertThrows(IllegalArgumentException.class, () -> FormulaNativeConfigNetwork.Packet.decode(buffer));
            buffer.clear();
            buffer.writeZero(FormulaNativeConfigNetwork.MAX_PACKET_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> FormulaNativeConfigNetwork.Packet.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void decoderRejectsNonFiniteValuesBeforePublishingAnyConfiguration() {
        var buffer = buffer();
        try {
            buffer.writeLong(1);
            buffer.writeUtf(SpellFormulaState.catalogFingerprint(), 64);
            buffer.writeVarInt(1);
            buffer.writeUtf("test:spell");
            buffer.writeDouble(1).writeDouble(Double.NaN).writeDouble(1);
            assertThrows(IllegalArgumentException.class, () -> FormulaNativeConfigNetwork.Packet.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void stagedOriginalsCanOnlyBeConsumedWithTheirMatchingFormulaGeneration() {
        var stage = new FormulaNativeConfigNetwork.Staging();
        var values = Map.of("test:spell", new FormulaNativeConfigNetwork.Values(2, 2, 27));
        stage.accept(new FormulaNativeConfigNetwork.Packet(5, values));
        assertThrows(IllegalArgumentException.class, () -> stage.complete(4));
        assertEquals(values, stage.complete(5));
        assertThrows(IllegalArgumentException.class, () -> stage.complete(5));
        assertThrows(IllegalArgumentException.class, () -> stage.accept(new FormulaNativeConfigNetwork.Packet(5, values)));
    }

    @Test
    void newerStagedOriginalsReplaceIncompleteOldStateAndLogoutAcceptsLowerGenerations() {
        var stage = new FormulaNativeConfigNetwork.Staging();
        var values = Map.of("test:spell", new FormulaNativeConfigNetwork.Values(2, 2, 27));
        stage.accept(new FormulaNativeConfigNetwork.Packet(5, values));
        stage.accept(new FormulaNativeConfigNetwork.Packet(7, Map.of()));
        stage.accept(new FormulaNativeConfigNetwork.Packet(6, values));
        assertThrows(IllegalArgumentException.class, () -> stage.complete(5));
        assertEquals(Map.of(), stage.complete(7));
        stage.reset();
        stage.accept(new FormulaNativeConfigNetwork.Packet(1, values));
        assertEquals(values, stage.complete(1));
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
