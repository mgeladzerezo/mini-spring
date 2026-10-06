package io.minispring.web.annotation;

import io.minispring.core.annotation.AliasFor;
import io.minispring.web.http.HttpMethod;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps requests to a handler method. On a class the path is a prefix for its methods. The
 * shortcuts {@link GetMapping}, {@link PostMapping} and friends are composed from this annotation
 * by meta-annotation, with the HTTP method preset.
 *
 * <p>Path patterns are made of literal segments, {@code {variables}} (a whole segment), a
 * {@code *} (any single segment) and a trailing {@code **} (any number of segments). When several
 * patterns match a request, the most specific wins: fewer variables and wildcards first, then
 * the longer literal text.
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequestMapping {

    @AliasFor(attribute = "path")
    String[] value() default {};

    @AliasFor(attribute = "value")
    String[] path() default {};

    /** Accepted HTTP methods; empty accepts all. */
    HttpMethod[] method() default {};

    /** Accepted request content types, e.g. {@code application/json}; empty accepts any. A mismatch is a 415. */
    String[] consumes() default {};
}
