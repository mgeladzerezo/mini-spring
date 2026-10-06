package io.minispring.core.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.annotation.Component;
import io.minispring.core.context.ApplicationContext;
import io.minispring.core.fixtures.scan.CustomAnnotated;
import io.minispring.core.fixtures.scan.ScannedService;
import io.minispring.core.fixtures.scan.sub.ScannedRepository;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves hand-written class path scanning over directories and over jar files. */
class ClassPathScannerTest {

    private static final String FIXTURES = "io.minispring.core.fixtures.scan";

    @Test
    void listsClassesOfAPackageTreeInADirectoryInSortedOrder() {
        Set<String> names = new ClassPathScanner(getClass().getClassLoader()).findClassNames(FIXTURES);

        assertEquals(List.of(
                FIXTURES + ".AbstractComponent",
                FIXTURES + ".ComponentInterface",
                FIXTURES + ".CustomAnnotated",
                FIXTURES + ".CustomStereotype",
                FIXTURES + ".NamedComponent",
                FIXTURES + ".PlainClass",
                FIXTURES + ".ScannedService",
                FIXTURES + ".sub.ScannedRepository"), List.copyOf(names), "package-info is not a class to load");
    }

    @Test
    void anUnknownPackageYieldsNothing() {
        assertTrue(new ClassPathScanner(getClass().getClassLoader()).scan("no.such.pkg").isEmpty());
    }

    @Test
    void scanningRegistersConcreteClassesCarryingAStereotypeDirectlyOrByMetaAnnotation() {
        try (ApplicationContext context = ApplicationContext.builder().scan(FIXTURES).build()) {
            Set<String> scanned = context.getBeanNames().stream()
                    .filter(name -> context.getBeanDefinition(name).beanClass().getPackageName().startsWith(FIXTURES))
                    .collect(Collectors.toSet());

            assertEquals(Set.of("customAnnotated", "custom-name", "scannedService", "scannedRepository"), scanned);
            assertNotNull(context.getBean(ScannedService.class));
            assertNotNull(context.getBean(ScannedRepository.class));
            assertNotNull(context.getBean(CustomAnnotated.class));
            assertEquals(8, context.startupReport().scannedClasses());
        }
    }

    @Test
    void scansInsideAJarAndReleasesTheFile(@TempDir Path temp) throws Exception {
        Path jar = buildJar(temp);
        Object origin;
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
            assertEquals(Set.of("jarfixture.JarService", "jarfixture.deep.JarHelper"),
                    new ClassPathScanner(loader).findClassNames("jarfixture"));

            try (ApplicationContext context = ApplicationContext.builder().classLoader(loader).scan("jarfixture").build()) {
                Object service = context.getBean("jarService");
                origin = service.getClass().getMethod("origin").invoke(service);
                assertTrue(service.getClass().getProtectionDomain().getCodeSource().getLocation().toString()
                        .endsWith("fixture.jar"));
                assertFalse(context.containsBean("jarHelper"), "JarHelper has no stereotype");
            }
        }
        assertEquals("loaded from a jar", origin);
        // On Windows an open JarFile handle would make this fail; the scanner must not leak one.
        Files.delete(jar);
    }

    /** Compiles a tiny component with the JDK compiler and packs it into a jar that is not on the class path. */
    private static Path buildJar(Path temp) throws Exception {
        Path sources = Files.createDirectories(temp.resolve("src/jarfixture/deep"));
        Path service = sources.getParent().resolve("JarService.java");
        Files.writeString(service, """
                package jarfixture;

                @io.minispring.core.annotation.Service
                public class JarService {
                    public String origin() {
                        return "loaded from a jar";
                    }
                }
                """);
        Path helper = sources.resolve("JarHelper.java");
        Files.writeString(helper, "package jarfixture.deep; public class JarHelper { }");

        Path classes = Files.createDirectories(temp.resolve("classes"));
        String coreClasses = Path.of(Component.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int exitCode = compiler.run(null, null, null, "-d", classes.toString(), "-classpath", coreClasses,
                service.toString(), helper.toString());
        assertEquals(0, exitCode, "fixture compilation failed");

        Path jar = temp.resolve("fixture.jar");
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out);
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.sorted().toList()) {
                String entryName = classes.relativize(file).toString().replace('\\', '/');
                if (entryName.isEmpty()) {
                    continue;
                }
                // Directory entries matter: ClassLoader.getResources("jarfixture") only finds a package that has one.
                jarOut.putNextEntry(new JarEntry(Files.isDirectory(file) ? entryName + "/" : entryName));
                if (Files.isRegularFile(file)) {
                    Files.copy(file, jarOut);
                }
                jarOut.closeEntry();
            }
        }
        return jar;
    }
}
