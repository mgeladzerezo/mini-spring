package io.minispring.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds a query parameter. Repeated parameters can be collected into a {@code List} or array. Declare the parameter {@code Optional} to make it optional without a default. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequestParam {

    /** The name to look up; empty means the parameter's own name (needs {@code -parameters}). */
    String value() default "";

    /** Whether absence is an error (400). Ignored when a default value is given. */
    boolean required() default true;

    /** Text to convert when the value is absent; empty means no default. */
    String defaultValue() default "";
}
