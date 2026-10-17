package com.example.spell_compiler.formula;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaConsumerNetworkTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation CHARGE = ResourceLocation.parse("irons_spellbooks:charged");

    @Test
    void boundedEffectPacketPreservesAllThreeValuesAndEntityIdentity() {
        var packet = packet(Map.of("percent_speed", 50f, "percent_attack_damage", 40f, "percent_spell_power", 60f));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            assertTrue(buffer.readableBytes() < 256);
            assertEquals(packet, FormulaConsumerNetwork.EffectPacket.decode(buffer));
            assertThrows(UnsupportedOperationException.class, () -> packet.values().put("percent_speed", 0f));
        } finally {
            buffer.release();
        }
    }

    @Test
    void malformedOrNonFiniteEffectValuesAreRejectedAtTheWireBoundary() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> packet(Map.of("percent_speed", invalid)));
        assertThrows(IllegalArgumentException.class, () -> packet(Map.of("mana_ratio", 2f)));
        assertThrows(IllegalArgumentException.class, () -> new FormulaConsumerNetwork.EffectPacket(1, UUID.randomUUID(),
                DIMENSION, ResourceLocation.parse("minecraft:speed"), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new FormulaConsumerNetwork.EffectPacket(-1, UUID.randomUUID(),
                DIMENSION, CHARGE, Map.of()));
    }

    @Test
    void duplicateKeysAndTrailingBytesCannotOverrideTheValidatedPayload() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(1).writeUUID(UUID.randomUUID()).writeUtf(DIMENSION.toString(), 128)
                    .writeUtf(CHARGE.toString(), 64).writeVarInt(2);
            buffer.writeUtf("percent_speed", 64).writeFloat(20);
            buffer.writeUtf("percent_speed", 64).writeFloat(90);
            assertThrows(IllegalArgumentException.class, () -> FormulaConsumerNetwork.EffectPacket.decode(buffer));
            buffer.clear();
            packet(Map.of()).encode(buffer);
            buffer.writeByte(1);
            assertThrows(IllegalArgumentException.class, () -> FormulaConsumerNetwork.EffectPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static FormulaConsumerNetwork.EffectPacket packet(Map<String, Float> values) {
        return new FormulaConsumerNetwork.EffectPacket(42, UUID.randomUUID(), DIMENSION, CHARGE, values);
    }
}
