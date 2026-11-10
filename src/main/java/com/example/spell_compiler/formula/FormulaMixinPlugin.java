package com.example.spell_compiler.formula;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import com.example.spell_compiler.formula.rules.FormulaBindingPlan;
import com.example.spell_compiler.formula.rules.FormulaNativeRules;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FormulaMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger(FormulaMixinPlugin.class);
    private static final Map<String, Set<String>> HOOKED_METHODS = new ConcurrentHashMap<>();
    private static final Set<String> AUTOMATIC_HOOKS = ConcurrentHashMap.newKeySet();
    private Map<String, Set<String>> targets = Map.of();
    private Map<String, SpellFormulaCatalog.Spell> definitions = Map.of();
    private FormulaAddonDiscovery.Discovery addons;
    private final Map<String, Set<String>> compatibilityMethods = new ConcurrentHashMap<>();
    private String mixinPackage;
    private String dynamicMixin;
    private Set<String> dynamicTargets = Set.of();
    private FormulaClassRepository bindingSource;
    private FormulaBindingPlan bindingPlan;

    public FormulaMixinPlugin() {}

    FormulaMixinPlugin(SpellFormulaCatalog catalog) {
        initialize(catalog);
    }

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage;
        try {
            addons = FormulaAddonDiscovery.discover();
            initialize(FormulaLoadedCatalog.prepare(FormulaMixinPlugin.class.getClassLoader(), addons));
            initializeAutomaticSites();
        } catch (IOException | RuntimeException | LinkageError failure) {
            FormulaLoadedCatalog.unavailable();
            targets = Map.of();
            definitions = Map.of();
            dynamicTargets = Set.of();
            addons = null;
            FormulaDynamicMixin.restore();
            LOGGER.warn("[SpellFormula] 无法确认实际法术来源，本次启动保留原生行为", failure);
        }
    }

    private void initializeAutomaticSites() {
        var repository = addons.repository();
        var usage = new FormulaUsage(repository, addons.sources());
        for (var spell : repository.baselineCoverage() ? definitions.values() : List.<SpellFormulaCatalog.Spell>of()) {
            Set<String> reserved = spell.formulas().stream().filter(method -> method.consumptionFingerprint().isEmpty())
                    .map(SpellFormulaCatalog.Method::alias).collect(java.util.stream.Collectors.toSet());
            var exposedMethods = spell.formulas().stream().filter(method -> method.consumptionFingerprint().isEmpty())
                    .map(method -> new FormulaDataFlow.MethodId(method.declaringClass(), method.name(), method.descriptor()))
                    .collect(java.util.stream.Collectors.toSet());
            try {
                FormulaContextualLevelProof.registerBindings(repository, spell.className(), exposedMethods);
                FormulaEffectAttributeControls.register(repository, spell.className());
                var getters = new com.google.gson.JsonArray();
                for (var method : spell.formulas()) {
                    var getter = new com.google.gson.JsonObject();
                    getter.addProperty("declaringClass", method.declaringClass());
                    getter.addProperty("name", method.name());
                    getter.addProperty("descriptor", method.descriptor());
                    getter.addProperty("alias", method.alias());
                    getters.add(getter);
                }
                FormulaManaRatioBridge.registerGetters(repository, spell.className(), getters);
                var automatic = FormulaAutomaticSites.scan(repository, spell.className(), reserved, exposedMethods);
                FormulaAutomaticSites.register(automatic);
                FormulaSourceControls.register(repository, spell.className(), automatic.sites());
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("[SpellFormula] {} 无法扫描自动消费入口，保留既有入口", spell.className(), failure);
            }
        }
        for (String name : addons.spellClasses().stream().sorted().toList()) {
            try {
                var owner = repository.classNode(name);
                var metadata = new com.google.gson.JsonObject();
                metadata.add("parameters", new com.google.gson.JsonObject());
                FormulaCatalogGenerator.extractSpell(repository, owner, metadata, "", usage, true,
                        new com.google.gson.JsonArray());
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("[SpellFormula] {} 无法扫描附属自动消费入口，保留既有入口", name, failure);
            }
        }
        FormulaManaRatioBridge.initialize(repository);
        var names = new java.util.LinkedHashSet<String>();
        addons.spellClasses().stream().map(name -> name.replace('/', '.')).forEach(names::add);
        if (!repository.baselineCoverage())
            addons.nativeSpellClasses().stream().map(name -> name.replace('/', '.')).forEach(names::add);
        FormulaAutomaticSites.targetClasses().stream().filter(name -> !targets.containsKey(name)).forEach(names::add);
        names.addAll(FormulaManaRatioBridge.targetClasses());
        names.addAll(bindingPlan().targets());
        names.removeAll(targets.keySet());
        dynamicTargets = Set.copyOf(names);
    }

    private void initialize(SpellFormulaCatalog catalog) {
        targets = addons != null && !addons.repository().baselineCoverage() ? Map.of() : bindings(catalog);
        Map<String, SpellFormulaCatalog.Spell> values = new LinkedHashMap<>();
        catalog.spells().forEach(spell -> values.put(spell.className(), spell));
        definitions = Map.copyOf(values);
    }

    private static Map<String, Set<String>> bindings(SpellFormulaCatalog catalog) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (var spell : catalog.spells()) {
            Set<String> declared = Set.copyOf(spell.formulas().stream()
                    .filter(method -> method.declaringClass().equals(spell.className()))
                    .map(SpellFormulaCatalog.Method::key).toList());
            result.put(spell.className(), declared);
        }
        FormulaNativeRules.baselineTargets().forEach(target -> result.putIfAbsent(target, Set.of()));
        return Map.copyOf(result);
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (FormulaLoadedCatalog.disabled()) return false;
        if (isDynamic(mixinClassName)) return dynamicTargets.contains(targetClassName);
        return targets.containsKey(targetClassName);
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        FormulaDynamicMixin.restore();
    }

    @Override
    public List<String> getMixins() {
        if (dynamicTargets.isEmpty()) {
            FormulaDynamicMixin.restore();
            return null;
        }
        if (dynamicMixin == null) {
            try {
                dynamicMixin = FormulaDynamicMixin.install(mixinPackage, dynamicTargets);
            } catch (RuntimeException | LinkageError failure) {
                FormulaDynamicMixin.restore();
                LOGGER.warn("[SpellFormula] 无法提供附属法术的动态Mixin资源，本次启动保留原生法术入口", failure);
                return null;
            }
        }
        return List.of(dynamicMixin);
    }

    public static Map<String, Set<String>> hookedMethods() {
        return Map.copyOf(HOOKED_METHODS);
    }

    public static Set<String> automaticHooks() {
        return AUTOMATIC_HOOKS.stream().filter(key -> {
            var site = FormulaAutomaticSites.find(key);
            return site == null || switch (site.contextMode()) {
                case DIRECT -> true;
                case ENTITY -> FormulaEntityFlow.hasVerifiedSource(site.definition().spellClassName());
                case SCOPED -> FormulaCastContext.hasVerifiedSource(site.definition().spellClassName());
            };
        }).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static void applyAutomatic(String targetClassName, ClassNode targetClass) {
        try {
            AUTOMATIC_HOOKS.addAll(FormulaAutomaticSites.apply(targetClassName, targetClass));
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("[SpellFormula] {} 自动消费入口校验失败，保留原生行为", targetClassName, failure);
        }
    }

    private boolean isDynamic(String mixinClassName) {
        return dynamicMixin != null && mixinClassName.equals(mixinPackage + '.' + dynamicMixin);
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        FormulaDynamicMixin.restore();
        if (FormulaLoadedCatalog.disabled()) return;
        if (isDynamic(mixinClassName)) {
            applyAddon(targetClassName, targetClass);
            return;
        }
        Set<String> selected = targets.get(targetClassName);
        if (selected == null) return;
        var definition = definitions.get(targetClassName);
        var missing = new java.util.HashSet<>(selected);
        targetClass.methods.forEach(method -> missing.remove(method.name + method.desc));
        if (!missing.isEmpty()) throw new IllegalStateException("公式目标方法不存在：" + targetClassName + '#' + missing);
        applyAutomatic(targetClassName, targetClass);
        FormulaDurationSites.apply(targetClass, definition);
        FormulaDirectPowerSites.apply(targetClass, definition);
        applyRules(targetClassName, targetClass);
        List<MethodNode> updated = new ArrayList<>(targetClass.methods);
        var unmatched = new java.util.HashSet<>(selected);
        for (int index = 0; index < updated.size(); index++) {
            MethodNode method = updated.get(index);
            String key = method.name + method.desc;
            if (!selected.contains(key)) continue;
            if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0)
                throw new IllegalStateException("公式目标没有可执行方法体：" + targetClassName + '#' + key);
            Type result = Type.getReturnType(method.desc);
            if (result.getSort() != Type.INT && result.getSort() != Type.LONG
                    && result.getSort() != Type.FLOAT && result.getSort() != Type.DOUBLE)
                throw new IllegalStateException("公式目标不是数值方法：" + targetClassName + '#' + key);
            var binding = definition.formulas().stream().filter(value -> value.key().equals(key)).findFirst().orElseThrow();
            MethodNode replacement = FormulaMethodInstrumentation.instrument(targetClass.name, method,
                    FormulaMethodInstrumentation.TargetKind.BASELINE, FormulaDurationSites.isDuration(binding));
            updated.set(index, replacement);
            unmatched.remove(key);
        }
        if (!unmatched.isEmpty()) throw new IllegalStateException("公式目标方法不存在：" + targetClassName + '#' + unmatched);
        targetClass.methods = updated;
        compatibilityMethods.put(targetClass.name, Set.copyOf(selected));
        applyDamageTooltips(targetClass, definition);
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        FormulaManaRatioBridge.verify(targetClassName, targetClass);
        if (!FormulaExplosionBindings.verifyHook(targetClassName, targetClass))
            LOGGER.warn("[SpellFormula] {} 爆炸来源挂钩未保留，隐藏关联的爆炸伤害公式", targetClassName);
        if (!FormulaEntityFlow.verifyCaptureHooks(targetClassName, targetClass))
            LOGGER.warn("[SpellFormula] {} 实体施法来源挂钩未保留，隐藏关联的实体公式", targetClassName);
        if (!FormulaCastContext.verifyRootHooks(targetClassName, targetClass))
            LOGGER.warn("[SpellFormula] {} 同步施法上下文挂钩未保留，隐藏关联的辅助方法公式", targetClassName);
        FormulaAutomaticHookRetention.pruneMissing(targetClassName, targetClass, AUTOMATIC_HOOKS,
                key -> LOGGER.warn("[SpellFormula] {} 自动消费挂钩未保留，隐藏该入口", key));
        Set<String> selected = compatibilityMethods.get(targetClass.name);
        if (selected == null) return;
        FormulaMethodCompatibility.observeTransformed(targetClass, selected);
    }


    private void applyAddon(String targetClassName, ClassNode targetClass) {
        if (addons == null) return;
        applyAutomatic(targetClassName, targetClass);
        applyRules(targetClassName, targetClass);
        try {
            FormulaManaRatioBridge.apply(targetClassName, targetClass, addons.repository());
        } catch (RuntimeException | LinkageError failure) {
            FormulaManaRatioBridge.failed(targetClassName, failure.toString());
            LOGGER.warn("[SpellFormula] {} 无法确认回魔效果消费，保留原生行为并隐藏该入口", targetClassName, failure);
        }
        if (!addons.spellClasses().contains(targetClass.name)
                && (!addons.nativeSpellClasses().contains(targetClass.name) || addons.repository().baselineCoverage())) return;
        boolean nativeSpell = addons.nativeSpellClasses().contains(targetClass.name) && !addons.repository().baselineCoverage();
        var definition = definitions.get(targetClassName);
        if (nativeSpell && definition != null) {
            var protectedClass = new ClassNode();
            targetClass.accept(protectedClass);
            try {
                FormulaDurationSites.apply(protectedClass, definition);
                targetClass.methods = protectedClass.methods;
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("[SpellFormula] {} 无法确认原生数值返回保护，保留实际原始方法", targetClassName, failure);
            }
        }
        Set<String> numeric = Set.of();
        try {
            numeric = FormulaAddonNumericSites.apply(targetClassName, targetClass);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("[SpellFormula] {} 无法接入附属消费数值，本次启动保留这些数值的原生行为", targetClassName, failure);
        }
        Set<String> selected = new java.util.LinkedHashSet<>();
        List<MethodNode> updated = new ArrayList<>(targetClass.methods);
        for (int index = 0; index < updated.size(); index++) {
            MethodNode method = updated.get(index);
            Type result = Type.getReturnType(method.desc);
            if (result.getSort() != Type.INT && result.getSort() != Type.LONG
                    && result.getSort() != Type.FLOAT && result.getSort() != Type.DOUBLE) continue;
            if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC)) != 0
                    || method.instructions.size() == 0) continue;
            String key = method.name + method.desc;
            try {
                var binding = definition == null ? null : definition.formulas().stream()
                        .filter(value -> value.key().equals(key)).findFirst().orElse(null);
                boolean duration = nativeSpell && binding != null && FormulaDurationSites.isDuration(binding)
                        && method.desc.equals("(ILnet/minecraft/world/entity/LivingEntity;)" + result.getDescriptor())
                        && (method.access & Opcodes.ACC_STATIC) == 0
                        && (result.getSort() == Type.INT || result.getSort() == Type.FLOAT);
                var targetKind = nativeSpell ? FormulaMethodInstrumentation.TargetKind.DISCOVERED_NATIVE
                        : FormulaMethodInstrumentation.TargetKind.ADDON;
                MethodNode replacement = FormulaMethodInstrumentation.instrument(targetClass.name, method, targetKind, duration);
                updated.set(index, replacement);
                selected.add(key);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn("[SpellFormula] {}#{} 无法建立附属公式挂钩，保留原方法", targetClassName, key, failure);
            }
        }
        targetClass.methods = updated;
        compatibilityMethods.put(targetClass.name, Set.copyOf(selected));
        Set<String> available = new java.util.LinkedHashSet<>(selected);
        available.addAll(numeric);
        HOOKED_METHODS.put(targetClassName, Set.copyOf(available));
        applyDamageTooltips(targetClass, definition);
    }

    private void applyDamageTooltips(ClassNode target, SpellFormulaCatalog.Spell definition) {
        try {
            FormulaDamageTooltips.apply(target, definition, addons == null ? FormulaClassRepository.of(target) : addons.repository());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("[SpellFormula] {} 无法确认伤害或属性说明来源，保留原生说明", target.name, failure);
        }
    }

    private FormulaBindingPlan bindingPlan() {
        FormulaClassRepository repository = addons.repository();
        if (bindingPlan == null || bindingSource != repository) {
            bindingPlan = FormulaNativeRules.prepare(repository);
            FormulaNativeRules.install(bindingPlan);
            bindingSource = repository;
        }
        return bindingPlan;
    }

    private void applyRules(String owner, ClassNode target) {
        if (addons == null) {
            FormulaNativeRules.applyBaseline(owner, target);
            return;
        }
        for (var result : bindingPlan().apply(owner, target)) {
            FormulaNativeRules.observe(result);
            if (!result.available()) LOGGER.warn("[SpellFormula] {} 规则 {} 未接入，保留该分支原生行为：{}",
                    owner, result.rule(), result.reason());
        }
    }

}
