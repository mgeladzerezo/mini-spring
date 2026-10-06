package io.minispring.core.condition;

import io.minispring.core.env.Environment;

/** What a {@link Condition} may consult. */
public record ConditionContext(Environment environment, ClassLoader classLoader) {
}
