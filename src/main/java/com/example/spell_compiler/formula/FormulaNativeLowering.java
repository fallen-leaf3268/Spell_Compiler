package com.example.spell_compiler.formula;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

import static com.example.spell_compiler.formula.FormulaExtractionModel.*;
import static com.example.spell_compiler.formula.FormulaExtractionBytecode.*;

final class FormulaNativeLowering {
    private static final String ABSTRACT_SPELL = "io/redspace/ironsspellbooks/api/spells/AbstractSpell";
    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String LEGACY_CONFIG = "io/redspace/ironsspellbooks/config/ServerConfigs";
    private static final String LEGACY_PARAMETERS = LEGACY_CONFIG + "$SpellConfigParameters";
    private static final String ATTRIBUTE_REGISTRY = "io/redspace/ironsspellbooks/api/registry/AttributeRegistry";
    private static final String UTILS = "io/redspace/ironsspellbooks/api/util/Utils";
    private static final String DEFERRED_HOLDER = "net/neoforged/neoforge/registries/DeferredHolder";
    private static final String CAST_TYPE = "io/redspace/ironsspellbooks/api/spells/CastType";

    private final FormulaExtractionInputs inputs;
    private final FormulaClassRepository repository;
    private final String receiverClass;
    private final MethodNode method;
    private final Map<Integer, Value> parameters;

    private FormulaNativeLowering(FormulaExtractionInputs inputs, FormulaClassRepository repository, String receiverClass,
                                  MethodNode method, Map<Integer, Value> parameters) {
        this.inputs = inputs;
        this.repository = repository;
        this.receiverClass = receiverClass;
        this.method = method;
        this.parameters = parameters;
    }

    static Value lower(FormulaExtractionInputs inputs, FormulaClassRepository repository, String receiverClass,
                       ClassNode owner, MethodNode method, Map<Integer, Value> parameters) {
        if (!owner.name.equals(ABSTRACT_SPELL)) return null;
        return new FormulaNativeLowering(inputs, repository, receiverClass, method, parameters).lower();
    }

    static String nativeCastType(FormulaClassRepository repository, String receiverClass) {
        String receiver = receiverClass.replace('.', '/');
        var resolved = repository.resolveMethod(receiver, "getCastType", "()L" + CAST_TYPE + ';');
        if (resolved == null) throw new IllegalArgumentException("Cannot resolve native cast type: " + receiver);
        List<AbstractInsnNode> instructions = executable(resolved.method().instructions.toArray(), 0,
                resolved.method().instructions.size());
        if (instructions.size() != 2 || !(instructions.get(0) instanceof FieldInsnNode field)
                || field.getOpcode() != Opcodes.GETSTATIC || !field.owner.equals(CAST_TYPE)
                || !field.desc.equals('L' + CAST_TYPE + ';')
                || !Set.of("INSTANT", "LONG", "CONTINUOUS", "NONE").contains(field.name)
                || instructions.get(1).getOpcode() != Opcodes.ARETURN)
            throw new IllegalArgumentException("Native cast type is not a fixed enum: " + receiver);
        return field.name;
    }

    private Value lower() {
        boolean base = method.name.equals("getCastTime") && method.desc.equals("(I)I");
        boolean effective = method.name.equals("getEffectiveCastTime") && method.desc.equals("(IL" + LIVING_ENTITY + ";)I");
        if (!base && !effective) return null;
        if ((method.access & Opcodes.ACC_STATIC) != 0)
            throw new Rejected("Native cast methods must retain instance dispatch");
        AbstractInsnNode[] original = method.instructions.toArray();
        List<AbstractInsnNode> code = executable(original, 0, original.length);
        Value receiver = parameters.get(0);
        if (base) {
            if (code.size() != 9 || !variableMatches(code.get(0), Opcodes.ALOAD, 0)
                    || !callMatches(code.get(1), Opcodes.INVOKEVIRTUAL, ABSTRACT_SPELL, "getCastType", "()L" + CAST_TYPE + ';')
                    || !fieldMatches(code.get(2), CAST_TYPE, "INSTANT", 'L' + CAST_TYPE + ';')
                    || !jumpMatches(code.get(3), Opcodes.IF_ACMPNE, code.get(6))
                    || code.get(4).getOpcode() != Opcodes.ICONST_0 || code.get(5).getOpcode() != Opcodes.IRETURN
                    || !variableMatches(code.get(6), Opcodes.ALOAD, 0)
                    || !(code.get(7) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(ABSTRACT_SPELL) || !field.name.equals("castTime") || !field.desc.equals("I")
                    || code.get(8).getOpcode() != Opcodes.IRETURN)
                throw new Rejected("Native cast time differs from the audited 1.21.1-3.16.3 structure");
            var typeCode = new ArrayList<AbstractInsnNode>(receiver.instructions());
            LabelNode nonInstant = new LabelNode();
            LabelNode done = new LabelNode();
            typeCode.add(code.get(1));
            typeCode.add(code.get(2));
            typeCode.add(new JumpInsnNode(Opcodes.IF_ACMPNE, nonInstant));
            typeCode.add(new InsnNode(Opcodes.ICONST_1));
            typeCode.add(new JumpInsnNode(Opcodes.GOTO, done));
            typeCode.add(nonInstant);
            typeCode.add(new InsnNode(Opcodes.ICONST_0));
            typeCode.add(done);
            Value instant = inputs.bind("is_instant", Type.INT_TYPE, typeCode, "castType:instant:" + receiver.reference().identity());
            Value time = inputs.bind("castTime", Type.INT_TYPE, append(code.get(7), receiver),
                    "field:" + ABSTRACT_SPELL + ".castTimeI:" + receiver.reference().identity());
            Node difference = new Binary(Type.INT_TYPE, new Constant(Type.INT_TYPE, 1), "-", instant.node());
            var resultCode = new ArrayList<AbstractInsnNode>(time.instructions());
            resultCode.add(new InsnNode(Opcodes.ICONST_1));
            resultCode.addAll(instant.instructions());
            resultCode.add(new InsnNode(Opcodes.ISUB));
            resultCode.add(new InsnNode(Opcodes.IMUL));
            return new Value(Type.INT_TYPE, new Binary(Type.INT_TYPE, time.node(), "*", difference), resultCode, null);
        }
        validateEffectiveCast(code);
        Value caster = parameters.get(2);
        if (receiverClass != null) return contextualCast(code, receiver, caster);
        int scratch = inputs.allocate(Type.DOUBLE_TYPE);
        int boundary = method.instructions.indexOf(code.get(20));
        var multiplierCode = new ArrayList<AbstractInsnNode>();
        for (int index = 0; index < boundary; index++) {
            AbstractInsnNode instruction = original[index];
            if (instruction instanceof FrameNode || instruction instanceof LineNumberNode) continue;
            if (variableMatches(instruction, Opcodes.ALOAD, 0)) multiplierCode.addAll(receiver.instructions());
            else if (variableMatches(instruction, Opcodes.ALOAD, 2)) multiplierCode.addAll(caster.instructions());
            else if (variableMatches(instruction, Opcodes.DSTORE, 3)) multiplierCode.add(new VarInsnNode(Opcodes.DSTORE, scratch));
            else multiplierCode.add(instruction);
        }
        multiplierCode.add(new VarInsnNode(Opcodes.DLOAD, scratch));
        privateLabels(multiplierCode);
        Value multiplier = inputs.bind("cast_time_multiplier", Type.DOUBLE_TYPE, multiplierCode,
                "castMultiplier:" + receiver.reference().identity() + ':' + caster.reference().identity());
        Value time = inputs.bind("native_cast_time", Type.INT_TYPE, append(code.get(22), receiver, parameters.get(1)),
                "castTime:" + receiver.reference().identity() + ':' + parameters.get(1).node().text());
        Node product = new Binary(Type.FLOAT_TYPE, new Conversion(Type.FLOAT_TYPE, time.node()), "*",
                new Conversion(Type.FLOAT_TYPE, multiplier.node()));
        var resultCode = new ArrayList<AbstractInsnNode>(time.instructions());
        resultCode.add(new InsnNode(Opcodes.I2F));
        resultCode.addAll(multiplier.instructions());
        resultCode.add(new InsnNode(Opcodes.D2F));
        resultCode.add(new InsnNode(Opcodes.FMUL));
        resultCode.add(code.get(27));
        return new Value(Type.INT_TYPE, new Function(Type.INT_TYPE, "round", List.of(product)), resultCode, null);
    }

    private Value contextualCast(List<AbstractInsnNode> code, Value receiver, Value caster) {
        String castType = fixedCastType();
        LabelNode absent = new LabelNode();
        LabelNode done = new LabelNode();
        var attributeCode = new ArrayList<AbstractInsnNode>(caster.instructions());
        attributeCode.add(new JumpInsnNode(Opcodes.IFNULL, absent));
        attributeCode.addAll(caster.instructions());
        attributeCode.addAll(code.subList(17, 19));
        attributeCode.add(new JumpInsnNode(Opcodes.GOTO, done));
        attributeCode.add(absent);
        attributeCode.add(new InsnNode(Opcodes.DCONST_1));
        attributeCode.add(done);
        Value reduction = inputs.bind("cast_time_reduction", Type.DOUBLE_TYPE, attributeCode,
                "castReduction:" + receiver.reference().identity() + ':' + caster.reference().identity());
        Node multiplier = reduction.node();
        var multiplierCode = new ArrayList<AbstractInsnNode>(reduction.instructions());
        if (!castType.equals("CONTINUOUS")) {
            Node one = new Constant(Type.DOUBLE_TYPE, 1.0);
            Node two = new Constant(Type.DOUBLE_TYPE, 2.0);
            Node denominator = new Binary(Type.DOUBLE_TYPE,
                    new Function(Type.DOUBLE_TYPE, "max", List.of(reduction.node(), new Constant(Type.DOUBLE_TYPE, 1.5))),
                    "-", one);
            Node reciprocal = new Binary(Type.DOUBLE_TYPE, one, "/", denominator);
            Node curve = new Binary(Type.DOUBLE_TYPE,
                    new Binary(Type.DOUBLE_TYPE, new Constant(Type.DOUBLE_TYPE, -0.25), "*", reciprocal), "+", two);
            multiplier = new Binary(Type.DOUBLE_TYPE, two, "-",
                    new Function(Type.DOUBLE_TYPE, "min", List.of(reduction.node(), curve)));
            multiplierCode.clear();
            multiplierCode.add(new LdcInsnNode(2.0));
            multiplierCode.addAll(reduction.instructions());
            multiplierCode.add(new LdcInsnNode(-0.25));
            multiplierCode.add(new InsnNode(Opcodes.DCONST_1));
            multiplierCode.addAll(reduction.instructions());
            multiplierCode.add(new LdcInsnNode(1.5));
            multiplierCode.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false));
            multiplierCode.add(new InsnNode(Opcodes.DCONST_1));
            multiplierCode.add(new InsnNode(Opcodes.DSUB));
            multiplierCode.add(new InsnNode(Opcodes.DDIV));
            multiplierCode.add(new InsnNode(Opcodes.DMUL));
            multiplierCode.add(new LdcInsnNode(2.0));
            multiplierCode.add(new InsnNode(Opcodes.DADD));
            multiplierCode.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false));
            multiplierCode.add(new InsnNode(Opcodes.DSUB));
        }
        Value time = inputs.bind("native_cast_time", Type.INT_TYPE, append(code.get(22), receiver, parameters.get(1)),
                "castTime:" + receiver.reference().identity() + ':' + parameters.get(1).node().text());
        Node product = new Binary(Type.FLOAT_TYPE, new Conversion(Type.FLOAT_TYPE, time.node()), "*",
                new Conversion(Type.FLOAT_TYPE, multiplier));
        var resultCode = new ArrayList<AbstractInsnNode>(time.instructions());
        resultCode.add(new InsnNode(Opcodes.I2F));
        resultCode.addAll(multiplierCode);
        resultCode.add(new InsnNode(Opcodes.D2F));
        resultCode.add(new InsnNode(Opcodes.FMUL));
        resultCode.add(code.get(27));
        return new Value(Type.INT_TYPE, new Function(Type.INT_TYPE, "round", List.of(product)), resultCode, null);
    }

    private String fixedCastType() {
        try {
            return nativeCastType(repository, receiverClass);
        } catch (IllegalArgumentException failure) {
            throw new Rejected(failure.getMessage());
        }
    }

    private static void validateEffectiveCast(List<AbstractInsnNode> code) {
        int[] opcodes = {Opcodes.DCONST_1, Opcodes.DSTORE, Opcodes.ALOAD, Opcodes.IFNULL, Opcodes.ALOAD,
                Opcodes.INVOKEVIRTUAL, Opcodes.GETSTATIC, Opcodes.IF_ACMPEQ, Opcodes.LDC, Opcodes.ALOAD,
                Opcodes.GETSTATIC, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESTATIC, Opcodes.DSUB,
                Opcodes.DSTORE, Opcodes.GOTO, Opcodes.ALOAD, Opcodes.GETSTATIC, Opcodes.INVOKEVIRTUAL,
                Opcodes.DSTORE, Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.INVOKEVIRTUAL, Opcodes.I2F,
                Opcodes.DLOAD, Opcodes.D2F, Opcodes.FMUL, Opcodes.INVOKESTATIC, Opcodes.IRETURN};
        if (code.size() != opcodes.length)
            throw new Rejected("Native effective cast time differs from the audited 1.21.1-3.16.3 structure");
        for (int index = 0; index < opcodes.length; index++)
            if (code.get(index).getOpcode() != opcodes[index])
                throw new Rejected("Native effective cast instruction structure is not supported");
        for (int index : new int[]{1, 14, 19})
            if (!variableMatches(code.get(index), Opcodes.DSTORE, 3))
                throw new Rejected("Native effective cast multiplier slot is not supported");
        for (int index : new int[]{2, 9, 16})
            if (!variableMatches(code.get(index), Opcodes.ALOAD, 2))
                throw new Rejected("Native effective cast caster slot is not supported");
        if (!variableMatches(code.get(4), Opcodes.ALOAD, 0) || !variableMatches(code.get(20), Opcodes.ALOAD, 0)
                || !variableMatches(code.get(21), Opcodes.ILOAD, 1) || !variableMatches(code.get(24), Opcodes.DLOAD, 3)
                || !callMatches(code.get(5), Opcodes.INVOKEVIRTUAL, ABSTRACT_SPELL, "getCastType", "()L" + CAST_TYPE + ';')
                || !fieldMatches(code.get(6), CAST_TYPE, "CONTINUOUS", 'L' + CAST_TYPE + ';')
                || !jumpMatches(code.get(3), Opcodes.IFNULL, code.get(20))
                || !jumpMatches(code.get(7), Opcodes.IF_ACMPEQ, code.get(16))
                || !jumpMatches(code.get(15), Opcodes.GOTO, code.get(20))
                || !(code.get(8) instanceof LdcInsnNode constant) || !Double.valueOf(2).equals(constant.cst)
                || !callMatches(code.get(12), Opcodes.INVOKESTATIC, UTILS, "softCapFormula", "(D)D")
                || !callMatches(code.get(22), Opcodes.INVOKEVIRTUAL, ABSTRACT_SPELL, "getCastTime", "(I)I")
                || !callMatches(code.get(27), Opcodes.INVOKESTATIC, "java/lang/Math", "round", "(F)I"))
            throw new Rejected("Native effective cast control flow is not supported");
        for (int index : new int[]{10, 17})
            if (!fieldMatches(code.get(index), ATTRIBUTE_REGISTRY, "CAST_TIME_REDUCTION", 'L' + DEFERRED_HOLDER + ';')
                    || !attributeValueCall(code.get(index + 1)))
                throw new Rejected("Native effective cast attribute source is not supported");
    }

    static void validateNativePower(MethodNode method) {
        boolean power = method.name.equals("getSpellPower");
        int[] expected = power
                ? new int[]{15,57,15,57,25,178,184,192,182,56,25,193,153,25,192,58,25,178,182,144,141,57,
                25,182,25,182,57,25,180,25,180,21,4,100,104,96,135,24,107,24,107,23,141,107,144,174}
                : new int[]{25,178,184,192,182,56,25,199,23,174,25,178,182,144,56,25,182,25,182,57,
                23,23,106,141,24,107,144,174};
        int[] expectedSlots = power
                ? new int[]{3,5,0,7,2,2,8,8,3,0,8,5,0,0,1,3,5,7}
                : new int[]{0,2,1,2,1,3,0,1,4,2,3,4};
        var executable = new ArrayList<AbstractInsnNode>();
        var slots = new ArrayList<Integer>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) executable.add(instruction);
            if (instruction instanceof VarInsnNode variable) slots.add(variable.var);
        }
        int configStart = power ? 5 : 1;
        boolean legacy = executable.size() > configStart + 2
                && executable.get(configStart) instanceof MethodInsnNode lookup
                && lookup.getOpcode() == Opcodes.INVOKESTATIC && lookup.owner.equals(LEGACY_CONFIG)
                && lookup.name.equals("getSpellConfig")
                && lookup.desc.equals("(L" + ABSTRACT_SPELL + ";)L" + LEGACY_PARAMETERS + ';')
                && executable.get(configStart + 1) instanceof MethodInsnNode getter
                && getter.getOpcode() == Opcodes.INVOKEVIRTUAL && getter.owner.equals(LEGACY_PARAMETERS)
                && getter.name.equals("powerMultiplier") && getter.desc.equals("()D")
                && executable.get(configStart + 2).getOpcode() == Opcodes.D2F;
        if (legacy) {
            int[] compatible = new int[expected.length - 1];
            System.arraycopy(expected, 0, compatible, 0, configStart);
            compatible[configStart] = Opcodes.INVOKESTATIC;
            compatible[configStart + 1] = Opcodes.INVOKEVIRTUAL;
            compatible[configStart + 2] = Opcodes.D2F;
            System.arraycopy(expected, configStart + 4, compatible, configStart + 3, expected.length - configStart - 4);
            expected = compatible;
        }
        if (executable.size() != expected.length || slots.size() != expectedSlots.length)
            throw new Rejected("Native power control flow differs from the audited 1.21.1-3.16.3 structure");
        for (int index = 0; index < expected.length; index++)
            if (executable.get(index).getOpcode() != expected[index])
                throw new Rejected("Native power instruction structure is not supported");
        for (int index = 0; index < expectedSlots.length; index++)
            if (slots.get(index) != expectedSlots[index])
                throw new Rejected("Native power local structure is not supported");
        JumpInsnNode jump = (JumpInsnNode) executable.get((power ? 12 : 7) - (legacy ? 1 : 0));
        AbstractInsnNode target = jump.label;
        while (target != null && target.getOpcode() < 0) target = target.getNext();
        if (target != executable.get((power ? 27 : 10) - (legacy ? 1 : 0)))
            throw new Rejected("Native power branch target is not supported");
    }

}
