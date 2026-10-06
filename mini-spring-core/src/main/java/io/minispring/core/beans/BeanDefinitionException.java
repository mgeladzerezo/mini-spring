package io.minispring.core.beans;

/** A bean is declared in a way the container cannot honour; detected before anything is created. */
public class BeanDefinitionException extends BeansException {

    public BeanDefinitionException(String message) {
        super(message);
    }

    public BeanDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
