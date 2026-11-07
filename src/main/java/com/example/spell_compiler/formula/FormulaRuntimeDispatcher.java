package com.example.spell_compiler.formula;

import net.minecraft.world.entity.Entity;
import net.neoforged.fml.util.thread.EffectiveSide;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public final class FormulaRuntimeDispatcher {
    @FunctionalInterface
    public interface Invoker {
        Number invoke(Object self, Object[] arguments) throws Throwable;
    }

    private static final Logger LOGGER = LogManager.getLogger(FormulaRuntimeDispatcher.class);
    private static volatile Map<String, Invoker> server = Map.of();
    private static volatile Map<String, Invoker> client = Map.of();
    private static volatile BiConsumer<String, Invoker> serverFailure = (key, invoker) -> {};

    private FormulaRuntimeDispatcher() {}

    public static String key(String owner, String methodKey) {
        return owner.replace('/', '.') + '#' + methodKey;
    }

    public static void replaceServer(Map<String, Invoker> overrides) { server = guarded(overrides, true, Set.of()); }

    public static void replaceClient(Map<String, Invoker> overrides) { replaceClient(overrides, Set.of()); }

    static void replaceClient(Map<String, Invoker> overrides, Set<String> disabled) {
        if (!overrides.keySet().containsAll(disabled)) throw new IllegalArgumentException("Unknown disabled formula");
        client = guarded(overrides, false, disabled);
    }

    public static Set<String> disabledServerKeys() {
        return server.entrySet().stream().filter(entry -> entry.getValue() instanceof GuardedInvoker guarded && guarded.disabled.get())
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static void onServerFailure(BiConsumer<String, Invoker> listener) { serverFailure = Objects.requireNonNull(listener); }

    static boolean isCurrentServerInvoker(String key, Invoker invoker) { return server.get(key) == invoker; }

    static Object serverBindingsIdentity() { return server; }

    record Installed(Map<String, Invoker> server, Map<String, Invoker> client) {}

    static Installed checkpoint() { return new Installed(server, client); }

    static void restore(Installed previous) {
        server = previous.server();
        client = previous.client();
    }

    static void disableClient(String key) {
        if (!(client.get(key) instanceof GuardedInvoker invoker)) throw new IllegalArgumentException("Unknown disabled formula: " + key);
        invoker.disabled.set(true);
    }

    public static void clearServer() { server = Map.of(); }

    public static void clearClient() { client = Map.of(); }

    public static boolean hasOverride(String key, Object actor) { return resolve(key, actor) != null; }

    public static boolean isActive(String key, Object actor) {
        return resolveActive(key, actor) != null;
    }

    public static Invoker resolveActive(String key, Object actor) {
        Invoker invoker = resolve(key, actor);
        return invoker instanceof GuardedInvoker guarded && guarded.disabled.get() ? null : invoker;
    }

    public static Invoker resolve(String key, Object actor) {
        if (FormulaNativeValues.isEvaluatingOriginal()) return null;
        boolean clientSide = actor instanceof Entity entity ? entity.level().isClientSide : EffectiveSide.get().isClient();
        return (clientSide ? client : server).get(key);
    }

    public static Invoker resolveDeclared(String owner, String methodKey, Object self, Object actor) {
        if (self == null) return resolve(key(owner, methodKey), actor);
        Invoker invoker = resolve(key(self.getClass().getName(), methodKey), actor);
        return invoker != null && FormulaNativeValues.declaredBy(self, owner, methodKey) ? invoker : null;
    }

    public static Number invoke(Invoker invoker, Object self, Object[] arguments) {
        try {
            return invoker.invoke(self, arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("执行数据包法术公式失败", failure);
        }
    }

    private static Map<String, Invoker> guarded(Map<String, Invoker> overrides, boolean serverSide, Set<String> disabled) {
        Map<String, Invoker> result = new LinkedHashMap<>();
        overrides.forEach((key, invoker) -> result.put(key, new GuardedInvoker(key, invoker, serverSide, disabled.contains(key))));
        return Map.copyOf(result);
    }

    private static final class GuardedInvoker implements Invoker {
        private final String key;
        private final String methodKey;
        private final Invoker delegate;
        private final AtomicBoolean disabled = new AtomicBoolean();
        private final AtomicBoolean reported = new AtomicBoolean();
        private final boolean serverSide;

        private GuardedInvoker(String key, Invoker delegate, boolean serverSide, boolean disabled) {
            this.key = key;
            int separator = key.indexOf('#');
            this.methodKey = separator < 0 ? key : key.substring(separator + 1);
            this.delegate = Objects.requireNonNull(delegate);
            this.serverSide = serverSide;
            this.disabled.set(disabled);
        }

        @Override
        public Number invoke(Object self, Object[] arguments) throws Throwable {
            if (disabled.get()) return null;
            try {
                Number value = Objects.requireNonNull(FormulaNativeValues.invokeConfigured(self, methodKey, arguments, delegate),
                        "法术公式返回了空数值");
                if (!Double.isFinite(value.doubleValue()))
                    throw new ArithmeticException("法术公式返回了非有限数值：" + value);
                return value;
            } catch (RuntimeException | LinkageError failure) {
                if (serverSide && disabled.compareAndSet(false, true)) {
                    LOGGER.error("[SpellFormula] {} 执行失败，本次加载期间回退到原生公式", key, failure);
                    if (isCurrentServerInvoker(key, this)) serverFailure.accept(key, this);
                } else if (!serverSide && reported.compareAndSet(false, true)) {
                    LOGGER.error("[SpellFormula] {} 客户端本次求值失败，使用原生结果；服务端状态未改变", key, failure);
                }
                return null;
            }
        }
    }
}
