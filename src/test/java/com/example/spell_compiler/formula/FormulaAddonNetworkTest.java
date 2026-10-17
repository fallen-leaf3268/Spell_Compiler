package com.example.spell_compiler.formula;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaAddonNetworkTest {
    private static final String CATALOG = "0123456789abcdef".repeat(4);
    private static final String OTHER_CATALOG = "fedcba9876543210".repeat(4);
    private static final Map<String, FormulaNativeConfigNetwork.Values> ORIGINALS = Map.of(
            "iss_csw:pinning_stake", new FormulaNativeConfigNetwork.Values(1.25, 2.5, 3));

    @Test
    void originalConfigurationCodecCarriesItsCatalogIdentityAndImmutableValues() {
        Map<String, FormulaNativeConfigNetwork.Values> source = new LinkedHashMap<>(ORIGINALS);
        var packet = packet(7, source, CATALOG);
        source.clear();
        var buffer = buffer();
        try {
            packet.encode(buffer);
            var decoded = FormulaNativeConfigNetwork.Packet.decode(buffer);
            assertEquals(CATALOG, fingerprint(decoded));
            assertEquals(packet, decoded);
            assertEquals(ORIGINALS, decoded.values());
            assertFalse(buffer.isReadable());
            assertThrows(UnsupportedOperationException.class, decoded.values()::clear);
        } finally {
            buffer.release();
        }
    }

    @Test
    void catalogIdentityIsIncludedInWireByteAccountingAtTheMaximumIdentifierAndEntryCounts() {
        Map<String, FormulaNativeConfigNetwork.Values> values = new LinkedHashMap<>();
        for (int index = 0; index < FormulaNativeConfigNetwork.MAX_ENTRIES; index++)
            values.put("test:" + "x".repeat(248) + String.format(java.util.Locale.ROOT, "%03d", index),
                    new FormulaNativeConfigNetwork.Values(1, 1, 1));
        int expectedBytes = 8 + FriendlyByteBuf.getVarIntSize(64) + 64 + FriendlyByteBuf.getVarIntSize(values.size());
        for (String id : values.keySet()) {
            int length = id.getBytes(StandardCharsets.UTF_8).length;
            expectedBytes += FriendlyByteBuf.getVarIntSize(length) + length + 24;
        }
        var buffer = buffer();
        try {
            packet(9, values, CATALOG).encode(buffer);
            assertEquals(expectedBytes, buffer.readableBytes());
            assertTrue(expectedBytes <= FormulaNativeConfigNetwork.MAX_PACKET_BYTES);
            assertEquals(9, buffer.readLong());
            assertEquals(CATALOG, buffer.readUtf(64));
            buffer.readerIndex(0);
            assertEquals(values, FormulaNativeConfigNetwork.Packet.decode(buffer).values());
        } finally {
            buffer.release();
        }
    }

    @Test
    void malformedCatalogIdentitiesAreRejectedBeforeTheyCanBecomePackets() {
        for (String invalid : new String[]{null, "", "a".repeat(63), "a".repeat(65), "g".repeat(64),
                "α".repeat(64), " " + CATALOG, CATALOG + " "})
            assertThrows(IllegalArgumentException.class, () -> packet(1, ORIGINALS, invalid), String.valueOf(invalid));
    }

    @Test
    void aDifferentCatalogCannotReplacePendingOriginalsOrAdvanceTheirAcceptedGeneration() {
        var staging = new FormulaNativeConfigNetwork.Staging();
        accept(staging, packet(5, ORIGINALS, CATALOG), CATALOG);
        assertThrows(IllegalArgumentException.class,
                () -> accept(staging, packet(11, Map.of(), OTHER_CATALOG), CATALOG));
        assertThrows(IllegalArgumentException.class, () -> staging.complete(11));
        assertEquals(ORIGINALS, staging.complete(5));
        accept(staging, packet(6, Map.of(), CATALOG), CATALOG);
        assertEquals(Map.of(), staging.complete(6));
    }

    @Test
    void evenAnOlderPacketMustPassTheCatalogGateBeforeItCanBeIgnoredAsStale() {
        var staging = new FormulaNativeConfigNetwork.Staging();
        accept(staging, packet(5, ORIGINALS, CATALOG), CATALOG);
        assertThrows(IllegalArgumentException.class,
                () -> accept(staging, packet(4, Map.of(), OTHER_CATALOG), CATALOG));
        assertEquals(ORIGINALS, staging.complete(5));
    }

    @Test
    void theProductionEntryRejectsMismatchedCatalogsBeforeTouchingOriginalsOrPartialFormulaChunks() throws Exception {
        var originalField = SpellFormulaNetwork.class.getDeclaredField("ORIGINAL_PENDING");
        var chunksField = SpellFormulaNetwork.class.getDeclaredField("PENDING");
        originalField.setAccessible(true);
        chunksField.setAccessible(true);
        var originals = (FormulaNativeConfigNetwork.Staging) originalField.get(null);
        var chunks = (SpellFormulaNetwork.Reassembler) chunksField.get(null);
        originals.reset();
        chunks.reset();
        try {
            String expected = SpellFormulaState.catalogFingerprint();
            String wrong = expected.equals(CATALOG) ? OTHER_CATALOG : CATALOG;
            var resources = largeResources();
            var parts = SpellFormulaNetwork.packets(resources, 31);
            assertTrue(parts.size() > 1);
            SpellFormulaNetwork.stageOriginalConfig(packet(31, ORIGINALS, expected));
            assertNull(chunks.accept(parts.get(0)));
            assertThrows(IllegalArgumentException.class,
                    () -> SpellFormulaNetwork.stageOriginalConfig(packet(99, Map.of(), wrong)));
            Map<String, JsonElement> completed = null;
            for (int index = 1; index < parts.size(); index++) completed = chunks.accept(parts.get(index));
            assertEquals(resources, completed);
            assertEquals(ORIGINALS, originals.complete(31));
            SpellFormulaNetwork.stageOriginalConfig(packet(32, Map.of(), expected));
            assertEquals(Map.of(), originals.complete(32));
        } finally {
            originals.reset();
            chunks.reset();
        }
    }

    @Test
    void matchingCatalogsStillCompleteMultipartSnapshotsAndAcceptLowerGenerationsAfterLogoutReset() {
        var originals = new FormulaNativeConfigNetwork.Staging();
        var chunks = new SpellFormulaNetwork.Reassembler();
        var resources = largeResources();
        var parts = SpellFormulaNetwork.packets(resources, 14);
        accept(originals, packet(14, ORIGINALS, CATALOG), CATALOG);
        Map<String, JsonElement> completed = null;
        for (int index = 0; index < parts.size(); index++) {
            completed = chunks.accept(parts.get(index));
            if (index + 1 < parts.size()) assertNull(completed);
        }
        assertEquals(resources, completed);
        assertEquals(ORIGINALS, originals.complete(14));
        originals.reset();
        chunks.reset();
        accept(originals, packet(1, Map.of(), CATALOG), CATALOG);
        assertEquals(Map.of(), chunks.accept(SpellFormulaNetwork.packets(Map.of(), 1).get(0)));
        assertEquals(Map.of(), originals.complete(1));
    }

    private static Map<String, JsonElement> largeResources() {
        return Map.of("iss_csw:spells/nature/pinning_stake.json", new JsonPrimitive("x".repeat(170_000)),
                "irons_spellbooks:spells/fire/fireball.json", new JsonPrimitive("y".repeat(170_000)));
    }

    private static FormulaNativeConfigNetwork.Packet packet(long generation,
            Map<String, FormulaNativeConfigNetwork.Values> values, String catalogFingerprint) {
        try {
            return FormulaNativeConfigNetwork.Packet.class.getConstructor(long.class, Map.class, String.class)
                    .newInstance(generation, values, catalogFingerprint);
        } catch (NoSuchMethodException absent) {
            return new FormulaNativeConfigNetwork.Packet(generation, values);
        } catch (InvocationTargetException failure) {
            throw propagate(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String fingerprint(FormulaNativeConfigNetwork.Packet packet) {
        try {
            return (String) packet.getClass().getMethod("catalogFingerprint").invoke(packet);
        } catch (NoSuchMethodException absent) {
            return null;
        } catch (InvocationTargetException failure) {
            throw propagate(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void accept(FormulaNativeConfigNetwork.Staging staging,
            FormulaNativeConfigNetwork.Packet packet, String expectedFingerprint) {
        try {
            var method = FormulaNativeConfigNetwork.Staging.class.getDeclaredMethod("accept",
                    FormulaNativeConfigNetwork.Packet.class, String.class);
            method.setAccessible(true);
            method.invoke(staging, packet, expectedFingerprint);
        } catch (NoSuchMethodException absent) {
            staging.accept(packet);
        } catch (InvocationTargetException failure) {
            throw propagate(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new RuntimeException(failure);
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }
}
