package io.minispring.core.annotation;

import io.minispring.core.condition.Condition;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers the bean only if every listed {@link Condition} matches. Usually used as a
 * meta-annotation, see {@link ConditionalOnProperty} and {@link Profile}.
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Conditional {

    Class<? extends Condition>[] value();
}
