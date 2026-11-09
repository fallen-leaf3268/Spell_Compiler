package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class FormulaSpellMetadata {
    private static final String CONFIG = "io/redspace/ironsspellbooks/api/config/DefaultConfig";
    private static final String SCHOOL_REGISTRY = "io/redspace/ironsspellbooks/api/registry/SchoolRegistry";
    private static final String RARITY = "io/redspace/ironsspellbooks/api/spells/SpellRarity";
    private static final String RESOURCE = "net/minecraft/resources/ResourceLocation";
    private static final String MOD = "io/redspace/ironsspellbooks/IronsSpellbooks";
    private static final String CONFIG_PARAMETER = "io/redspace/ironsspellbooks/api/config/SpellConfigParameter";
    private static final Object UNKNOWN = new Object();
    private static final Object THIS = new Object();
    static final List<String> BASE_PARAMETERS = List.of("baseManaCost", "manaCostPerLevel",
            "baseSpellPower", "spellPowerPerLevel", "castTime");

    private final FormulaClassRepository repository;
    private final String namespace;
    private final Map<String, Object> schools;
    private final Map<String, Object> configDefaults;
    private final Map<String, Double> multipliers;

    FormulaSpellMetadata(FormulaClassRepository repository) {
        this.repository = repository;
        namespace = namespace(repository);
        schools = resourceFields(repository.classNode(SCHOOL_REGISTRY), namespace);
        configDefaults = defaultConfig(repository.classNode(CONFIG));
        var parameterClass = repository.classNode(CONFIG_PARAMETER);
        multipliers = parameterClass == null
                ? Map.of("POWER_MULTIPLIER", 1d, "MANA_MULTIPLIER", 1d) : multiplierDefaults(parameterClass);
    }

    JsonObject extract(ClassNode owner) {
        return encode(owner, readConstructor(repository, owner, schools, configDefaults, namespace), multipliers);
    }

    private static JsonObject encode(ClassNode owner, Map<String, Object> values, Map<String, Double> multipliers) {
        String id = required(values, "spellId", String.class);
        ConfigValue config = required(values, "defaultConfig", ConfigValue.class);
        Map<String, Object> defaults = config.values;
        String school = required(defaults, "schoolResource", String.class);
        JsonObject spell = new JsonObject();
        spell.addProperty("id", id);
        spell.addProperty("className", owner.name.replace('/', '.'));
        spell.addProperty("school", school);
        JsonObject advanced = new JsonObject();
        advanced.addProperty("enabled", required(defaults, "enabled", Number.class).intValue() != 0);
        advanced.addProperty("max_level", required(defaults, "maxLevel", Number.class).intValue());
        advanced.addProperty("min_rarity", required(defaults, "minRarity", String.class).toLowerCase(Locale.ROOT));
        advanced.addProperty("allow_crafting", required(defaults, "allowCrafting", Number.class).intValue() != 0);
        advanced.addProperty("school", school);
        spell.add("advanced", advanced);
        JsonObject parameters = new JsonObject();
        for (String field : BASE_PARAMETERS) parameters.addProperty(field, required(values, field, Number.class).intValue());
        parameters.addProperty("cooldown_in_seconds", required(defaults, "cooldownInSeconds", Number.class).doubleValue());
        parameters.addProperty("power_multiplier", required(multipliers, "POWER_MULTIPLIER", Double.class));
        parameters.addProperty("mana_cost_multiplier", required(multipliers, "MANA_MULTIPLIER", Double.class));
        spell.add("parameters", parameters);
        return spell;
    }

    static Map<String, Object> readConstructor(FormulaClassRepository repository, ClassNode owner,
                                               Map<String, Object> schools, Map<String, Object> configDefaults,
                                               String namespace) {
        MethodNode constructor = owner.methods.stream().filter(method -> method.name.equals("<init>")
                && method.desc.equals("()V")).findFirst().orElseThrow(() -> new IllegalArgumentException("No no-argument constructor"));
        if (!constructor.tryCatchBlocks.isEmpty())
            throw new IllegalArgumentException("Constructor exception regions are not supported");
        repository.ensureSource(owner);
        var flow = FormulaDataFlow.analyze(repository, owner.name, constructor);
        if (!flow.successful()) throw new IllegalArgumentException("Constructor shared data flow is unavailable: " + flow.diagnostics());
        Map<FormulaDataFlow.Node, Object> lowered = new HashMap<>();
        for (var parameter : flow.parameters())
            lowered.put(parameter, parameter.parameterSlot() == 0 ? THIS : UNKNOWN);
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var operation : flow.instructions()) {
            AbstractInsnNode instruction = operation.instruction();
            int opcode = instruction.getOpcode();
            if (opcode < 0 || !flow.reachable(instruction)) continue;
            Object result = constant(instruction);
            if (result == UNKNOWN) {
                switch (opcode) {
                    case Opcodes.NOP, Opcodes.RETURN, Opcodes.POP, Opcodes.POP2 -> { continue; }
                    case Opcodes.ACONST_NULL -> result = UNKNOWN;
                    case Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.LLOAD,
                            Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.LSTORE,
                            Opcodes.CHECKCAST, Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2, Opcodes.DUP2,
                            Opcodes.DUP2_X1, Opcodes.DUP2_X2, Opcodes.SWAP -> {
                        for (var output : operation.results()) {
                            var node = metadataNode(output);
                            var inputs = flow.dependencies(node);
                            if (inputs.size() != 1) throw new IllegalArgumentException("Constructor copy lacks an exact IR dependency");
                            lowered.put(node, metadataValue(inputs.get(0), lowered));
                        }
                        continue;
                    }
                    case Opcodes.NEW -> result = switch (((TypeInsnNode) instruction).desc) {
                        case CONFIG -> new ConfigValue(new LinkedHashMap<>(configDefaults));
                        case RESOURCE -> new ResourceValue();
                        default -> UNKNOWN;
                    };
                    case Opcodes.GETSTATIC -> {
                        var field = operation.field();
                        result = field.owner().equals(SCHOOL_REGISTRY) ? schools.getOrDefault(field.name(), UNKNOWN)
                                : field.owner().equals(RARITY) ? field.name() : UNKNOWN;
                    }
                    case Opcodes.PUTFIELD -> {
                        var field = operation.field();
                        Object receiver = metadataValue(field.receiver(), lowered);
                        Object value = metadataValue(field.value(), lowered);
                        if (receiver == THIS) fields.put(field.name(), value);
                        continue;
                    }
                    case Opcodes.GETFIELD -> {
                        var field = operation.field();
                        result = metadataValue(field.receiver(), lowered) == THIS ? fields.getOrDefault(field.name(), UNKNOWN) : UNKNOWN;
                    }
                    case Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL, Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> {
                        var call = operation.call();
                        Object[] arguments = call.arguments().stream().map(value -> metadataValue(value, lowered)).toArray();
                        Object receiver = call.receiver() == null ? UNKNOWN : metadataValue(call.receiver(), lowered);
                        result = invocation((MethodInsnNode) instruction, receiver, arguments, namespace);
                    }
                    default -> throw new IllegalArgumentException("Unsupported constructor opcode " + opcode);
                }
            }
            if (operation.results().isEmpty()) continue;
            if (operation.results().size() != 1) throw new IllegalArgumentException("Constructor IR result is not unique");
            lowered.put(metadataNode(operation.results().get(0)), result);
        }
        return fields;
    }

    private static FormulaDataFlow.Node metadataNode(FormulaDataFlow.Value value) {
        if (value == null || value.sources().size() != 1)
            throw new IllegalArgumentException("Constructor value lacks an exact IR source");
        return value.sources().iterator().next();
    }

    private static Object metadataValue(FormulaDataFlow.Value value, Map<FormulaDataFlow.Node, Object> lowered) {
        var node = metadataNode(value);
        if (!lowered.containsKey(node)) throw new IllegalArgumentException("Constructor source was not lowered: " + node.id());
        return lowered.get(node);
    }

    private static Object invocation(MethodInsnNode method, Object receiver, Object[] args, String namespace) {
        if (method.owner.equals(RESOURCE) && method.name.equals("<init>") && receiver instanceof ResourceValue resource) {
            if (args.length == 2 && args[0] instanceof String first && args[1] instanceof String second)
                resource.value = first + ':' + second;
            else if (args.length == 1 && args[0] instanceof String value)
                resource.value = value.indexOf(':') >= 0 ? value : "minecraft:" + value;
            return UNKNOWN;
        }
        if (method.owner.equals(RESOURCE) && method.name.equals("fromNamespaceAndPath")
                && args.length == 2 && args[0] instanceof String first && args[1] instanceof String second)
            return first + ':' + second;
        if (method.owner.equals(MOD) && method.name.equals("id") && args.length == 1 && args[0] instanceof String path)
            return namespace + ':' + path;
        if (method.owner.equals(CONFIG) && receiver instanceof ConfigValue config) {
            String field = switch (method.name) {
                case "setMinRarity" -> "minRarity";
                case "setSchoolResource" -> "schoolResource";
                case "setMaxLevel" -> "maxLevel";
                case "setCooldownSeconds" -> "cooldownInSeconds";
                case "setAllowCrafting" -> "allowCrafting";
                case "setDeprecated" -> "enabled";
                default -> null;
            };
            if (field != null && args.length == 1) {
                Object value = args[0];
                if (method.name.equals("setDeprecated"))
                    value = value instanceof Number number ? (number.intValue() == 0 ? 1 : 0) : UNKNOWN;
                config.values.put(field, value);
                return config;
            }
            if (method.name.equals("build") && args.length == 0) return config;
            if (method.name.equals("<init>") && args.length == 0) return UNKNOWN;
            throw new IllegalArgumentException("Unsupported DefaultConfig invocation " + method.name + method.desc);
        }
        return UNKNOWN;
    }

    private static String namespace(FormulaClassRepository repository) {
        ClassNode mod = repository.classNode(MOD);
        MethodNode helper = mod.methods.stream().filter(method -> method.name.equals("id")
                && method.desc.equals("(Ljava/lang/String;)L" + RESOURCE + ';')).findFirst().orElseThrow();
        List<AbstractInsnNode> instructions = executable(helper);
        if (instructions.size() == 6 && instructions.get(0) instanceof TypeInsnNode allocation
                && allocation.getOpcode() == Opcodes.NEW && allocation.desc.equals(RESOURCE)
                && instructions.get(1).getOpcode() == Opcodes.DUP && instructions.get(2) instanceof LdcInsnNode literal
                && literal.cst instanceof String result && instructions.get(3) instanceof VarInsnNode load
                && load.getOpcode() == Opcodes.ALOAD && load.var == 0 && instructions.get(4) instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(RESOURCE) && call.name.equals("<init>")
                && call.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V") && instructions.get(5).getOpcode() == Opcodes.ARETURN)
            return result;
        if (instructions.size() != 4 || !(instructions.get(0) instanceof LdcInsnNode literal)
                || !(literal.cst instanceof String result) || !(instructions.get(1) instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD || load.var != 0
                || !(instructions.get(2) instanceof MethodInsnNode call) || !call.owner.equals(RESOURCE)
                || !call.name.equals("fromNamespaceAndPath") || instructions.get(3).getOpcode() != Opcodes.ARETURN)
            throw new IllegalArgumentException("Unrecognized native namespace helper");
        return result;
    }

    private static Map<String, Object> resourceFields(ClassNode owner, String namespace) {
        Map<String, Object> result = new LinkedHashMap<>();
        MethodNode initializer = owner.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElseThrow();
        List<AbstractInsnNode> instructions = executable(initializer);
        for (int index = 2; index < instructions.size(); index++) {
            if (!(instructions.get(index) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !field.desc.equals('L' + RESOURCE + ';')) continue;
            if (!(instructions.get(index - 1) instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(MOD) && call.name.equals("id") && constant(instructions.get(index - 2)) instanceof String path)
                result.put(field.name, namespace + ':' + path);
            else if (index >= 3 && call.owner.equals(RESOURCE)
                    && (call.name.equals("fromNamespaceAndPath") || call.name.equals("<init>")
                    && call.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V"))
                    && constant(instructions.get(index - 3)) instanceof String first
                    && constant(instructions.get(index - 2)) instanceof String second)
                result.put(field.name, first + ':' + second);
        }
        return result;
    }

    private static Map<String, Object> defaultConfig(ClassNode owner) {
        MethodNode constructor = owner.methods.stream().filter(method -> method.name.equals("<init>")
                && method.desc.equals("()V")).findFirst().orElseThrow();
        List<AbstractInsnNode> instructions = executable(constructor);
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 2; index < instructions.size(); index++) {
            if (instructions.get(index) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                    && field.owner.equals(CONFIG) && instructions.get(index - 2) instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ALOAD && load.var == 0) {
                Object value = constant(instructions.get(index - 1));
                if (value != UNKNOWN) values.put(field.name, value);
            }
        }
        return values;
    }

    private static Map<String, Double> multiplierDefaults(ClassNode owner) {
        MethodNode initializer = owner.methods.stream().filter(method -> method.name.equals("<clinit>"))
                .findFirst().orElseThrow();
        List<AbstractInsnNode> instructions = executable(initializer);
        Map<String, Double> values = new LinkedHashMap<>();
        for (int index = 3; index < instructions.size(); index++) {
            if (!(instructions.get(index) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !Set.of("POWER_MULTIPLIER", "MANA_MULTIPLIER").contains(field.name)) continue;
            if (instructions.get(index - 1) instanceof MethodInsnNode constructor && constructor.owner.equals(CONFIG_PARAMETER)
                    && constructor.name.equals("<init>") && constructor.desc.endsWith("Ljava/lang/Object;)V")
                    && instructions.get(index - 2) instanceof MethodInsnNode boxing && boxing.owner.equals("java/lang/Double")
                    && boxing.name.equals("valueOf") && constant(instructions.get(index - 3)) instanceof Double value)
                values.put(field.name, value);
        }
        return values;
    }

    private static Object constant(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (opcode >= Opcodes.LCONST_0 && opcode <= Opcodes.LCONST_1) return (long) (opcode - Opcodes.LCONST_0);
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return (float) (opcode - Opcodes.FCONST_0);
        if (opcode >= Opcodes.DCONST_0 && opcode <= Opcodes.DCONST_1) return (double) (opcode - Opcodes.DCONST_0);
        if (instruction instanceof LdcInsnNode literal && (literal.cst instanceof String || literal.cst instanceof Number))
            return literal.cst;
        if (instruction instanceof IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return push.operand;
        return UNKNOWN;
    }

    private static List<AbstractInsnNode> executable(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    private static <T> T required(Map<String, ?> source, String name, Class<T> type) {
        Object value = source.get(name);
        if (value instanceof ResourceValue resource) value = resource.value;
        if (!type.isInstance(value)) throw new IllegalArgumentException("Unresolved native default " + name);
        return type.cast(value);
    }

    private record ConfigValue(Map<String, Object> values) {}
    private static final class ResourceValue {
        String value;
    }
}
