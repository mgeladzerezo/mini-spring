package io.minispring.web.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the JSON request body to the parameter. The conversion target is the parameter's full
 * generic type, so {@code List<OrderDto>} yields {@code OrderDto} elements.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequestBody {

    /** Whether an empty body is an error (400). */
    boolean required() default true;
}
