package io.minispring.aop;

import java.lang.reflect.Method;

/**
 * One intercepted call, as seen from a particular position in the interceptor chain.
 *
 * <p>Each interceptor receives its own view. Calling {@link #proceed()} runs <em>the rest</em>
 * of the chain and finally the real method, and may be done more than once: a retry interceptor
 * simply calls it again and every interceptor after it runs again as well.
 */
public interface MethodInvocation {

    /** The method being called, as declared on the target class (so its annotations are the user's). */
    Method method();

    /** The user's class, never a proxy class. */
    Class<?> targetClass();

    /** The object the call will finally run on. */
    Object target();

    /** The proxy the caller invoked; for a subclass proxy this is the same object as {@link #target()}. */
    Object proxy();

    /** The live argument array; an interceptor may replace elements before proceeding. */
    Object[] arguments();

    /** Continues with the next interceptor, or with the real method after the last one. */
    Object proceed() throws Throwable;
}
