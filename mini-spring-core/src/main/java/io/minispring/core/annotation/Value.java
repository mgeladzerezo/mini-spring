package io.minispring.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a property. The expression may contain <code>${key}</code> or
 * <code>${key:default}</code> placeholders, nested to any depth; the resolved text is converted
 * to the injection point's type, including generic targets such as {@code List<Duration>}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Value {

    String value();
}
