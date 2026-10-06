package io.minispring.core.context;

/** Published first thing on shutdown, while every bean is still usable. */
public record ContextClosedEvent(ApplicationContext context) {
}
