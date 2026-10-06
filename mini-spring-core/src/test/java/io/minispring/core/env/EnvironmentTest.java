package io.minispring.core.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minispring.core.convert.ConversionService;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proves property precedence, placeholder expansion and profile matching. */
class EnvironmentTest {

    private static Environment environment(Map<String, String> values) {
        Environment environment = new Environment(new ConversionService());
        environment.addLast(new MapPropertySource("test", values));
        return environment;
    }

    @Test
    void expandsPlaceholdersWithAndWithoutDefaults() {
        Environment environment = environment(Map.of("host", "db.internal"));

        assertEquals("jdbc://db.internal:5432/app",
                environment.resolvePlaceholders("jdbc://${host}:${port:5432}/app"));
    }

    @Test
    void expandsNestedDefaultsKeysAndValues() {
        Environment environment = environment(Map.of(
                "env", "prod",
                "db.prod.url", "jdbc:${vendor:h2}:prod",
                "fallback", "from-fallback"));

        assertEquals("jdbc:h2:prod", environment.resolvePlaceholders("${db.${env}.url}"));
        assertEquals("from-fallback", environment.resolvePlaceholders("${missing:${fallback:literal}}"));
        assertEquals("literal", environment.resolvePlaceholders("${missing:${also.missing:literal}}"));
    }

    @Test
    void aDefaultMayContainColons() {
        assertEquals("http://localhost:8080", environment(Map.of()).resolvePlaceholders("${url:http://localhost:8080}"));
    }

    @Test
    void anEmptyDefaultIsAllowed() {
        assertEquals("", environment(Map.of()).resolvePlaceholders("${nothing:}"));
    }

    @Test
    void reportsAnUnresolvablePlaceholderWithItsKeyAndContext() {
        UnresolvedPlaceholderException error = assertThrows(UnresolvedPlaceholderException.class,
                () -> environment(Map.of()).resolvePlaceholders("url=${db.url}"));

        assertEquals("db.url", error.key());
        assertTrue(error.getMessage().contains("url=${db.url}"), error.getMessage());
    }

    @Test
    void detectsCircularReferencesInsteadOfOverflowingTheStack() {
        Environment environment = environment(Map.of("a", "${b}", "b", "${a}"));

        UnresolvedPlaceholderException error = assertThrows(UnresolvedPlaceholderException.class,
                () -> environment.getProperty("a"));

        assertTrue(error.getMessage().contains("circular reference b -> a -> b"), error.getMessage());
    }

    @Test
    void leavesUnterminatedPlaceholdersAlone() {
        assertEquals("cost: ${5", environment(Map.of()).resolvePlaceholders("cost: ${5"));
    }

    @Test
    void earlierSourcesOverrideLaterOnes() {
        Environment environment = environment(Map.of("port", "8080", "name", "base"));
        environment.addFirst(new MapPropertySource("override", Map.of("port", "9090")));

        assertEquals(Optional.of(9090), environment.getProperty("port", Integer.class));
        assertEquals("base", environment.getProperty("name", "none"));
        assertEquals(Duration.ofSeconds(3), environment.getProperty("timeout", Duration.class, Duration.ofSeconds(3)));
    }

    @Test
    void environmentVariablesAreFoundUnderRelaxedNames() {
        PropertySource source = new SystemEnvironmentPropertySource(Map.of("BANK_SEED_DEMO_DATA", "true", "exact.key", "1"));

        assertEquals(Optional.of("true"), source.get("bank.seed-demo-data"));
        assertEquals(Optional.of("1"), source.get("exact.key"));
        assertEquals(Optional.empty(), source.get("bank.other"));
    }

    @Test
    void commandLineArgumentsBecomeProperties() {
        PropertySource source = MapPropertySource.fromCommandLine("--server.port=0", "--verbose", "ignored", "--a=b=c");

        assertEquals(Optional.of("0"), source.get("server.port"));
        assertEquals(Optional.of("true"), source.get("verbose"));
        assertEquals(Optional.of("b=c"), source.get("a"));
        assertEquals(Optional.empty(), source.get("ignored"));
    }

    @Test
    void profilesComeFromThePropertyAndFromCode() {
        Environment environment = environment(Map.of(Environment.ACTIVE_PROFILES_PROPERTY, "dev, local"));
        environment.addActiveProfiles("test");

        assertEquals(Set.of("dev", "local", "test"), environment.activeProfiles());
        assertTrue(environment.acceptsProfiles("prod", "dev"));
        assertTrue(environment.acceptsProfiles("!prod"));
        assertFalse(environment.acceptsProfiles("!dev"));
        assertFalse(environment.acceptsProfiles("prod"));
    }

    @Test
    void loadsPropertyFilesFromTheClassPath() {
        ClassLoader loader = getClass().getClassLoader();

        assertTrue(MapPropertySource.fromClasspath("no-such-file.properties", loader).isEmpty());
        assertEquals(Optional.of("from-file"),
                MapPropertySource.fromClasspath("env-test.properties", loader).orElseThrow().get("sample.key"));
    }
}
