package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.Objects;

public final class FormulaAttributes {
    private FormulaAttributes() {}

    public enum Operation {
        ADDITION("addition", AttributeModifier.Operation.ADDITION),
        MULTIPLY_BASE("multiply_base", AttributeModifier.Operation.MULTIPLY_BASE),
        MULTIPLY_TOTAL("multiply_total", AttributeModifier.Operation.MULTIPLY_TOTAL);

        private final String id;
        private final AttributeModifier.Operation nativeOperation;

        Operation(String id, AttributeModifier.Operation nativeOperation) {
            this.id = id;
            this.nativeOperation = nativeOperation;
        }

        public String id() { return id; }

        public AttributeModifier.Operation nativeOperation() { return nativeOperation; }

        public static Operation parse(String value) {
            for (Operation operation : values()) if (operation.id.equals(value)) return operation;
            throw new IllegalArgumentException("未知属性操作：" + value);
        }

        public static Operation fromNative(String value) {
            for (Operation operation : values()) if (operation.nativeOperation.name().equals(value)) return operation;
            throw new IllegalArgumentException("未知原生属性操作：" + value);
        }

        public double toRaw(double value) { return this == ADDITION ? value : value / 100; }

        public double fromRaw(double value) { return this == ADDITION ? value : value * 100; }
    }

    public record Component(String key, String alias, String binding, String effectId, String sourceAttribute,
                            String modifierId, Operation operation) {
        public Component {
            Objects.requireNonNull(key);
            Objects.requireNonNull(alias);
            Objects.requireNonNull(binding);
            Objects.requireNonNull(effectId);
            Objects.requireNonNull(sourceAttribute);
            Objects.requireNonNull(modifierId);
            Objects.requireNonNull(operation);
        }

        public JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("key", key);
            result.addProperty("alias", alias);
            result.addProperty("binding", binding);
            result.addProperty("effectId", effectId);
            result.addProperty("sourceAttribute", sourceAttribute);
            result.addProperty("modifierId", modifierId);
            result.addProperty("operation", operation.id());
            return result;
        }
    }

    public record Config(String targetAttribute, Operation operation, String formula) {}

    public record Value(String sourceAttribute, String sourceModifierId, String targetAttribute, String modifierId,
                        Operation operation, double amount) {}
}
