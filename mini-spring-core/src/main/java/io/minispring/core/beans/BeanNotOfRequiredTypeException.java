package io.minispring.core.beans;

/**
 * The bean exists but its runtime class does not fit the requested type. The usual cause is a
 * JDK dynamic proxy, which implements the bean's interfaces but is not an instance of its class.
 */
public class BeanNotOfRequiredTypeException extends BeansException {

    public BeanNotOfRequiredTypeException(String beanName, Class<?> requiredType, Object actual) {
        super("Bean '" + beanName + "' is expected to be of type " + requiredType.getName() + " but is "
                + actual.getClass().getName()
                + (java.lang.reflect.Proxy.isProxyClass(actual.getClass())
                ? ". It was wrapped in a JDK dynamic proxy, which only implements the bean's interfaces: "
                + "inject it by interface instead of by class"
                : ""));
    }
}
