package io.minispring.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds a {@code {variable}} of the matched path pattern to a parameter, converted to its type. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PathVariable {

    /** The name to look up; empty means the parameter's own name (needs {@code -parameters}). */
    String value() default "";
}
