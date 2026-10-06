package io.minispring.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Narrows injection to the bean carrying the same qualifier value (or having that bean name).
 *
 * <p>An annotation that is itself annotated with {@code @Qualifier} becomes a type-safe
 * qualifier: it matches beans that carry the same annotation.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Qualifier {

    String value() default "";
}
