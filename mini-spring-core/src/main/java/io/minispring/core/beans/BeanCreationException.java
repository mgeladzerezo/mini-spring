package io.minispring.core.beans;

/** Creating a bean failed. The message names the bean and where it was defined. */
public class BeanCreationException extends BeansException {

    private final String beanName;

    public BeanCreationException(BeanDefinition definition, Throwable cause) {
        super("Error creating bean '" + definition.name() + "' (" + definition.source() + "): " + describe(cause), cause);
        this.beanName = definition.name();
    }

    public String beanName() {
        return beanName;
    }

    private static String describe(Throwable cause) {
        return cause instanceof BeansException ? cause.getMessage() : cause.toString();
    }

    /** The first cause that is not itself a wrapper, i.e. the actual problem. */
    public Throwable rootCause() {
        Throwable current = this;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
