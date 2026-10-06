package io.minispring.core.beans;

/**
 * The container's extension point: a bean that gets to see, replace or wrap every other bean.
 *
 * <p>The container itself knows nothing about proxies, transactions or event listeners. Those
 * features are post-processors, discovered like any other bean and instantiated first.
 * A bean's life passes through the hooks in this order:
 *
 * <ol>
 *   <li>{@link #determineInstantiationClass} - before the constructor runs; may substitute a
 *       subclass (this is how class-based AOP proxies are created without a second instance);</li>
 *   <li>constructor, then field and method injection;</li>
 *   <li>{@link #postProcessBeforeInitialization};</li>
 *   <li>{@code @PostConstruct} and init methods;</li>
 *   <li>{@link #postProcessAfterInitialization} - may return a wrapper (JDK proxies are created
 *       here), which is what other beans will receive.</li>
 * </ol>
 */
public interface BeanPostProcessor {

    /**
     * Chooses the class to instantiate for a component the container constructs itself.
     * The returned class must be {@code beanClass} or a subclass with the same constructor
     * signatures. Not called for {@code @Bean} methods, whose object the user creates.
     */
    default Class<?> determineInstantiationClass(BeanDefinition definition, Class<?> beanClass) {
        return beanClass;
    }

    /** Called after injection, before init callbacks. Return the bean or a replacement. */
    default Object postProcessBeforeInitialization(Object bean, BeanDefinition definition) {
        return bean;
    }

    /** Called after init callbacks. Return the bean or the object to expose in its place. */
    default Object postProcessAfterInitialization(Object bean, BeanDefinition definition) {
        return bean;
    }
}
