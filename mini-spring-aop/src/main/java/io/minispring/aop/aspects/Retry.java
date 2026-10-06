package io.minispring.aop.aspects;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Re-runs the method when it throws one of the listed exceptions.
 *
 * <p>The retry advisor is ordered outside the transaction advisor on purpose: every attempt
 * then runs in a transaction of its own, after the failed attempt's transaction rolled back.
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Retry {

    /** Total number of attempts, including the first. */
    int maxAttempts() default 3;

    /** Pause between attempts. */
    long delayMillis() default 0;

    /** Exception types worth another attempt; subclasses count. */
    Class<? extends Throwable>[] on() default {RuntimeException.class};
}
