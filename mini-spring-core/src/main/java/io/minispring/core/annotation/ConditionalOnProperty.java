package io.minispring.core.annotation;

import io.minispring.core.condition.OnPropertyCondition;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers the bean only if a property has the expected value.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnPropertyCondition.class)
public @interface ConditionalOnProperty {

    /** Property key. */
    String name();

    /** Required value; empty means "present and not {@code false}". */
    String havingValue() default "";

    /** Whether the condition holds when the property is absent. */
    boolean matchIfMissing() default false;
}
