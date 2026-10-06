package io.minispring.core.env;

import io.minispring.core.convert.ConversionService;
import io.minispring.core.type.ResolvedType;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The application's configuration: an ordered stack of {@link PropertySource}s, the set of
 * active profiles and placeholder expansion.
 *
 * <p>Sources are consulted first to last and the first hit wins, which is what lets a command
 * line argument override an environment variable override {@code application.properties}.
 */
public final class Environment {

    /** Property that lists active profiles, comma separated. */
    public static final String ACTIVE_PROFILES_PROPERTY = "mini.profiles.active";

    private final List<PropertySource> sources = new CopyOnWriteArrayList<>();
    private final ConversionService conversionService;
    private final PlaceholderResolver placeholders = new PlaceholderResolver(this::rawProperty);
    private final Set<String> explicitProfiles = new LinkedHashSet<>();

    public Environment(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    /** Adds a source that overrides every source added so far. */
    public void addFirst(PropertySource source) {
        sources.addFirst(source);
    }

    /** Adds a source consulted only when no other source has the key. */
    public void addLast(PropertySource source) {
        sources.addLast(source);
    }

    public List<PropertySource> propertySources() {
        return List.copyOf(sources);
    }

    /** Activates profiles programmatically, in addition to {@value #ACTIVE_PROFILES_PROPERTY}. */
    public void addActiveProfiles(String... profiles) {
        explicitProfiles.addAll(Arrays.asList(profiles));
    }

    public Set<String> activeProfiles() {
        Set<String> profiles = new LinkedHashSet<>(explicitProfiles);
        getProperty(ACTIVE_PROFILES_PROPERTY).ifPresent(list -> {
            for (String profile : list.split(",")) {
                if (!profile.isBlank()) {
                    profiles.add(profile.strip());
                }
            }
        });
        return profiles;
    }

    /** Whether any expression matches: a profile name, or {@code !name} for "not active". */
    public boolean acceptsProfiles(String... expressions) {
        Set<String> active = activeProfiles();
        for (String expression : expressions) {
            boolean negated = expression.startsWith("!");
            String profile = negated ? expression.substring(1) : expression;
            if (active.contains(profile) != negated) {
                return true;
            }
        }
        return false;
    }

    /** The property's value with placeholders expanded. */
    public Optional<String> getProperty(String key) {
        return rawProperty(key).map(placeholders::resolve);
    }

    public String getProperty(String key, String defaultValue) {
        return getProperty(key).orElse(defaultValue);
    }

    public <T> Optional<T> getProperty(String key, Class<T> type) {
        return getProperty(key).map(value -> conversionService.convert(value, type));
    }

    public <T> T getProperty(String key, Class<T> type, T defaultValue) {
        return getProperty(key, type).orElse(defaultValue);
    }

    public boolean containsProperty(String key) {
        return rawProperty(key).isPresent();
    }

    /**
     * Expands every placeholder in {@code text}.
     *
     * @throws UnresolvedPlaceholderException if a placeholder has neither a value nor a default
     */
    public String resolvePlaceholders(String text) {
        return placeholders.resolve(text);
    }

    /** Expands placeholders and converts the result, e.g. for {@code @Value} injection. */
    public Object resolveValue(String expression, ResolvedType targetType) {
        return conversionService.convert(resolvePlaceholders(expression), targetType);
    }

    public ConversionService conversionService() {
        return conversionService;
    }

    private Optional<String> rawProperty(String key) {
        for (PropertySource source : sources) {
            Optional<String> value = source.get(key);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }
}
