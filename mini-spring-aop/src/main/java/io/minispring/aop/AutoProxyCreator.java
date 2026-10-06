package io.minispring.aop;

import io.minispring.aop.proxy.GeneratedProxy;
import io.minispring.aop.proxy.ProxyCreationException;
import io.minispring.aop.proxy.ProxyPlan;
import io.minispring.core.annotation.PostConstruct;
import io.minispring.core.beans.BeanDefinition;
import io.minispring.core.beans.BeanFactory;
import io.minispring.core.beans.BeanPostProcessor;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connects AOP to the container. It is a {@link BeanPostProcessor} and nothing more: the
 * container has no idea proxies exist.
 *
 * <p>For every bean it asks all {@link Advisor} beans which methods they match and builds a
 * {@link ProxyPlan}. The two proxy strategies hook in at different moments:
 * <ul>
 *   <li>a <b>subclass proxy</b> must be decided <em>before</em> instantiation, because the
 *       generated class is instantiated instead of the user's class
 *       ({@link #determineInstantiationClass}); it is switched on after initialisation;</li>
 *   <li>a <b>JDK proxy</b> wraps the finished bean ({@link #postProcessAfterInitialization}).</li>
 * </ul>
 * Either way advice is inactive during construction, injection and {@code @PostConstruct}.
 *
 * <p>Advisors are beans too. They are collected once, in this bean's own init callback: at
 * that point the container is still in its post-processor phase and no application bean is under
 * construction, so creating the advisors (and whatever they depend on) cannot be mistaken for a
 * dependency cycle through a half-built bean. The price is that those early beans are created
 * before proxying starts and are therefore never advised themselves; an interceptor that needs
 * an advised collaborator should take a {@code Provider<T>}.
 */
public final class AutoProxyCreator implements BeanPostProcessor {

    private final BeanFactory beanFactory;
    private final Map<Class<?>, ProxyPlan> plans = new ConcurrentHashMap<>();
    private volatile List<Advisor> advisors;

    public AutoProxyCreator(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /** Collects all {@link Advisor} beans, sorted by order. Runs as the init callback of this bean. */
    @PostConstruct
    public void resolveAdvisors() {
        advisors = beanFactory.getBeansOfType(Advisor.class).values().stream()
                .sorted(Comparator.comparingInt(Advisor::order))
                .toList();
    }

    @Override
    public Class<?> determineInstantiationClass(BeanDefinition definition, Class<?> beanClass) {
        if (beanClass != definition.beanClass() || isInfrastructure(beanClass)) {
            return beanClass;
        }
        ProxyPlan plan = planFor(beanClass);
        return plan.strategy() == ProxyPlan.Strategy.SUBCLASS ? plan.proxyClass() : beanClass;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, BeanDefinition definition) {
        if (bean instanceof GeneratedProxy) {
            planFor(bean.getClass().getSuperclass()).activate(bean);
            return bean;
        }
        Class<?> beanClass = bean.getClass();
        if (isInfrastructure(beanClass) || AopUtils.isProxy(bean)) {
            return bean;
        }
        ProxyPlan plan = planFor(beanClass);
        return switch (plan.strategy()) {
            case NONE -> bean;
            case JDK -> plan.newJdkProxy(bean);
            case SUBCLASS -> throw new ProxyCreationException("Bean '" + definition.name() + "' ("
                    + definition.source() + ") has advised methods that no interface declares "
                    + plan.methods().stream().map(method -> method.getName() + "()").toList()
                    + ", so it needs a subclass proxy; but the object was created by user code and cannot be "
                    + "replaced by a subclass instance. Let the container instantiate the class (make it a "
                    + "@Component) or put the advised methods on an interface");
        };
    }

    private ProxyPlan planFor(Class<?> beanClass) {
        if (advisors == null) {
            resolveAdvisors(); // used without a container calling the init callback
        }
        return plans.computeIfAbsent(beanClass, type -> ProxyPlan.of(type, advisors));
    }

    /** The pieces AOP itself is made of are never proxied. */
    private static boolean isInfrastructure(Class<?> type) {
        return Advisor.class.isAssignableFrom(type)
                || MethodInterceptor.class.isAssignableFrom(type)
                || Pointcut.class.isAssignableFrom(type)
                || BeanPostProcessor.class.isAssignableFrom(type);
    }
}
