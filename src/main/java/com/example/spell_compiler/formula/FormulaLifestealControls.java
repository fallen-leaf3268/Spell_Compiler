package com.example.spell_compiler.formula;

import io.redspace.ironsspellbooks.damage.SpellDamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class FormulaLifestealControls {
    public record Control(FormulaNumericSites.Definition numeric, String fingerprint, Set<String> producerSpellClasses,
                          boolean entitySource, boolean castSource) {
        public Control { producerSpellClasses = Set.copyOf(producerSpellClasses); }
    }
    private record Index(Map<String, Control> bySpell, Map<String, Control> byBinding, Map<String, List<Control>> byProducer) {}
    private static volatile Index registered = new Index(Map.of(), Map.of(), Map.of());
    private static volatile boolean getterRetained;
    private static volatile boolean consumerRetained;

    private FormulaLifestealControls() {}

    public static synchronized List<Control> register(FormulaClassRepository repository, String spellClass) {
        String spell = spellClass.replace('/', '.');
        Control control = discover(repository, spell);
        Map<String, Control> bySpell = new LinkedHashMap<>(registered.bySpell());
        if (control == null) bySpell.remove(spell); else bySpell.put(spell, control);
        Map<String, Control> byBinding = new LinkedHashMap<>();
        Map<String, List<Control>> byProducer = new LinkedHashMap<>();
        bySpell.values().forEach(value -> {
            byBinding.put(value.numeric().dispatchKey(), value);
            value.producerSpellClasses().forEach(producer -> byProducer.computeIfAbsent(producer, key -> new ArrayList<>()).add(value));
        });
        byProducer.replaceAll((key, values) -> List.copyOf(values));
        registered = new Index(Map.copyOf(bySpell), Map.copyOf(byBinding), Map.copyOf(byProducer));
        return control == null ? List.of() : List.of(control);
    }

    public static FormulaNumericSites.Definition find(String key) {
        Control control = registered.byBinding().get(key);
        return control == null ? null : control.numeric();
    }

    public static boolean availableBinding(String key) {
        Control control = registered.byBinding().get(key);
        return control != null && getterRetained && consumerRetained
                && (!control.entitySource() || FormulaEntityFlow.hasVerifiedSource(control.numeric().spellClassName()))
                && (!control.castSource() || FormulaCastContext.hasVerifiedSource(control.numeric().spellClassName()));
    }

    public static boolean validateSourceTarget(ClassNode target) { return FormulaLifestealContracts.source(target) != null; }
    public static boolean validateConsumerTarget(ClassNode target) { return FormulaLifestealContracts.consumer(target) != null; }
    public static void observeSourceTarget(ClassNode target) { getterRetained = FormulaLifestealContracts.getterRetained(target); }
    public static void observeConsumerTarget(ClassNode target) {
        consumerRetained = FormulaLifestealContracts.consumer(FormulaRetentionBytecode.analysisCopy(target)) != null;
    }

    public static float lifestealRatio(float original, Object value) {
        if (!(value instanceof SpellDamageSource source) || source.spell() == null
                || !(source.getEntity() instanceof LivingEntity caster) || caster.level().isClientSide) return original;
        List<Control> candidates = registered.byProducer().get(source.spell().getClass().getName());
        if (candidates == null) return original;
        Entity direct = source.getDirectEntity();
        for (Control control : candidates) {
            if (!FormulaRuntimeDispatcher.isActive(control.numeric().dispatchKey(), caster)) continue;
            Number result = null;
            if (control.entitySource() && direct != null && direct != caster && direct.level() == caster.level()) {
                var context = direct.getPersistentData().getCompound(FormulaEntityContext.DATA_KEY);
                if (FormulaEntityContext.valid(context) && context.getString("class").equals(control.numeric().spellClassName())
                        && context.hasUUID("caster") && context.getUUID("caster").equals(caster.getUUID()))
                    result = FormulaEntityContext.evaluateSource(control.numeric(), original, direct);
            }
            if (result == null && control.castSource())
                result = FormulaEntityContext.evaluateCastSource(control.numeric(), original, source.spell(), caster);
            if (result != null) return result.floatValue();
        }
        return original;
    }

    private static Control discover(FormulaClassRepository repository, String spell) {
        var contract = FormulaLifestealContracts.source(repository.classNode(FormulaLifestealContracts.SOURCE));
        MethodNode consumer = FormulaLifestealContracts.consumer(repository.classNode(FormulaLifestealContracts.EVENTS));
        if (contract == null || consumer == null) return null;
        var sources = FormulaEntityFlow.entityEffectPublications(repository, spell);
        Set<String> producers = new TreeSet<>();
        List<String> proof = new ArrayList<>();
        boolean entity = false, cast = false;
        for (var method : sources.provenMethods()) {
            var resolved = repository.resolveMethod(method.owner(), method.name(), method.descriptor());
            if (resolved == null) continue;
            var flow = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
            if (!flow.successful()) continue;
            for (var instruction : resolved.method().instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("getDamageSource")
                        || !call.desc.endsWith(")L" + FormulaLifestealContracts.SOURCE + ';') || !flow.reachable(call)) continue;
                String producer = receiver(repository, flow, flow.receiver(call), method.owner(), spell, new HashSet<>());
                if (producer == null) continue;
                var producing = repository.resolveMethod(producer, "getDamageSource",
                        "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;)L" + FormulaLifestealContracts.SOURCE + ';');
                if (producing == null || !setsReturnedRatio(repository, producing.owner().name, producing.method())) continue;
                boolean directCast = repository.isSubtype(resolved.owner().name, "io/redspace/ironsspellbooks/api/spells/AbstractSpell");
                entity |= !directCast;
                cast |= directCast;
                producers.add(producer.replace('/', '.'));
                proof.add(method.key() + ':' + FormulaAutomaticInjector.fingerprint(resolved.method()) + ':'
                        + FormulaAutomaticInjector.fingerprint(producing.method()));
            }
        }
        if (producers.isEmpty()) return null;
        Collections.sort(proof);
        String fingerprint = "LIFESTEAL_RATIO:" + hash(contract.fingerprint() + ':'
                + FormulaAutomaticInjector.fingerprint(consumer) + ':' + String.join("|", proof));
        var numeric = new FormulaNumericSites.Definition(spell, "lifesteal_ratio", 0f,
                FormulaNumericSites.ValueType.FLOAT, 0, Math.sqrt(Float.MAX_VALUE));
        return new Control(numeric, fingerprint, producers, entity, cast);
    }

    private static String receiver(FormulaClassRepository repository, FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value,
                                   String owner, String spell, Set<FormulaDataFlow.Node> seen) {
        if (flow.parameter(value, 0) && repository.isSubtype(owner, "io/redspace/ironsspellbooks/api/spells/AbstractSpell"))
            return spell.replace('.', '/');
        var origin = flow.origin(value);
        if (origin == null || !seen.add(origin)) return null;
        if (origin.instruction() instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC)
            return FormulaExecutionGraph.index(repository).registeredSpellReceiver(field.owner, field.name, field.desc);
        if (origin.instruction() instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST)
            return receiver(repository, flow, flow.instruction(cast).operands().get(0), owner, spell, seen);
        if (origin.instruction() instanceof MethodInsnNode call && call.name.equals("get") && call.desc.startsWith("()"))
            return receiver(repository, flow, flow.receiver(call), owner, spell, seen);
        return null;
    }

    private static boolean setsReturnedRatio(FormulaClassRepository repository, String owner, MethodNode method) {
        var flow = FormulaDataFlow.analyze(repository, owner, method);
        if (!flow.successful()) return false;
        List<MethodInsnNode> setters = FormulaAutomaticInjector.executable(method).stream()
                .filter(instruction -> instruction instanceof MethodInsnNode call && call.owner.equals(FormulaLifestealContracts.SOURCE)
                        && call.name.equals("setLifestealPercent") && call.desc.equals("(F)L" + FormulaLifestealContracts.SOURCE + ';'))
                .map(instruction -> (MethodInsnNode) instruction).toList();
        if (setters.size() != 1) return false;
        int returns = 0;
        for (var instruction : flow.instructions()) if (instruction.instruction().getOpcode() == Opcodes.ARETURN) {
            if (instruction.operands().size() != 1 || !returnedSource(repository, flow, instruction.operands().get(0), setters.get(0), new HashSet<>())) return false;
            returns++;
        }
        return returns > 0;
    }

    private static boolean returnedSource(FormulaClassRepository repository, FormulaDataFlow.Analysis flow, FormulaDataFlow.Value value, MethodInsnNode setter,
                                          Set<FormulaDataFlow.Node> seen) {
        var origin = flow.origin(value);
        if (origin == null || !seen.add(origin)) return false;
        if (origin.instruction() == setter) return true;
        if (origin.instruction() instanceof MethodInsnNode call && call.owner.equals(FormulaLifestealContracts.SOURCE)
                && call.desc.endsWith(")L" + FormulaLifestealContracts.SOURCE + ';')) {
            var resolved = repository.resolveMethod(call.owner, call.name, call.desc);
            if (resolved == null || (resolved.method().access & Opcodes.ACC_STATIC) != 0) return false;
            var fluent = FormulaDataFlow.analyze(repository, resolved.owner().name, resolved.method());
            if (!fluent.successful()) return false;
            int returns = 0;
            for (var instruction : fluent.instructions()) if (instruction.instruction().getOpcode() == Opcodes.ARETURN) {
                if (instruction.operands().size() != 1 || !fluent.parameter(instruction.operands().get(0), 0)) return false;
                returns++;
            }
            return returns > 0 && returnedSource(repository, flow, flow.receiver(call), setter, seen);
        }
        return false;
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
