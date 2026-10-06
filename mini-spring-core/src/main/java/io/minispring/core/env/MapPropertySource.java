package io.minispring.core.env;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** A property source backed by a map. */
public record MapPropertySource(String name, Map<String, String> values) implements PropertySource {

    public MapPropertySource {
        values = Map.copyOf(values);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    /** JVM system properties, read live so later {@code System.setProperty} calls are visible. */
    public static PropertySource systemProperties() {
        return new PropertySource() {
            @Override
            public String name() {
                return "systemProperties";
            }

            @Override
            public Optional<String> get(String key) {
                return Optional.ofNullable(System.getProperty(key));
            }
        };
    }

    /**
     * Loads a {@code .properties} file from the class path (UTF-8).
     *
     * @return the source, or empty if the resource does not exist
     */
    public static Optional<PropertySource> fromClasspath(String resource, ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            Map<String, String> values = new LinkedHashMap<>();
            properties.stringPropertyNames().forEach(key -> values.put(key, properties.getProperty(key)));
            return Optional.of(new MapPropertySource("classpath:" + resource, values));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read class path resource " + resource, e);
        }
    }

    /** Parses {@code --key=value} and {@code --flag} program arguments. */
    public static PropertySource fromCommandLine(String... args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.length() > 2) {
                int equals = arg.indexOf('=');
                if (equals < 0) {
                    values.put(arg.substring(2), "true");
                } else {
                    values.put(arg.substring(2, equals), arg.substring(equals + 1));
                }
            }
        }
        return new MapPropertySource("commandLine", values);
    }
}
