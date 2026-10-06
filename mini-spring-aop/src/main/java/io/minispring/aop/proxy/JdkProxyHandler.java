package io.minispring.aop.proxy;

import io.minispring.aop.MethodInterceptor;
import io.minispring.core.beans.ReflectionSupport;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The handler behind an interface-based proxy. The proxy and the target are two objects: calls
 * arriving through the proxy run the interceptor chain and are then forwarded to the target.
 *
 * <p>This is the root of the self-invocation pitfall. Inside the target, {@code this} is the
 * target, so {@code this.other()} is an ordinary call that never comes near this handler.
 */
final class JdkProxyHandler implements InvocationHandler {

    private static final Object[] NO_ARGUMENTS = {};

    /** What an interface method resolves to: the implementing method and the advice that applies to it. */
    private record Route(Method targetMethod, List<MethodInterceptor> chain) {
    }

    private final Object target;
    private final ProxyPlan plan;
    private final Map<Method, Route> routes = new ConcurrentHashMap<>();

    JdkProxyHandler(Object target, ProxyPlan plan) {
        this.target = target;
        this.plan = plan;
    }

    Class<?> targetClass() {
        return plan.targetClass();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> target + " (JDK proxy)";
            };
        }
        Route route = routes.computeIfAbsent(method, this::route);
        Object[] arguments = args != null ? args : NO_ARGUMENTS;
        if (route.chain().isEmpty()) {
            return ReflectionSupport.invoke(target, method, arguments);
        }
        // A checked exception the interface method does not declare is wrapped by java.lang.reflect.Proxy
        // itself (UndeclaredThrowableException); nothing to do here.
        return ChainInvocation.invoke(proxy, target, route.targetMethod(), plan.targetClass(), arguments,
                route.chain(), finalArguments -> ReflectionSupport.invoke(target, method, finalArguments));
    }

    /** Maps the invoked interface method to the method of the target class that implements it. */
    private Route route(Method interfaceMethod) {
        Method targetMethod = interfaceMethod;
        for (Class<?> current = plan.targetClass(); current != null; current = current.getSuperclass()) {
            try {
                targetMethod = current.getDeclaredMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
                break;
            } catch (NoSuchMethodException ignored) {
                // inherited from a superclass, or a default method that is not overridden
            }
        }
        return new Route(targetMethod, plan.chainFor(targetMethod));
    }
}
