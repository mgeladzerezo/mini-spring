package io.minispring.core.annotation;

/** Thrown when annotation declarations contradict each other, for example a broken {@link AliasFor}. */
public class AnnotationConfigurationException extends RuntimeException {

    public AnnotationConfigurationException(String message) {
        super(message);
    }
}
