package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.List;
import java.util.Set;

final class FormulaHealingEventFlow {
    private static final String EVENT = "io/redspace/ironsspellbooks/api/events/SpellHealEvent";
    private static final String CONSTRUCTOR = "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/LivingEntity;FLio/redspace/ironsspellbooks/api/spells/SchoolType;)V";

    private FormulaHealingEventFlow() {}

    static AbstractInsnNode sharedStore(FormulaClassRepository repository, String owner, MethodNode method,
                                       MethodInsnNode heal, FormulaAutomaticSites.Site site) {
        if (site.kind() != FormulaAutomaticSites.Kind.HEALING || site.argumentIndex() != 0
                || !Set.of("heal", "m_5634_").contains(heal.name) || !heal.desc.equals("(F)V")
                || !FormulaAutomaticInjector.fingerprint(method).equals(site.methodFingerprint())
                || !immutableEvent(repository.classNode(EVENT))) return null;
        if (!(previous(heal) instanceof VarInsnNode value) || value.getOpcode() != Opcodes.FLOAD
                || !(previous(value) instanceof VarInsnNode target) || target.getOpcode() != Opcodes.ALOAD) return null;
        List<AbstractInsnNode> instructions = FormulaAutomaticInjector.executable(method);
        Frame<SourceValue>[] frames;
        try { frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method); }
        catch (AnalyzerException failure) { return null; }
        Frame<SourceValue> healFrame = frames[method.instructions.indexOf(heal)];
        if (healFrame == null || !(single(healFrame.getLocal(value.var)) instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.FSTORE || store.var != value.var) return null;
        int reads = 0;
        int stores = 0;
        for (var instruction : method.instructions) if (instruction instanceof VarInsnNode variable && variable.var == value.var) {
            if (variable.getOpcode() == Opcodes.FLOAD) reads++;
            if (variable.getOpcode() == Opcodes.FSTORE) stores++;
        }
        if (reads != 2 || stores != 1) return null;
        int storeIndex = instructions.indexOf(store);
        int parameterSlots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type parameter : Type.getArgumentTypes(method.desc)) parameterSlots += parameter.getSize();
        for (var input : site.inputs()) {
            if (input.producerIndex() >= storeIndex) return null;
            if (input.localSlot() == value.var) return null;
            if (input.localSlot() >= parameterSlots) {
                var frame = frames[method.instructions.indexOf(store)];
                if (input.localSlot() >= frame.getLocals() || single(frame.getLocal(input.localSlot())) == null) return null;
            }
        }
        MethodInsnNode matched = null;
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode event) || event.getOpcode() != Opcodes.INVOKESPECIAL
                    || !event.owner.equals(EVENT) || !event.name.equals("<init>") || !event.desc.equals(CONSTRUCTOR)) continue;
            var frame = frames[method.instructions.indexOf(event)];
            if (frame == null || frame.getStackSize() < 5 || single(frame.getLocal(value.var)) != store) continue;
            var amount = single(frame.getStack(frame.getStackSize() - 2));
            var eventTarget = single(frame.getStack(frame.getStackSize() - 3));
            if (!(amount instanceof VarInsnNode read) || read.getOpcode() != Opcodes.FLOAD || read.var != value.var
                    || !(eventTarget instanceof VarInsnNode entity) || entity.getOpcode() != Opcodes.ALOAD || entity.var != target.var) continue;
            if (!(next(event) instanceof MethodInsnNode post) || !post.name.equals("post")
                    || !Set.of("net/neoforged/bus/api/IEventBus", "net/minecraftforge/eventbus/api/IEventBus").contains(post.owner)
                    || next(post) == null || next(post).getOpcode() != Opcodes.POP
                    || next(next(post)) != target || next(target) != value || next(value) != heal) continue;
            if (matched != null) return null;
            matched = event;
        }
        return matched == null ? null : store;
    }

    private static boolean immutableEvent(ClassNode event) {
        if (event == null || event.interfaces.stream().anyMatch(name -> name.endsWith("/ICancellableEvent"))) return false;
        if (event.visibleAnnotations != null && event.visibleAnnotations.stream().anyMatch(annotation -> annotation.desc.endsWith("/Cancelable;"))) return false;
        if (event.invisibleAnnotations != null && event.invisibleAnnotations.stream().anyMatch(annotation -> annotation.desc.endsWith("/Cancelable;"))) return false;
        return event.fields.stream().anyMatch(field -> field.name.equals("healAmount") && field.desc.equals("F")
                && (field.access & Opcodes.ACC_FINAL) != 0 && (field.access & Opcodes.ACC_STATIC) == 0)
                && event.methods.stream().noneMatch(method -> method.desc.equals("(F)V") && !method.name.equals("<init>"));
    }

    private static AbstractInsnNode single(SourceValue value) {
        return value != null && value.insns.size() == 1 ? value.insns.iterator().next() : null;
    }
    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}
