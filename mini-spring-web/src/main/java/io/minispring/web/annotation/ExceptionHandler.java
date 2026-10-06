package io.minispring.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that turns an exception into a response. Inside a controller it applies to that
 * controller's handlers; inside a {@link ControllerAdvice} to all of them. When several handlers fit,
 * the one whose declared exception type is closest to the thrown one wins. If {@link #value()} is
 * empty, the exception types are taken from the method's parameters.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExceptionHandler {

    Class<? extends Throwable>[] value() default {};
}
