package io.minispring.web.annotation;

import io.minispring.core.annotation.AliasFor;
import io.minispring.core.annotation.Component;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A bean whose {@link ExceptionHandler} methods apply to every controller; consulted after the controller's own. */
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface ControllerAdvice {

    @AliasFor(annotation = Component.class, attribute = "value")
    String value() default "";
}
