package com.example.spell_compiler.formula;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.network.connection.ConnectionType;
import java.util.Objects;

public final class FormulaEffectStreamCodec {
    private FormulaEffectStreamCodec() {}

    public static StreamCodec<RegistryFriendlyByteBuf, MobEffectInstance> create(
            StreamCodec<RegistryFriendlyByteBuf, MobEffectInstance> original) {
        Objects.requireNonNull(original);
        var extended = ByteBufCodecs.fromCodecWithRegistries(MobEffectInstance.CODEC);
        return new StreamCodec<>() {
            @Override
            public MobEffectInstance decode(RegistryFriendlyByteBuf buffer) {
                return switch (buffer.readUnsignedByte()) {
                    case 0 -> original.decode(buffer);
                    case 1 -> extended.decode(buffer);
                    default -> throw new IllegalArgumentException("Unknown formula effect stream mode");
                };
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, MobEffectInstance effect) {
                boolean compact = canUseCompact(buffer, effect);
                buffer.writeByte(compact ? 0 : 1);
                (compact ? original : extended).encode(buffer, effect);
            }
        };
    }

    private static boolean canUseCompact(RegistryFriendlyByteBuf buffer, MobEffectInstance effect) {
        for (var current = effect; current != null;) {
            var carrier = (FormulaConsumerHooks.PersistentEffect) current;
            if (!carrier.apoth$getFormulaValues().isEmpty() || !carrier.apoth$getAttributeValues().isEmpty()
                    || carrier.apoth$hasFormulaSource()
                    || buffer.getConnectionType() != ConnectionType.NEOFORGE && !current.getCures().isEmpty()) return false;
            current = carrier.apoth$getHiddenEffect();
        }
        return true;
    }
}
