package com.example.spell_compiler.formula;

import net.minecraftforge.fml.util.thread.SidedThreadGroups;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaRuntimeDispatcherTest {
    @AfterEach
    void clear() {
        FormulaRuntimeDispatcher.clearServer();
        FormulaRuntimeDispatcher.clearClient();
    }

    @Test
    void serverAndClientSnapshotsStaySeparateAndInstallationCopiesInput() throws Exception {
        String key = FormulaRuntimeDispatcher.key("a/b/Spell", "damage(I)F");
        assertEquals("a.b.Spell#damage(I)F", key);
        FormulaRuntimeDispatcher.Invoker server = (self, arguments) -> 17f;
        FormulaRuntimeDispatcher.Invoker client = (self, arguments) -> 23f;
        Map<String, FormulaRuntimeDispatcher.Invoker> source = new HashMap<>();
        source.put(key, server);
        FormulaRuntimeDispatcher.replaceServer(source);
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, client));
        source.clear();
        assertEquals(23f, FormulaRuntimeDispatcher.invoke(FormulaRuntimeDispatcher.resolve(key, null), null, new Object[0]));
        FutureTask<FormulaRuntimeDispatcher.Invoker> lookup = new FutureTask<>(() -> FormulaRuntimeDispatcher.resolve(key, null));
        SidedThreadGroups.SERVER.newThread(lookup).start();
        assertEquals(17f, FormulaRuntimeDispatcher.invoke(lookup.get(5, TimeUnit.SECONDS), null, new Object[0]));
    }

    @Test
    void resolvedInvokerRemainsValidWhenReloadReplacesOrClearsSnapshot() {
        String key = FormulaRuntimeDispatcher.key("a.b.Spell", "damage(I)F");
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, (self, arguments) -> 11f));
        var resolved = FormulaRuntimeDispatcher.resolve(key, null);
        FormulaRuntimeDispatcher.clearClient();
        assertFalse(FormulaRuntimeDispatcher.hasOverride(key, null));
        assertEquals(11f, FormulaRuntimeDispatcher.invoke(resolved, null, new Object[]{3}));
        FormulaRuntimeDispatcher.replaceClient(Map.of(key, (self, arguments) -> 19f));
        assertEquals(11f, FormulaRuntimeDispatcher.invoke(resolved, null, new Object[]{3}));
        assertEquals(19f, FormulaRuntimeDispatcher.invoke(FormulaRuntimeDispatcher.resolve(key, null), null, new Object[]{3}));
    }

    @Test
    void checkedFailuresAreWrappedAndFatalErrorsRemainVisible() {
        var checked = assertThrows(IllegalStateException.class, () -> FormulaRuntimeDispatcher.invoke(
                installed((self, arguments) -> { throw new IOException("failure"); }), null, new Object[0]));
        assertInstanceOf(IOException.class, checked.getCause());
        for (Error expected : new Error[]{new AssertionError("assertion"), new OutOfMemoryError("memory"), new ThreadDeath()}) {
            var invoker = installed((self, arguments) -> { throw expected; });
            assertSame(expected, assertThrows(expected.getClass(), () -> FormulaRuntimeDispatcher.invoke(
                    invoker, null, new Object[0])));
        }
    }

    @Test
    void recoverableFailuresAndNonFiniteResultsDisableOnlyTheInstalledInvoker() {
        for (FormulaRuntimeDispatcher.Invoker delegate : new FormulaRuntimeDispatcher.Invoker[]{
                (self, arguments) -> { throw new ArithmeticException("/ by zero"); },
                (self, arguments) -> { throw new NoClassDefFoundError("delegate dependency"); },
                (self, arguments) -> Float.NaN,
                (self, arguments) -> Float.POSITIVE_INFINITY,
                (self, arguments) -> Double.NEGATIVE_INFINITY,
                (self, arguments) -> null}) {
            AtomicInteger calls = new AtomicInteger();
            var invoker = installed((self, arguments) -> {
                calls.incrementAndGet();
                return delegate.invoke(self, arguments);
            });
            assertNull(FormulaRuntimeDispatcher.invoke(invoker, null, new Object[0]));
            assertNull(FormulaRuntimeDispatcher.invoke(invoker, new Object(), new Object[]{7}));
            assertEquals(1, calls.get());
            assertTrue(FormulaRuntimeDispatcher.hasOverride("fixture", null));
        }
    }

    @Test
    void failedOldInvokerLogsOnceAndReloadCreatesIndependentHealthyState() {
        AtomicInteger calls = new AtomicInteger();
        ArithmeticException expected = new ArithmeticException("broken formula");
        FormulaRuntimeDispatcher.Invoker delegate = (self, arguments) -> {
            calls.incrementAndGet();
            if (arguments.length == 0) throw expected;
            return 23;
        };
        Logger logger = (Logger) LogManager.getLogger(FormulaRuntimeDispatcher.class);
        var events = new ArrayList<LogEvent>();
        var appender = new AbstractAppender("formula-runtime-test", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) { events.add(event.toImmutable()); }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            var old = installed(delegate);
            assertNull(FormulaRuntimeDispatcher.invoke(old, null, new Object[0]));
            assertNull(FormulaRuntimeDispatcher.invoke(old, null, new Object[]{1}));
            var fresh = installed(delegate);
            assertNotSame(old, fresh);
            assertEquals(23, FormulaRuntimeDispatcher.invoke(fresh, null, new Object[]{1}));
            assertNull(FormulaRuntimeDispatcher.invoke(old, null, new Object[0]));
            assertEquals(2, calls.get());
            assertEquals(1, events.size());
            assertSame(expected, events.get(0).getThrown());
            assertTrue(events.get(0).getMessage().getFormattedMessage().contains("fixture"));
            assertNull(FormulaRuntimeDispatcher.invoke(fresh, null, new Object[0]));
            assertEquals(2, events.size());
            assertSame(expected, events.get(1).getThrown());
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    @Test
    void lateFailureOfOldInvokerCannotDisableReplacementOrOtherMethods() {
        FormulaRuntimeDispatcher.replaceClient(Map.of("fixture", (self, arguments) -> {
            throw new ArithmeticException("stale formula");
        }));
        var old = FormulaRuntimeDispatcher.resolve("fixture", null);
        FormulaRuntimeDispatcher.replaceClient(Map.of(
                "fixture", (self, arguments) -> 31,
                "other", (self, arguments) -> 47));
        var replacement = FormulaRuntimeDispatcher.resolve("fixture", null);
        assertNull(FormulaRuntimeDispatcher.invoke(old, null, new Object[0]));
        assertSame(replacement, FormulaRuntimeDispatcher.resolve("fixture", null));
        assertEquals(31, FormulaRuntimeDispatcher.invoke(replacement, null, new Object[0]));
        assertEquals(47, FormulaRuntimeDispatcher.invoke(FormulaRuntimeDispatcher.resolve("other", null),
                null, new Object[0]));
    }

    @Test
    void successfulInvocationsContinueUsingTheirOwnCasterAndArguments() {
        AtomicInteger calls = new AtomicInteger();
        var invoker = installed((self, arguments) -> {
            calls.incrementAndGet();
            return (Integer) self + (Integer) arguments[0];
        });
        assertEquals(7, FormulaRuntimeDispatcher.invoke(invoker, 2, new Object[]{5}));
        assertEquals(15, FormulaRuntimeDispatcher.invoke(invoker, 4, new Object[]{11}));
        assertEquals(2, calls.get());
    }

    private static FormulaRuntimeDispatcher.Invoker installed(FormulaRuntimeDispatcher.Invoker delegate) {
        FormulaRuntimeDispatcher.replaceClient(Map.of("fixture", delegate));
        return FormulaRuntimeDispatcher.resolve("fixture", null);
    }
}
