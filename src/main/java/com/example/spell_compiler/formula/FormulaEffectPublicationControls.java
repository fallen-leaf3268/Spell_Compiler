package com.example.spell_compiler.formula;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class FormulaEffectPublicationControls {
    private static final String POISON = "net/minecraft/world/effect/MobEffect";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String DAMAGE_DESCRIPTOR = "(Lnet/minecraft/world/damagesource/DamageSource;F)Z";
    public record Control(FormulaNumericSites.Definition numeric, String effectId, String registryOwner,
                          String registryField, String registryDescriptor, String fingerprint,
                          List<FormulaEntityFlow.EntityEffectPublication> publications) {
        public Control { publications = List.copyOf(publications); }
        public String defaultExpression() { return "native_value"; }
    }
    private record Index(Map<String, List<Control>> bySpell, Map<String, Control> byBinding) {}
    private record Tick(Control control, MobEffectInstance instance, LivingEntity holder, Tick previous) {}
    private static volatile Index registered = new Index(Map.of(), Map.of());
    private static volatile boolean poisonHookRetained;
    private static final ThreadLocal<Tick> CURRENT = new ThreadLocal<>();

    private FormulaEffectPublicationControls() {}

    public static synchronized List<Control> register(FormulaClassRepository repository, String spellClass) {
        String spell = spellClass.replace('/', '.');
        List<Control> controls = discover(repository, spell);
        Map<String, List<Control>> bySpell = new LinkedHashMap<>(registered.bySpell());
        bySpell.put(spell, controls);
        Map<String, Control> byBinding = new LinkedHashMap<>();
        bySpell.values().forEach(values -> values.forEach(control -> byBinding.put(control.numeric().dispatchKey(), control)));
        registered = new Index(Map.copyOf(bySpell), Map.copyOf(byBinding));
        return controls;
    }

    public static List<Control> controls(String spellClass) {
        return registered.bySpell().getOrDefault(spellClass.replace('/', '.'), List.of());
    }

    public static List<FormulaNumericSites.Definition> definitions(String spellClass) {
        return controls(spellClass).stream().map(Control::numeric).toList();
    }

    public static FormulaNumericSites.Definition find(String dispatchKey) {
        Control control = registered.byBinding().get(dispatchKey);
        return control == null ? null : control.numeric();
    }

    public static boolean isDamage(String dispatchKey) { return registered.byBinding().containsKey(dispatchKey); }

    public static boolean available(String spellClass) {
        return controls(spellClass).stream().anyMatch(control -> availableBinding(control.numeric().dispatchKey()));
    }

    public static boolean availableBinding(String dispatchKey) {
        Control control = registered.byBinding().get(dispatchKey);
        return control != null && poisonHookRetained
                && FormulaEntityFlow.hasVerifiedEffectSource(control.numeric().spellClassName(), control.registryOwner(), control.registryField());
    }

    private static List<Control> discover(FormulaClassRepository repository, String spell) {
        ClassNode poison = repository.classNode(POISON);
        MethodNode consumer = poisonConsumer(poison);
        if (consumer == null) return List.of();
        var publications = FormulaEntityFlow.entityEffectPublications(repository, spell).publications().stream()
                .filter(publication -> publication.registryOwner().equals("net/minecraft/world/effect/MobEffects")
                        && Set.of("POISON", "f_19614_").contains(publication.registryField())).toList();
        if (publications.isEmpty()) return List.of();
        var first = publications.get(0);
        String sources = publications.stream().map(publication -> publication.capture().owner() + '#'
                        + publication.capture().methodName() + publication.capture().methodDescriptor() + ':'
                        + publication.capture().producerIndex() + ':' + publication.capture().methodFingerprint() + ':'
                        + publication.registryDescriptor()).sorted().reduce("", (a, b) -> a + '\n' + b);
        String fingerprint = "EFFECT_DAMAGE:POISON:" + hash(FormulaAutomaticInjector.fingerprint(consumer) + sources);
        var numeric = new FormulaNumericSites.Definition(spell, "poison_damage", 1f,
                FormulaNumericSites.ValueType.FLOAT, 0, Math.sqrt(Float.MAX_VALUE));
        return List.of(new Control(numeric, "minecraft:poison", first.registryOwner(), first.registryField(),
                first.registryDescriptor(), fingerprint, publications));
    }

    private static String hash(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    public static boolean validatePoisonTarget(ClassNode target) { return poisonConsumer(target) != null; }

    public static boolean applyPoisonTarget(ClassNode target) {
        MethodNode original = poisonConsumer(target);
        if (original == null) return false;
        ClassNode copy = new ClassNode();
        target.accept(copy);
        MethodNode method = poisonConsumer(copy);
        if (method == null) return false;
        var hurts = FormulaAutomaticInjector.executable(method).stream().filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(LIVING) && Set.of("hurt", "m_6469_").contains(call.name)
                && call.desc.equals(DAMAGE_DESCRIPTOR) && FormulaEffectPublicationRetention.poisonBranch(method, call)).toList();
        if (hurts.size() != 1) return false;
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/example/spell_compiler/formula/FormulaEffectPublicationControls", "poisonDamage", "(FL" + LIVING + ";)F", false));
        method.instructions.insertBefore(hurts.get(0), replacement);
        method.maxStack = Math.max(4, method.maxStack + 1);
        if (!FormulaEffectPublicationRetention.retainedPoison(copy)) return false;
        original.instructions = method.instructions;
        original.tryCatchBlocks = method.tryCatchBlocks;
        original.localVariables = method.localVariables;
        original.visibleLocalVariableAnnotations = method.visibleLocalVariableAnnotations;
        original.invisibleLocalVariableAnnotations = method.invisibleLocalVariableAnnotations;
        original.maxLocals = method.maxLocals;
        original.maxStack = method.maxStack;
        return true;
    }

    private static MethodNode poisonConsumer(ClassNode target) {
        if (target == null || !target.name.equals(POISON)) return null;
        var methods = target.methods.stream().filter(method -> Set.of("applyEffectTick", "m_6742_").contains(method.name)
                && Set.of("(L" + LIVING + ";I)Z", "(L" + LIVING + ";I)V").contains(method.desc)).toList();
        if (methods.size() != 1) return null;
        MethodNode method = methods.get(0);
        var instructions = FormulaAutomaticInjector.executable(method);
        var hurts = instructions.stream().filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(LIVING) && Set.of("hurt", "m_6469_").contains(call.name)
                && call.desc.equals(DAMAGE_DESCRIPTOR) && FormulaEffectPublicationRetention.poisonBranch(method, call)).toList();
        if (hurts.size() != 1) return null;
        if (instructions.stream().filter(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(LIVING) && Set.of("hurt", "m_6469_").contains(call.name)
                && call.desc.equals(DAMAGE_DESCRIPTOR)).findFirst().orElse(null) != hurts.get(0)) return null;
        int hurt = instructions.indexOf(hurts.get(0));
        if (hurt < 1 || instructions.get(hurt - 1).getOpcode() != Opcodes.FCONST_1) return null;
        for (int i = 0; i + 3 < hurt; i++) {
            if (!(instructions.get(i) instanceof MethodInsnNode health) || !health.owner.equals(LIVING)
                    || !Set.of("getHealth", "m_21223_").contains(health.name) || !health.desc.equals("()F")
                    || instructions.get(i + 1).getOpcode() != Opcodes.FCONST_1 || instructions.get(i + 2).getOpcode() != Opcodes.FCMPL
                    || !(instructions.get(i + 3) instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFLE) continue;
            if (method.instructions.indexOf(jump.label) > method.instructions.indexOf(hurts.get(0))) return method;
        }
        return null;
    }

    public static void observePoisonTarget(ClassNode target) {
        poisonHookRetained = FormulaEffectPublicationRetention.retainedPoison(target);
    }

    public static boolean enterTick(Object instance, LivingEntity holder, CompoundTag source) {
        Tick previous = CURRENT.get();
        Control control = null;
        if (instance instanceof MobEffectInstance effect && effect.getEffect().equals(MobEffects.POISON)
                && source != null && !source.isEmpty()) {
            for (var candidate : controls(source.getString("class")))
                if (FormulaRuntimeDispatcher.isActive(candidate.numeric().dispatchKey(), holder)
                        && poisonHookRetained && FormulaEntityContext.valid(source)) { control = candidate; break; }
        }
        if (control == null && previous == null) return false;
        CURRENT.set(new Tick(control, control == null ? null : (MobEffectInstance) instance, holder, previous));
        return true;
    }

    public static void exitTick() {
        Tick current = CURRENT.get();
        if (current == null || current.previous() == null) CURRENT.remove();
        else CURRENT.set(current.previous());
    }

    public static float poisonDamage(float original, LivingEntity target) {
        Tick current = CURRENT.get();
        if (current == null || current.control() == null || current.holder() != target) return original;
        Number value = FormulaEntityContext.evaluateEffect(current.control().numeric(), original, current.instance(), target);
        return value == null ? original : Math.min(value.floatValue(), Math.max(0, target.getHealth() - 1));
    }

}
