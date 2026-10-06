package io.minispring.core.fixtures.scan;

import io.minispring.core.annotation.Component;

/** Annotated but an interface: scanning must skip it instead of failing. */
@Component
public interface ComponentInterface {
}
