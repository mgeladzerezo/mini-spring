package io.minispring.core.event;

import io.minispring.core.annotation.EventListener;
import io.minispring.core.annotation.MergedAnnotations;
import io.minispring.core.annotation.Order;
import io.minispring.core.beans.BeanDefinition;
import io.minispring.core.beans.BeanDefinitionException;
import io.minispring.core.beans.BeanPostProcessor;
import io.minispring.core.beans.DefaultBeanFactory;
import io.minispring.core.beans.ReflectionSupport;
import io.minispring.core.type.ResolvedType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Wires {@code @EventListener} methods and {@link ApplicationListener} beans to the
 * {@link EventMulticaster}. It is an ordinary {@link BeanPostProcessor}: event support is not
 * built into the container, it is plugged in through the same hook AOP uses.
 *
 * <p>A listener is registered by bean <em>name</em> and the bean is fetched when an event
 * arrives. That way the call goes through whatever the container finally exposes for that name
 * (for example a transactional proxy), regardless of post-processor ordering.
 */
public final class EventListenerRegistrar implements BeanPostProcessor {

    private final DefaultBeanFactory beanFactory;
    private final EventMulticaster multicaster;
    private final Map<String, Class<?>> factoryMethodClasses = new ConcurrentHashMap<>();

    public EventListenerRegistrar(DefaultBeanFactory beanFactory, EventMulticaster multicaster) {
        this.beanFactory = beanFactory;
        this.multicaster = multicaster;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, BeanDefinition definition) {
        if (definition.factoryMethod() != null) {
            // Only the live object knows its class when a @Bean method declares a supertype.
            factoryMethodClasses.put(definition.name(), bean.getClass());
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, BeanDefinition definition) {
        Class<?> userClass = definition.factoryMethod() != null
                ? factoryMethodClasses.remove(definition.name())
                : definition.beanClass();
        List<Method> listenerMethods = ReflectionSupport.methodsSuperclassFirst(userClass).stream()
                .filter(method -> MergedAnnotations.isPresent(method, EventListener.class))
                .toList();
        boolean listenerInterface = ApplicationListener.class.isAssignableFrom(userClass);
        if (listenerMethods.isEmpty() && !listenerInterface) {
            return bean;
        }
        if (!definition.isSingleton()) {
            throw new BeanDefinitionException("Prototype bean '" + definition.name()
                    + "' declares event listeners; only singletons can listen, because every prototype "
                    + "instance would otherwise stay registered forever");
        }
        String name = definition.name();
        Supplier<Object> exposedBean = () -> beanFactory.getSingletonIfCreated(name);
        for (Method method : listenerMethods) {
            if (method.getParameterCount() != 1) {
                throw new BeanDefinitionException("@EventListener method " + userClass.getName() + "."
                        + method.getName() + "() must take exactly one parameter, the event");
            }
            ResolvedType eventType = ResolvedType.forParameter(method.getParameters()[0], userClass);
            int order = MergedAnnotations.find(method, Order.class).map(Order::value).orElse(definition.order());
            multicaster.addListener(eventType, order, name + "." + method.getName() + "(" + eventType + ")",
                    EventMulticaster.methodInvoker(exposedBean, method));
        }
        if (listenerInterface) {
            ResolvedType eventType = ResolvedType.forClass(userClass).as(ApplicationListener.class).typeArgument(0);
            multicaster.addListener(eventType, definition.order(), name + " (ApplicationListener<" + eventType + ">)",
                    event -> {
                        @SuppressWarnings("unchecked") // delivery is filtered by the listener's resolved event type
                        ApplicationListener<Object> listener = (ApplicationListener<Object>) exposedBean.get();
                        if (listener != null) {
                            listener.onEvent(event);
                        }
                    });
        }
        return bean;
    }
}
