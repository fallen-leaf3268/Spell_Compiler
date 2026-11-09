package com.example.spell_compiler.formula;

import com.google.gson.JsonElement;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.util.thread.EffectiveSide;
import net.minecraftforge.common.MinecraftForge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class FormulaSpellConfig {
    private static final List<String> KEYS = List.of("enabled", "max_level", "min_rarity", "allow_crafting", "school",
            "cooldown_in_seconds", "power_multiplier", "mana_cost_multiplier");
    private static final Map<String, String> MODERN_FIELDS = Map.of("enabled", "ENABLED", "max_level", "MAX_LEVEL",
            "min_rarity", "MIN_RARITY", "allow_crafting", "ALLOW_CRAFTING", "school", "SCHOOL",
            "cooldown_in_seconds", "COOLDOWN_IN_SECONDS", "power_multiplier", "POWER_MULTIPLIER", "mana_cost_multiplier", "MANA_MULTIPLIER");
    private static final class Selected {
        private static final Backend VALUE = select();
    }
    private interface Backend {
        Object read(Object spell, String key);
        void initialize();
        void apply(SpellFormulaFiles.Snapshot snapshot, boolean clientSide);
        void reset(boolean clientSide);
        void resetNative();
        default boolean ready() { return true; }
        default String key(Object parameter) { throw new IllegalArgumentException("Unknown spell configuration parameter: " + parameter); }
    }

    private FormulaSpellConfig() {}

    public static Object read(AbstractSpell spell, String key) {
        if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown spell configuration key: " + key);
        return Selected.VALUE.read(spell, key);
    }

    public static double number(AbstractSpell spell, String key) { return ((Number) read(spell, key)).doubleValue(); }
    public static String key(Object parameter) {
        if (parameter instanceof String name && KEYS.contains(name)) return name;
        return Selected.VALUE.key(parameter);
    }
    public static void initialize() { Selected.VALUE.initialize(); }
    public static void apply(SpellFormulaFiles.Snapshot snapshot, boolean clientSide) { Selected.VALUE.apply(snapshot, clientSide); }
    public static void reset(boolean clientSide) { Selected.VALUE.reset(clientSide); }
    public static void resetNative() { Selected.VALUE.resetNative(); }
    public static boolean ready() { return Selected.VALUE.ready(); }

    private static Backend select() {
        ClassLoader loader = FormulaSpellConfig.class.getClassLoader();
        try {
            if (loader.getResource("io/redspace/ironsspellbooks/api/config/SpellConfigManager.class") != null)
                return new Modern(loader);
            return new Legacy(Class.forName("io.redspace.ironsspellbooks.config.ServerConfigs", false, loader),
                    Class.forName("io.redspace.ironsspellbooks.config.ServerConfigs$SpellConfigParameters", false, loader), AbstractSpell.class);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported Iron's spell configuration API", failure);
        }
    }

    private static Object value(String key, JsonElement value) {
        return switch (key) {
            case "enabled", "allow_crafting" -> value.getAsBoolean();
            case "max_level" -> value.getAsInt();
            case "min_rarity" -> SpellRarity.valueOf(value.getAsString().toUpperCase(Locale.ROOT));
            case "school" -> {
                var school = SchoolRegistry.REGISTRY.get().getValue(new ResourceLocation(value.getAsString()));
                if (school == null) throw new IllegalArgumentException("Unknown spell school: " + value.getAsString());
                yield school;
            }
            case "cooldown_in_seconds", "power_multiplier", "mana_cost_multiplier" -> value.getAsDouble();
            default -> throw new IllegalArgumentException("Unknown spell configuration key: " + key);
        };
    }

    private static Map<String, Object> changes(SpellFormulaFiles.SpellOverride override) {
        var values = new LinkedHashMap<String, Object>();
        override.advanced().forEach((key, json) -> values.put(key, value(key, json)));
        override.parameters().forEach((key, json) -> { if (KEYS.contains(key)) values.put(key, value(key, json)); });
        return Map.copyOf(values);
    }

    private static void resetRarity(AbstractSpell spell) {
        try {
            Method method;
            try { method = AbstractSpell.class.getMethod("resetRarityWeights"); }
            catch (NoSuchMethodException absent) {
                Field field = AbstractSpell.class.getDeclaredField("rarityWeights");
                if (!List.class.isAssignableFrom(field.getType())) throw new IllegalStateException("Unknown rarity cache type");
                field.setAccessible(true);
                field.set(spell, null);
                return;
            }
            invoke(method, spell);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot reset spell rarity cache", failure); }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Spell configuration API failed: " + method, cause);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot call spell configuration API: " + method, failure); }
    }

    private static final class Modern implements Backend {
        private final Class<?> eventClass;
        private final Class<?> parameterType;
        private final Method read, holderGet, holderSet, instance, refresh, sync, eventSpell;
        private final Constructor<?> packet;
        private final Field eventConfig;
        private final Map<String, Object> parameters = new LinkedHashMap<>();
        private final Map<Object, String> keys = new IdentityHashMap<>();
        private boolean registered;
        private boolean parametersReady;

        private Modern(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> manager = Class.forName("io.redspace.ironsspellbooks.api.config.SpellConfigManager", false, loader);
            Class<?> parameter = Class.forName("io.redspace.ironsspellbooks.api.config.SpellConfigParameter", false, loader);
            parameterType = parameter;
            Class<?> holder = Class.forName("io.redspace.ironsspellbooks.api.config.SpellConfigHolder", false, loader);
            Class<?> packetClass = Class.forName("io.redspace.ironsspellbooks.network.SyncJsonConfigPacket", false, loader);
            eventClass = Class.forName("io.redspace.ironsspellbooks.api.config.ModifyDefaultConfigValuesEvent", false, loader);
            read = manager.getMethod("getSpellConfigValue", AbstractSpell.class, parameter);
            holderGet = holder.getMethod("get", parameter);
            holderSet = holder.getMethod("set", parameter, Object.class);
            instance = manager.getMethod("getInstance");
            refresh = manager.getMethod("handleServerConfigUpdate");
            sync = manager.getMethod("handleClientSync", packetClass);
            packet = packetClass.getConstructor(Map.class);
            eventSpell = eventClass.getMethod("getSpell");
            eventConfig = eventClass.getDeclaredField("config");
            eventConfig.setAccessible(true);
        }
        private synchronized void ensureParameters() {
            if (parametersReady) return;
            try {
                for (String key : KEYS) {
                    Object value = parameterType.getField(MODERN_FIELDS.get(key)).get(null);
                    parameters.put(key, value); keys.put(value, key);
                }
                parametersReady = true;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot read modern spell configuration parameters", failure);
            }
        }
        @Override public Object read(Object spell, String key) {
            ensureParameters();
            return invoke(read, null, spell, parameters.get(key));
        }
        @Override public String key(Object parameter) {
            ensureParameters();
            String key = keys.get(parameter);
            if (key == null) throw new IllegalArgumentException("Unknown modern spell configuration parameter: " + parameter);
            return key;
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        @Override public synchronized void initialize() {
            if (registered) return;
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class) eventClass, (Consumer) (Consumer<Object>) this::modify);
            registered = true;
        }
        private void modify(Object event) {
            ensureParameters();
            AbstractSpell spell = (AbstractSpell) invoke(eventSpell, event);
            Object holder;
            try { holder = eventConfig.get(event); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot read native spell configuration event", failure); }
            var original = new LinkedHashMap<String, Object>();
            parameters.forEach((key, parameter) -> original.put(key, invoke(holderGet, holder, parameter)));
            FormulaNativeValues.captureConfig(spell, original, EffectiveSide.get().isClient(), false);
            if (!EffectiveSide.get().isServer()) return;
            var override = SpellFormulaState.serverSnapshot().byClass().get(spell.getClass().getName());
            if (override == null) return;
            changes(override).forEach((key, value) -> invoke(holderSet, holder, parameters.get(key), value));
            if (!override.advanced().isEmpty()) resetRarity(spell);
        }
        @Override public void apply(SpellFormulaFiles.Snapshot snapshot, boolean clientSide) {
            if (!clientSide) invoke(refresh, invoke(instance, null));
        }
        @Override public void reset(boolean clientSide) {}
        @Override public void resetNative() {
            try { invoke(sync, invoke(instance, null), packet.newInstance(Map.of())); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot restore native spell configuration", failure); }
        }
    }

    static final class Legacy implements Backend {
        private static final Map<String, String> GETTERS = Map.of("enabled", "enabled", "max_level", "maxLevel",
                "min_rarity", "minRarity", "allow_crafting", "allowCrafting", "school", "school",
                "power_multiplier", "powerMultiplier", "mana_cost_multiplier", "manaMultiplier");
        private final Method getConfig, getConfigs, spellId, defaults;
        private final Constructor<?> constructor;
        private final Field seconds;
        private final Field spec;
        private final Method isLoaded;
        private final Map<String, Method> getters = new LinkedHashMap<>();
        private final Map<String, Object> originals = new LinkedHashMap<>();
        private final Set<String> absent = new LinkedHashSet<>();
        private final Set<AbstractSpell> rarityChanged = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private SpellFormulaFiles.Snapshot server, client;
        private Legacy(Class<?> configs, Class<?> holder, Class<?> spell) throws ReflectiveOperationException {
            getConfig = configs.getMethod("getSpellConfig", spell);
            getConfigs = configs.getMethod("getSpellConfigs");
            spec = configs.getField("SPEC");
            isLoaded = spec.getType().getMethod("isLoaded");
            spellId = spell.getMethod("getSpellId");
            defaults = spell.getMethod("getDefaultConfig");
            var arguments = new ArrayList<Class<?>>(); arguments.add(defaults.getReturnType());
            for (int index = 0; index < 8; index++) arguments.add(Supplier.class);
            constructor = holder.getDeclaredConstructor(arguments.toArray(Class[]::new)); constructor.setAccessible(true);
            seconds = holder.getDeclaredField("CS");
            if (!Supplier.class.isAssignableFrom(seconds.getType())) throw new NoSuchFieldException("Legacy cooldown CS is not a Supplier");
            seconds.setAccessible(true);
            for (var entry : GETTERS.entrySet()) getters.put(entry.getKey(), holder.getMethod(entry.getValue()));
        }
        @Override public Object read(Object spell, String key) { return from(invoke(getConfig, null, spell), key); }
        @Override public boolean ready() {
            try { return (Boolean) invoke(isLoaded, spec.get(null)); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot inspect native spell configuration readiness", failure); }
        }
        private Object from(Object holder, String key) {
            if (key.equals("cooldown_in_seconds")) {
                try { return ((Supplier<?>) seconds.get(holder)).get(); }
                catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot read native cooldown seconds", failure); }
            }
            Method getter = getters.get(key);
            if (getter == null) throw new IllegalArgumentException("Unknown legacy spell configuration key: " + key);
            return invoke(getter, holder);
        }
        @SuppressWarnings("unchecked")
        private Map<String, Object> map() { return (Map<String, Object>) invoke(getConfigs, null); }
        private Object baseline(Object spell) {
            String id = (String) invoke(spellId, spell);
            if (!originals.containsKey(id)) {
                if (!map().containsKey(id)) absent.add(id);
                originals.put(id, invoke(getConfig, null, spell));
            }
            return originals.get(id);
        }
        private void install(Object spell, Map<String, Object> changes) {
            Object original = baseline(spell);
            var values = new ArrayList<Object>(); values.add(invoke(defaults, spell));
            for (String key : List.of("enabled", "school", "max_level", "min_rarity", "mana_cost_multiplier",
                    "power_multiplier", "cooldown_in_seconds", "allow_crafting")) {
                values.add((Supplier<Object>) () -> {
                    Object result = changes.containsKey(key) ? changes.get(key) : from(original, key);
                    if (key.equals("school")) {
                        if (result instanceof String string) return string;
                        try { return invoke(result.getClass().getMethod("getId"), result).toString(); }
                        catch (NoSuchMethodException failure) { throw new IllegalStateException("Unknown legacy school API", failure); }
                    }
                    return result;
                });
            }
            Object replacement;
            try { replacement = constructor.newInstance(values.toArray()); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot construct legacy spell configuration", failure); }
            map().put((String) invoke(spellId, spell), replacement);
        }
        private void restore() {
            var values = map();
            originals.forEach((id, original) -> { if (absent.contains(id)) values.remove(id); else values.put(id, original); });
            rarityChanged.forEach(FormulaSpellConfig::resetRarity);
            rarityChanged.clear();
        }
        @Override public void initialize() {}
        @Override public synchronized void apply(SpellFormulaFiles.Snapshot snapshot, boolean clientSide) {
            if (clientSide) client = snapshot; else server = snapshot;
            rebuild();
        }
        private void rebuild() {
            restore();
            var active = server != null ? server : client;
            if (active == null) return;
            for (var override : active.byClass().values()) {
                AbstractSpell spell = SpellRegistry.REGISTRY.get().getValue(new ResourceLocation(override.spell().id()));
                if (spell == null) throw new IllegalArgumentException("Unregistered legacy spell: " + override.spell().id());
                Object original = baseline(spell);
                var values = new LinkedHashMap<String, Object>();
                for (String key : KEYS) values.put(key, from(original, key));
                FormulaNativeValues.captureConfig(spell, values, server == null, server == null);
                var changes = changes(override);
                install(spell, changes);
                if (!override.advanced().isEmpty()) { resetRarity(spell); rarityChanged.add(spell); }
            }
        }
        @Override public synchronized void reset(boolean clientSide) {
            if (clientSide) client = null; else server = null;
            rebuild();
        }
        @Override public void resetNative() { restore(); }
    }

    private record AoeAccess(Method delay, Method getInterval, Method setInterval, Field interval, boolean zeroDelay) {}
    private static final ClassValue<AoeAccess> AOE = new ClassValue<>() {
        @Override protected AoeAccess computeValue(Class<?> type) {
            Method delay = optional(type, "getDelay"), get = optional(type, "getReapplicationDelay"), set = optional(type, "setReapplicationDelay", int.class);
            Field field = null;
            if (get == null || set == null) {
                for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
                    if (!owner.getName().equals("io.redspace.ironsspellbooks.entity.spells.AoeEntity")) continue;
                    try {
                        field = owner.getDeclaredField("reapplicationDelay");
                        if (field.getType() != int.class) throw new IllegalStateException("Unknown native area interval type");
                        field.setAccessible(true);
                    } catch (NoSuchFieldException failure) { throw new IllegalStateException("No verified native area interval API", failure); }
                    break;
                }
                if (field == null) throw new IllegalStateException("No native AoeEntity superclass");
            }
            return new AoeAccess(delay, get, set, field, delay == null && zeroDelay(type));
        }
    };
    private static Method optional(Class<?> owner, String name, Class<?>... arguments) {
        try { return owner.getMethod(name, arguments); }
        catch (NoSuchMethodException absent) { return null; }
    }
    private static boolean zeroDelay(Class<?> type) {
        Class<?> base = type;
        while (base != null && !base.getName().equals("io.redspace.ironsspellbooks.entity.spells.AoeEntity")) base = base.getSuperclass();
        if (base == null) return false;
        try (var input = base.getResourceAsStream("AoeEntity.class")) {
            if (input == null) return false;
            var owner = new ClassNode(); new ClassReader(input).accept(owner, ClassReader.SKIP_DEBUG);
            var tick = owner.methods.stream().filter(method -> method.name.equals("tick") && method.desc.equals("()V")).findFirst().orElse(null);
            if (tick == null) return false;
            var code = java.util.Arrays.stream(tick.instructions.toArray()).filter(instruction -> instruction.getOpcode() >= 0).toList();
            if (code.size() < 11) return false;
            int[] expected = {Opcodes.ALOAD, Opcodes.INVOKESPECIAL, Opcodes.ALOAD, Opcodes.GETFIELD, Opcodes.ALOAD,
                    Opcodes.GETFIELD, Opcodes.IF_ICMPLE, Opcodes.ALOAD, Opcodes.INVOKEVIRTUAL, Opcodes.RETURN};
            for (int index = 0; index < expected.length; index++) if (code.get(index).getOpcode() != expected[index]) return false;
            for (int index : new int[]{0, 2, 4, 7}) if (((VarInsnNode) code.get(index)).var != 0) return false;
            var parentTick = (MethodInsnNode) code.get(1);
            var age = (FieldInsnNode) code.get(3);
            var duration = (FieldInsnNode) code.get(5);
            var discard = (MethodInsnNode) code.get(8);
            var target = ((JumpInsnNode) code.get(6)).label.getNext();
            while (target != null && target.getOpcode() < 0) target = target.getNext();
            return parentTick.owner.equals(owner.superName) && parentTick.name.equals("tick") && parentTick.desc.equals("()V")
                    && age.owner.equals(owner.name) && age.name.equals("tickCount") && age.desc.equals("I")
                    && duration.owner.equals(owner.name) && duration.name.equals("duration") && duration.desc.equals("I")
                    && discard.owner.equals(owner.name) && discard.name.equals("discard") && discard.desc.equals("()V")
                    && target == code.get(10);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot prove native area lifetime structure", failure); }
    }
    public static int aoeDelay(Object area) {
        AoeAccess access = AOE.get(area.getClass());
        Method delay = access.delay();
        if (delay != null) return ((Number) invoke(delay, area)).intValue();
        if (access.zeroDelay()) return 0;
        throw new IllegalStateException("Native area delay API is unavailable");
    }
    public static int aoeInterval(Object area) {
        var access = AOE.get(area.getClass());
        if (access.getInterval() != null) return ((Number) invoke(access.getInterval(), area)).intValue();
        try { return access.interval().getInt(area); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot read native area interval", failure); }
    }
    public static void aoeInterval(Object area, int interval) {
        var access = AOE.get(area.getClass());
        if (access.setInterval() != null) { invoke(access.setInterval(), area, interval); return; }
        try { access.interval().setInt(area, interval); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Cannot update native area interval", failure); }
    }
}
