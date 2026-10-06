package io.minispring.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a container-managed bean that component scanning should pick up.
 *
 * <p>Every other stereotype ({@link Service}, {@link Repository}, {@link Configuration}, and any
 * annotation an application defines) is simply an annotation that is itself annotated with
 * {@code @Component}; the scanner finds it by walking the meta-annotation graph.
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Component {

    /** Bean name; empty means the decapitalised simple class name. */
    String value() default "";
}
