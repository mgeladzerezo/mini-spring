package io.minispring.aop.aspects;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Records call count, total and maximum duration of the method in {@link MethodTimings}. */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Timed {

    /** Metric name; empty means {@code SimpleClassName.method}. */
    String value() default "";
}
