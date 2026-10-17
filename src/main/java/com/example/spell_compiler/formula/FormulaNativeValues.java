package com.example.spell_compiler.formula;

import io.redspace.ironsspellbooks.api.config.SpellConfigHolder;
import io.redspace.ironsspellbooks.api.config.SpellConfigManager;
import io.redspace.ironsspellbooks.api.config.SpellConfigParameter;
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
    private static volatile Map<String, SpellFormulaCatalog.Spell> definitions = Map.of();
    private static volatile Map<AbstractSpell, Map<SpellConfigParameter<?>, Object>> serverConfig = Map.of();
    private static volatile Map<AbstractSpell, Map<SpellConfigParameter<?>, Object>> clientConfig = Map.of();
    private static final ClassValue<Map<String, FormulaRuntimeDispatcher.Invoker>> NATIVE = new ClassValue<>() {
        @Override protected Map<String, FormulaRuntimeDispatcher.Invoker> computeValue(Class<?> owner) {
            return new ConcurrentHashMap<>();
        }
    };

    private FormulaNativeValues() {}

    public static void initialize(SpellFormulaCatalog catalog) {
        Map<String, SpellFormulaCatalog.Spell> selected = new LinkedHashMap<>();
        catalog.spells().forEach(spell -> selected.put(spell.className(), spell));
        definitions = Map.copyOf(selected);
    }

    public static boolean isEvaluatingOriginal() {
        Evaluation evaluation = EVALUATION.get();
        return evaluation != null && evaluation.original();
    }

    static boolean declaredBy(Object spell, String owner, String methodKey) {
        var definition = definitions.get(spell.getClass().getName());
        return definition != null && definition.formulas().stream().anyMatch(method -> method.key().equals(methodKey)
                && method.declaringClass().equals(owner.replace('/', '.')));
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

    static Number invokeConfigured(Object spell, String dispatchKey, Object[] arguments,
                                   FormulaRuntimeDispatcher.Invoker invoker) throws Throwable {
        int separator = dispatchKey.indexOf('#');
        String methodKey = separator < 0 ? dispatchKey : dispatchKey.substring(separator + 1);
        return invoke(spell, methodKey, arguments, isEvaluatingOriginal(), true, invoker);
    }

    private static Number evaluate(Object spell, String methodKey, Object[] arguments, boolean original) throws Throwable {
        var definition = definitions.get(spell.getClass().getName());
        if (definition == null) throw new IllegalArgumentException("找不到原生数值目录：" + spell.getClass().getName());
        var method = definition.formulas().stream().filter(value -> value.key().equals(methodKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到原生数值方法：" + methodKey));
        var invoker = NATIVE.get(spell.getClass()).computeIfAbsent(methodKey + '#' + method.strictExpression() + '#' + original, ignored -> {
            var compiled = FormulaDelegateFactory.compileNativeSpell(spell.getClass(), definition,
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
        var method = definition.formulas().stream().filter(value -> value.key().equals(evaluation.method()))
                .findFirst().orElse(null);
        if (method == null || method.declaringClass().equals(FormulaSiteValues.class.getName())
                || !"I".equals(method.variables().get("level"))) return null;
        Type[] types = Type.getArgumentTypes(method.descriptor());
        int levelIndex = -1;
        for (int index = 0; index < types.length; index++) {
            if (!types[index].equals(Type.INT_TYPE)) continue;
            if (levelIndex >= 0) return null;
            levelIndex = index;
        }
        return levelIndex >= 0 && levelIndex < evaluation.arguments().length
                && evaluation.arguments()[levelIndex] instanceof Integer level ? level : null;
    }

    static Number originalField(Object spell, String name) {
        if (!isEvaluatingOriginal() || !(spell instanceof AbstractSpell source) || !BASE_FIELDS.contains(name)) return null;
        var definition = definitions.get(spell.getClass().getName());
        if (definition == null || !definition.parameters().has(name)) return null;
        return SpellFormulaRuntime.originalParameter(source, name, definition.parameters().get(name).getAsInt());
    }

    public static <T> T readConfig(AbstractSpell spell, SpellConfigParameter<T> parameter) {
        if (isEvaluatingOriginal()) return originalConfig(spell, parameter, actor());
        return SpellConfigManager.getSpellConfigValue(spell, parameter);
    }

    @SuppressWarnings("unchecked")
    public static <T> T originalConfig(AbstractSpell spell, SpellConfigParameter<T> parameter, Object caster) {
        var saved = (client(caster) ? clientConfig : serverConfig).get(spell);
        if (saved != null && saved.containsKey(parameter)) return (T) saved.get(parameter);
        return SpellConfigManager.getSpellConfigValue(spell, parameter);
    }

    static synchronized void captureConfig(AbstractSpell spell, SpellConfigHolder holder) {
        Map<SpellConfigParameter<?>, Object> values = new LinkedHashMap<>();
        for (SpellConfigParameter<?> parameter : List.of(SpellConfigParameter.ENABLED, SpellConfigParameter.MAX_LEVEL,
                SpellConfigParameter.MIN_RARITY, SpellConfigParameter.ALLOW_CRAFTING, SpellConfigParameter.SCHOOL,
                SpellConfigParameter.COOLDOWN_IN_SECONDS, SpellConfigParameter.POWER_MULTIPLIER, SpellConfigParameter.MANA_MULTIPLIER)) {
            Object value = holder.get(parameter);
            if (value != null) values.put(parameter, value);
        }
        boolean clientSide = EffectiveSide.get().isClient();
        var updated = new LinkedHashMap<>(clientSide ? clientConfig : serverConfig);
        updated.put(spell, Map.copyOf(values));
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
                    ((Number) values.get(SpellConfigParameter.MANA_MULTIPLIER)).doubleValue(),
                    ((Number) values.get(SpellConfigParameter.POWER_MULTIPLIER)).doubleValue(),
                    ((Number) values.get(SpellConfigParameter.COOLDOWN_IN_SECONDS)).doubleValue()));
        }
        return Map.copyOf(exported);
    }

    public static synchronized void applyOriginalConfig(Map<String, FormulaNativeConfigNetwork.Values> values) {
        Map<AbstractSpell, Map<SpellConfigParameter<?>, Object>> updated = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            AbstractSpell spell = SpellRegistry.REGISTRY.get().getValue(ResourceLocation.parse(entry.getKey()));
            var definition = spell == null ? null : definitions.get(spell.getClass().getName());
            if (definition == null || !definition.id().equals(entry.getKey()))
                throw new IllegalArgumentException("原生法术配置没有匹配的法术：" + entry.getKey());
            var value = entry.getValue();
            updated.put(spell, Map.of(SpellConfigParameter.MANA_MULTIPLIER, value.manaMultiplier(),
                    SpellConfigParameter.POWER_MULTIPLIER, value.powerMultiplier(),
                    SpellConfigParameter.COOLDOWN_IN_SECONDS, value.cooldownSeconds()));
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
