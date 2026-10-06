package io.minispring.aop;

import io.minispring.core.annotation.Import;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Switches on proxy creation and the built-in {@link io.minispring.aop.aspects.Timed} and
 * {@link io.minispring.aop.aspects.Retry} aspects. All it does is {@code @Import} a
 * configuration class; the container treats it like any other meta-annotation.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(AopConfiguration.class)
public @interface EnableAspects {
}
