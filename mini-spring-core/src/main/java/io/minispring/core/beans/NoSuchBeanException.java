package io.minispring.core.beans;

/** No bean matches the requested name or type. */
public class NoSuchBeanException extends BeansException {

    public NoSuchBeanException(String message) {
        super(message);
    }
}
