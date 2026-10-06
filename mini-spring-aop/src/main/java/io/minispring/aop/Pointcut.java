package io.minispring.aop;

import java.lang.reflect.Method;

/** Selects the methods an interceptor applies to. Evaluated once per method when a bean is created. */
@FunctionalInterface
public interface Pointcut {

    /**
     * @param method      a method of the target class (the most specific declaration)
     * @param targetClass the user's class
     */
    boolean matches(Method method, Class<?> targetClass);
}
