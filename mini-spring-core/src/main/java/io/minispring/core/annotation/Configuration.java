package io.minispring.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class whose {@link Bean} methods contribute bean definitions.
 *
 * <p>Unlike Spring, the class is not subclassed to intercept calls between {@code @Bean} methods
 * (Spring's "full" mode). Express a dependency between two factory methods as a method
 * parameter instead of calling the other method.
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Configuration {

    /** Bean name, forwarded to {@link Component#value()}. */
    @AliasFor(annotation = Component.class, attribute = "value")
    String value() default "";
}
