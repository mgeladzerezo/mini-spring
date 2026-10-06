package io.minispring.web.annotation;

import io.minispring.core.annotation.AliasFor;
import io.minispring.core.annotation.Component;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A class whose {@link RequestMapping} methods handle web requests. It is a {@link Component} by
 * meta-annotation, so scanning registers it like any other bean.
 *
 * <p>There is no view layer: the return value of every handler method is written as the response
 * body (see {@link RestController}).
 */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Controller {

    /** Bean name, forwarded to {@link Component#value()}. */
    @AliasFor(annotation = Component.class, attribute = "value")
    String value() default "";
}
