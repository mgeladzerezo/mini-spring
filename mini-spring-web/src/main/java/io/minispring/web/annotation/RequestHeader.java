package io.minispring.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds a request header (case-insensitive name). */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequestHeader {

    /** The name to look up; empty means the parameter's own name (needs {@code -parameters}). */
    String value() default "";

    /** Whether absence is an error (400). Ignored when a default value is given. */
    boolean required() default true;

    /** Text to convert when the value is absent; empty means no default. */
    String defaultValue() default "";
}
