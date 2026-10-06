package io.minispring.web.annotation;

import io.minispring.core.annotation.AliasFor;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A {@link Controller} whose methods return response bodies; composed from {@code @Controller} and {@code @ResponseBody}. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Controller
@ResponseBody
public @interface RestController {

    /** Bean name, forwarded through {@link Controller} to the component name. */
    @AliasFor(annotation = Controller.class, attribute = "value")
    String value() default "";
}
