package io.minispring.core.condition;

import java.lang.reflect.AnnotatedElement;

/**
 * A yes/no decision about registering a bean, taken while definitions are read and before any
 * bean exists. Implementations need a public no-arg constructor.
 */
public interface Condition {

    /**
     * @param element the class or {@code @Bean} method carrying the condition annotation
     */
    boolean matches(ConditionContext context, AnnotatedElement element);

    /** Explains a negative outcome; shown when someone later asks for the missing bean. */
    default String describeMismatch(ConditionContext context, AnnotatedElement element) {
        return getClass().getSimpleName() + " did not match";
    }
}
