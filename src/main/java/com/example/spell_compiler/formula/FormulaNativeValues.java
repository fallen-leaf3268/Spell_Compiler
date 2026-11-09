package com.example.spell_compiler.formula;

import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.util.thread.EffectiveSide;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaNativeValues {
    private record Evaluation(Object spell, String method, Object[] arguments, boolean original, boolean configured) {}
    private static final ThreadLocal<Evaluation> EVALUATION = new ThreadLocal<>();
    private static final String ENTITY_POWER = "getEntityPowerMultiplier(Lnet/minecraft/world/entity/LivingEntity;)F";
    private static final Set<String> BASE_FIELDS = Set.of("baseManaCost", "manaCostPerLevel", "baseSpellPower", "spellPowerPerLevel", "castTime");
    private static final Logger LOGGER = LogManager.getLogger(FormulaNativeValues.class);
    private static final Set<String> FAILURES = ConcurrentHashMap.newKeySet();
    private record MethodMetadata(SpellFormulaCatalog.Method definition, int levelIndex,
                                  String nativeKey, String originalKey) {}
    private record SpellMetadata(SpellFormulaCatalog.Spell definition, Map<String, MethodMetadata> methods) {}
    private static volatile Map<String, SpellMetadata> definitions = Map.of();
    private static volatile Map<AbstractSpell, Map<String, Object>> serverConfig = Map.of();
    private static volatile Map<AbstractSpell, Map<String, Object>> clientConfig = Map.of();
    record ConfigState(Map<AbstractSpell, Map<String, Object>> server, Map<AbstractSpell, Map<String, Object>> client) {}

    static synchronized ConfigState configCheckpoint() { return new ConfigState(serverConfig, clientConfig); }

    static synchronized void restoreConfig(ConfigState previous) {
        serverConfig = previous.server();
        clientConfig = previous.client();
    }

    static synchronized void restoreClientConfig(ConfigState previous) { clientConfig = previous.client(); }
    private static final ClassValue<Map<String, FormulaRuntimeDispatcher.Invoker>> NATIVE = new ClassValue<>() {
        @Override protected Map<String, FormulaRuntimeDispatcher.Invoker> computeValue(Class<?> owner) {
            return new ConcurrentHashMap<>();
        }
    };

    private FormulaNativeValues() {}

    public static void initialize(SpellFormulaCatalog catalog) {
        Map<String, SpellMetadata> selected = new LinkedHashMap<>();
        catalog.spells().forEach(spell -> {
            Map<String, MethodMetadata> methods = new LinkedHashMap<>();
            for (var method : spell.formulas()) {
                String key = method.key();
                String nativeKey = key + '#' + method.strictExpression() + '#';
                methods.put(key, new MethodMetadata(method, levelIndex(method), nativeKey + false, nativeKey + true));
            }
            selected.put(spell.className(), new SpellMetadata(spell, Map.copyOf(methods)));
        });
        definitions = Map.copyOf(selected);
    }

    private static int levelIndex(SpellFormulaCatalog.Method method) {
        if (method.declaringClass().equals(FormulaSiteValues.class.getName())
                || !"I".equals(method.variables().get("level"))) return -1;
        Type[] types = Type.getArgumentTypes(method.descriptor());
        int levelIndex = -1;
        for (int index = 0; index < types.length; index++) {
            if (!types[index].equals(Type.INT_TYPE)) continue;
            if (levelIndex >= 0) return -1;
            levelIndex = index;
        }
        return levelIndex;
    }

    public static boolean isEvaluatingOriginal() {
        Evaluation evaluation = EVALUATION.get();
        return evaluation != null && evaluation.original();
    }

    static boolean declaredBy(Object spell, String owner, String methodKey) {
        var definition = definitions.get(spell.getClass().getName());
        var method = definition == null ? null : definition.methods().get(methodKey);
        return method != null && method.definition().declaringClass().equals(owner.replace('/', '.'));
    }

    public static boolean isModified(Object spell, Object caster) {
        return !isEvaluatingOriginal() && override(spell, caster) != null;
    }

    public static SpellFormulaFiles.SpellOverride override(Object spell, Object caster) {
        return (client(caster) ? SpellFormulaState.clientSnapshot() : SpellFormulaState.serverSnapshot())
                .byClass().get(spell.getClass().getName());
    }

    public static Number evaluateNative(Object spell, String methodKey, Object[] arguments) throws Throwable {
        return evaluate(spell, methodKey, arguments, false);
    }

    public static Number evaluateOriginal(Object spell, String methodKey, Object[] arguments) throws Throwable {
        return evaluate(spell, methodKey, arguments, true);
    }

    static Number invokeConfigured(Object spell, String methodKey, Object[] arguments,
                                   FormulaRuntimeDispatcher.Invoker invoker) throws Throwable {
        return invoke(spell, methodKey, arguments, isEvaluatingOriginal(), true, invoker);
    }

    private static Number evaluate(Object spell, String methodKey, Object[] arguments, boolean original) throws Throwable {
        var definition = definitions.get(spell.getClass().getName());
        if (definition == null) throw new IllegalArgumentException("找不到原生数值目录：" + spell.getClass().getName());
        var metadata = definition.methods().get(methodKey);
        if (metadata == null) throw new IllegalArgumentException("找不到原生数值方法：" + methodKey);
        var method = metadata.definition();
        var invoker = NATIVE.get(spell.getClass()).computeIfAbsent(original ? metadata.originalKey() : metadata.nativeKey(), ignored -> {
            var compiled = FormulaDelegateFactory.compileNativeSpell(spell.getClass(), definition.definition(),
                    Map.of(methodKey, '(' + method.readableExpression() + ")+0"), !original);
            var selected = compiled.get(methodKey);
            if (selected == null) throw new IllegalArgumentException("无法创建原生数值求值器：" + methodKey);
            return selected;
        });
        return invoke(spell, methodKey, arguments, original, invoker);
    }

    private static Number invoke(Object spell, String methodKey, Object[] arguments, boolean original,
                                 FormulaRuntimeDispatcher.Invoker invoker) throws Throwable {
        return invoke(spell, methodKey, arguments, original, false, invoker);
    }

    private static Number invoke(Object spell, String methodKey, Object[] arguments, boolean original,
                                 boolean configured, FormulaRuntimeDispatcher.Invoker invoker) throws Throwable {
        Evaluation previous = EVALUATION.get();
        EVALUATION.set(new Evaluation(spell, methodKey, arguments, original, configured));
        try {
            return invoker.invoke(spell, arguments);
        } finally {
            if (previous == null) EVALUATION.remove();
            else EVALUATION.set(previous);
        }
    }

    public static float originalPower(Object spell, int level, Object caster) {
        try {
            return FormulaFunctions.requireFinite(evaluateOriginal(spell,
                    "getSpellPower(ILnet/minecraft/world/entity/Entity;)F", new Object[]{level, caster}).floatValue());
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            if (!(failure instanceof RuntimeException || failure instanceof LinkageError))
                throw new IllegalStateException("读取原生法术强度失败", failure);
            report(spell, "originalPower", caster, failure);
            return 0;
        }
    }

    public static float originalEntityPower(Object spell, Object caster) {
        try {
            var invoker = NATIVE.get(spell.getClass()).computeIfAbsent(ENTITY_POWER + "#original", ignored -> {
                var source = new ClassNode();
                String resource = '/' + AbstractSpell.class.getName().replace('.', '/') + ".class";
                try (var stream = AbstractSpell.class.getResourceAsStream(resource)) {
                    if (stream == null) throw new IllegalArgumentException("找不到实体强度倍率原类资源");
                    new ClassReader(stream.readAllBytes()).accept(source, 0);
                } catch (java.io.IOException failure) {
                    throw new IllegalArgumentException("无法读取实体强度倍率原类资源", failure);
                }
                var method = source.methods.stream().filter(value -> (value.name + value.desc).equals(ENTITY_POWER))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("找不到实体强度倍率原方法"));
                var analysis = AutomaticFormulaExtractor.analyzeRuntime(source, method, FormulaClassRepository.of(source));
                if (!analysis.supported()) throw new IllegalArgumentException("无法提取实体强度倍率：" + analysis.rejection());
                var compiled = FormulaDelegateFactory.compile(AbstractSpell.class,
                        Map.of(ENTITY_POWER, '(' + analysis.formula().readableExpression() + ")+0"));
                var selected = compiled.get(ENTITY_POWER);
                if (selected == null) throw new IllegalArgumentException("无法创建实体强度倍率原值求值器");
                return selected;
            });
            return FormulaFunctions.requireFinite(invoke(spell, ENTITY_POWER, new Object[]{caster}, true, invoker).floatValue());
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            if (!(failure instanceof RuntimeException || failure instanceof LinkageError))
                throw new IllegalStateException("读取原生实体强度倍率失败", failure);
            report(spell, "originalEntityPower", caster, failure);
            return 0;
        }
    }

    static Integer nativeLevel(Object spell) {
        Evaluation evaluation = EVALUATION.get();
        if (evaluation == null || evaluation.configured() || evaluation.method().startsWith("getSpellPower(")) return null;
        return currentLevel(spell);
    }

    static Integer currentLevel(Object spell) {
        Evaluation evaluation = EVALUATION.get();
        if (evaluation == null || evaluation.original() || spell == null || evaluation.spell() != spell) return null;
        var definition = definitions.get(spell.getClass().getName());
        if (definition == null) return null;
        var method = definition.methods().get(evaluation.method());
        if (method == null) return null;
        int levelIndex = method.levelIndex();
        return levelIndex >= 0 && levelIndex < evaluation.arguments().length
                && evaluation.arguments()[levelIndex] instanceof Integer level ? level : null;
    }

    static Number originalField(Object spell, String name) {
        if (!isEvaluatingOriginal() || !(spell instanceof AbstractSpell source) || !BASE_FIELDS.contains(name)) return null;
        var definition = definitions.get(spell.getClass().getName());
        if (definition == null || !definition.definition().parameters().has(name)) return null;
        return SpellFormulaRuntime.originalParameter(source, name, definition.definition().parameters().get(name).getAsInt());
    }

    @SuppressWarnings("unchecked")
    public static <T> T readConfig(AbstractSpell spell, Object parameter) {
        if (isEvaluatingOriginal()) return originalConfig(spell, parameter, actor());
        return (T) FormulaSpellConfig.read(spell, FormulaSpellConfig.key(parameter));
    }

    @SuppressWarnings("unchecked")
    public static <T> T originalConfig(AbstractSpell spell, Object parameter, Object caster) {
        String key = FormulaSpellConfig.key(parameter);
        var saved = (client(caster) ? clientConfig : serverConfig).get(spell);
        if (saved != null && saved.containsKey(key)) return (T) saved.get(key);
        return (T) FormulaSpellConfig.read(spell, key);
    }

    static synchronized void captureConfig(AbstractSpell spell, Map<String, Object> values, boolean clientSide, boolean preserveExisting) {
        var updated = new LinkedHashMap<>(clientSide ? clientConfig : serverConfig);
        var merged = new LinkedHashMap<>(values);
        if (preserveExisting) merged.putAll(updated.getOrDefault(spell, Map.of()));
        updated.put(spell, Map.copyOf(merged));
        if (clientSide) clientConfig = Map.copyOf(updated);
        else serverConfig = Map.copyOf(updated);
    }

    static void resetConfig(boolean clientSide) {
        if (clientSide) clientConfig = Map.of();
        else serverConfig = Map.of();
    }

    public static Map<String, FormulaNativeConfigNetwork.Values> exportOriginalConfig(SpellFormulaFiles.Snapshot snapshot) {
        Map<String, FormulaNativeConfigNetwork.Values> exported = new LinkedHashMap<>();
        for (var override : snapshot.byClass().values()) {
            AbstractSpell spell = SpellRegistry.getSpell(override.spell().id());
            var values = serverConfig.get(spell);
            if (values == null) throw new IllegalStateException("未捕获服务器原生法术配置：" + override.spell().id());
            exported.put(override.spell().id(), new FormulaNativeConfigNetwork.Values(
                    ((Number) values.get("mana_cost_multiplier")).doubleValue(),
                    ((Number) values.get("power_multiplier")).doubleValue(),
                    ((Number) values.get("cooldown_in_seconds")).doubleValue()));
        }
        return Map.copyOf(exported);
    }

    public static synchronized void applyOriginalConfig(Map<String, FormulaNativeConfigNetwork.Values> values) {
        Map<AbstractSpell, Map<String, Object>> updated = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            AbstractSpell spell = SpellRegistry.REGISTRY.get().getValue(new ResourceLocation(entry.getKey()));
            var definition = spell == null ? null : definitions.get(spell.getClass().getName());
            if (definition == null || !definition.definition().id().equals(entry.getKey()))
                throw new IllegalArgumentException("原生法术配置没有匹配的法术：" + entry.getKey());
            var value = entry.getValue();
            updated.put(spell, Map.of("mana_cost_multiplier", value.manaMultiplier(),
                    "power_multiplier", value.powerMultiplier(), "cooldown_in_seconds", value.cooldownSeconds()));
        }
        clientConfig = Map.copyOf(updated);
    }

    static void clearFailures() {
        FAILURES.clear();
    }

    public static void report(Object spell, String method, Object caster, Throwable failure) {
        String key = spell.getClass().getName() + '#' + method + '#' + client(caster);
        if (FAILURES.add(key)) LOGGER.error("[SpellFormula] {} 数值计算无效，使用原生安全结果", key, failure);
    }

    private static Object actor() {
        Evaluation evaluation = EVALUATION.get();
        if (evaluation != null) for (Object argument : evaluation.arguments())
            if (argument instanceof Entity) return argument;
        return null;
    }

    public static Object currentActor() {
        return actor();
    }

    private static boolean client(Object caster) {
        return caster instanceof Entity entity ? entity.level().isClientSide : EffectiveSide.get().isClient();
    }
}
