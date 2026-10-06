package io.minispring.core.fixtures.scan;

import io.minispring.core.annotation.Component;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An application-defined stereotype: nothing in the framework knows about it. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface CustomStereotype {
}
