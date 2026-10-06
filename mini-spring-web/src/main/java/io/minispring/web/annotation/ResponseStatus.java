package io.minispring.web.annotation;

import io.minispring.web.http.HttpStatus;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The status of a successful response, when the handler does not return a {@code ResponseEntity}; also usable on exception handlers. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ResponseStatus {

    HttpStatus value();
}
