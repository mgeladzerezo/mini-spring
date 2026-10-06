package io.minispring.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a factory method. The container calls it with every parameter resolved as a
 * dependency and registers the result under the method name. The bean's type for injection
 * purposes is the method's <em>declared generic return type</em>, so
 * {@code Repository<Order> orders()} is injectable as {@code Repository<Order>} even though the
 * object is created by hand.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Bean {

    /** Bean name; empty means the method name. */
    String name() default "";

    /** Name of a no-arg method to call after injection, in addition to any {@link PostConstruct}. */
    String initMethod() default "";

    /** Name of a no-arg method to call on shutdown, in addition to any {@link PreDestroy}. */
    String destroyMethod() default "";
}
