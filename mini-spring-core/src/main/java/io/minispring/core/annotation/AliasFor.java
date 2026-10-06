package io.minispring.core.annotation;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an annotation attribute is another name for an attribute elsewhere.
 *
 * <ul>
 *   <li>With {@link #annotation()} set, the attribute overrides the named attribute of a
 *       meta-annotation: {@code @GetMapping("/x")} sets {@code @RequestMapping.path}.</li>
 *   <li>Without it, two attributes of the same annotation mirror each other
 *       ({@code value} and {@code path}).</li>
 * </ul>
 *
 * Interpreted by {@link MergedAnnotations}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AliasFor {

    /** Target attribute name; defaults to the name of the annotated attribute. */
    String attribute() default "";

    /** Meta-annotation that declares the target attribute; {@code Annotation.class} means "this one". */
    Class<? extends Annotation> annotation() default Annotation.class;
}
