package com.example.spell_compiler.formula;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.util.thread.EffectiveSide;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FormulaRuntimeDispatcher {
    @FunctionalInterface
    public interface Invoker {
        Number invoke(Object self, Object[] arguments) throws Throwable;
    }

    private static final Logger LOGGER = LogManager.getLogger(FormulaRuntimeDispatcher.class);
    private static volatile Map<String, Invoker> server = Map.of();
    private static volatile Map<String, Invoker> client = Map.of();

    private FormulaRuntimeDispatcher() {}

    public static String key(String owner, String methodKey) {
        return owner.replace('/', '.') + '#' + methodKey;
    }

    public static void replaceServer(Map<String, Invoker> overrides) { server = guarded(overrides); }

    public static void replaceClient(Map<String, Invoker> overrides) { client = guarded(overrides); }

    public static void clearServer() { server = Map.of(); }

    public static void clearClient() { client = Map.of(); }

    public static boolean hasOverride(String key, Object actor) { return resolve(key, actor) != null; }

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

    private static Map<String, Invoker> guarded(Map<String, Invoker> overrides) {
        Map<String, Invoker> result = new LinkedHashMap<>();
        overrides.forEach((key, invoker) -> result.put(key, new GuardedInvoker(key, invoker)));
        return Map.copyOf(result);
    }

    private static final class GuardedInvoker implements Invoker {
        private final String key;
        private final Invoker delegate;
        private final AtomicBoolean disabled = new AtomicBoolean();

        private GuardedInvoker(String key, Invoker delegate) {
            this.key = key;
            this.delegate = Objects.requireNonNull(delegate);
        }

        @Override
        public Number invoke(Object self, Object[] arguments) throws Throwable {
            if (disabled.get()) return null;
            try {
                Number value = Objects.requireNonNull(FormulaNativeValues.invokeConfigured(self, key, arguments, delegate),
                        "法术公式返回了空数值");
                if (!Double.isFinite(value.doubleValue()))
                    throw new ArithmeticException("法术公式返回了非有限数值：" + value);
                return value;
            } catch (RuntimeException | LinkageError failure) {
                if (disabled.compareAndSet(false, true))
                    LOGGER.error("[SpellFormula] {} 执行失败，本次加载期间回退到原生公式", key, failure);
                return null;
            }
        }
    }
}
