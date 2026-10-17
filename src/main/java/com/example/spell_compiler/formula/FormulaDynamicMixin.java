package com.example.spell_compiler.formula;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

final class FormulaDynamicMixin {
    private static final AtomicInteger SERIAL = new AtomicInteger();
    private static ResourceClassLoader pending;

    private FormulaDynamicMixin() {}

    static synchronized String install(String mixinPackage, Set<String> targets) {
        restore();
        if (targets.isEmpty()) throw new IllegalArgumentException("附属法术Mixin目标不能为空");
        String simpleName = "GeneratedAddonFormulaMixin$" + SERIAL.incrementAndGet();
        String internalName = mixinPackage.replace('.', '/') + '/' + simpleName;
        byte[] bytes = create(internalName, targets);
        Thread thread = Thread.currentThread();
        var loader = new ResourceClassLoader(thread, internalName + ".class", bytes);
        thread.setContextClassLoader(loader);
        pending = loader;
        return simpleName;
    }

    static synchronized void restore() {
        ResourceClassLoader loader = pending;
        pending = null;
        if (loader != null && loader.thread.getContextClassLoader() == loader)
            loader.thread.setContextClassLoader(loader.getParent());
    }

    private static byte[] create(String name, Set<String> targets) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
        writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Pseudo;", false).visitEnd();
        var mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        mixin.visit("remap", false);
        mixin.visit("priority", 1100);
        var values = mixin.visitArray("targets");
        targets.stream().sorted().forEach(target -> values.visit(null, target));
        values.visitEnd();
        mixin.visitEnd();
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class ResourceClassLoader extends ClassLoader {
        private final Thread thread;
        private final String resource;
        private final URL url;

        private ResourceClassLoader(Thread thread, String resource, byte[] bytes) {
            super(thread.getContextClassLoader());
            this.thread = thread;
            this.resource = resource;
            try {
                url = new URL(null, "spell-compiler-mixin:/" + resource, new URLStreamHandler() {
                    @Override
                    protected URLConnection openConnection(URL target) {
                        return new URLConnection(target) {
                            @Override
                            public void connect() {}

                            @Override
                            public InputStream getInputStream() {
                                return new ByteArrayInputStream(bytes);
                            }

                            @Override
                            public int getContentLength() {
                                return bytes.length;
                            }
                        };
                    }
                });
            } catch (java.net.MalformedURLException failure) {
                throw new IllegalStateException("无法创建附属法术的内存Mixin资源", failure);
            }
        }

        @Override
        public URL getResource(String name) {
            if (!resource.equals(name)) return super.getResource(name);
            restore();
            return url;
        }
    }
}
