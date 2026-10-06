package io.minispring.web.mvc;

import io.minispring.core.annotation.Import;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Switches on the web layer: JSON mapper, dispatcher and the embedded HTTP server. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(WebConfiguration.class)
public @interface EnableWebMvc {
}
