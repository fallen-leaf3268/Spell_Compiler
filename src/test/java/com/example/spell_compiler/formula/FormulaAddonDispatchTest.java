package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaAddonDispatchTest {
    private static final String DAMAGE = "getDamage(I)I";
    private static final String RANGE = "getRange()F";
    private SpellFormulaCatalog previous;

    @BeforeEach
    void saveDefinitions() throws Exception {
        previous = SpellFormulaState.initialize(getClass().getClassLoader());
        FormulaRuntimeDispatcher.clearServer();
        FormulaRuntimeDispatcher.clearClient();
    }

    @AfterEach
    void restoreDefinitions() {
        FormulaRuntimeDispatcher.clearServer();
        FormulaRuntimeDispatcher.clearClient();
        FormulaNativeValues.initialize(previous);
    }

    @Test
    void inheritedMethodUsesItsDeclaringOwnerForTheActualLeaf() throws Throwable {
        var definition = definition(InheritedLeaf.class, Parent.class, DAMAGE);
        install(InheritedLeaf.class, definition, DAMAGE, "300");
        var leaf = new InheritedLeaf();
        var invoker = FormulaRuntimeDispatcher.resolveDeclared(Parent.class.getName(), DAMAGE, leaf, null);
        assertNotNull(invoker);
        assertEquals(300, FormulaRuntimeDispatcher.invoke(invoker, leaf, new Object[]{2}).intValue());
        assertNull(FormulaRuntimeDispatcher.resolveDeclared(InheritedLeaf.class.getName(), DAMAGE, leaf, null));
        assertNull(FormulaRuntimeDispatcher.resolveDeclared(Parent.class.getName(), DAMAGE, new Parent(), null));
        assertEquals(12, leaf.getDamage(2));
    }

    @Test
    void explicitSuperEntryCannotResolveTheLeafOverride() throws Throwable {
        var definition = definition(OverrideLeaf.class, OverrideLeaf.class, DAMAGE);
        install(OverrideLeaf.class, definition, DAMAGE, "400");
        var leaf = new OverrideLeaf();
        assertNull(FormulaRuntimeDispatcher.resolveDeclared(Parent.class.getName(), DAMAGE, leaf, null));
        var invoker = FormulaRuntimeDispatcher.resolveDeclared(OverrideLeaf.class.getName(), DAMAGE, leaf, null);
        assertNotNull(invoker);
        assertEquals(400, FormulaRuntimeDispatcher.invoke(invoker, leaf, new Object[]{2}).intValue());
        assertEquals(12, leaf.superDamage(2));
        assertEquals(112, leaf.getDamage(2));
    }

    @Test
    void sameSignaturePrivateParentEntryCannotUseTheLeafPrivateBinding() throws Throwable {
        var definition = definition(PrivateLeaf.class, PrivateLeaf.class, DAMAGE);
        install(PrivateLeaf.class, definition, DAMAGE, "500");
        var leaf = new PrivateLeaf();
        assertNull(FormulaRuntimeDispatcher.resolveDeclared(PrivateParent.class.getName(), DAMAGE, leaf, null));
        var invoker = FormulaRuntimeDispatcher.resolveDeclared(PrivateLeaf.class.getName(), DAMAGE, leaf, null);
        assertNotNull(invoker);
        assertEquals(500, FormulaRuntimeDispatcher.invoke(invoker, leaf, new Object[]{2}).intValue());
        assertEquals(12, leaf.parentDamage(2));
        assertEquals(22, leaf.leafDamage(2));
    }

    @Test
    void staticEntryWithNullSelfResolvesOnlyItsOwnDeclaringClass() throws Throwable {
        var definition = definition(StaticLeaf.class, StaticLeaf.class, RANGE);
        install(StaticLeaf.class, definition, RANGE, "70");
        var invoker = FormulaRuntimeDispatcher.resolveDeclared(StaticLeaf.class.getName(), RANGE, null, null);
        assertNotNull(invoker);
        assertEquals(70F, FormulaRuntimeDispatcher.invoke(invoker, null, new Object[0]).floatValue());
        assertNull(FormulaRuntimeDispatcher.resolveDeclared(StaticParent.class.getName(), RANGE, null, null));
        assertEquals(20F, StaticParent.getRange());
        assertEquals(30F, StaticLeaf.getRange());
    }

    private static void install(Class<?> receiver, SpellFormulaCatalog.Spell definition,
                                String key, String expression) {
        FormulaNativeValues.initialize(new SpellFormulaCatalog(List.of(definition)));
        var compiled = FormulaDelegateFactory.compileSpell(receiver, definition, Map.of(key, expression));
        assertTrue(compiled.containsKey(key));
        var overrides = Map.of(FormulaRuntimeDispatcher.key(receiver.getName(), key), compiled.get(key));
        FormulaRuntimeDispatcher.replaceServer(overrides);
        FormulaRuntimeDispatcher.replaceClient(overrides);
    }

    private static SpellFormulaCatalog.Spell definition(Class<?> receiver, Class<?> declaring, String key) {
        boolean range = key.equals(RANGE);
        var method = new SpellFormulaCatalog.Method(range ? "range" : "damage",
                range ? "getRange" : "getDamage", range ? "()F" : "(I)I",
                range ? "30" : "level + 10", range ? "30" : "level + 10",
                range ? Map.of() : Map.of("level", "I"), declaring.getName());
        return new SpellFormulaCatalog.Spell("addon_dispatch:" + receiver.getSimpleName().toLowerCase(java.util.Locale.ROOT),
                receiver.getName(), "irons_spellbooks:fire", new JsonObject(), new JsonObject(), List.of(method));
    }

    public static class Parent {
        public int getDamage(int level) { return 10 + level; }
    }

    public static class InheritedLeaf extends Parent {}

    public static class OverrideLeaf extends Parent {
        @Override
        public int getDamage(int level) { return 100 + super.getDamage(level); }
        public int superDamage(int level) { return super.getDamage(level); }
    }

    public static class PrivateParent {
        private int getDamage(int level) { return 10 + level; }
        public int parentDamage(int level) { return getDamage(level); }
    }

    public static class PrivateLeaf extends PrivateParent {
        private int getDamage(int level) { return 20 + level; }
        public int leafDamage(int level) { return getDamage(level); }
    }

    public static class StaticParent {
        public static float getRange() { return 20; }
    }

    public static class StaticLeaf extends StaticParent {
        public static float getRange() { return 30; }
    }
}
