package io.minispring.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stereotype for a business service. It carries no behaviour of its own: it is a
 * {@link Component} by meta-annotation, which is exactly how the scanner finds it.
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Service {

    /** Bean name, forwarded to {@link Component#value()}. */
    @AliasFor(annotation = Component.class, attribute = "value")
    String value() default "";
}
