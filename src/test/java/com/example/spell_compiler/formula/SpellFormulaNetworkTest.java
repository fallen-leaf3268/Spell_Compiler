package com.example.spell_compiler.formula;

import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SpellFormulaNetworkTest {
    @Test
    void snapshotValidationAcceptsEffectiveRulesBeforePacketConstruction() throws Exception {
        var catalog = SpellFormulaCatalog.load(getClass().getClassLoader());
        var fireball = catalog.spells().stream().filter(spell -> spell.id().equals("irons_spellbooks:fireball")).findFirst().orElseThrow();
        var override = SpellFormulaFiles.parse(fireball, "{\"parameters\":{\"baseManaCost\":63}}");
        var snapshot = SpellFormulaFiles.snapshot(Map.of(fireball.className(), override), java.util.List.of());
        var resources = SpellFormulaFiles.resourcesForSnapshot(snapshot);
        assertDoesNotThrow(() -> SpellFormulaNetwork.validateSnapshot(snapshot));
        assertEquals(resources, SpellFormulaFiles.resourcesForSnapshot(snapshot));
        assertEquals(1, SpellFormulaNetwork.packets(resources, 1).size());
    }

    @Test
    void snapshotValidationRejectsLegalPaddedFormulasThatExceedTheTotalSyncBudget() throws Exception {
        var catalog = SpellFormulaCatalog.load(getClass().getClassLoader());
        Map<String, SpellFormulaFiles.SpellOverride> overrides = new LinkedHashMap<>();
        for (var spell : catalog.spells()) {
            if (spell.editableFormulas().isEmpty()) continue;
            JsonObject json = new JsonObject();
            JsonObject formulas = new JsonObject();
            for (var method : spell.editableFormulas()) {
                String expression = "(" + method.readableExpression() + ")+0";
                formulas.addProperty(method.alias(), expression + "\t".repeat(8192 - expression.length()));
            }
            json.add("formulas", formulas);
            overrides.put(spell.className(), SpellFormulaFiles.parse(spell, json));
        }
        var snapshot = SpellFormulaFiles.snapshot(overrides, java.util.List.of());
        var resources = SpellFormulaFiles.resourcesForSnapshot(snapshot);
        assertTrue(resources.values().stream().allMatch(value -> value.toString().getBytes(StandardCharsets.UTF_8).length
                <= SpellFormulaNetwork.MAX_FILE_BYTES));
        var failure = assertThrows(IllegalArgumentException.class, () -> SpellFormulaNetwork.validateSnapshot(snapshot));
        assertEquals("Spell formula snapshot is too large", failure.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SpellFormulaNetwork.packets(resources, 1));
    }

    @Test
    void validationAndEncodingIncludeKeysAndVarIntHeadersInTheSnapshotLimit() {
        Map<String, JsonElement> resources = new LinkedHashMap<>();
        for (char key = 'a'; key < 'h'; key++)
            resources.put(String.valueOf(key), new JsonPrimitive("x".repeat(SpellFormulaNetwork.MAX_FILE_BYTES - 2)));
        resources.put("h", new JsonPrimitive("x".repeat(262_101)));
        assertDoesNotThrow(() -> SpellFormulaNetwork.validateResources(resources));
        assertEquals(SpellFormulaNetwork.MAX_SNAPSHOT_BYTES, SpellFormulaNetwork.packets(resources, 1).get(0).totalLength());
        resources.put("h", new JsonPrimitive("x".repeat(262_102)));
        var failure = assertThrows(IllegalArgumentException.class, () -> SpellFormulaNetwork.validateResources(resources));
        var encodingFailure = assertThrows(IllegalArgumentException.class, () -> SpellFormulaNetwork.packets(resources, 2));
        assertEquals("Spell formula snapshot is too large", failure.getMessage());
        assertEquals(failure.getMessage(), encodingFailure.getMessage());
    }

    @Test
    void validationCountsUtf8BytesForFileAndKeyLimits() {
        String value = "魔".repeat(87_380) + "xx";
        String key = "法".repeat(85) + "a";
        Map<String, JsonElement> resources = Map.of(key, new JsonPrimitive(value));
        assertEquals(SpellFormulaNetwork.MAX_FILE_BYTES, resources.get(key).toString().getBytes(StandardCharsets.UTF_8).length);
        assertEquals(256, key.getBytes(StandardCharsets.UTF_8).length);
        assertDoesNotThrow(() -> SpellFormulaNetwork.validateResources(resources));
        assertDoesNotThrow(() -> SpellFormulaNetwork.packets(resources, 1));
        assertThrows(IllegalArgumentException.class,
                () -> SpellFormulaNetwork.validateResources(Map.of(key, new JsonPrimitive(value + "x"))));
        assertThrows(IllegalArgumentException.class,
                () -> SpellFormulaNetwork.validateResources(Map.of(key + "x", new JsonPrimitive("x"))));
        assertThrows(IllegalArgumentException.class,
                () -> SpellFormulaNetwork.validateResources(Map.of("", new JsonPrimitive("x"))));
    }

    @Test
    void validationAcceptsTheMaximumResourceCountAndRejectsOneMore() {
        Map<String, JsonElement> resources = largeResources(512, 1);
        assertDoesNotThrow(() -> SpellFormulaNetwork.validateResources(resources));
        assertEquals(512, SpellFormulaNetwork.packets(resources, 1).get(0).resources().size());
        resources.put("test:extra", new JsonPrimitive("x"));
        assertThrows(IllegalArgumentException.class, () -> SpellFormulaNetwork.validateResources(resources));
        assertThrows(IllegalArgumentException.class, () -> SpellFormulaNetwork.packets(resources, 2));
    }

    @Test
    void effectiveRulesRoundTripWithoutRequiringClientFiles() throws Exception {
        var catalog = SpellFormulaCatalog.load(getClass().getClassLoader());
        var fireball = catalog.spells().stream().filter(spell -> spell.id().equals("irons_spellbooks:fireball")).findFirst().orElseThrow();
        var overrides = SpellFormulaFiles.parse(fireball,
                "{\"advanced\":{\"max_level\":6},\"parameters\":{\"baseManaCost\":63},\"formulas\":{\"damage\":\"min("
                        + fireball.formulas().stream().filter(method -> method.alias().equals("damage")).findFirst().orElseThrow().readableExpression()
                        + ", 25)\"}}");
        var snapshot = SpellFormulaFiles.snapshot(Map.of(fireball.className(), overrides), java.util.List.of());
        var packet = new SpellFormulaNetwork.SnapshotPacket(SpellFormulaFiles.resourcesForSnapshot(snapshot));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buffer);
            var received = SpellFormulaNetwork.SnapshotPacket.decode(buffer);
            assertEquals(snapshot.fingerprint(), SpellFormulaFiles.parseResources(received.resources(), catalog).fingerprint());
            assertEquals(0, buffer.readableBytes());
            assertFalse(received.resources().values().toString().contains("_说明"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void duplicateResourcesAndInvalidCountsAreRejected() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(2);
            for (int index = 0; index < 2; index++) {
                buffer.writeByteArray("irons_spellbooks:fire/fireball".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                buffer.writeByteArray("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertThrows(IllegalArgumentException.class, () -> packet(buffer).resources());
            buffer.clear();
            buffer.writeVarInt(513);
            assertThrows(IllegalArgumentException.class, () -> packet(buffer).resources());
        } finally {
            buffer.release();
        }
    }

    @Test
    void packetCopiesItsSourceResources() {
        var source = JsonParser.parseString("{\"parameters\":{\"baseManaCost\":63}}");
        var packet = new SpellFormulaNetwork.SnapshotPacket(Map.of("irons_spellbooks:fire/fireball", source));
        source.getAsJsonObject().getAsJsonObject("parameters").addProperty("baseManaCost", 99);
        assertEquals(63, packet.resources().get("irons_spellbooks:fire/fireball").getAsJsonObject()
                .getAsJsonObject("parameters").get("baseManaCost").getAsInt());
    }

    @Test
    void snapshotLargerThanVanillaPayloadLimitReassemblesOnlyAfterAllParts() {
        Map<String, JsonElement> resources = largeResources(6, 230_000);
        var packets = SpellFormulaNetwork.packets(resources, 11);
        assertTrue(packets.size() > 4);
        var assembler = new SpellFormulaNetwork.Reassembler();
        Map<String, JsonElement> received = null;
        for (int index = 0; index < packets.size(); index++) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                packets.get(index).encode(buffer);
                assertTrue(buffer.readableBytes() <= SpellFormulaNetwork.MAX_PART_BYTES);
                var decoded = SpellFormulaNetwork.SnapshotPacket.decode(buffer);
                received = assembler.accept(decoded);
                if (index + 1 < packets.size()) assertNull(received);
            } finally {
                buffer.release();
            }
        }
        assertEquals(resources, received);
    }

    @Test
    void limitsCountUtf8BytesAndMultibyteCharactersMayCrossPartBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> SpellFormulaNetwork.packets(
                Map.of("test:large", new JsonPrimitive("魔".repeat(90_000))), 1));
        Map<String, JsonElement> resources = Map.of("test:a", new JsonPrimitive("魔".repeat(80_000)),
                "test:b", new JsonPrimitive("法".repeat(80_000)));
        var packets = SpellFormulaNetwork.packets(resources, 2);
        assertEquals(2, packets.size());
        var assembler = new SpellFormulaNetwork.Reassembler();
        assertNull(assembler.accept(packets.get(0)));
        assertEquals(resources, assembler.accept(packets.get(1)));
        assertThrows(IllegalArgumentException.class, () -> SpellFormulaNetwork.packets(largeResources(9, 240_000), 3));
        assertFalse(SpellFormulaNetwork.packets(largeResources(8, 240_000), 4).isEmpty());
    }

    @Test
    void emptySnapshotStillSendsOnePartAndLogoutResetAllowsANewConnection() {
        var packets = SpellFormulaNetwork.packets(Map.of(), 1);
        assertEquals(1, packets.size());
        var assembler = new SpellFormulaNetwork.Reassembler();
        assertEquals(Map.of(), assembler.accept(packets.get(0)));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(packets.get(0)));
        assembler.reset();
        assertEquals(Map.of(), assembler.accept(packets.get(0)));
    }

    @Test
    void duplicateOutOfOrderAndInconsistentPartsAreRejected() {
        var packets = SpellFormulaNetwork.packets(largeResources(2, 230_000), 1);
        assertEquals(2, packets.size());
        assertThrows(IllegalArgumentException.class, () -> new SpellFormulaNetwork.Reassembler().accept(packets.get(1)));
        var duplicate = new SpellFormulaNetwork.Reassembler();
        assertNull(duplicate.accept(packets.get(0)));
        assertThrows(IllegalArgumentException.class, () -> duplicate.accept(packets.get(0)));
        var inconsistent = new SpellFormulaNetwork.Reassembler();
        assertNull(inconsistent.accept(packets.get(0)));
        var second = packets.get(1);
        var altered = new SpellFormulaNetwork.SnapshotPacket(second.generation(), second.index(), second.count(),
                second.totalLength() + 1, Arrays.copyOf(second.data(), second.data().length + 1));
        assertThrows(IllegalArgumentException.class, () -> inconsistent.accept(altered));
        assertThrows(IllegalArgumentException.class, () -> new SpellFormulaNetwork.SnapshotPacket(1, 0, 99, 1, new byte[1]));
    }

    @Test
    void newerGenerationDiscardsIncompleteOldBatchAndOldPartsCannotRestoreIt() {
        var old = SpellFormulaNetwork.packets(largeResources(2, 230_000), 1);
        var current = SpellFormulaNetwork.packets(Map.of(), 2);
        var assembler = new SpellFormulaNetwork.Reassembler();
        assertNull(assembler.accept(old.get(0)));
        assertEquals(Map.of(), assembler.accept(current.get(0)));
        assertNull(assembler.accept(old.get(1)));
    }

    @Test
    void invalidUtf8TrailingBytesAndOversizedPartsAreRejected() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(1);
            buffer.writeByteArray(new byte[]{(byte) 0xff});
            buffer.writeByteArray(new byte[]{'{', '}'});
            assertThrows(IllegalArgumentException.class, () -> packet(buffer).resources());
            buffer.clear();
            new SpellFormulaNetwork.SnapshotPacket(Map.of()).encode(buffer);
            buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> SpellFormulaNetwork.SnapshotPacket.decode(buffer));
            buffer.clear();
            buffer.writeZero(SpellFormulaNetwork.MAX_PART_BYTES + 1);
            assertThrows(IllegalArgumentException.class, () -> SpellFormulaNetwork.SnapshotPacket.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static SpellFormulaNetwork.SnapshotPacket packet(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return new SpellFormulaNetwork.SnapshotPacket(1, 0, 1, bytes.length, bytes);
    }

    private static Map<String, JsonElement> largeResources(int count, int valueSize) {
        Map<String, JsonElement> resources = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) resources.put("test:school/spell_" + index,
                new JsonPrimitive("x".repeat(valueSize)));
        return resources;
    }
}
