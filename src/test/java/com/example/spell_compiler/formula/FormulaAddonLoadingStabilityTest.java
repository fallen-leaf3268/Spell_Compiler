package com.example.spell_compiler.formula;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class FormulaAddonLoadingStabilityTest {
    private ClassLoader original;

    @BeforeEach
    void saveLoader() {
        original = Thread.currentThread().getContextClassLoader();
    }

    @AfterEach
    void restoreLoader() {
        FormulaDynamicMixin.restore();
        Thread.currentThread().setContextClassLoader(original);
    }

    @Test
    void repeatedIndependentResourceSupplyRestoresTheLoaderAndKeepsTargetBytesUsable() throws Exception {
        String previous = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            String generated = FormulaDynamicMixin.install("fixture.loading",
                    Set.of("fixture.addon.Zeta", "fixture.addon.Alpha"));
            assertNotEquals(previous, generated);
            ClassLoader provider = Thread.currentThread().getContextClassLoader();
            assertNotSame(original, provider);
            assertSame(original, provider.getParent());
            URL resource = provider.getResource("fixture/loading/" + generated + ".class");
            assertNotNull(resource);
            assertSame(original, Thread.currentThread().getContextClassLoader());
            var node = new ClassNode();
            try (var input = resource.openStream()) { new ClassReader(input).accept(node, 0); }
            assertEquals("fixture/loading/" + generated, node.name);
            assertTrue(node.invisibleAnnotations.stream()
                    .anyMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Pseudo;")));
            var mixin = node.invisibleAnnotations.stream()
                    .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
            int targets = mixin.values.indexOf("targets");
            assertEquals(List.of("fixture.addon.Alpha", "fixture.addon.Zeta"), mixin.values.get(targets + 1));
            previous = generated;
        }
    }

    @Test
    void replacingAPendingResourceWithAFailedCreationRestoresTheOriginalLoader() {
        FormulaDynamicMixin.install("fixture.loading", Set.of("fixture.addon.Alpha"));
        assertNotSame(original, Thread.currentThread().getContextClassLoader());
        assertThrows(NullPointerException.class,
                () -> FormulaDynamicMixin.install(null, Set.of("fixture.addon.Alpha")));
        assertSame(original, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void pluginResourceSupplyFailureKeepsNativeBindingsAndDeclinesTheAddonMixin() throws Exception {
        var method = new SpellFormulaCatalog.Method("damage", "getDamage", "()I", "int(5)", "5",
                Map.of(), "fixture.NativeSpell");
        var nativeSpell = new SpellFormulaCatalog.Spell("fixture:native", "fixture.NativeSpell", "fixture:school",
                new JsonObject(), new JsonObject(), List.of(method));
        var plugin = new FormulaMixinPlugin(new SpellFormulaCatalog(List.of(nativeSpell)));
        var addon = new ClassNode(Opcodes.ASM9);
        addon.name = "fixture/addon/Alpha";
        addon.superName = "java/lang/Object";
        var discovery = new FormulaAddonDiscovery.Discovery(FormulaClassRepository.of(addon), List.of(addon),
                Set.of(addon.name), Map.of(addon.name, "fixture_addon"));
        set(plugin, "addons", discovery);
        set(plugin, "mixinPackage", null);
        FormulaDynamicMixin.install("fixture.loading", Set.of("fixture.addon.Pending"));
        assertNull(plugin.getMixins());
        assertSame(original, Thread.currentThread().getContextClassLoader());
        assertTrue(plugin.shouldApplyMixin("fixture.NativeSpell", "fixture.GeneratedSpellFormulaMixin"));
        assertFalse(plugin.shouldApplyMixin("fixture.addon.Alpha", "fixture.loading.GeneratedAddonFormulaMixin"));
    }

    @Test
    void abortingBeforeTheResourceIsRequestedRestoresTheLoaderAtTheMixinLifecycleBoundary() {
        FormulaDynamicMixin.install("fixture.loading", Set.of("fixture.addon.Alpha"));
        var plugin = new FormulaMixinPlugin(new SpellFormulaCatalog(List.of()));
        plugin.acceptTargets(Set.of(), Set.of());
        assertSame(original, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void unrelatedResourceProviderFailurePropagatesAndCanBeExplicitlyRestored() {
        var expected = new IllegalStateException("resource provider failure");
        ClassLoader failing = new ClassLoader(original) {
            @Override
            public URL getResource(String name) {
                if (name.equals("fixture/loading/broken.class")) throw expected;
                return super.getResource(name);
            }
        };
        Thread.currentThread().setContextClassLoader(failing);
        FormulaDynamicMixin.install("fixture.loading", Set.of("fixture.addon.Alpha"));
        var provider = Thread.currentThread().getContextClassLoader();
        assertSame(expected, assertThrows(IllegalStateException.class,
                () -> provider.getResource("fixture/loading/broken.class")));
        FormulaDynamicMixin.restore();
        assertSame(failing, Thread.currentThread().getContextClassLoader());
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
