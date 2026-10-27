/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 8365439
 * @summary Include class-loader details when same-named modules cause access errors.
 * @run main/othervm SameNamedModulesDifferentClassLoaders
 */

import java.io.IOException;
import java.io.OutputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public class SameNamedModulesDifferentClassLoaders {
    private static final String MODULE_NAME = "same.name";

    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("A JDK compiler is required to run this test");
        }

        Path work = Files.createTempDirectory("same-named-modules");
        Path sourceDir = Files.createDirectories(work.resolve("source-src/p1"));
        Path targetDir = Files.createDirectories(work.resolve("target-src/p2"));
        Path sourceClasses = Files.createDirectories(work.resolve("source-classes"));
        Path targetClasses = Files.createDirectories(work.resolve("target-classes"));
        Path targetHiddenClasses = Files.createDirectories(work.resolve("target-hidden-classes"));
        Path exportedClasses = Files.createDirectories(work.resolve("target-exported-classes"));

        Path sourceClass = sourceDir.resolve("c1.java");
        Path targetClass = targetDir.resolve("c2.java");
        Path sourceModuleInfo = sourceDir.getParent().resolve("module-info.java");
        Path targetExportedSource = Files.createDirectories(work.resolve("target-exported-src"));
        Path targetHiddenSource = Files.createDirectories(work.resolve("target-hidden-src"));
        Path targetExportedModuleInfo = targetExportedSource.resolve("module-info.java");
        Path targetHiddenModuleInfo = targetHiddenSource.resolve("module-info.java");

        Files.writeString(sourceClass,
                "package p1; public class c1 { " +
                "public c1() { new p2.c2().method2(); } }\n");
        Files.writeString(targetClass,
                "package p2; public class c2 { public void method2() {} }\n");
        Files.writeString(sourceModuleInfo,
                "module " + MODULE_NAME + " { exports p1; }\n");
        Files.writeString(targetExportedModuleInfo,
                "module " + MODULE_NAME + " { exports p2; }\n");
        Files.writeString(targetHiddenModuleInfo,
                "module " + MODULE_NAME + " { }\n");

        compile(compiler, "-d", targetClasses.toString(), targetClass.toString());
        compile(compiler, "-classpath", targetClasses.toString(),
                "-d", sourceClasses.toString(), sourceClass.toString());
        compile(compiler, "-d", sourceClasses.toString(), sourceModuleInfo.toString());

        Path targetClassFile = targetClasses.resolve("p2/c2.class");
        Files.copy(targetClassFile, exportedClasses.resolve("p2/c2.class"));
        Files.copy(targetClassFile, targetHiddenClasses.resolve("p2/c2.class"));
        compile(compiler, "-d", exportedClasses.toString(), targetExportedModuleInfo.toString());
        compile(compiler, "-d", targetHiddenClasses.toString(), targetHiddenModuleInfo.toString());

        Path sourceJar = createJar(work.resolve("source.jar"), sourceClasses);
        Path exportedTargetJar = createJar(work.resolve("target-exported.jar"), exportedClasses);
        Path hiddenTargetJar = createJar(work.resolve("target-hidden.jar"), targetHiddenClasses);

        // Same-named modules in different class loaders, without a read edge.
        expectDiagnostic(sourceJar, exportedTargetJar, false, "does not read module");
        // Once readability is added, the non-exported package is the restriction.
        expectDiagnostic(sourceJar, hiddenTargetJar, true, "does not export p2");
    }

    private static void compile(JavaCompiler compiler, String... arguments) {
        int result = compiler.run(null, System.out, System.err, arguments);
        if (result != 0) {
            throw new AssertionError("javac failed with exit code " + result);
        }
    }

    private static Path createJar(Path jar, Path classes) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(classes)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.naturalOrder());
        try (OutputStream fileOut = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            for (Path file : files) {
                String entryName = classes.relativize(file).toString()
                        .replace(file.getFileSystem().getSeparator(), "/");
                jarOut.putNextEntry(new JarEntry(entryName));
                Files.copy(file, jarOut);
                jarOut.closeEntry();
            }
        }
        return jar;
    }

    private static void expectDiagnostic(Path sourceJar, Path targetJar,
                                         boolean addReadEdge, String expectedReason) throws Exception {
        ClassLoader platformLoader = ClassLoader.getPlatformClassLoader();
        try (URLClassLoader targetLoader = new URLClassLoader(
                     new URL[] { targetJar.toUri().toURL() }, platformLoader)) {
            ModuleLayer targetLayer = defineLayer(targetJar, targetLoader);

            URLClassLoader sourceLoader = new URLClassLoader(
                    new URL[] { sourceJar.toUri().toURL() }, platformLoader) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve)
                        throws ClassNotFoundException {
                    if (name.equals("p2.c2")) {
                        return targetLoader.loadClass(name);
                    }
                    return super.loadClass(name, resolve);
                }
            };
            try (sourceLoader) {
                ModuleLayer.Controller controller = defineLayerController(sourceJar, sourceLoader);
                ModuleLayer sourceLayer = controller.layer();

                Module sourceModule = sourceLayer.findModule(MODULE_NAME).orElseThrow();
                Module targetModule = targetLayer.findModule(MODULE_NAME).orElseThrow();
                if (addReadEdge) {
                    controller.addReads(sourceModule, targetModule);
                }

                Class<?> sourceClass = sourceLayer.findLoader(MODULE_NAME).loadClass("p1.c1");
                Class<?> targetClass = targetLayer.findLoader(MODULE_NAME).loadClass("p2.c2");
                if (sourceClass.getModule() == targetClass.getModule()
                        || !sourceModule.getName().equals(targetModule.getName())
                        || sourceClass.getClassLoader() == targetClass.getClassLoader()) {
                    throw new AssertionError(
                            "Test setup did not create same-named modules in different loaders");
                }

                try {
                    sourceClass.getDeclaredConstructor().newInstance();
                    throw new AssertionError("Expected IllegalAccessError for " + expectedReason);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (!(cause instanceof IllegalAccessError)) {
                        throw new AssertionError("Expected IllegalAccessError, got " + cause, cause);
                    }
                    String message = cause.getMessage();
                    requireContains(message, "p1.c1");
                    requireContains(message, "p2.c2");
                    requireContains(message, MODULE_NAME);
                    requireContains(message, expectedReason);
                    requireContains(message, "of loader");
                    int firstLoader = message.indexOf("of loader");
                    if (message.indexOf("of loader", firstLoader + 1) < 0) {
                        throw new AssertionError("Expected source and target loader details: " + message);
                    }
                }
            } finally {
                // Explicitly close loaders even if test code throws.
                sourceLoader.close();
            }
        }
    }

    private static ModuleLayer defineLayer(Path jar, ClassLoader loader) {
        return defineLayerController(jar, loader).layer();
    }

    private static ModuleLayer.Controller defineLayerController(Path jar, ClassLoader loader) {
        Configuration configuration = ModuleLayer.boot().configuration()
                .resolve(ModuleFinder.of(jar), ModuleFinder.of(), Set.of(MODULE_NAME));
        return ModuleLayer.defineModules(configuration, List.of(ModuleLayer.boot()), name -> loader);
    }

    private static void requireContains(String text, String expected) {
        if (text == null || !text.contains(expected)) {
            throw new AssertionError("Expected '" + expected + "' in error message: " + text);
        }
    }
}
