package io.minispring.core.env;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Operating system environment variables with relaxed names: {@code server.port} and
 * {@code bank.seed-demo-data} are also looked up as {@code SERVER_PORT} and
 * {@code BANK_SEED_DEMO_DATA}, which is what a container can actually set.
 */
public final class SystemEnvironmentPropertySource implements PropertySource {

    private final Map<String, String> environment;

    public SystemEnvironmentPropertySource() {
        this(System.getenv());
    }

    public SystemEnvironmentPropertySource(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override
    public String name() {
        return "systemEnvironment";
    }

    @Override
    public Optional<String> get(String key) {
        String exact = environment.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }
        String relaxed = key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        return Optional.ofNullable(environment.get(relaxed));
    }
}
