package io.minispring.core.beans;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small reflection helpers shared by the container and the modules built on it. */
public final class ReflectionSupport {

    private ReflectionSupport() {
    }

    /**
     * Invokes a method and rethrows whatever it threw, unwrapped. Reflection wraps the callee's
     * exception in {@link InvocationTargetException}; callers such as the transaction
     * interceptor must see the original to apply rollback rules.
     */
    public static Object invoke(Object target, Method method, Object... args) throws Throwable {
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Finds a method that can be invoked on {@code bean} and corresponds to {@code method}.
     * A JDK proxy is not an instance of the user's class, so a method found on that class has
     * to be swapped for the same-signature method of an interface the proxy implements.
     *
     * @throws IllegalStateException if the proxy exposes no such method
     */
    public static Method invocableOn(Object bean, Method method) {
        if (method.getDeclaringClass().isInstance(bean)) {
            return method;
        }
        for (Class<?> implemented : bean.getClass().getInterfaces()) {
            try {
                return implemented.getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                // try the next interface
            }
        }
        throw new IllegalStateException("Method " + method.getDeclaringClass().getSimpleName() + "."
                + method.getName() + "() cannot be called on " + bean.getClass().getName()
                + ": the bean is an interface-based proxy and no interface declares this method. "
                + "Declare the method on an interface, or make the bean not implement interfaces "
                + "so that a subclass proxy is used");
    }

    /**
     * All methods of a class hierarchy, superclass first, with overridden methods reported once
     * (as the most specific declaration). Private methods are never overridden, so each is kept.
     */
    public static List<Method> methodsSuperclassFirst(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.addFirst(current);
        }
        Map<String, Method> bySignature = new LinkedHashMap<>();
        for (Class<?> current : hierarchy) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                String key = method.getName() + java.util.Arrays.toString(method.getParameterTypes());
                if (Modifier.isPrivate(method.getModifiers())) {
                    key = current.getName() + "#" + key;
                }
                bySignature.put(key, method);
            }
        }
        return new ArrayList<>(bySignature.values());
    }

    /** Rethrows any throwable without declaring it; used where a checked exception must pass through. */
    @SuppressWarnings("unchecked")
    public static <X extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws X {
        throw (X) throwable;
    }
}
