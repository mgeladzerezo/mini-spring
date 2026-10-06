package io.minispring.core.scan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Finds the classes of a package by reading the class path, which Java offers no API for.
 *
 * <p>A class loader can be asked for a <em>resource</em> by name, and a package is a resource:
 * {@code getResources("com/acme/shop")} returns one URL per class path entry containing that
 * directory. A {@code file:} URL is a directory to walk; a {@code jar:} URL points into an
 * archive whose entries are listed. Either way the result is a set of class names, which are
 * then loaded <em>without initialising them</em> so that scanning runs no static initialisers.
 *
 * <p>Names are sorted, so registration order (and therefore startup order) does not depend on
 * file system iteration order. Jars nested inside jars are not supported.
 */
public final class ClassPathScanner {

    private static final System.Logger LOG = System.getLogger(ClassPathScanner.class.getName());
    private static final String CLASS_SUFFIX = ".class";

    private final ClassLoader classLoader;

    public ClassPathScanner(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** Loads every class in {@code basePackage} and its sub-packages. */
    public List<Class<?>> scan(String basePackage) {
        List<Class<?>> classes = new ArrayList<>();
        for (String className : findClassNames(basePackage)) {
            try {
                classes.add(Class.forName(className, false, classLoader));
            } catch (ClassNotFoundException | LinkageError e) {
                // A class whose own dependencies are missing cannot be a bean; skip it like Spring does.
                LOG.log(System.Logger.Level.DEBUG, "Skipping unloadable class {0}: {1}", className, e);
            }
        }
        return classes;
    }

    /** Lists fully qualified class names under a package without loading anything. */
    public Set<String> findClassNames(String basePackage) {
        String packagePath = basePackage.replace('.', '/');
        Set<String> names = new TreeSet<>();
        try {
            Enumeration<URL> roots = classLoader.getResources(packagePath);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                switch (root.getProtocol()) {
                    case "file" -> scanDirectory(Path.of(root.toURI()), basePackage, names);
                    case "jar" -> scanJar(root, names);
                    default -> LOG.log(System.Logger.Level.WARNING,
                            "Cannot scan {0}: unsupported protocol ''{1}''", root, root.getProtocol());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Scanning package " + basePackage + " failed", e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Class path entry is not a valid URI", e);
        }
        return names;
    }

    private static void scanDirectory(Path directory, String basePackage, Set<String> names) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.filter(Files::isRegularFile)
                    .map(file -> directory.relativize(file).toString().replace('\\', '/'))
                    .filter(ClassPathScanner::isCandidate)
                    .map(relative -> basePackage + "." + toClassName(relative))
                    .forEach(names::add);
        }
    }

    private static void scanJar(URL root, Set<String> names) throws IOException {
        JarURLConnection connection = (JarURLConnection) root.openConnection();
        // Without this the JDK caches the JarFile, and closing our handle would break other users of it.
        connection.setUseCaches(false);
        String entry = connection.getEntryName();
        String prefix = entry.endsWith("/") ? entry : entry + "/";
        try (JarFile jar = connection.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                if (entryName.startsWith(prefix) && isCandidate(entryName)) {
                    names.add(toClassName(entryName));
                }
            }
        }
    }

    private static boolean isCandidate(String path) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return fileName.endsWith(CLASS_SUFFIX)
                && !fileName.equals("module-info.class")
                && !fileName.equals("package-info.class");
    }

    private static String toClassName(String path) {
        return path.substring(0, path.length() - CLASS_SUFFIX.length()).replace('/', '.');
    }
}
