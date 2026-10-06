package io.minispring.core.context;

/** Published once startup has finished: all singletons exist and {@link Lifecycle} beans run. */
public record ContextRefreshedEvent(ApplicationContext context) {
}
