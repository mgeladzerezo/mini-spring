package io.minispring.core.fixtures.scan;

import io.minispring.core.annotation.Component;

/** Annotated but abstract: scanning must skip it instead of failing. */
@Component
public abstract class AbstractComponent {
}
